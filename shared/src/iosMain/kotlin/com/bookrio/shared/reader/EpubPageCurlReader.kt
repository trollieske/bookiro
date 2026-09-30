@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.bookrio.shared.reader

import com.bookrio.shared.platform.AppPrefs
import com.bookrio.shared.platform.PrefKeys
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.NSLineBreakByTruncatingTail
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIControlContentHorizontalAlignmentLeft
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIPageViewController
import platform.UIKit.UIPageViewControllerDataSourceProtocol
import platform.UIKit.UIPageViewControllerDelegateProtocol
import platform.UIKit.UIPageViewControllerNavigationDirection
import platform.UIKit.UIPageViewControllerNavigationOrientationHorizontal
import platform.UIKit.UIPageViewControllerSpineLocationMid
import platform.UIKit.UIPageViewControllerSpineLocationMin
import platform.UIKit.UIPageViewControllerTransitionStylePageCurl
import platform.UIKit.UIScreen
import platform.UIKit.UIScrollView
import platform.UIKit.UIUserInterfaceSizeClassRegular
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

/**
 * Native iOS EPUB reader: a container `UIViewController` with the Android reader
 * chrome (top bar: back / title / chapter / progress; bottom bar: contents,
 * A-, A+, theme, bookmark) around a `UIPageViewController` with Apple's built-in
 * `.pageCurl` transition. Every page is a child view controller whose content is
 * a WKWebView rendering the chapter's real HTML with the Android reader CSS
 * ([buildEpubReaderHtml]) — never plain text.
 *
 * Pagination is asynchronous: chapters are measured serially with JavaScript
 * (`EpubHtmlPaginator`) and the first page appears as soon as its chapter is
 * measured. A placeholder page controller is always installed in `init`, before
 * `viewWillAppear` (see HANDOFF.md — deferring `setViewControllers` crashes).
 *
 * Phone portrait keeps the `.min` spine; iPad landscape with a regular width
 * uses the two-up `.mid` spine (paired within the current chapter).
 *
 * Settings persist through [AppPrefs] / [PrefKeys]: READER_FONT_SIZE (16),
 * READER_LINE_HEIGHT (140) and READER_THEME ("light"). Bookmarks persist through
 * [AppPrefs] under a per-book key (the reader only receives the parsed [EpubBook],
 * not the library row id). Reading progress stays on `ReadingProgressDao` — it is
 * reported through [onPageChanged], which App.kt wires to the DAO.
 */
