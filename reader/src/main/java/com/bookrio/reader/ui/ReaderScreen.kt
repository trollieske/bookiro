package com.bookrio.reader.ui

import android.app.Activity
import android.util.Log
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.reader.engine.HtmlPageRenderer
import com.bookrio.reader.engine.RenderCoordinator
import com.bookrio.reader.engine.PageBitmapCache
import com.bookrio.reader.engine.PageIndexMath
import com.bookrio.reader.engine.PageNavAction
import com.bookrio.reader.engine.PageNavigator
import com.bookrio.reader.engine.ReaderBookState
import com.bookrio.reader.pageturn.*
import com.bookrio.reader.R
import com.bookrio.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Varighet på den enkle side-overgangen (slide + fade). Kort og subtil —
// ingen krøll, ingen shaders, ingen fantomside.
private const val PAGE_TRANSITION_MS = 180

// Diagnostikk for blink-jakt (slås AV i release): logger kun hendelser som kan
// påvirke synlig innhold — prepare-fullføring, cache-skriving og vist side.
// Aldri rått bokinnhold.
private const val READER_DIAG = false // gated: kun diagnostikkbygg
private fun readerDiag(msg: String) { if (READER_DIAG) Log.d("ReaderDiag", msg) }

// Gated diagnostikk for kontroll-overlegg (AV som standard): logger KUN mål og
// nøkler ved vis/skjul av kontroller — aldri rått bokinnhold, URL-er, filnavn eller base64.
private const val READER_LAYOUT_DIAG = false
private fun layoutDiag(msg: String) { if (READER_LAYOUT_DIAG) Log.d("ReaderLayoutDiag", msg) }

/**
 * Ren (Compose-fri) beregning av leserens innholdsboks.
 *
 * INVARIANT: kildebitmapmen fra HtmlPageRenderer (effectiveWidthPx × contentHeightPx)
 * og Canvas-destinasjonen (destinationWidthPx × destinationHeightPx) deler nøyaktig
 * samme logiske innholdsboks — samme mål, samme aspektratio. Padden påføres
 * nøyaktig ÉN gang til hver: aldri uavhengig X/Y-skalering, aldri dobbel padding.
 *
 * Målene utledes KUN av det stabile fullskjerm-lesevinduet (målt én gang) —
 * aldri av om kontrollene er synlige.
 */
