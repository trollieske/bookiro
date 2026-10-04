@file:OptIn(
    org.readium.r2.shared.ExperimentalReadiumApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.bookrio.reader.readium

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.BookmarkTypeEntity
import com.bookrio.data.local.entity.HighlightEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.Url
import kotlin.math.abs

/**
 * One entry in the in-book navigation list (a real TOC entry, or a spine fallback).
 */
internal data class ChapterEntry(
    val link: Link,
    val title: String,
    val depth: Int,
    /** True when the publication provides no title (spine fallback / blank TOC title). */
    val unnamed: Boolean,
    /** Normalized resource path used to match against the current Locator. */
    val resourceKey: String,
)

/** Result of reading a publication's navigation data. */
internal data class BookChapters(
    /** Hierarchical TOC, or an honest unnamed fallback list built from the spine. */
    val entries: List<ChapterEntry>,
    val fromTableOfContents: Boolean,
)

/** Flattens a hierarchical TOC into depth-annotated entries. */
internal fun flattenTocEntries(links: List<Link>, keyOf: (Link) -> String): List<ChapterEntry> {
    val entries = ArrayList<ChapterEntry>()
    fun walk(links: List<Link>, depth: Int) {
        links.forEach { link ->
            val raw = link.title?.trim().orEmpty()
            entries.add(
                ChapterEntry(
                    link = link,
                    title = raw.ifBlank { fileNameLabel(link.href.toString()) },
                    depth = depth,
                    unnamed = raw.isBlank(),
                    resourceKey = keyOf(link),
                )
            )
            walk(link.children, depth + 1)
        }
    }
    walk(links, 0)
    return entries
}

/** Builds a clearly-unnamed section list from the spine (used when the TOC is empty). */
internal fun spineFallbackEntries(links: List<Link>, keyOf: (Link) -> String): List<ChapterEntry> =
    links.mapIndexed { index, link ->
        val raw = link.title?.trim().orEmpty()
        ChapterEntry(
            link = link,
            title = raw,
            depth = 0,
            unnamed = raw.isBlank(),
            resourceKey = keyOf(link),
        )
    }

/**
 * Builds the TOC list. Prefers the real `publication.tableOfContents` (hierarchical).
 * When that is empty, falls back to `publication.readingOrder` (spine) as clearly
 * unnamed "Seksjon N" entries — no invented timed/fixed chapters.
 */
internal fun buildBookChapters(publication: Publication): BookChapters {
    val keyOf: (Link) -> String = { link ->
        runCatching { publication.url(link).toString() }.getOrDefault(link.href.toString())
    }
    if (publication.tableOfContents.isNotEmpty()) {
        val entries = flattenTocEntries(publication.tableOfContents, keyOf)
        if (entries.isNotEmpty()) return BookChapters(entries, fromTableOfContents = true)
    }
    return BookChapters(spineFallbackEntries(publication.readingOrder, keyOf), fromTableOfContents = false)
}

private fun fileNameLabel(href: String): String {
    val file = href.substringBefore('#').substringAfterLast('/').substringBeforeLast('.')
    return file.replace(Regex("[-_]+"), " ").trim()
}

/** Matches a locator href against a chapter resource key (path-suffix aware). */
internal fun chapterIndexForLocator(entries: List<ChapterEntry>, locatorHref: Url): Int {
    val target = normalize(locatorHref.toString())
    var best = -1
    entries.forEachIndexed { index, entry ->
        if (resourcesMatch(entry.resourceKey, target)) best = index
    }
    return best
}

private fun normalize(value: String): String =
    value.substringBefore('#').substringBefore('?').trimStart('/')

private fun resourcesMatch(a: String, b: String): Boolean {
    val x = normalize(a)
    val y = normalize(b)
    return x == y || x.endsWith("/$y") || y.endsWith("/$x")
}

/**
 * Readium-backed EPUB reader: opens the publication with Readium and renders it
 * with the stable [EpubNavigatorFragment] hosted inside Compose. Bookiro owns the
 * chrome only — Readium lays out and paginates the EPUB itself.
 *
 * Behaviour:
 *  - starts in a clean reading state (controls and system bars hidden);
 *  - a single tap in the reading area toggles the chrome through Readium's supported
 *    `VisualNavigator.addInputListener` API. The listener always returns `false`, so
 *    Readium keeps ownership of link activation, selection and drag gestures; the tap is
 *    classified asynchronously (link / image / plain text) and any locator change during
 *    that round-trip suppresses the chrome toggle;
 *  - chrome is a true overlay: toggling it never resizes the navigator, so the
 *    viewport, reflow and Locator stay stable;
 *  - the current chapter comes from the Readium Locator matched against the TOC (or the
 *    spine fallback) and updates on swipes, TOC jumps and resume;
 *  - bookmarks/highlights/search use the existing Room tables and real Readium Locators.
 */
@Composable
internal fun ReadiumEpubReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val db = remember { ShelfDatabase.getInstance(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val preferencesStore = remember { ReadiumPreferencesStore(context.applicationContext) }

    var publication by remember { mutableStateOf<Publication?>(null) }
    var chapters by remember { mutableStateOf<BookChapters?>(null) }
    var initialLocator by remember { mutableStateOf<Locator?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bookTitle by remember { mutableStateOf("") }
    // Clean reading state: controls hidden, system bars immersive.
    var showControls by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showMarks by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showPreferences by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(preferencesStore.load()) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var progressPercent by remember { mutableStateOf(0) }
    var activeChapterIndex by remember { mutableStateOf(-1) }
    // Full-screen illustration shown when the reader taps an image.
    var zoomImage by remember { mutableStateOf<Bitmap?>(null) }
    var draggedSeek by remember { mutableStateOf<Float?>(null) }
    var hud by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<ReadiumSearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var activeHighlight by remember { mutableStateOf<HighlightEntity?>(null) }

    val bookmarks by remember(bookId) { db.bookmarkDao().observeByBook(bookId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val highlights by remember(bookId) { db.highlightDao().observeByBook(bookId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    LaunchedEffect(hud) {
        if (hud != null) {
            delay(1800)
            hud = null
        }
    }

    // ── Open the publication + resolve the resume position (new Locator, or legacy best effort) ──
    LaunchedEffect(bookId) {
        val book = runCatching { db.bookDao().getById(bookId) }.getOrNull()
        bookTitle = book?.title ?: ""
        val filePath = book?.filePath
        val fileUri = book?.fileUri
        if (filePath.isNullOrBlank() && fileUri.isNullOrBlank()) {
            error = context.getString(R.string.rdr_error_file_not_found)
            return@LaunchedEffect
        }
        val saved = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
        progressPercent = (((saved?.progressPercent ?: 0f) * 100).toInt()).coerceIn(0, 100)

        ReadiumPublicationOpener.open(context.applicationContext, filePath, fileUri)
            .onSuccess { pub ->
                val chapterData = buildBookChapters(pub)
                chapters = chapterData
                val resolved = ReadiumResume.resolve(
                    pub,
                    chapterData,
                    ReadiumResume.progressIntent(saved),
                )
                initialLocator = resolved?.locator
                if (resolved != null && resolved.approximate) {
                    // Additive migration: remember the derived Readium Locator, but keep every
                    // legacy column (chapter/page/percent) untouched as a fallback.
                    persistDerivedAnchor(db, bookId, resolved.locator, scope)
                    hud = context.getString(R.string.rdr_migrated_position)
                }
                publication = pub
                activeChapterIndex = initialLocator
                    ?.let { chapterIndexForLocator(chapterData.entries, it.href) }
                    ?: -1
            }
            .onFailure { error = it.message ?: context.getString(R.string.rdr_open_error) }
    }

    // ── Immersive system bars: hidden while reading, visible while the chrome is up. ──
    DisposableEffect(showControls, activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (showControls) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        DisposableEffect(activity) {
            val window = activity?.window ?: return@DisposableEffect onDispose { }
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
            onDispose { }
        }
    }

    fun activeChapterTitle(): String? =
        chapters?.entries?.getOrNull(activeChapterIndex)?.title?.takeIf { it.isNotBlank() }

    fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.rdr_share)))
        }.onFailure { hud = context.getString(R.string.rdr_share_unavailable) }
    }

    fun sharePosition() {
        val chapter = activeChapterTitle()
        val where = if (chapter.isNullOrBlank()) {
            context.getString(R.string.rdr_percent_only, progressPercent)
        } else {
            context.getString(R.string.rdr_chapter_percent, chapter, progressPercent)
        }
        shareText(context.getString(R.string.rdr_share_locator, bookTitle, where))
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.rdr_selection_clip_label), text),
        )
        hud = context.getString(R.string.rdr_copied)
    }

    fun saveHighlight(selection: Selection) {
        val entity = ReadiumHighlights.toEntity(bookId, selection.locator) ?: return
        scope.launch {
            runCatching { db.highlightDao().insert(entity) }
            runCatching { navigator?.clearSelection() }
            hud = context.getString(R.string.rdr_highlight_saved)
        }
    }

    /**
     * Applies preferences through `submitPreferences`, then defensively re-anchors if the
     * reflow moved the Locator more than 2 % of the book. Readium invalidates the pager and
     * restores the current Locator for scroll-mode changes; this guard covers font/theme/margin
     * reflows where the web view could drift.
     */
    fun applyPreferences(next: ReadiumReaderPreferences) {
        preferences = next
        preferencesStore.save(next)
        val nav = navigator ?: return
        val anchor = nav.currentLocator.value
        nav.submitPreferences(next.toEpubPreferences())
        scope.launch {
            delay(450)
            val current = navigator?.currentLocator?.value ?: return@launch
            val from = anchor.locations.totalProgression ?: anchor.locations.progression ?: return@launch
            val to = current.locations.totalProgression ?: current.locations.progression ?: return@launch
            if (abs(from - to) > 0.02) {
                navigator?.go(anchor, animated = false)
            }
        }
    }

    fun toggleBookmark() {
        val nav = navigator ?: return
        val locator = nav.currentLocator.value
        val json = ReadiumResume.locatorJson(locator) ?: return
        val pct = (locator.locations.totalProgression ?: locator.locations.progression ?: 0.0)
            .toFloat().coerceIn(0f, 1f)
        val chapterIndex = activeChapterIndex.takeIf { it >= 0 }
        val chapterTitle = activeChapterTitle()
        val snippet = bookmarkSnippet(context, locator, progressPercent)
        scope.launch(Dispatchers.IO) {
            val message = runCatching {
                val existing = db.bookmarkDao().getNear(bookId, pct)
                if (existing != null) {
                    db.bookmarkDao().delete(existing)
                    context.getString(R.string.rdr_bookmark_removed)
                } else {
                    db.bookmarkDao().insert(
                        BookmarkEntity(
                            bookId = bookId,
                            type = BookmarkTypeEntity.GENERIC,
                            title = chapterTitle ?: context.getString(R.string.rdr_bookmark),
                            snippet = snippet,
                            pageIndex = null,
                            pageOffset = null,
                            anchorHref = locator.href.toString(),
                            anchorCfi = json,
                            positionMs = null,
                            chapterIndex = chapterIndex,
                            positionPercent = pct,
                        ),
                    )
                    context.getString(R.string.rdr_bookmark_saved)
                }
            }.getOrElse { context.getString(R.string.rdr_open_error) }
            hud = message
        }
    }

    fun jumpToBookmark(bookmark: BookmarkEntity) {
        val nav = navigator ?: return
        val pub = publication ?: return
        val data = chapters ?: return
        val resolved = ReadiumResume.resolve(pub, data, ReadiumResume.bookmarkIntent(bookmark))
        if (resolved == null) {
            hud = context.getString(R.string.rdr_legacy_bookmark_unavailable)
            return
        }
        showMarks = false
        nav.go(resolved.locator, animated = true)
    }

    fun removeBookmark(bookmark: BookmarkEntity) {
        scope.launch(Dispatchers.IO) {
            runCatching { db.bookmarkDao().delete(bookmark) }
            hud = context.getString(R.string.rdr_bookmark_removed)
        }
    }

    fun jumpToHighlight(highlight: HighlightEntity) {
        val nav = navigator ?: return
        val locator = ReadiumHighlights.locatorOf(highlight)
        if (locator == null) {
            hud = context.getString(R.string.rdr_legacy_bookmark_unavailable)
            return
        }
        activeHighlight = null
        showMarks = false
        nav.go(locator, animated = true)
    }

    fun removeHighlight(highlight: HighlightEntity) {
        activeHighlight = null
        scope.launch(Dispatchers.IO) {
            runCatching { db.highlightDao().delete(highlight) }
            hud = context.getString(R.string.rdr_highlight_removed)
        }
    }

    fun startSearch() {
        val pub = publication ?: return
        val query = searchQuery
        if (query.isBlank()) return
        searchJob?.cancel()
        searching = true
        searchResults = emptyList()
        val data = chapters
        searchJob = scope.launch {
            val hits = runCatching { searchEpub(pub, query, data) }.getOrDefault(emptyList())
            if (isActive) {
                searchResults = hits
                searching = false
            }
        }
    }

    fun closeSearch() {
        showSearch = false
        searchJob?.cancel()
        searching = false
    }

    // ── Read the REAL window insets through the Android API. Compose's WindowInsets ──
    // were already consumed by the app shell, which is why the camera cutout was
    // ignored. `displayCutout` is physical and constant, so padding the reading
    // surface from it keeps the viewport stable while the menu is toggled.
    val localView = LocalView.current
    val density = LocalDensity.current
    var windowInsets by remember { mutableStateOf(WindowInsetsCompat.CONSUMED) }
    DisposableEffect(localView) {
        ViewCompat.setOnApplyWindowInsetsListener(localView) { _, insets ->
            windowInsets = insets
            insets
        }
        ViewCompat.requestApplyInsets(localView)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(localView, null) }
    }
    val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
    val readerPadding = with(density) {
        PaddingValues(
            start = cutout.left.toDp() + 8.dp,
            end = cutout.right.toDp() + 8.dp,
            top = cutout.top.toDp() + 10.dp,
            bottom = cutout.bottom.toDp() + 10.dp,
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(OmarchyColors.Bg)) {
        when {
            error != null -> ReaderMessage(error!!, onBack)
            publication == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = OmarchyColors.Accent)
            }
            else -> {
                val pub = publication!!
                if (activity == null) {
                    ReaderMessage(context.getString(R.string.rdr_reader_unavailable), onBack)
                } else {
                    Box(Modifier.fillMaxSize().background(readiumThemeBackground(preferences.themeName))) {
                        val factory = remember(pub) {
                            EpubNavigatorFactory(pub).createFragmentFactory(
                                initialLocator = initialLocator,
                                initialPreferences = preferences.toEpubPreferences(),
                                // Bookiro owns insets: never let Readium add its own
                                // system-bar padding, which caused wrong page sizing.
                                configuration = EpubNavigatorFragment.Configuration(
                                    shouldApplyInsetsPadding = false,
                                    selectionActionModeCallback = ReadiumSelectionActionModeCallback(
                                        copyLabel = context.getString(android.R.string.copy),
                                        shareLabel = context.getString(R.string.rdr_share),
                                        highlightLabel = context.getString(R.string.rdr_highlight),
                                        scope = scope,
                                        selectionProvider = { navigator?.currentSelection() },
                                        onCopy = { text -> copyToClipboard(text) },
                                        onShare = { text -> shareText(text) },
                                        onHighlight = { selection -> saveHighlight(selection) },
                                    ),
                                ),
                            )
                        }
                        DisposableEffect(factory) {
                            activity.supportFragmentManager.fragmentFactory = factory
                            onDispose { }
                        }
                        // The reading surface is inset by the CONSTANT device safe area
                        // (camera cutout / rounded corners) plus a small margin. It does
                        // NOT depend on system-bar visibility, so the viewport never
                        // changes when the chrome is toggled and the text stays put.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(readerPadding),
                        ) {
                            AndroidFragment(
                                EpubNavigatorFragment::class.java,
                                modifier = Modifier.fillMaxSize(),
                            ) { fragment ->
                                navigator = fragment
                            }
                        }

                        // A tap opens an illustration for zooming, leaves link activation to
                        // Readium, or toggles the chrome. The listener never consumes the
                        // gesture: Readium keeps selecting, dragging and navigating links.
                        DisposableEffect(navigator, publication) {
                            val nav = navigator ?: return@DisposableEffect onDispose { }
                            val pub = publication ?: return@DisposableEffect onDispose { }
                            val densityScale = context.resources.displayMetrics.density
                            val listener = object : InputListener {
                                override fun onTap(event: TapEvent): Boolean {
                                    val before = nav.currentLocator.value
                                    scope.launch {
                                        val target = runCatching {
                                            nav.tapTargetAt(event.point.x, event.point.y, densityScale)
                                        }.getOrNull()
                                        val after = nav.currentLocator.value
                                        if (after.href != before.href || after.locations != before.locations) {
                                            // A link or a page change happened while we hit-tested:
                                            // never fight Readium by also toggling the chrome.
                                            return@launch
                                        }
                                        val src = target?.imageSrc
                                        if (src != null) {
                                            val base = runCatching { pub.url(before) }.getOrNull()
                                            val bitmap = runCatching {
                                                loadReadiumImage(pub, base, src)
                                            }.getOrNull()
                                            if (bitmap != null) {
                                                zoomImage = bitmap
                                                return@launch
                                            }
                                        }
                                        if (target?.isLink == true) return@launch
                                        showControls = !showControls
                                    }
                                    return false
                                }
                            }
                            nav.addInputListener(listener)
                            onDispose { nav.removeInputListener(listener) }
                        }

                        LaunchedEffect(navigator) {
                            val nav = navigator ?: return@LaunchedEffect
                            nav.currentLocator.collect { locator ->
                                progressPercent = (((locator.locations.totalProgression
                                    ?: locator.locations.progression
                                    ?: 0.0) * 100).toInt()).coerceIn(0, 100)
                                activeChapterIndex = chapterIndexForLocator(
                                    chapters?.entries.orEmpty(),
                                    locator.href,
                                )
                                persistLocator(db, bookId, locator, scope)
                                // Centre block images and mark them zoomable on each page.
                                runCatching { nav.evaluateJavascript(READER_IMAGE_CSS_JS) }
                            }
                        }

                        // Submit the persistent highlight rows to Readium's decorator whenever
                        // the database changes. Readium only supports the Highlight style on
                        // reflowable content; if unsupported, the list still works as a jump
                        // target (the sheet says so implicitly by never having fake in-page marks).
                        LaunchedEffect(navigator, highlights) {
                            val nav = navigator ?: return@LaunchedEffect
                            val supported = runCatching {
                                nav.supportsDecorationStyle(Decoration.Style.Highlight::class)
                            }.getOrDefault(false)
                            if (!supported) return@LaunchedEffect
                            val decorations = highlights.mapNotNull { ReadiumHighlights.toDecoration(it) }
                            runCatching { nav.applyDecorations(decorations, ReadiumHighlights.GROUP) }
                        }

                        val latestHighlights by rememberUpdatedState(highlights)
                        DisposableEffect(navigator) {
                            val nav = navigator ?: return@DisposableEffect onDispose { }
                            val listener = object : DecorableNavigator.Listener {
                                override fun onDecorationActivated(
                                    event: DecorableNavigator.OnActivatedEvent,
                                ): Boolean {
                                    val id = ReadiumHighlights.entityId(event.decoration.id)
                                        ?: return false
                                    activeHighlight = latestHighlights.firstOrNull { it.id == id }
                                    return activeHighlight != null
                                }
                            }
                            nav.addDecorationListener(ReadiumHighlights.GROUP, listener)
                            onDispose { nav.removeDecorationListener(listener) }
                        }

                        // True overlay: bars are aligned children of the Box, so the
                        // navigator keeps its full size and never reflows.
                        val activeChapterLabel = activeChapterTitle()
                        if (showControls) {
                            ReadiumReaderTopBar(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .statusBarsPadding(),
                                title = bookTitle,
                                chapter = activeChapterLabel
                                    ?: stringResource(
                                        R.string.rdr_section_n,
                                        activeChapterIndex + 1,
                                    ).takeIf { activeChapterIndex >= 0 },
                                percent = progressPercent,
                                draggedSeek = draggedSeek,
                                onSeekChange = { draggedSeek = it },
                                onSeekCommit = { fraction ->
                                    draggedSeek = null
                                    val pub = publication
                                    if (pub != null) {
                                        scope.launch {
                                            val locator = runCatching {
                                                pub.locateProgression(fraction.toDouble())
                                            }.getOrNull()
                                            if (locator != null) {
                                                navigator?.go(locator, animated = false)
                                            }
                                        }
                                    }
                                },
                                onBack = onBack,
                            )
                            ReadiumReaderBottomBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onContents = { showContents = true },
                                onMarks = { showMarks = true },
                                onSearch = { showSearch = true },
                                onPreferences = { showPreferences = true },
                                onShare = { sharePosition() },
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ReadiumHud(hud)
        }
    }

    if (showContents && chapters != null) {
        val chapterData = chapters!!
        ReadiumContentsSheet(
            chapters = chapterData,
            activeChapterIndex = activeChapterIndex,
            onJump = { entry ->
                showContents = false
                navigator?.go(entry.link, animated = true)
            },
            onDismiss = { showContents = false },
        )
    }

    if (showMarks) {
        ReadiumMarksSheet(
            bookmarks = bookmarks,
            highlights = highlights,
            onAddBookmark = { toggleBookmark() },
            onRemoveBookmark = { removeBookmark(it) },
            onJumpBookmark = { jumpToBookmark(it) },
            onRemoveHighlight = { removeHighlight(it) },
            onJumpHighlight = { jumpToHighlight(it) },
            onDismiss = { showMarks = false },
        )
    }

    if (showSearch) {
        ReadiumSearchSheet(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { startSearch() },
            searching = searching,
            results = searchResults,
            onJump = { hit ->
                closeSearch()
                navigator?.go(hit.locator, animated = true)
            },
            onDismiss = { closeSearch() },
        )
    }

    if (showPreferences) {
        ReadiumPreferencesSheet(
            preferences = preferences,
            onPreferencesChange = { applyPreferences(it) },
            onDismiss = { showPreferences = false },
        )
    }

    activeHighlight?.let { highlight ->
        ReadiumHighlightDialog(
            highlight = highlight,
            onJump = { jumpToHighlight(highlight) },
            onRemove = { removeHighlight(highlight) },
            onDismiss = { activeHighlight = null },
        )
    }

    zoomImage?.let { bmp ->
        ReadiumImageZoomDialog(
            bitmap = bmp,
            closeContentDescription = stringResource(R.string.rdr_close),
            onDismiss = { zoomImage = null },
        )
    }
}

@Composable
private fun ReaderMessage(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = OmarchyColors.Fg, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onBack) {
            Text(stringResource(R.string.rdr_back), color = OmarchyColors.Accent)
        }
    }
}

