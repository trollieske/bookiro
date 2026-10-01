package com.bookrio.reader.ui

import android.app.Activity
import android.content.Context
import androidx.compose.animation.*
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
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.reader.engine.PageIndexMath
import com.bookrio.reader.engine.PageNavAction
import com.bookrio.reader.engine.PageNavigator
import com.bookrio.reader.engine.ReaderBookState
import com.bookrio.reader.engine.ReaderWebEngine
import com.bookrio.reader.pageturn.*
import com.bookrio.reader.readium.ReadiumEpubReaderScreen
import com.bookrio.reader.R
import com.bookrio.reader.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    val context = LocalContext.current

    // EPUB is rendered by the Readium navigator (Readium owns layout/pagination).
    // All other formats keep the existing Bookiro reader engine.
    var epubMode by remember(bookId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(bookId) {
        val format = runCatching {
            com.bookrio.data.local.ShelfDatabase.getInstance(context.applicationContext)
                .bookDao().getById(bookId)?.format
        }.getOrNull()
        epubMode = format == com.bookrio.data.local.entity.FormatEntity.EPUB
        if (epubMode != true) vm.load(bookId)
    }
    if (epubMode == true) {
        ReadiumEpubReaderScreen(bookId = bookId, onBack = onBack)
        return
    }
    var showControls by rememberSaveable { mutableStateOf(false) }
    var showContentsSheet by rememberSaveable { mutableStateOf(false) }
    var showThemesSheet by rememberSaveable { mutableStateOf(false) }
    var showBookmarksSheet by rememberSaveable { mutableStateOf(false) }
    var showSearchDialog by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var orientationLocked by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
    // vm.load is triggered by the format-check effect above (non-EPUB only).

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
                    DirectWebReader(
                        modifier = Modifier.matchParentSize(),
                        ui = ui,
                        onToggleControls = { showControls = !showControls; if (!showControls) showContentsSheet = false; tracker.onUserInteraction() },
                        onPageTurned = { vm.onPageTurned(it); tracker.onUserInteraction() },
                        onTotalPages = { vm.onPageCountKnown(it) },
                        onJumpToChapterPage = { ch, page, pct -> vm.jumpToChapterPage(ch, page, pct) },
                        onPreviousChapter = { vm.previousChapter(); tracker.onUserInteraction() },
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

/** Leserens horisontale/vertikale padding (leseområdet = vinduet minus denne). */
private val PAGE_H_PAD = 32.dp
private val PAGE_V_PAD = 48.dp

/**
 * DIREKTE LESER: kapittel-HTML-en vises i en on-screen WebView (Compose
 * `AndroidView`) — ingen offscreen WebView, ingen bitmap og ingen capture.
 *
 * - Paginering: CSS multi-column (`column-width: 100vw`) i `#content-wrapper`.
 *   Sidetall = ceil(scrollWidth / viewportWidth), gjeldende side = scrollX/vw.
 * - Sidevending: programmatisk horisontal scroll (`scrollLeft = side × bredde`).
 * - Tap-soner (JS i WebView-en + Compose i padding-kantene): venstre 28 % =
 *   forrige, høyre 28 % = neste, midten = kontroller — som før.
 * - ViewModel-ens kapittel-lokale sideindeks er fortsatt sannheten: nytt
 *   sidetall rapporteres via [onTotalPages] → onPageCountKnown, sidevendinger
 *   via [onPageTurned], kapittelkryss via [onJumpToChapterPage] /
 *   [onPreviousChapter] (bakover-kryss går alltid til forrige kapittels SISTE
 *   side — aldri side 0 av feil).
 */
@Composable
private fun DirectWebReader(
    modifier: Modifier = Modifier,
    ui: ReaderBookState,
    onToggleControls: () -> Unit,
    onPageTurned: (Int) -> Unit,
    onTotalPages: (Int) -> Unit,
    onJumpToChapterPage: (sectionIndex: Int, localPageIndex: Int, chapterPct: Float) -> Unit,
    onPreviousChapter: () -> Unit,
    onHighlight: (com.bookrio.reader.engine.HighlightData) -> Unit = {},
) {
    val context = LocalContext.current
    val engine = remember { ReaderWebEngine(context) }
    DisposableEffect(engine) { onDispose { engine.release() } }

    val chapters = ui.chapters
    val chapterCount = chapters.size
    val chapIdx = ui.currentChapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
    val themeColors = readerThemeColors(ui.readerTheme)
    val paperColor = Color(themeColors.paperColorInt)

    // Kapittel + typografi identifiserer HTML-en som lastes i WebView-en.
    val loadKey = "$chapIdx|${ui.fontSizeSp}|${ui.readerTheme}"

    // Sidetallet er kun gyldig når NETTOPP dette kapittelet/typografien er målt.
    val measured = engine.measuredLoadKey == loadKey
    val pageCount = if (measured) engine.pageCount else 0
    // Kjent sidetall → trygg clamp. Ukjent → vis lagret indeks uendret (aldri
    // stille tilbake til side 0); scrollen skjer først når målingen er klar.
    val page = if (pageCount > 0) PageIndexMath.clampPage(ui.currentPage, pageCount)
               else ui.currentPage.coerceAtLeast(0)

    LaunchedEffect(loadKey) {
        val chapter = chapters.getOrNull(chapIdx) ?: return@LaunchedEffect
        engine.load(loadKey, chapter.htmlContent, ui.fontSizeSp, themeColors, lang = "en")
    }

    // Målt sidetall → eksisterende kapittel-lokale side-modell i ViewModel.
    LaunchedEffect(loadKey, measured, pageCount) {
        if (measured && pageCount > 0) onTotalPages(pageCount)
    }

    // VM-ens side → programmatisk scroll. Kjører også etter ny måling
    // (rotasjon/vindusendring) slik at posisjonen gjenopprettes.
    LaunchedEffect(loadKey, measured, pageCount, page) {
        if (!measured || pageCount <= 0) return@LaunchedEffect
        engine.scrollToPage(page)
    }

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
            // Bakover-kryss trenger forrige kapittels sidetall, som først er kjent
            // etter at kapittelet er lastet og målt. Grensekrysset går derfor via
            // ViewModel-ens kapittel-vei (chapterPct = 1 → SISTE side, aldri 0).
            when (
                val action = PageNavigator.turnBackward(
                    currentIndex = page,
                    pageCount = pageCount,
                    chapterIndex = chapIdx,
                    chapterCount = chapterCount,
                    previousChapterPageCount = null,
                )
            ) {
                is PageNavAction.TurnTo -> onPageTurned(action.page)
                is PageNavAction.JumpToChapter ->
                    onJumpToChapterPage(action.chapterIndex, action.page, action.chapterPct)
                PageNavAction.None -> if (page == 0 && chapIdx > 0) onPreviousChapter()
            }
        }
    }
    val onTap: (Float) -> Unit = { frac ->
        when {
            frac < 0.28f -> onTurnBackward()
            frac > 0.72f -> onTurnForward()
            else -> onToggleControls()
        }
    }

    // Bro-callbacks oppdateres hver komposisjon — de kalles asynkront fra WebView-en.
    SideEffect {
        engine.onTapZone = onTap
        engine.onHighlight = onHighlight
        engine.onPageSettled = { settled ->
            // ScrollX er sannheten: en bruker-drag (eller snap) som lander på en
            // annen side oppdaterer den kapittel-lokale modellen — men bare når
            // dette kapittelet faktisk er målt.
            if (measured && pageCount > 0 && settled != ui.currentPage) {
                onPageTurned(settled.coerceIn(0, pageCount - 1))
            }
        }
    }

    Box(modifier.fillMaxSize().background(paperColor)) {
        // Tap-soner i padding-kantene rundt leseområdet (samme soner som før).
        // Tegnes UNDER WebView-en, så tekstutvalg i leseområdet eies av WebView-en.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(chapIdx, page, pageCount, chapterCount) {
                    detectTapGestures { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        onTap(offset.x.coerceIn(0f, width) / width)
                    }
                }
        )

        AndroidView(
            factory = { engine.webView },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PAGE_H_PAD, vertical = PAGE_V_PAD)
                .onSizeChanged { size -> engine.onViewportChanged(size.width, size.height) },
        )
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