internal class EpubPageCurlReader(
    private val book: EpubBook,
    startPage: Int,
    private val onPageChanged: (page: Int, totalPages: Int) -> Unit,
) : UIViewController(null, null) {

    // Weak self keeps data source/delegate/actions from forming retain cycles.
    private val selfRef = WeakReference(this)

    // ── Persisted reader settings (same keys/defaults as the Android app) ──
    private var fontSizeSp = AppPrefs.getInt(PrefKeys.READER_FONT_SIZE, DEFAULT_FONT_SIZE)
        .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    private var lineHeightPct = AppPrefs.getInt(PrefKeys.READER_LINE_HEIGHT, DEFAULT_LINE_HEIGHT)
        .coerceIn(MIN_LINE_HEIGHT, MAX_LINE_HEIGHT)
    private var theme = AppPrefs.getString(PrefKeys.READER_THEME)?.lowercase()?.takeIf { it in THEMES }
        ?: DEFAULT_THEME

    // ── Host + pagination ──
    private val innerPageController = UIPageViewController(
        UIPageViewControllerTransitionStylePageCurl,
        UIPageViewControllerNavigationOrientationHorizontal,
        null,
    )
    private val paginator = EpubHtmlPaginator(book.chapters) { chapter, pages ->
        selfRef.value?.onChapterMeasured(chapter, pages)
    }
    private val pageSource = EpubPageSource(
        before = { controller -> selfRef.value?.controllerBefore(controller) },
        after = { controller -> selfRef.value?.controllerAfter(controller) },
    )
    private val pageDelegate = EpubPageDelegate(
        onSettled = { controller -> selfRef.value?.onSettled(controller) },
        onSpineLocation = { orientation ->
            selfRef.value?.spineLocationForOrientation(orientation) ?: UIPageViewControllerSpineLocationMin
        },
    )

    // ── Chrome ──
    private val topBar = UIView()
    private val bottomBar = UIView()
    private val topHairline = UIView()
    private val bottomHairline = UIView()
    private val backButton = UIButton()
    private val titleLabel = UILabel()
    private val subtitleLabel = UILabel()
    private val hudLabel = UILabel()
    private val contentsButton = UIButton()
    private val fontDownButton = UIButton()
    private val fontUpButton = UIButton()
    private val themeButton = UIButton()
    private val bookmarkButton = UIButton()
    private val chromeButtons = ArrayList<UIButton>()

    // ── Page/grid state ──
    private var contentWidth = 0
    private var contentHeight = 0
    private var topBarHeight = MIN_TOP_BAR_HEIGHT
    private var bottomBarHeight = MIN_BOTTOM_BAR_HEIGHT
    private var shownChapter = -1
    private var shownLocal = -1
    private var pendingChapter = 0
    private var pendingFraction = 0f
    private var restorePage: Int? = startPage.takeIf { it > 0 }
    private var hasRealPage = false
    private var lastReportedPage = -1
    private var lastReportedTotal = -1

    init {
        innerPageController.dataSource = pageSource
        innerPageController.delegate = pageDelegate
        // A UIPageViewController must own at least one view controller before it
        // appears. Install the placeholder here (not in viewDidLayoutSubviews),
        // then replace it with the first measured page.
        innerPageController.setViewControllers(
            viewControllers = listOf(EpubPlaceholderViewController(pageColor())),
            direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
            animated = false,
            completion = null,
        )
        val screen = UIScreen.mainScreen.bounds
        contentWidth = contentWidthFor(CGRectGetWidth(screen))
        contentHeight = contentHeightFor(CGRectGetHeight(screen))
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = barColor()

        // Measuring webview first (bottom-most): it is fully covered by the
        // opaque page controller, so it can lay out but never shows.
        view.addSubview(paginator.measureView)

        addChildViewController(innerPageController)
        view.addSubview(innerPageController.view)
        innerPageController.didMoveToParentViewController(this)
        innerPageController.view.backgroundColor = pageColor()

        buildChrome()
        applyThemeToChrome()
        updateChrome()
        // Start paginating only now: the measuring webview is in the view
        // hierarchy, so its CSS viewport is real when the JS measures it.
        reconfigurePagination()
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val viewWidth = CGRectGetWidth(view.bounds)
        val viewHeight = CGRectGetHeight(view.bounds)
        if (viewWidth <= 0.0 || viewHeight <= 0.0) return
        view.bringSubviewToFront(topBar)
        view.bringSubviewToFront(bottomBar)
        view.bringSubviewToFront(hudLabel)
        layoutChrome()

        val width = contentWidthFor(CGRectGetWidth(view.bounds))
        val height = contentHeightFor(CGRectGetHeight(view.bounds))
        if (width != contentWidth || height != contentHeight) {
            contentWidth = width
            contentHeight = height
            if (shownChapter >= 0) {
                pendingChapter = shownChapter
                pendingFraction = shownFraction()
            } else if (!hasRealPage) {
                innerPageController.setViewControllers(
                    viewControllers = listOf(EpubPlaceholderViewController(pageColor())),
                    direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
                    animated = false,
                    completion = null,
                )
            }
            reconfigurePagination()
        }
    }

    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)
        if (isBeingDismissed()) paginator.shutdown()
    }

    // ══════════════════════════ pagination callbacks ══════════════════════════

    private fun onChapterMeasured(chapter: Int, pages: Int) {
        // Restoring a previous position: wait until the global grid resolves the
        // target instead of flashing a wrong page first.
        val restore = restorePage
        if (restore != null) {
            val location = locate(restore)
            if (location == null) return
            restorePage = null
            pendingChapter = -1
            showPage(location.first, location.second)
            return
        }
        if (pendingChapter >= 0 && paginator.countOf(pendingChapter) != null) {
            showPending()
            return
        }
        updateChrome()
        reportProgress()
    }

    private fun showPending() {
        val chapter = pendingChapter
        val count = paginator.countOf(chapter) ?: return
        val local = if (count <= 1) 0 else (pendingFraction * (count - 1)).roundToInt()
        pendingChapter = -1
        showPage(chapter, local.coerceIn(0, count - 1))
    }

    private fun showPage(chapter: Int, local: Int) {
        val count = paginator.countOf(chapter) ?: return
        val page = local.coerceIn(0, count - 1)
        shownChapter = chapter
        shownLocal = page
        hasRealPage = true
        // A page controller with a .mid spine requires exactly two view controllers;
        // a single one would throw (the same invariant as the viewWillAppear fix).
        if (innerPageController.doubleSided && count >= 2) {
            var left = if (page % 2 == 1) page - 1 else page
            if (left + 1 >= count) left = count - 2
            val leftController = pageController(chapter, left)
            val rightController = pageController(chapter, left + 1)
            if (leftController != null && rightController != null) {
                shownLocal = left
                innerPageController.setViewControllers(
                    viewControllers = listOf(leftController, rightController),
                    direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
                    animated = false,
                    completion = null,
                )
            } else {
                innerPageController.doubleSided = false
                setSinglePage(chapter, page)
            }
        } else {
            innerPageController.doubleSided = false
            setSinglePage(chapter, page)
        }
        updateChrome()
        reportProgress()
    }

    private fun setSinglePage(chapter: Int, page: Int) {
        innerPageController.setViewControllers(
            viewControllers = listOf(pageControllerOrEmpty(chapter, page)),
            direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
            animated = false,
            completion = null,
        )
    }

    private fun pageControllerOrEmpty(chapter: Int, page: Int): UIViewController =
        pageController(chapter, page) ?: EpubPlaceholderViewController(pageColor())

    private fun onSettled(controller: EpubHtmlPageViewController) {
        shownChapter = controller.chapterIndex
        shownLocal = controller.localPageIndex
        hasRealPage = true
        updateChrome()
        reportProgress()
    }

    private fun controllerBefore(controller: UIViewController): UIViewController? {
        val page = controller as? EpubHtmlPageViewController ?: return null
        if (page.localPageIndex > 0) return pageController(page.chapterIndex, page.localPageIndex - 1)
        val previous = page.chapterIndex - 1
        if (previous < 0) return null
        val count = paginator.countOf(previous) ?: return null
        return pageController(previous, count - 1)
    }

    private fun controllerAfter(controller: UIViewController): UIViewController? {
        val page = controller as? EpubHtmlPageViewController ?: return null
        val count = paginator.countOf(page.chapterIndex) ?: return null
        if (page.localPageIndex + 1 < count) return pageController(page.chapterIndex, page.localPageIndex + 1)
        val next = page.chapterIndex + 1
        if (next >= book.chapters.size || paginator.countOf(next) == null) return null
        return pageController(next, 0)
    }

    private fun pageController(chapter: Int, local: Int): EpubHtmlPageViewController? {
        val count = paginator.countOf(chapter) ?: return null
        val page = local.coerceIn(0, count - 1)
        return EpubHtmlPageViewController(
            chapterIndex = chapter,
            localPageIndex = page,
            pageHtml = paginator.html(chapter, page),
            pageColor = pageColor(),
        )
    }

    /**
     * iPad landscape (regular width) gets the two-up `.mid` spine, pairing pages
     * inside the current chapter; everything else stays `.min` (phone portrait).
     */
    private fun spineLocationForOrientation(orientation: Long): Long {
        val landscape = orientation == UIInterfaceOrientationLandscapeLeft ||
            orientation == UIInterfaceOrientationLandscapeRight
        val regularWidth = traitCollection().horizontalSizeClass == UIUserInterfaceSizeClassRegular
        if (!landscape || !regularWidth || shownChapter < 0) {
            val wasDoubleSided = innerPageController.doubleSided
            innerPageController.doubleSided = false
            if (wasDoubleSided) {
                pageController(shownChapter, shownLocal)?.let { single ->
                    innerPageController.setViewControllers(
                        viewControllers = listOf(single),
                        direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
                        animated = false,
                        completion = null,
                    )
                }
            }
            return UIPageViewControllerSpineLocationMin
        }
        val count = paginator.countOf(shownChapter) ?: run {
            innerPageController.doubleSided = false
            return UIPageViewControllerSpineLocationMin
        }
        if (count < 2) {
            innerPageController.doubleSided = false
            return UIPageViewControllerSpineLocationMin
        }
        var left = if (shownLocal % 2 == 1) shownLocal - 1 else shownLocal
        if (left + 1 >= count) left = count - 2
        val leftController = pageController(shownChapter, left)
        val rightController = pageController(shownChapter, left + 1)
        if (leftController == null || rightController == null) {
            innerPageController.doubleSided = false
            return UIPageViewControllerSpineLocationMin
        }
        innerPageController.doubleSided = true
        innerPageController.setViewControllers(
            viewControllers = listOf(leftController, rightController),
            direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
            animated = false,
            completion = null,
        )
        shownLocal = left
        updateChrome()
        reportProgress()
        return UIPageViewControllerSpineLocationMid
    }

    /** Global index of a page once every preceding chapter has been measured. */
    private fun globalIndex(chapter: Int, local: Int): Int? {
        if (chapter < 0) return null
        var total = 0
        for (i in 0 until chapter) {
            total += paginator.countOf(i) ?: return null
        }
        return total + local
    }

    private fun locate(globalPage: Int): Pair<Int, Int>? {
        var remaining = globalPage.coerceAtLeast(0)
        var lastReady = -1
        var lastCount = 0
        for (i in book.chapters.indices) {
            val count = paginator.countOf(i) ?: return null
            if (remaining < count) return i to remaining
            remaining -= count
            lastReady = i
            lastCount = count
        }
        if (lastReady >= 0) return lastReady to max(0, lastCount - 1)
        return null
    }

    /** Reports (global page, total pages) — App.kt persists it via ReadingProgressDao. */
    private fun reportProgress() {
        val page = globalIndex(shownChapter, shownLocal) ?: return
        val total = paginator.knownTotalPages()
        if (total <= 0) return
        if (page == lastReportedPage && total == lastReportedTotal) return
        lastReportedPage = page
        lastReportedTotal = total
        onPageChanged(page, total)
    }

    private fun shownFraction(): Float {
        val count = paginator.countOf(shownChapter) ?: return 0f
        return if (count <= 1) 0f else shownLocal.toFloat() / (count - 1).toFloat()
    }

    // ═════════════════════════════ reader chrome ═════════════════════════════

    private fun buildChrome() {
        topBar.addSubview(backButton)
        topBar.addSubview(titleLabel)
        topBar.addSubview(subtitleLabel)
        topBar.addSubview(topHairline)
        view.addSubview(topBar)

        backButton.setTitle("‹", UIControlStateNormal)
        backButton.titleLabel?.font = UIFont.boldSystemFontOfSize(24.0)
        backButton.addAction(
            UIAction.actionWithHandler { selfRef.value?.closeReader() },
            UIControlEventTouchUpInside,
        )

        titleLabel.font = UIFont.boldSystemFontOfSize(16.0)
        titleLabel.text = book.title.ifBlank { "EPUB" }
        titleLabel.numberOfLines = 1
        titleLabel.lineBreakMode = NSLineBreakByTruncatingTail

        subtitleLabel.font = UIFont.systemFontOfSize(12.0)
        subtitleLabel.numberOfLines = 1
        subtitleLabel.lineBreakMode = NSLineBreakByTruncatingTail

        addChromeButton(contentsButton, "Innhold", 13.0) { selfRef.value?.openContents() }
        addChromeButton(fontDownButton, "A-", 15.0) { selfRef.value?.changeFontSize(-1) }
        addChromeButton(fontUpButton, "A+", 17.0) { selfRef.value?.changeFontSize(1) }
        addChromeButton(themeButton, "Tema", 13.0) { selfRef.value?.cycleTheme() }
        addChromeButton(bookmarkButton, "Bokmerke", 12.0) { selfRef.value?.toggleBookmark() }
        bottomBar.addSubview(bottomHairline)
        view.addSubview(bottomBar)

        hudLabel.font = UIFont.systemFontOfSize(12.0)
        hudLabel.textAlignment = NSTextAlignmentCenter
        hudLabel.numberOfLines = 1
        hudLabel.hidden = true
        view.addSubview(hudLabel)
    }

    private fun addChromeButton(button: UIButton, title: String, fontSize: Double, action: () -> Unit) {
        button.setTitle(title, UIControlStateNormal)
        button.titleLabel?.font = UIFont.systemFontOfSize(fontSize)
        button.addAction(UIAction.actionWithHandler { action() }, UIControlEventTouchUpInside)
        bottomBar.addSubview(button)
        chromeButtons.add(button)
    }

    private fun layoutChrome() {
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        // Fixed chrome that respects the notch/Dynamic Island and the home indicator.
        val insets = view.safeAreaInsets
        val safeTop = insets.useContents { top }
        val safeBottom = insets.useContents { bottom }
        topBarHeight = max(MIN_TOP_BAR_HEIGHT, safeTop + 60.0)
        bottomBarHeight = max(MIN_BOTTOM_BAR_HEIGHT, safeBottom + 52.0)
        topBar.setFrame(CGRectMake(0.0, 0.0, width, topBarHeight))
        bottomBar.setFrame(CGRectMake(0.0, max(0.0, height - bottomBarHeight), width, bottomBarHeight))
        backButton.setFrame(CGRectMake(4.0, topBarHeight - 50.0, 44.0, 44.0))
        val textLeft = 48.0
        titleLabel.setFrame(CGRectMake(textLeft, topBarHeight - 56.0, max(1.0, width - textLeft - 10.0), 22.0))
        subtitleLabel.setFrame(CGRectMake(textLeft, topBarHeight - 32.0, max(1.0, width - textLeft - 10.0), 18.0))
        topHairline.setFrame(CGRectMake(0.0, topBarHeight - 0.5, width, 0.5))
        bottomHairline.setFrame(CGRectMake(0.0, 0.0, width, 0.5))
        val buttonWidth = (width - 8.0) / chromeButtons.size.toDouble()
        val buttonTop = max(4.0, (bottomBarHeight - 46.0 - safeBottom) / 2.0)
        chromeButtons.forEachIndexed { index, button ->
            button.setFrame(CGRectMake(4.0 + index * buttonWidth, buttonTop, buttonWidth, 46.0))
        }
        hudLabel.setFrame(CGRectMake(0.0, max(0.0, height - bottomBarHeight - 30.0), width, 24.0))
        innerPageController.view.setFrame(contentFrame(width, height))
        paginator.measureView.setFrame(contentFrame(width, height))
    }

    private fun updateChrome() {
        val rawTitle = book.chapters.getOrNull(shownChapter)?.title
        val chapterTitle = when {
            !rawTitle.isNullOrBlank() -> rawTitle
            shownChapter >= 0 -> "Kapittel ${shownChapter + 1}"
            else -> ""
        }
        val global = globalIndex(shownChapter, shownLocal)
        val total = paginator.knownTotalPages()
        val count = paginator.countOf(shownChapter)
        val position = when {
            global != null && total > 0 -> {
                val percent = if (total > 1) (global * 100.0 / (total - 1)).roundToInt() else 100
                "Side ${global + 1} av $total · $percent %"
            }
            count != null && count > 0 -> "Side ${shownLocal + 1} av $count"
            else -> "Paginerer …"
        }
        subtitleLabel.text = if (chapterTitle.isEmpty()) position else "$chapterTitle · $position"
        updateBookmarkButton()
    }

    private fun applyThemeToChrome() {
        val colors = epubReaderColors(theme)
        val bar = barColor()
        val ink = epubColorFromHex(colors.textColor)
        topBar.backgroundColor = bar
        bottomBar.backgroundColor = bar
        view.backgroundColor = bar
        titleLabel.textColor = epubColorFromHex(colors.headingColor)
        subtitleLabel.textColor = ink
        hudLabel.textColor = ink
        hudLabel.backgroundColor = bar
        backButton.setTitleColor(epubColorFromHex(colors.headingColor), UIControlStateNormal)
        for (button in chromeButtons) button.setTitleColor(ink, UIControlStateNormal)
        topHairline.backgroundColor = epubColorFromHex(colors.textColor, alpha = 0.18)
        bottomHairline.backgroundColor = epubColorFromHex(colors.textColor, alpha = 0.18)
        innerPageController.view.backgroundColor = bar
        updateBookmarkButton()
    }

    private fun changeFontSize(delta: Int) {
        val updated = (fontSizeSp + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        if (updated == fontSizeSp) return
        fontSizeSp = updated
        AppPrefs.putInt(PrefKeys.READER_FONT_SIZE, updated)
        reconfigureForSettings()
        showHud("Tekststørrelse: $updated")
    }

    private fun cycleTheme() {
        val index = THEMES.indexOf(theme).coerceAtLeast(0)
        theme = THEMES[(index + 1) % THEMES.size]
        AppPrefs.putString(PrefKeys.READER_THEME, theme)
        applyThemeToChrome()
        reconfigureForSettings()
        showHud("Tema: ${themeLabel(theme)}")
    }

    private fun reconfigureForSettings() {
        if (shownChapter >= 0) {
            pendingChapter = shownChapter
            pendingFraction = shownFraction()
        } else if (!hasRealPage) {
            innerPageController.setViewControllers(
                viewControllers = listOf(EpubPlaceholderViewController(pageColor())),
                direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
                animated = false,
                completion = null,
            )
        }
        reconfigurePagination()
    }

    /** Rebuilds the page grid for the current size/settings; the shown chapter is measured first. */
    private fun reconfigurePagination() {
        paginator.configure(
            widthPx = contentWidth,
            heightPx = contentHeight,
            fontSizeSp = fontSizeSp,
            lineHeightPct = lineHeightPct,
            theme = theme,
            priorityChapter = shownChapter,
        )
    }

    private fun openContents() {
        val colors = epubReaderColors(theme)
        val toc = EpubTocViewController(
            chapters = book.chapters,
            background = barColor(),
            ink = epubColorFromHex(colors.headingColor),
            onSelect = { chapter -> selfRef.value?.jumpToChapter(chapter) },
        )
        presentViewController(toc, true, null)
    }

    private fun jumpToChapter(chapter: Int) {
        if (chapter < 0 || chapter >= book.chapters.size) return
        pendingChapter = chapter
        pendingFraction = 0f
        if (paginator.countOf(chapter) != null) {
            showPending()
        } else {
            paginator.prioritize(chapter)
            showHud("Åpner kapittel …")
        }
    }

    private fun toggleBookmark() {
        if (shownChapter < 0) return
        val key = bookmarkKey()
        val value = "$shownChapter:$shownLocal"
        if (AppPrefs.getString(key) == value) {
            AppPrefs.putString(key, null)
            showHud("Bokmerke fjernet")
        } else {
            AppPrefs.putString(key, value)
            showHud("Bokmerke lagret")
        }
        updateBookmarkButton()
    }

    private fun updateBookmarkButton() {
        val marked = shownChapter >= 0 && AppPrefs.getString(bookmarkKey()) == "$shownChapter:$shownLocal"
        val normal = epubColorFromHex(epubReaderColors(theme).textColor)
        bookmarkButton.setTitleColor(if (marked) BOOKMARK_ACTIVE_COLOR else normal, UIControlStateNormal)
    }

    private fun showHud(message: String) {
        hudLabel.text = message
        hudLabel.hidden = false
        view.bringSubviewToFront(hudLabel)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, HUD_VISIBLE_NANOS), dispatch_get_main_queue()) {
            val reader = selfRef.value ?: return@dispatch_after
            if (reader.hudLabel.text == message) reader.hudLabel.hidden = true
        }
    }

    private fun closeReader() {
        paginator.shutdown()
        dismissViewControllerAnimated(true, null)
    }

    private fun bookmarkKey(): String = "$BOOKMARK_KEY_PREFIX${book.title}|${book.author ?: ""}"

    private fun themeLabel(key: String): String = when (key) {
        "sepia" -> "Sepia"
        "dark" -> "Mørk"
        "black" -> "Svart"
        else -> "Lys"
    }

    private fun barColor(): UIColor = epubColorFromHex(epubReaderColors(theme).bodyBg)

    private fun pageColor(): UIColor = epubColorFromHex(epubReaderColors(theme).bodyBg)

    private fun contentFrame(width: Double, height: Double): CValue<CGRect> = CGRectMake(
        CONTENT_HORIZONTAL_PADDING,
        topBarHeight + CONTENT_VERTICAL_PADDING,
        max(1.0, width - CONTENT_HORIZONTAL_PADDING * 2.0),
        max(1.0, height - topBarHeight - bottomBarHeight - CONTENT_VERTICAL_PADDING * 2.0),
    )

    private fun contentWidthFor(screenWidth: Double): Int =
        max(1, floor(screenWidth - CONTENT_HORIZONTAL_PADDING * 2.0).toInt())

    private fun contentHeightFor(screenHeight: Double): Int =
        max(1, floor(screenHeight - topBarHeight - bottomBarHeight - CONTENT_VERTICAL_PADDING * 2.0).toInt())
}