private fun bookmarkSnippet(context: Context, locator: Locator, percent: Int): String {
    val highlight = locator.text.highlight.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (highlight.isNotEmpty()) return highlight.take(160)
    val before = locator.text.before.orEmpty().replace(Regex("\\s+"), " ").trim().takeLast(160)
    if (before.isNotEmpty()) return before
    return context.getString(R.string.rdr_percent_only, percent)
}

private fun persistLocator(
    db: ShelfDatabase,
    bookId: Long,
    locator: Locator,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val json = ReadiumResume.locatorJson(locator) ?: return
    val pct = (locator.locations.totalProgression ?: locator.locations.progression ?: 0.0).toFloat()
    scope.launch(Dispatchers.IO) {
        runCatching {
            val prior = db.progressDao().getByBook(bookId) ?: ReadingProgressEntity(bookId = bookId)
            db.progressDao().insertOrReplace(
                prior.copy(
                    anchorCfi = json,
                    anchorHref = locator.href.toString(),
                    progressPercent = pct.coerceIn(0f, 1f),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

/**
 * Writes the best-effort Readium Locator derived from legacy columns. Legacy `page_index`,
 * `chapter_index` and `progress_percent` are intentionally left in place: if the derived
 * position turns out wrong, the old values are still available.
 */
private fun persistDerivedAnchor(
    db: ShelfDatabase,
    bookId: Long,
    locator: Locator,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val json = ReadiumResume.locatorJson(locator) ?: return
    scope.launch(Dispatchers.IO) {
        runCatching {
            val prior = db.progressDao().getByBook(bookId)
                ?: ReadingProgressEntity(bookId = bookId)
            db.progressDao().insertOrReplace(
                prior.copy(
                    anchorCfi = json,
                    anchorHref = locator.href.toString(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}

/**
 * Background colour of the Readium reading surface, matched to Readium CSS
 * (`--RS__backgroundColor`): light `#FFFFFF`, sepia `#FAF4E8`, night `#000000`.
 * Painting the surrounding (cutout/margin) area with the same colour stops the app's
 * black background from showing as bars around the page.
 */
private fun readiumThemeBackground(themeName: String): Color = when (themeName) {
    ReadiumReaderPreferences.THEME_LIGHT -> Color(0xFFFFFFFF)
    ReadiumReaderPreferences.THEME_SEPIA -> Color(0xFFFAF4E8)
    else -> Color(0xFF000000)
}