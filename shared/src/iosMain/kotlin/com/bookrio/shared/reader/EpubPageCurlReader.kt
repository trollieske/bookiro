@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.bookrio.shared.reader

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.UIKit.NSLineBreakByWordWrapping
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIColor
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
import platform.UIKit.UIUserInterfaceSizeClassRegular
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * Native iOS EPUB reader: a `UIPageViewController` with the built-in `.pageCurl`
 * transition, one paginated page per child view controller — the same structural
 * pattern as `PdfPageCurlReader` (weak self, data source/delegate objects, a
 * "Lukk" close button kept on top, progress reported through [onPageChanged]).
 *
 * Pagination is lazy: the reader owns the parsed [EpubBook] and does not know its
 * page grid until `viewDidLayoutSubviews` reports a non-empty `view.bounds`. It
 * then measures the label font, computes columns/rows and runs the common
 * [EpubPaginator] once.
 */
internal class EpubPageCurlReader(
    private val book: EpubBook,
    private val startPage: Int,
    private val onPageChanged: (page: Int, totalPages: Int) -> Unit,
) : UIPageViewController(
    UIPageViewControllerTransitionStylePageCurl,
    UIPageViewControllerNavigationOrientationHorizontal,
    null,
) {

    // Weak self keeps the data source/delegate from forming a retain cycle with the
    // controller (ObjC holds both weakly; we own them here).
    private val selfRef = WeakReference(this)
    private val pageFont = UIFont.systemFontOfSize(PAGE_FONT_SIZE)
    private val closeButton = UIButton(CGRectMake(16.0, 52.0, 92.0, 40.0))

    private var pages: List<EpubPage> = emptyList()
    private var currentIndex: Int = startPage.coerceAtLeast(0)
    private var paginated = false

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

    init {
        dataSource = pageSource
        delegate = pageDelegate
        // Initial controllers are set in viewDidLayoutSubviews, once the page grid
        // can be computed from the real bounds.
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = PAPER_COLOR
        closeButton.setTitle("Lukk", UIControlStateNormal)
        closeButton.setTitleColor(CLOSE_BUTTON_COLOR, UIControlStateNormal)
        val closeAction = UIAction.actionWithHandler { selfRef.value?.closeReader() }
        closeButton.addAction(closeAction, UIControlEventTouchUpInside)
        view.addSubview(closeButton)
        view.bringSubviewToFront(closeButton)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // UIPageViewController adds each page's view above ours on every turn, so
        // re-assert the close button on top after layout.
        view.bringSubviewToFront(closeButton)
        if (!paginated) paginateIfPossible()
    }

    private fun paginateIfPossible() {
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        if (width <= 0.0 || height <= 0.0) return
        val columns = ((width - HORIZONTAL_PADDING * 2.0) /
            (pageFont.pointSize * CHARACTER_WIDTH_RATIO)).toInt().coerceIn(MIN_COLUMNS, MAX_GRID)
        val rows = ((height - TOP_RESERVE - BOTTOM_RESERVE) /
            pageFont.lineHeight).toInt().coerceIn(MIN_ROWS, MAX_GRID)
        val paginatedPages = EpubPaginator.paginate(book.chapters, columns, rows)
        if (paginatedPages.isEmpty()) return
        pages = paginatedPages
        paginated = true
        currentIndex = startPage.coerceIn(0, paginatedPages.size - 1)
        showSinglePage(UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward)
        onPageChanged(currentIndex, pages.size)
    }

    private fun controllerAt(index: Int): EpubPageViewController? {
        if (index !in pages.indices) return null
        return EpubPageViewController(index, pages[index], pageFont)
    }

    private fun controllerBefore(controller: UIViewController): UIViewController? {
        val index = (controller as? EpubPageViewController)?.pageIndex ?: return null
        if (index <= 0) return null
        return controllerAt(index - 1)
    }

    private fun controllerAfter(controller: UIViewController): UIViewController? {
        val index = (controller as? EpubPageViewController)?.pageIndex ?: return null
        if (index >= pages.size - 1) return null
        return controllerAt(index + 1)
    }

    private fun showSinglePage(direction: UIPageViewControllerNavigationDirection) {
        val controller = controllerAt(currentIndex) ?: return
        setViewControllers(listOf(controller), direction, false, null)
    }

    /**
     * iPad landscape (regular width) gets the two-up `.mid` spine. If anything is
     * off (no pages, a single page) we fall back to `.min`, which always works.
     */
    private fun spineLocationForOrientation(orientation: Long): Long {
        if (pages.size < 2) return UIPageViewControllerSpineLocationMin
        val landscape = orientation == UIInterfaceOrientationLandscapeLeft ||
            orientation == UIInterfaceOrientationLandscapeRight
        val regularWidth = traitCollection().horizontalSizeClass == UIUserInterfaceSizeClassRegular
        if (!landscape || !regularWidth) {
            doubleSided = false
            showSinglePage(UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward)
            return UIPageViewControllerSpineLocationMin
        }
        doubleSided = true
        currentIndex -= currentIndex % 2
        val left = controllerAt(currentIndex)
        val right = controllerAt(currentIndex + 1)
        if (left == null || right == null) {
            doubleSided = false
            showSinglePage(UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward)
            return UIPageViewControllerSpineLocationMin
        }
        setViewControllers(
            viewControllers = listOf(left, right),
            direction = UIPageViewControllerNavigationDirection.UIPageViewControllerNavigationDirectionForward,
            animated = false,
            completion = null,
        )
        onPageChanged(currentIndex, pages.size)
        return UIPageViewControllerSpineLocationMid
    }

    private fun onSettled(controller: EpubPageViewController) {
        currentIndex = controller.pageIndex
        onPageChanged(currentIndex, pages.size)
    }

    private fun closeReader() {
        dismissViewControllerAnimated(true, null)
    }
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
    private val onSettled: (EpubPageViewController) -> Unit,
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
        val controller = pageViewController.viewControllers?.firstOrNull() as? EpubPageViewController ?: return
        onSettled(controller)
    }
}

