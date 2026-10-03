@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookrio.reader.readium

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.reader.R
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url

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
 *  - a single tap in the reading area toggles the chrome through Readium's
 *    supported `VisualNavigator.addInputListener` API (swipes/selection untouched);
 *  - chrome is a true overlay: toggling it never resizes the navigator, so the
 *    viewport, reflow and Locator stay stable;
 *  - the current chapter is derived from the Readium Locator matched against the
 *    TOC (or the spine fallback) and updates on swipes, TOC jumps and resume.
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

    var publication by remember { mutableStateOf<Publication?>(null) }
    var chapters by remember { mutableStateOf<BookChapters?>(null) }
    var initialLocator by remember { mutableStateOf<Locator?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bookTitle by remember { mutableStateOf("") }
    // Clean reading state: controls hidden, system bars immersive.
    var showControls by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var fontSize by remember { mutableStateOf(1.0) }
    var theme by remember { mutableStateOf(Theme.DARK) }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    var progressPercent by remember { mutableStateOf(0) }
    var activeChapterIndex by remember { mutableStateOf(-1) }

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
        progressPercent = ((saved?.progressPercent ?: 0f) * 100).toInt()
        initialLocator = saved?.anchorCfi?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        }
        ReadiumPublicationOpener.open(context.applicationContext, filePath, fileUri)
            .onSuccess { pub ->
                publication = pub
                chapters = buildBookChapters(pub)
                initialLocator?.let { locator ->
                    activeChapterIndex = chapterIndexForLocator(chapters!!.entries, locator.href)
                }
            }
            .onFailure { error = it.message ?: context.getString(R.string.rdr_open_error) }
    }

    // Immersive system bars: hidden while reading, visible while the chrome is up.
    // Applied in the Readium route itself (ReaderScreen returns early for EPUB).
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

    fun applyPrefs() {
        navigator?.submitPreferences(EpubPreferences(fontSize = fontSize, theme = theme))
    }

    // Read the REAL window insets through the Android API. Compose's WindowInsets
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
                    Box(Modifier.fillMaxSize()) {
                        val factory = remember(pub) {
                            EpubNavigatorFactory(pub).createFragmentFactory(
                                initialLocator = initialLocator,
                                initialPreferences = EpubPreferences(fontSize = fontSize, theme = theme),
                                // Bookiro owns insets: never let Readium add its own
                                // system-bar padding, which caused wrong page sizing.
                                configuration = EpubNavigatorFragment.Configuration(
                                    shouldApplyInsetsPadding = false,
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

                        // Supported gesture hook: one tap toggles the chrome. Swipes and
                        // long-press/selection are not consumed (onDrag/onTap only).
                        DisposableEffect(navigator) {
                            val nav = navigator ?: return@DisposableEffect onDispose { }
                            val listener = object : InputListener {
                                override fun onTap(event: TapEvent): Boolean {
                                    showControls = !showControls
                                    return true
                                }
                            }
                            nav.addInputListener(listener)
                            onDispose { nav.removeInputListener(listener) }
                        }

                        LaunchedEffect(navigator) {
                            navigator?.currentLocator?.collect { locator ->
                                progressPercent =
                                    ((locator.locations.totalProgression ?: 0.0) * 100).toInt()
                                activeChapterIndex = chapterIndexForLocator(
                                    chapters?.entries.orEmpty(),
                                    locator.href,
                                )
                                persistLocator(db, bookId, locator, scope)
                            }
                        }

                        // True overlay: bars are aligned children of the Box, so the
                        // navigator keeps its full size and never reflows.
                        val activeEntry = chapters?.entries?.getOrNull(activeChapterIndex)
                        val activeChapterLabel = activeEntry?.let { entry ->
                            entry.title.ifBlank {
                                stringResource(R.string.rdr_section_n, activeChapterIndex + 1)
                            }
                        }
                        if (showControls) {
                            ReaderTopBar(
                                modifier = Modifier.align(Alignment.TopCenter),
                                title = bookTitle,
                                chapter = activeChapterLabel,
                                percent = progressPercent,
                                onBack = onBack,
                                onHide = { showControls = false },
                            )
                            ReaderBottomBar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onContents = { showContents = true },
                                onFontDown = { fontSize = (fontSize - 0.1).coerceAtLeast(0.6); applyPrefs() },
                                onFontUp = { fontSize = (fontSize + 0.1).coerceAtMost(3.0); applyPrefs() },
                                onTheme = {
                                    theme = when (theme) {
                                        Theme.DARK -> Theme.LIGHT
                                        Theme.LIGHT -> Theme.SEPIA
                                        Theme.SEPIA -> Theme.DARK
                                    }
                                    applyPrefs()
                                },
                                onHide = { showControls = false },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showContents && chapters != null) {
        val chapterList = chapters!!.entries
        ModalBottomSheet(
            onDismissRequest = { showContents = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = OmarchyColors.Panel,
            contentColor = OmarchyColors.Fg,
        ) {
            Text(
                stringResource(R.string.rdr_contents),
                style = ShelfTypography.TitleMedium,
                color = OmarchyColors.FgBright,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!chapters!!.fromTableOfContents) {
                Text(
                    stringResource(R.string.rdr_no_builtin_toc),
                    color = OmarchyColors.Dim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            LazyColumn(modifier = Modifier.navigationBarsPadding()) {
                itemsIndexed(chapterList, key = { index, _ -> index }) { index, entry ->
                    val active = index == activeChapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showContents = false
                                navigator?.go(entry.link, animated = true)
                            }
                            .padding(
                                start = (20 + entry.depth * 16).dp,
                                end = 20.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title.ifBlank {
                                    stringResource(R.string.rdr_section_n, index + 1)
                                },
                                color = if (active) OmarchyColors.Accent else OmarchyColors.FgBright,
                                fontSize = if (entry.depth == 0) 15.sp else 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.unnamed) {
                                Text(
                                    stringResource(R.string.rdr_unnamed_section),
                                    color = OmarchyColors.Dim,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        if (active) {
                            Text("•", color = OmarchyColors.Accent, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    modifier: Modifier,
    title: String,
    chapter: String?,
    percent: Int,
    onBack: () -> Unit,
    onHide: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel)
            .statusBarsPadding()
            .clickable { onHide() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.rdr_back),
                tint = OmarchyColors.Accent,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = OmarchyColors.FgBright,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
            )
            Text(
                chapter?.takeIf { it.isNotBlank() } ?: "$percent %",
                color = OmarchyColors.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
            )
        }
        Text("$percent %", color = OmarchyColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun ReaderBottomBar(
    modifier: Modifier,
    onContents: () -> Unit,
    onFontDown: () -> Unit,
    onFontUp: () -> Unit,
    onTheme: () -> Unit,
    onHide: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel)
            .navigationBarsPadding()
            .clickable { onHide() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        ChromeButton(stringResource(R.string.rdr_contents), Icons.Default.Menu, onContents)
        ChromeButton("A-", Icons.Default.FormatSize, onFontDown)
        ChromeButton("A+", Icons.Default.FormatSize, onFontUp)
        ChromeButton(stringResource(R.string.rdr_theme), Icons.Default.Palette, onTheme)
    }
}

@Composable
private fun ChromeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(icon, contentDescription = label, tint = OmarchyColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = OmarchyColors.Dim, fontSize = 10.sp)
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
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.rdr_back),
            color = OmarchyColors.Accent,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onBack() },
        )
    }
}

private fun persistLocator(
    db: ShelfDatabase,
    bookId: Long,
    locator: Locator,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val json = runCatching { locator.toJSON().toString() }.getOrNull() ?: return
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