/** Bridges the ObjC data source to weak-self lookups on the reader. */
private class EpubPageSource(
    private val before: (UIViewController) -> UIViewController?,
    private val after: (UIViewController) -> UIViewController?,
) : NSObject(), UIPageViewControllerDataSourceProtocol {

    @ObjCSignatureOverride
    override fun pageViewController(
        pageViewController: UIPageViewController,
        viewControllerBeforeViewController: UIViewController,
    ): UIViewController? = before(viewControllerBeforeViewController)

    @ObjCSignatureOverride
    override fun pageViewController(
        pageViewController: UIPageViewController,
        viewControllerAfterViewController: UIViewController,
    ): UIViewController? = after(viewControllerAfterViewController)
}

private class EpubPageDelegate(
    private val onSettled: (EpubHtmlPageViewController) -> Unit,
    private val onSpineLocation: (Long) -> Long,
) : NSObject(), UIPageViewControllerDelegateProtocol {

    override fun pageViewController(
        pageViewController: UIPageViewController,
        spineLocationForInterfaceOrientation: Long,
    ): Long = onSpineLocation(spineLocationForInterfaceOrientation)

    override fun pageViewController(
        pageViewController: UIPageViewController,
        didFinishAnimating: Boolean,
        previousViewControllers: List<*>,
        transitionCompleted: Boolean,
    ) {
        if (!transitionCompleted) return
        val controller = pageViewController.viewControllers?.firstOrNull() as? EpubHtmlPageViewController ?: return
        onSettled(controller)
    }
}