/** One page of text on paper-white, with the chapter title and page counter. */
private class EpubPageViewController(
    val pageIndex: Int,
    private val page: EpubPage,
    private val pageFont: UIFont,
) : UIViewController(null, null) {

    private val titleLabel = UILabel()
    private val textLabel = UILabel()
    private val footerLabel = UILabel()

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = PAPER_COLOR

        titleLabel.text = page.title ?: ""
        titleLabel.font = UIFont.systemFontOfSize(TITLE_FONT_SIZE)
        titleLabel.textColor = DIM_COLOR
        titleLabel.textAlignment = NSTextAlignmentCenter
        titleLabel.numberOfLines = 1

        textLabel.text = page.text
        textLabel.font = pageFont
        textLabel.textColor = INK_COLOR
        textLabel.numberOfLines = 0
        textLabel.lineBreakMode = NSLineBreakByWordWrapping

        footerLabel.text = "${page.globalIndex + 1} / ${page.globalCount}"
        footerLabel.font = UIFont.systemFontOfSize(FOOTER_FONT_SIZE)
        footerLabel.textColor = DIM_COLOR
        footerLabel.textAlignment = NSTextAlignmentCenter
        footerLabel.numberOfLines = 1

        view.addSubview(titleLabel)
        view.addSubview(textLabel)
        view.addSubview(footerLabel)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        val width = CGRectGetWidth(view.bounds)
        val height = CGRectGetHeight(view.bounds)
        val contentWidth = (width - HORIZONTAL_PADDING * 2.0).coerceAtLeast(1.0)
        titleLabel.setFrame(CGRectMake(HORIZONTAL_PADDING, TOP_RESERVE - 28.0, contentWidth, 20.0))
        footerLabel.setFrame(CGRectMake(HORIZONTAL_PADDING, (height - BOTTOM_RESERVE + 12.0).coerceAtLeast(1.0), contentWidth, 18.0))
        textLabel.setFrame(
            CGRectMake(
                HORIZONTAL_PADDING,
                TOP_RESERVE,
                contentWidth,
                (height - TOP_RESERVE - BOTTOM_RESERVE).coerceAtLeast(1.0),
            ),
        )
    }
}

private const val PAGE_FONT_SIZE = 18.0
private const val TITLE_FONT_SIZE = 12.0
private const val FOOTER_FONT_SIZE = 11.0
private const val CHARACTER_WIDTH_RATIO = 0.55
private const val HORIZONTAL_PADDING = 24.0
private const val TOP_RESERVE = 88.0
private const val BOTTOM_RESERVE = 48.0
private const val MIN_COLUMNS = 8
private const val MIN_ROWS = 4
private const val MAX_GRID = 400

private val PAPER_COLOR = UIColor(red = 0.984, green = 0.973, blue = 0.941, alpha = 1.0)
private val INK_COLOR = UIColor(red = 0.13, green = 0.12, blue = 0.11, alpha = 1.0)
private val DIM_COLOR = UIColor(red = 0.45, green = 0.44, blue = 0.42, alpha = 1.0)
private val CLOSE_BUTTON_COLOR = UIColor(red = 0.25, green = 0.24, blue = 0.22, alpha = 1.0)