internal data class ReaderContentBox(
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val hPadPx: Int,
    val vPadPx: Int,
) {
    val effectiveWidthPx: Int get() = (viewportWidthPx - 2 * hPadPx).coerceAtLeast(1)
    val contentHeightPx: Int get() = (viewportHeightPx - 2 * vPadPx).coerceAtLeast(1)

    /** Destinasjonsinnholdsboks i Canvas — identisk med rendererens bitmapdims. */
    val destinationWidthPx: Int get() = effectiveWidthPx
    val destinationHeightPx: Int get() = contentHeightPx

    val sourceAspectRatio: Float
        get() = effectiveWidthPx.toFloat() / contentHeightPx.toFloat()

    val destinationAspectRatio: Float
        get() = destinationWidthPx.toFloat() / destinationHeightPx.toFloat()

    /** Kanonisk størrelsesnøkkel — identisk med ReaderScreen sin "$w-$h". */
    fun sizeKey(): String = "$effectiveWidthPx-$contentHeightPx"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
    initialPositionPercent: Float? = null,
    onNavigateToOther: ((targetBookId: Long, positionMs: Long?, positionPercent: Float) -> Unit)? = null,
    onNavigateBack: (() -> Unit)? = null,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: ReaderViewModel = viewModel(factory = vmFactory ?: defaultReaderVmFactory()),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var showControls by rememberSaveable { mutableStateOf(false) }
    var showContentsSheet by rememberSaveable { mutableStateOf(false) }
    var showThemesSheet by rememberSaveable { mutableStateOf(false) }
    var showBookmarksSheet by rememberSaveable { mutableStateOf(false) }
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var orientationLocked by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val tracker = remember {
        (context.applicationContext as com.bookrio.core.di.AppDependenciesProvider).readingTracker
    }

    // Kompakt bokmerke-HUD: kort bekreftelse ("Bokmerke lagret" / "Bokmerke fjernet"),
    // aldri stor Snackbar — bokmerket endrer aldri sidetilstand.
    val bookmarkHud by vm.bookmarkHudMessage.collectAsStateWithLifecycle()
    LaunchedEffect(bookmarkHud) {
        if (bookmarkHud != null) {
            delay(1400)
            vm.clearBookmarkHud()
        }
    }

    LaunchedEffect(bookId) {
        tracker.startSession(bookId.toString(), com.bookrio.core.gamification.model.SessionSource.READER)
    }

    DisposableEffect(bookId) {
        onDispose {
            tracker.endSession()
        }
    }

    // ── Immersive Mode ──────────────────────────────────────────────────────
    DisposableEffect(showControls) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (showControls) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    var brightness by rememberSaveable { mutableFloatStateOf(-1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(brightness) { setWindowBrightness(context, brightness) }
    LaunchedEffect(bookId) { vm.load(bookId) }

    Box(Modifier.fillMaxSize()) {
        when {
            ui.error != null -> ErrorView(ui.error!!, onBack)
            ui.chapters.isEmpty() && ui.bookTitle.isNotBlank() -> ErrorView(
                message = stringResource(R.string.rdr_no_chapters),
                onBack = onBack,
            )
            ui.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val themeColors = readerThemeColors(ui.readerTheme)
                val bgC = Color(themeColors.paperColorInt)

                // ── FULLSKJERM-LESER: boken fyller ALLTID hele lesevinduet. Kontroller
                // er ren overlegg over den allerede viste siden — de påvirker aldri
                // leserens mål, bitmapgeometri eller leseflatens dimensjoner. ──
                Box(Modifier.matchParentSize().background(bgC)) {
                    RealBookSlideReader(
                        modifier = Modifier.matchParentSize(),
                        ui = ui,
                        showControls = showControls,
                        onToggleControls = { showControls = !showControls; if (!showControls) showContentsSheet = false; tracker.onUserInteraction() },
                        onPageTurned = { vm.onPageTurned(it); tracker.onUserInteraction() },
                        onTotalPages = { vm.onPageCountKnown(it) },
                        onJumpToChapterPage = { ch, page, pct -> vm.jumpToChapterPage(ch, page, pct) },
                        onHighlight = { hl -> vm.saveHighlight(hl.text, hl.colorInt, hl.pageIndex, hl.startPageOffset, hl.endPageOffset) }
                    )
                }

                // ═══ OVERLAY-KONTROLLER: tegnes OVER den allerede viste boksiden.
                // ALDRI en Column-søsken eller Scaffold topBar — påvirker aldri
                // leserens layout, mål eller constraints. ──
                if (showControls) {
                    ReaderControlsOverlay(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .zIndex(1f),
                        ui = ui,
                        orientationLocked = orientationLocked,
                        onBack = onBack,
                        onOpenSearch = { showSearchDialog = true },
                        onOpenContents = { showContentsSheet = true },
                        onOpenThemes = { showThemesSheet = true },
                        onToggleBookmark = { vm.toggleBookmark(); tracker.onUserInteraction() },
                        onToggleOrientationLock = {
                            orientationLocked = !orientationLocked
                            val activity = context as? Activity
                            if (activity != null) {
                                activity.requestedOrientation = if (orientationLocked) {
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                } else {
                                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            }
                            android.widget.Toast.makeText(
                                context,
                                if (orientationLocked) context.getString(R.string.rdr_screen_locked) else context.getString(R.string.rdr_screen_unlocked),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                    )
                }

                // ── Kompakt bokmerke-HUD (kort bekreftelse, endrer aldri sidetilstand) ──
                AnimatedVisibility(
                    visible = bookmarkHud != null,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(160)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(2f)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 0.dp,
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            bookmarkHud.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }

        // ── Themes & Settings Dialog ──────────────────────────────────────────
        if (showThemesSheet) {
            AlertDialog(
                onDismissRequest = { showThemesSheet = false },
                title = {
                    Text(stringResource(R.string.rdr_themes_settings), fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(stringResource(R.string.rdr_font_size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp - 1) }, modifier = Modifier.size(42.dp)) { Text("A-", fontSize = 11.sp) }
                            Text("${ui.fontSizeSp} sp", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            FilledTonalIconButton(onClick = { vm.setFontSize(ui.fontSizeSp + 1) }, modifier = Modifier.size(42.dp)) { Text("A+", fontSize = 14.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.rdr_theme), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val themes = listOf(
                                "light" to stringResource(R.string.rdr_theme_light),
                                "sepia" to stringResource(R.string.rdr_theme_sepia),
                                "dark" to stringResource(R.string.rdr_theme_dark),
                                "black" to stringResource(R.string.rdr_theme_black)
                            )
                            themes.forEach { (storageKey, label) ->
                                val isSelected = ui.readerTheme == storageKey
                                Surface(
                                    onClick = { vm.setTheme(storageKey) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.height(40.dp).weight(1f)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showThemesSheet = false }) { Text(stringResource(R.string.rdr_close), fontWeight = FontWeight.Bold) }
                }
            )
        }

        // ── Bottom Sheet (Contents / Chapters) ──────────────────────────────────────
        if (showContentsSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showContentsSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 6.dp,
                dragHandle = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(36.dp, 4.dp)) {}
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ui.bookTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { scope.launch { sheetState.hide(); showContentsSheet = false } }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.rdr_close))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 28.dp)
                ) {
                    val tocItems = ui.chapters.withIndex().filter { it.value.inToc }
                    itemsIndexed(tocItems) { _, tocEntry ->
                        val idx = tocEntry.index
                        val chapter = tocEntry.value
                        val selected = idx == ui.currentChapterIndex
                        Surface(
                            onClick = {
                                vm.setCurrentChapter(idx)
                                scope.launch { sheetState.hide(); showContentsSheet = false }
                            },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${idx + 1}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    chapter.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSearchDialog) {
            AlertDialog(
                onDismissRequest = { showSearchDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.rdr_search_unavailable, searchQuery),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        showSearchDialog = false
                    }) { Text(stringResource(R.string.rdr_search)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSearchDialog = false }) { Text(stringResource(R.string.rdr_cancel)) }
                },
                title = { Text(stringResource(R.string.rdr_search_in_chapter)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text(stringResource(R.string.rdr_search_label)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            stringResource(R.string.rdr_search_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            if (activity != null && orientationLocked) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

/** Avvis gjenvunnet, feil størrelse, gjennomsiktig eller ellers ugyldig bitmap. */
private fun isUsableRenderBitmap(b: Bitmap): Boolean {
    if (b.isRecycled || b.width < 2 || b.height < 2) return false
    val w = b.width
    val h = b.height
    val samples = listOf(
        intArrayOf(1, 1), intArrayOf(w / 2, h / 2), intArrayOf(w - 2, h - 2), intArrayOf(w / 2, 1)
    )
    var first = 0
    var transparent = 0
    samples.forEachIndexed { i, pt ->
        val c = runCatching { b.getPixel(pt[0], pt[1]) }.getOrElse { return false }
        if (i == 0) first = c else if (c != first) return true
        if ((c ushr 24) == 0) transparent++
    }
    return transparent != samples.size
}

/**
 * Siden som faktisk vises i leseflaten: nøkkel + posisjon + bitmap. Nøyaktig én
 * slik instans tegnes av gangen — aldri en fantomside eller et stale underlag.
 */
private data class DisplayedPage(
    val key: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val bitmap: Bitmap,
)

/**
 * Felles Paint-flagg for ALLE drawBitmap-kall av rendererte leser-sider:
 * bilinær filtering ved skalering, dithering og antialiasing — aldri null-Paint.
 * Konstant (ikke instans) slik at flagg-settet er JVM-enhetstestbart.
 */
internal const val PAGE_BITMAP_PAINT_FLAGS: Int =
    android.graphics.Paint.ANTI_ALIAS_FLAG or
    android.graphics.Paint.FILTER_BITMAP_FLAG or
    android.graphics.Paint.DITHER_FLAG

@Composable
private fun RealBookSlideReader(
    modifier: Modifier = Modifier,
    ui: ReaderBookState,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onJumpToChapterPage: (sectionIndex: Int, localPageIndex: Int, chapterPct: Float) -> Unit,
    onHighlight: (com.bookrio.reader.engine.HighlightData) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Fullskjerm-målet (hele skjermen i dp → px) er stabilt på tvers av
    // kontroll-toggle: vis/skjul av systembars endrer aldri denne verdien, så
    // effectiveWidthPx/contentHeightPx/sizeKey/configKey/renderKey og hele
    // bitmapgeometrien er UENDRET før og etter en toggle. (Padding påføres
    // nøyaktig én gang til renderer-dims og én gang — med SAMME tall — til
    // Canvas-destinasjonen, se ReaderContentBox.)
    val configuration = LocalConfiguration.current
    val swPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val shPx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()

    // Sideinnholdsboksen (identisk med paddingen i leseflaten):
    // rendererens bitmapdimensjoner MÅ være nøyaktig disse — aldri en blanding
    // av fullskjerm- og padde-mål (som gav vertikal skvis + skaleringsartefakter).
    // Padden påføres nøyaktig ÉN gang: samme tall brukes til Canvas-destinasjonen.
    val pageHPad = 32.dp
    val pageVPad = 48.dp
    val contentBox = ReaderContentBox(
        viewportWidthPx = swPx,
        viewportHeightPx = shPx,
        hPadPx = with(density) { pageHPad.toPx() }.toInt(),
        vPadPx = with(density) { pageVPad.toPx() }.toInt(),
    )
    val effectiveWidthPx = contentBox.effectiveWidthPx
    val contentHeightPx = contentBox.contentHeightPx
    val sizeKey = "$effectiveWidthPx-$contentHeightPx"
    val fontKey = "${ui.fontSizeSp}-${ui.readerTheme}"
    val configKey = "$fontKey-$sizeKey"
    fun renderKey(ch: Int, page: Int): String = "$ch|$configKey|p$page"

    val chapters = ui.chapters
    val chapterCount = chapters.size
    val chapIdx = ui.currentChapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
    val hasNextSect = chapIdx < chapterCount - 1
    val hasPrevSect = chapIdx > 0

    // Én WebView-renderer per kapittel — eierskap håndheves av RenderCoordinator.
    val renderers = remember(effectiveWidthPx, contentHeightPx) {
        java.util.concurrent.ConcurrentHashMap<Int, HtmlPageRenderer>()
    }
    fun rendererFor(ch: Int): HtmlPageRenderer =
        renderers.getOrPut(ch) { HtmlPageRenderer(context, effectiveWidthPx, contentHeightPx, onHighlight) }
    DisposableEffect(effectiveWidthPx, contentHeightPx) {
        onDispose {
            renderers.values.forEach { it.release() }
            renderers.clear()
        }
    }

    // ── Bitmap-cache + klargjøringskart. Nøklet på font/tema/størrelse: byttes de,
    // fødes nye instanser (og gamle WebViews re-klargjøres) — aldri delt stale cache. ──
    val cache = remember(effectiveWidthPx, contentHeightPx, fontKey) { PageBitmapCache(maxSize = 14) }
    val prepared = remember(effectiveWidthPx, contentHeightPx, fontKey) { mutableStateMapOf<Int, Int>() }

    // ── Render-koordinator: enkelt-eierskap, dedup på nøkkel, spesulativ lav prio ──
    val coordinatorScope = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
    DisposableEffect(effectiveWidthPx, contentHeightPx, fontKey) {
        onDispose { coordinatorScope.cancel() }
    }
    // Koordinatoren er ENESTE forfatter i cachen: callbacken validerer bitmapmen
    // (isUsable) og skriver den under den kanoniske nøkkelen (pageKey === renderKey).
    val coordinator = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        RenderCoordinator<Bitmap>(
            scope = coordinatorScope,
            isUsable = ::isUsableRenderBitmap,
            onRendered = { _, key, bmp ->
                cache.put(key, bmp)
                readerDiag("CACHE-PUT key='$key' bmp=${bmp.width}x${bmp.height}")
            },
        )
    }

    val scope = rememberCoroutineScope()
    val updatedUi by rememberUpdatedState(ui)

    // ── Sidetall for gjeldende kapittel. Kun en fersk prepare-teller er autoritativ:
    // prepared[chapIdx] fylles av kapittel-inngangens prepare og av nabo-prefetch.
    // Er tallet ukjent gater vi sidevendinger — vi gjetter ALDRI en grense. ──
    val chapterPages = prepared[chapIdx]?.takeIf { it > 0 }
    val pageCount = chapterPages ?: 0

    // Sideindeksen er ALLTID VM-ens chapter-lokale currentPage (samme tall DB lagrer).
    // Kjent sidetall → trygg clamp. Ukjent → vis den lagrede indeksen UENDRET; vi
    // faller aldri stille tilbake til side 0 (gammel posisjon skal alltid forsøkes).
    val page = if (chapterPages != null)
        PageIndexMath.clampPage(ui.currentPage, chapterPages)
    else
        ui.currentPage.coerceAtLeast(0)
    val targetKey = renderKey(chapIdx, page)

    // ── Gated layout-diagnostikk (READER_LAYOUT_DIAG = false som standard) ──
    // Logger KUN mål, aspektratio, nøkler og sidetilstand ved vis/skjul av kontroller.
    // En korrekt toggle rapporterer identiske verdier før og etter.
    LaunchedEffect(showControls) {
        if (!READER_LAYOUT_DIAG) return@LaunchedEffect
        val srcBmp = cache.getSync(renderKey(chapIdx, page))
        val srcW = srcBmp?.width ?: -1
        val srcH = srcBmp?.height ?: -1
        val dstW = contentBox.destinationWidthPx
        val dstH = contentBox.destinationHeightPx
        val srcAr = if (srcH > 0) (srcW.toFloat() / srcH) else -1f
        val dstAr = contentBox.destinationAspectRatio
        layoutDiag(
            "TOGGLE showControls=$showControls" +
            " viewport=${swPx}x$shPx" +
            " effective=${effectiveWidthPx}x$contentHeightPx" +
            " src=${srcW}x$srcH dst=${dstW}x$dstH" +
            " srcAR=$srcAr dstAR=$dstAr" +
            " sizeKey=$sizeKey configKey=$configKey" +
            " renderKey=${renderKey(chapIdx, page)}" +
            " page=$page pages=$pageCount chapters=$chapterCount"
        )
    }

    /** Kjerne uten lås — kalleren (koordinatoren) eier eier-låsen. */
    suspend fun prepareChapterUnlocked(ch: Int): Int {
        prepared[ch]?.let { return it }
        if (ch < 0 || ch >= chapterCount) return 0
        val count = runCatching {
            rendererFor(ch).prepare(chapters[ch].htmlContent, updatedUi.fontSizeSp, readerThemeColors(updatedUi.readerTheme))
        }.getOrDefault(0)
        if (count > 0) prepared[ch] = count
        return count
    }

    /** Klargjør kapittel i sin renderer — gjennom koordinatorens eier-lås. */
    suspend fun prepareChapter(ch: Int): Int = coordinator.withOwner(ch) { prepareChapterUnlocked(ch) }

    /**
     * Forhåndstegn side [page] i nabo-kapittelet [chapter]: prepare via eier-låsen,
     * deretter render GJENNOM koordinatoren (renderCurrent) — aldri direkte
     * rendererFor(...).renderPage(...). Koordinator-callbacken skriver cachen under
     * den kanoniske renderKey.
     */
    suspend fun prefetchNeighbor(chapter: Int, page: Int) {
        val count = prepareChapter(chapter)
        if (count <= 0 || chapter < 0 || chapter >= chapterCount) return
        val target = if (page < 0) count - 1 else page.coerceIn(0, count - 1)
        val key = renderKey(chapter, target)
        if (cache.getSync(key) != null) return
        coordinator.renderCurrent(
            ownerKey = chapter,
            pageKey = key,
            page = target,
        ) { p -> rendererFor(chapter).renderPage(p, key) }
        readerDiag("PREFETCH-DONE ch=$chapter page=$target")
    }

    // Klargjør nåværende kapittel (font/tema/størrelse re-klargjør) og forhåndstegn
    // nabo-kapitlenes kant-sider — grensekryss viser da en ferdig bitmap umiddelbart.
    LaunchedEffect(chapIdx, fontKey, sizeKey) {
        if (chapterCount == 0) return@LaunchedEffect
        coordinator.cancelSpeculative()
        val count = prepareChapter(chapIdx)
        readerDiag("PREPARE-DONE ch=$chapIdx pages=$count")
        if (count > 0 && chapIdx == updatedUi.currentChapterIndex) onTotalPages(count)
        if (hasPrevSect) scope.launch { runCatching { prefetchNeighbor(chapIdx - 1, -1) } }
        if (hasNextSect) scope.launch { runCatching { prefetchNeighbor(chapIdx + 1, 0) } }
    }

    // ÉN gjenbrukbar Paint for ALLE drawBitmap-kall av rendererte leser-sider:
    // filtering + dithering + antialiasing (aldri null-Paint), opprettes ikke på nytt per ramme.
    val pageBitmapPaint = remember(effectiveWidthPx, contentHeightPx, fontKey) {
        android.graphics.Paint(PAGE_BITMAP_PAINT_FLAGS)
    }
    val themeColors = readerThemeColors(ui.readerTheme)
    val paperColor = Color(themeColors.paperColorInt)

    // ── Tap-soner: NØYAKTIG samme terskler som den gamle curl-leseren ──
    // venstre 28 % = forrige side, høyre 28 % = neste side, midten = kontroller.
    // Sidevendinger og kapittelkryss går via de eksisterende VM-veiene
    // (onPageTurned / jumpToChapterPage) — ingen egen vindusmaskin.
    val onTurnForward: () -> Unit = {
        if (pageCount > 0) {
            when (val action = PageNavigator.turnForward(page, pageCount, chapIdx, chapterCount)) {
                is PageNavAction.TurnTo -> onPageTurned(action.page)
                is PageNavAction.JumpToChapter ->
                    onJumpToChapterPage(action.chapterIndex, action.page, action.chapterPct)
                PageNavAction.None -> Unit
            }
        }
    }
    val onTurnBackward: () -> Unit = {
        if (pageCount > 0) {
            val prevPages = prepared[chapIdx - 1]?.takeIf { it > 0 }
            when (val action = PageNavigator.turnBackward(page, pageCount, chapIdx, chapterCount, prevPages)) {
                is PageNavAction.TurnTo -> onPageTurned(action.page)
                is PageNavAction.JumpToChapter ->
                    onJumpToChapterPage(action.chapterIndex, action.page, action.chapterPct)
                PageNavAction.None -> {
                    // Står på første side i et kapittel, men forrige kapittel er ikke
                    // klargjort ennå: klargjør og hopp til SISTE side når tallet er kjent.
                    // Brukeren blir aldri stående uten respons, og vi lander aldri på
                    // side 0 av en feil.
                    if (page == 0 && chapIdx > 0) {
                        // Frys navigasjonskonteksten ved tappetidspunktet. Klargjøringen
                        // kan ta tid, og brukeren kan ha navigert (TOC, framover-tapp,
                        // fontbytte) innen den er ferdig.
                        val tapChapter = chapIdx
                        val tapPage = page
                        scope.launch {
                            val prevCount = prepareChapter(tapChapter - 1)
                            // Revalider: commit bare når vi fortsatt står på samme side.
                            // Ellers ville en sen commit rive brukeren tilbake til et
                            // sted de allerede har forlatt.
                            if (!PageNavigator.shouldCommitBackwardCross(
                                    tapChapterIndex = tapChapter,
                                    tapPage = tapPage,
                                    currentChapterIndex = updatedUi.currentChapterIndex,
                                    currentPage = updatedUi.currentPage,
                                )
                            ) {
                                return@launch
                            }
                            val retry = if (prevCount > 0)
                                PageNavigator.turnBackward(tapPage, pageCount, tapChapter, chapterCount, prevCount)
                            else null
                            if (retry is PageNavAction.JumpToChapter) {
                                onJumpToChapterPage(retry.chapterIndex, retry.page, retry.chapterPct)
                            }
                            // Klargjøring feilet: gjør ingenting. Aldri hopp til side 0
                            // (det ville vært feil landingsside) og aldri gjett en kant.
                        }
                    }
                }
            }
        }
    }
    // rememberUpdatedState: pointerInput-blokken lever over flere komposisjoner og
    // må alltid kalle den NYESTE handleren (aldri en stale closure fra en tidligere side).
    val currentForward by rememberUpdatedState(onTurnForward)
    val currentBackward by rememberUpdatedState(onTurnBackward)
    val currentToggle by rememberUpdatedState(onToggleControls)

    // ── Vist side: NØYAKTIG én bitmap i én leseflate. Bitmapmen byttes først når den
    // nye siden er ferdig rendret — ingen fantomside, ingen stale underlay og ingen
    // bitmap under kontrollene. ──
    var displayed by remember { mutableStateOf<DisplayedPage?>(null) }

    // ── Maks ÉN spesulativ prefetch: neste side, bare når koordinatoren er ledig.
    // På grensen forhåndstegnes nabo-kapittelets landingsside (side 0 / siste side)
    // slik at et grensekryss viser ferdig bitmap uten tom side. ──
    // Nøkkelen inkluderer displayed?.key: prefetchen kjøres først NÅR den gjeldende
    // siden faktisk vises. Ellers ville display-effecten rett etterpaa kalle
    // renderCurrent(samme kapittel) og kansellere den spekulative side+1-renderingen
    // (RenderCoordinator.cancelSpeculativeFor), saa forhaandstegning var død.
    LaunchedEffect(displayed?.key, chapIdx, page, pageCount, fontKey, sizeKey) {
        // Vent til gjeldende side er paa skjermen; da er renderCurrent ferdig og
        // kansellerer ikke lenger den spekulative naboen.
        if (displayed?.key != targetKey) return@LaunchedEffect
        coordinator.cancelSpeculative()
        if (pageCount <= 0) return@LaunchedEffect
        val next = page + 1
        if (next < pageCount) {
            if (coordinator.isIdle()) {
                coordinator.requestSpeculative(
                    ownerKey = chapIdx,
                    pageKey = renderKey(chapIdx, next),
                    page = next,
                ) { p -> rendererFor(chapIdx).renderPage(p, renderKey(chapIdx, next)) }
            }
        } else if (hasNextSect) {
            scope.launch { runCatching { prefetchNeighbor(chapIdx + 1, 0) } }
        }
        if (page == 0 && hasPrevSect) {
            scope.launch { runCatching { prefetchNeighbor(chapIdx - 1, -1) } }
        }
    }

    // ── Vist side: bytt bitmap først når den nye siden er ferdig rendret. ──
    LaunchedEffect(targetKey, fontKey, sizeKey, chapterPages) {
        if (targetKey.isEmpty() || displayed?.key == targetKey) return@LaunchedEffect
        val cached = cache.getSync(targetKey)
        val bmp = cached ?: coordinator.renderCurrent(
            ownerKey = chapIdx,
            pageKey = targetKey,
            page = page,
        ) { p -> rendererFor(chapIdx).renderPage(p, targetKey) }
        if (bmp != null) {
            readerDiag("DISPLAY key='$targetKey' bmp=${bmp.width}x${bmp.height}")
            displayed = DisplayedPage(targetKey, chapIdx, page, bmp)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(paperColor)
            .pointerInput(chapIdx, page, pageCount, chapterCount) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val xFrac = offset.x.coerceIn(0f, width) / width
                    when {
                        xFrac < 0.28f -> currentBackward()
                        xFrac > 0.72f -> currentForward()
                        else -> currentToggle()
                    }
                }
            }
    ) {
        AnimatedContent(
            targetState = displayed,
            contentKey = { it?.key ?: "" },
            transitionSpec = {
                val from = initialState
                val to = targetState
                val forward = when {
                    from == null || to == null -> true
                    to.chapterIndex != from.chapterIndex -> to.chapterIndex > from.chapterIndex
                    else -> to.pageIndex >= from.pageIndex
                }
                if (forward) {
                    (slideInHorizontally(tween(PAGE_TRANSITION_MS)) { it / 6 } + fadeIn(tween(PAGE_TRANSITION_MS))) togetherWith
                        (slideOutHorizontally(tween(PAGE_TRANSITION_MS)) { -it / 6 } + fadeOut(tween(PAGE_TRANSITION_MS)))
                } else {
                    (slideInHorizontally(tween(PAGE_TRANSITION_MS)) { -it / 6 } + fadeIn(tween(PAGE_TRANSITION_MS))) togetherWith
                        (slideOutHorizontally(tween(PAGE_TRANSITION_MS)) { it / 6 } + fadeOut(tween(PAGE_TRANSITION_MS)))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = pageHPad, vertical = pageVPad),
            contentAlignment = Alignment.Center,
            label = "reader-page",
        ) { shown ->
            val b = shown?.bitmap
            if (b != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas {
                        val native = it.nativeCanvas
                        if (!b.isRecycled) {
                            native.drawBitmap(
                                b, null,
                                // Destinasjon = innholdsboksen beregnet fra SAMME mål som
                                // rendereren (effectiveWidthPx × contentHeightPx) — kilde og
                                // destinasjon deler aspektratio, aldri uavhengig X/Y-skalering.
                                android.graphics.RectF(0f, 0f, contentBox.destinationWidthPx.toFloat(), contentBox.destinationHeightPx.toFloat()),
                                pageBitmapPaint
                            )
                        }
                    }
                }
            }
        }

        if (displayed == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        if (showControls) {
            val metrics by rendererFor(chapIdx).lastMetrics.collectAsStateWithLifecycle()
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
                color = Color.Black.copy(0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "${stringResource(R.string.rdr_rendering)}: $metrics",
                    color = Color.White,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * OVERLAY-KONTROLLER: tegnes OVER den allerede rendererte boksiden.
 *
 * - Er ALDRI en Column-søsken over leseren og aldri en Scaffold topBar med
 *   innerPadding — påvirker aldri leserens layout, mål, insets eller constraints.
 * - Roterer/fader kun seg selv (alpha + vertikal translasjon), aldri boksiden.
 * - Konsumerer kun berøringer inne på de synlige kontrollene; tomme områder
 *   (ingen bakgrunn/pointerInput) lar leseflaten og tap-sonene passere uhindret.
 */
@Composable
private fun ReaderControlsOverlay(
    ui: ReaderBookState,
    orientationLocked: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenThemes: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleOrientationLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxSize()) {
        // ═══ TOPP-STRIP (hele veien opp, solid strip) ═══
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInVertically { -it / 3 },
            exit = fadeOut(tween(90)) + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        ) {
            Surface(
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rdr_back), modifier = Modifier.size(26.dp))
                    }
                    Text(
                        ui.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                    )
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, stringResource(R.string.rdr_search), modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            val pct = ((ui.percent.coerceIn(0f, 1f)) * 100).toInt()
                            val chapTitle = ui.chapters.getOrNull(ui.currentChapterIndex)?.title?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.rdr_chapter, ui.currentChapterIndex + 1)
                            putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                ctx.getString(R.string.rdr_share_text, ui.bookTitle, chapTitle, ui.currentPage + 1, ui.totalPages.coerceAtLeast(1), pct)
                            )
                            putExtra(android.content.Intent.EXTRA_TITLE, ui.bookTitle)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(shareIntent, ctx.getString(R.string.rdr_share_progress)))
                    }) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.rdr_share), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        // ═══ BUNN-STRIP (hele veien ned, solid strip) ═══
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(120)) + slideInVertically { it / 3 },
            exit = fadeOut(tween(90)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Surface(
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                val pagesLeftInChapter = (ui.totalPages - ui.currentPage - 1).coerceAtLeast(0)
                val pctStr = "${((ui.percent.coerceIn(0f, 1f)) * 100).toInt()}%"
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(top = 6.dp, bottom = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.rdr_pages_left, pagesLeftInChapter, pctStr, ui.currentPage + 1, ui.totalPages.coerceAtLeast(1)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavigationBarItem(
                            selected = false,
                            onClick = onOpenContents,
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null, modifier = Modifier.size(26.dp)) },
                            label = { Text(stringResource(R.string.rdr_contents), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = onOpenThemes,
                            icon = {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("A", fontSize = 20.sp, fontWeight = FontWeight.Black)
                                    Text("A", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 1.dp))
                                }
                            },
                            label = { Text(stringResource(R.string.rdr_themes), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = onToggleBookmark,
                            icon = { Icon(Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(26.dp)) },
                            label = { Text(stringResource(R.string.rdr_bookmark), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                        )
                        NavigationBarItem(
                            selected = orientationLocked,
                            onClick = onToggleOrientationLock,
                            icon = {
                                Icon(
                                    if (orientationLocked) Icons.Default.Lock else Icons.Outlined.Lock,
                                    null,
                                    modifier = Modifier.size(26.dp),
                                    tint = if (orientationLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            label = { Text(if (orientationLocked) stringResource(R.string.rdr_locked) else stringResource(R.string.rdr_lock), fontWeight = FontWeight.SemiBold, fontSize = 11.sp) },
                        )
                    }
                }
            }
        }
    }
}

private fun setWindowBrightness(context: Context, brightness: Float) {
    val lp = (context as? Activity)?.window?.attributes ?: return
    lp.screenBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.01f, 1.0f)
    (context as? Activity)?.window?.attributes = lp
}

@Composable
private fun ErrorView(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp)); Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp)); Button(onClick = onBack) { Text(stringResource(R.string.rdr_go_back)) }
    }
}

private fun defaultReaderVmFactory(): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val ctx = this[APPLICATION_KEY] as android.app.Application
        ReaderViewModel(ctx, com.bookrio.data.local.ShelfDatabase.getInstance(ctx))
    }
}