/** Simple native table of contents (scroll view + one button per chapter). */
private class EpubTocViewController(
    private val chapters: List<EpubChapter>,
    private val background: UIColor,
    private val ink: UIColor,
    private val onSelect: (Int) -> Unit,
) : UIViewController(null, null) {

    private val selfRef = WeakReference(this)
    private val header = UILabel()
    private val closeButton = UIButton()
    private val scroll = UIScrollView(CGRectMake(0.0, 0.0, 1.0, 1.0))
    private val buttons = ArrayList<UIButton>()

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = background

        header.text = "Innhold"
        header.font = UIFont.boldSystemFontOfSize(18.0)
        header.textColor = ink
        header.textAlignment = NSTextAlignmentCenter

        closeButton.setTitle("Lukk", UIControlStateNormal)
        closeButton.setTitleColor(ink, UIControlStateNormal)
        closeButton.titleLabel?.font = UIFont.systemFontOfSize(15.0)
        closeButton.addAction(
            UIAction.actionWithHandler { selfRef.value?.dismissViewControllerAnimated(true, null) },
            UIControlEventTouchUpInside,
        )

        view.addSubview(header)
        view.addSubview(closeButton)
        view.addSubview(scroll)

        chapters.forEach { chapter ->
            val title = if (chapter.title.isNullOrBlank()) "Kapittel ${chapter.index + 1}" else chapter.title
            val button = UIButton()
            button.setTitle("${chapter.index + 1}.  $title", UIControlStateNormal)
            button.setTitleColor(ink, UIControlStateNormal)
            button.titleLabel?.font = UIFont.systemFontOfSize(15.0)
            button.contentHorizontalAlignment = UIControlContentHorizontalAlignmentLeft
            button.titleLabel?.lineBreakMode = NSLineBreakByTruncatingTail
            val index = chapter.index
            button.addAction(
                UIAction.actionWithHandler { selfRef.value?.select(index) },
                UIControlEventTouchUpInside,
            )
            scroll.addSubview(button)
            buttons.add(button)
        }
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        header.setFrame(CGRectMake(16.0, 30.0, max(1.0, width - 120.0), 24.0))
        closeButton.setFrame(CGRectMake(max(0.0, width - 80.0), 22.0, 72.0, 40.0))
        scroll.setFrame(CGRectMake(0.0, 64.0, width, max(1.0, height - 64.0)))
        var y = 8.0
        for (button in buttons) {
            button.setFrame(CGRectMake(20.0, y, max(1.0, width - 40.0), TOC_ROW_HEIGHT))
            y += TOC_ROW_HEIGHT
        }
        scroll.setContentSize(CGSizeMake(width, y + 24.0))
    }

    private fun select(chapter: Int) {
        onSelect(chapter)
        dismissViewControllerAnimated(true, null)
    }
}

private const val DEFAULT_FONT_SIZE = 16
private const val DEFAULT_LINE_HEIGHT = 140
private const val DEFAULT_THEME = "light"
private const val MIN_FONT_SIZE = 12
private const val MAX_FONT_SIZE = 36
private const val MIN_LINE_HEIGHT = 100
private const val MAX_LINE_HEIGHT = 220

private const val MIN_TOP_BAR_HEIGHT = 96.0
private const val MIN_BOTTOM_BAR_HEIGHT = 64.0
private const val CONTENT_HORIZONTAL_PADDING = 32.0
private const val CONTENT_VERTICAL_PADDING = 12.0
private const val TOC_ROW_HEIGHT = 46.0

private const val HUD_VISIBLE_NANOS = 1_400_000_000L
private const val BOOKMARK_KEY_PREFIX = "reader_bookmark_page::"

private val THEMES = listOf("light", "sepia", "dark", "black")
private val BOOKMARK_ACTIVE_COLOR = UIColor(red = 0.75, green = 0.36, blue = 0.06, alpha = 1.0)