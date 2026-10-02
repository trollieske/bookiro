package com.bookrio.player.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.HandoffPrecisionEntity
import com.bookrio.data.repository.HandoffRepository
import com.bookrio.data.repository.ResolvedHandoff
import com.bookrio.designsystem.components.SleepTimerSheet
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfColors
import com.bookrio.designsystem.theme.ShelfFonts
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.player.R
import com.bookrio.player.engine.AudiobookState
import com.bookrio.player.engine.AudiobookTimeline
import com.bookrio.player.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    onBack: () -> Unit,
    onNavigateToEbook: ((targetBookId: Long, positionPercent: Float) -> Unit)? = null,
    onFindEbook: (() -> Unit)? = null,
    vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null,
    vm: PlayerViewModel = viewModel(factory = vmFactory ?: defaultPlayerVmFactory())
) {
    val state by vm.state.collectAsState()
    val serviceBound by vm.serviceBound.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val res = ctx.resources
    val prefs = remember { com.bookrio.data.prefs.UserPreferencesRepository(ctx.applicationContext as android.app.Application) }
    val handoffRepo = remember { HandoffRepository(ShelfDatabase.getInstance(ctx.applicationContext as android.app.Application)) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showSpeedDialog by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    val playerReady = serviceBound && state.mediaUri != null && state.error == null

    val workAndSibling: Pair<com.bookrio.data.local.dao.WorkWithEditions?, Long?> by androidx.compose.runtime.produceState<Pair<com.bookrio.data.local.dao.WorkWithEditions?, Long?>>(
        initialValue = null to null,
        key1 = bookId,
        producer = {
            val work = handoffRepo.getWorkForBook(bookId)
            val siblingId = work?.editions?.firstOrNull { e -> e.bookId != bookId }?.bookId
            value = work to siblingId
        }
    )
    val handoffPrecisionName by prefs.handoffPrecision.collectAsStateWithLifecycle(initialValue = HandoffPrecisionEntity.SMART.name)
    val handoffPrecision = remember(handoffPrecisionName) {
        runCatching { HandoffPrecisionEntity.valueOf(handoffPrecisionName) }.getOrElse { HandoffPrecisionEntity.SMART }
    }
    val showToast by prefs.handoffToastEnabled.collectAsStateWithLifecycle(initialValue = true)
    val skipBackSec by prefs.audioSkipBackSec.collectAsStateWithLifecycle(initialValue = 10)
    val skipFwdSec by prefs.audioSkipFwdSec.collectAsStateWithLifecycle(initialValue = 30)
    var animatingToggle by remember { mutableStateOf(false) }

    DisposableEffect(bookId) {
        // #region debug-point UI:player-screen-enter
        dbgPlayerUi("E", "player-screen-enter", "bookId=$bookId")
        // #endregion
        onDispose {
            // #region debug-point UI:player-screen-exit
            dbgPlayerUi("E", "player-screen-exit", "bookId=$bookId")
            // #endregion
        }
    }

    LaunchedEffect(bookId) {
        // #region debug-point UI:player-load-request
        dbgPlayerUi("E", "player-load-request", "bookId=$bookId")
        // #endregion
        vm.load(bookId)
    }

    LaunchedEffect(state.error, state.mediaUri, serviceBound) {
        val info = "bookId=$bookId mediaUri=${state.mediaUri ?: ""} serviceBound=$serviceBound error=${state.error ?: ""}"
        if (state.error != null) {
            // #region debug-point UI:player-error
            dbgPlayerUi("E", "player-screen-error", info)
            // #endregion
        } else if (state.mediaUri != null && serviceBound) {
            // #region debug-point UI:player-ready
            dbgPlayerUi("E", "player-screen-ready", info)
            // #endregion
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ply_title), style = ShelfTypography.TitleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    if (animatingToggle) {
                        Spacer(Modifier.width(48.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        val siblingId = workAndSibling.second
                        if (siblingId != null) {
                            AssistChip(
                                onClick = {
                                    if (animatingToggle) return@AssistChip
                                    animatingToggle = true
                                    scope.launch(Dispatchers.Default) {
                                        val totalDur = state.durationMs.coerceAtLeast(0L)
                                        val curDur = state.currentMs.coerceAtLeast(0L)
                                        val frac = if (totalDur > 0L) curDur.toFloat() / totalDur.toFloat() else 0f
                                        val chapTitle = state.chapters.getOrNull(state.currentChapterIndex.coerceAtLeast(0))?.title
                                        val chapterDurations = state.chapters.map { c ->
                                            val start = c.startMs.coerceAtLeast(0L)
                                            val end = (c.endMs ?: (start + 1L)).coerceAtLeast(start)
                                            (end - start).coerceAtLeast(0L)
                                        }
                                        val resolved: ResolvedHandoff = handoffRepo.resolveHandoffPosition(
                                            fromBookId = bookId,
                                            toBookId = siblingId,
                                            ctx = HandoffRepository.HandoffContext(
                                                fromProgressFraction = frac.coerceIn(0f, 1f),
                                                fromChapterIndex = state.currentChapterIndex.takeIf { it >= 0 },
                                                fromChapterLabel = chapTitle,
                                                fromPositionMs = curDur,
                                                fromTotalDurationMs = totalDur,
                                                toAudioChapterTitles = state.chapters.mapNotNull { it.title },
                                                toAudioChapterDurationsMs = chapterDurations,
                                                toAudioTotalDurationMs = totalDur.takeIf { it > 0L },
                                                precision = handoffPrecision
                                            )
                                        )
                                        runCatching {
                                            handoffRepo.recordHandoff(
                                                fromEditionBookId = bookId,
                                                toEditionBookId = siblingId,
                                                precision = handoffPrecision,
                                                fromFraction = frac.coerceIn(0f, 1f),
                                                toFraction = resolved.positionFraction,
                                                fromChapter = chapTitle,
                                                toChapter = resolved.chapterLabel,
                                                method = resolved.mappingMethod,
                                                estimate = resolved.wasEstimate
                                            )
                                        }
                                        withContext(Dispatchers.Main.immediate) {
                                            val estimateLabel = if (resolved.wasEstimate && resolved.chapterLabel != null) {
                                                ctx.getString(R.string.ply_estimate_toast, resolved.chapterLabel)
                                            } else {
                                                ctx.getString(R.string.ply_continue_toast)
                                            }
                                            if (showToast) {
                                                android.widget.Toast.makeText(ctx, estimateLabel, android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                            runCatching { if (state.isPlaying) vm.playPause() }
                                            onNavigateToEbook?.invoke(siblingId, resolved.positionFraction)
                                            if (onNavigateToEbook == null) onBack()
                                            animatingToggle = false
                                        }
                                    }
                                },
                                modifier = Modifier.padding(end = 4.dp),
                                label = { Text(stringResource(R.string.ply_read_switch), fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.Default.AutoStories, null, Modifier.size(16.dp)) },
                                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                            )
                        }
                    }
                    if (state.sleepTimerRemainingMs > 0L) {
                        // Aktiv søvntimer: kompakt live-nedtelling øverst til høyre.
                        // Én linje, HUD-stil, åpner søvntimer-hjulet ved trykk.
                        Surface(
                            onClick = { showSleep = true },
                            shape = RoundedCornerShape(50),
                            color = OmarchyColors.Accent.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, OmarchyColors.Accent.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                "☾ ${formatSleepCountdown(state.sleepTimerRemainingMs / 1000L)}",
                                style = ShelfTypography.LabelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = OmarchyColors.Accent,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    } else {
                        IconButton(onClick = { showSleep = true }) {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = stringResource(R.string.ply_sleep_a11y),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.ply_chapters_a11y))
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(200.dp),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = ShelfColors.SpineBurgundy)
            ) {
                Box(Modifier.fillMaxSize()) {
                    val cp = state.coverPath
                    if (cp != null) {
                        val req = remember(cp) {
                            ImageRequest.Builder(ctx)
                                .data(java.io.File(cp))
                                .memoryCacheKey(cp)
                                .diskCacheKey(cp)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = state.title.ifBlank { stringResource(R.string.ply_book_cover_a11y) },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.18f),
                                            Color.Black.copy(alpha = 0.62f)
                                        ),
                                        startY = 0f
                                    )
                                )
                        )
                    } else {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF2B2446),
                                        ShelfColors.SpineBurgundy,
                                        Color(0xFF3E1A2B)
                                    )
                                )
                            )
                            drawRect(
                                brush = Brush.radialGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.38f)
                                    ),
                                    center = Offset(w * 0.55f, h * 0.42f),
                                    radius = h * 0.75f
                                )
                            )
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                state.title.ifBlank { stringResource(R.string.ply_title) },
                                style = ShelfTypography.TitleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                state.author.ifBlank { "" },
                                style = ShelfTypography.BodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.isPlaying) WaveformOverlay()
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!playerReady) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (state.error != null) stringResource(R.string.ply_prepare_error) else stringResource(R.string.ply_preparing),
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            state.error ?: stringResource(R.string.ply_waiting_source),
                            style = ShelfTypography.BodySmall
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            val chapterCount = state.chapters.size
            val currentChapterIdx = state.currentChapterIndex
            // Aldri "1 av 1": telleren vises bare når boken virkelig har flere kapitler.
            val chapterLabel = when {
                chapterCount > 1 -> stringResource(R.string.ply_chapter_counter, currentChapterIdx + 1, chapterCount)
                chapterCount == 1 -> stringResource(R.string.ply_audiobook_upper)
                else -> stringResource(R.string.ply_audio_player_upper)
            }

            val chapterObj = state.chapters.getOrNull(currentChapterIdx)
            val displayTitle = when {
                chapterObj != null && chapterObj.title.isNotBlank() && !chapterObj.title.equals(state.title, ignoreCase = true) -> chapterObj.title
                else -> state.title.ifBlank { stringResource(R.string.ply_title) }
            }

            Text(
                text = chapterLabel,
                style = ShelfTypography.LabelMedium.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = displayTitle,
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (state.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.author,
                    style = ShelfTypography.BodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))

            SeekProgressSection(
                state = state,
                onSeek = { ms -> scope.launch { vm.seekTo(ms) } },
            )

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { scope.launch { vm.skipBack(skipBackSec * 1000L) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            when (skipBackSec) {
                                5 -> Icons.Default.Replay5
                                10 -> Icons.Default.Replay10
                                else -> Icons.Default.Replay30
                            },
                            contentDescription = stringResource(R.string.ply_skip_back, skipBackSec),
                            Modifier.size(28.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { scope.launch { vm.prevChapter() } },
                    // Kapittelnavigasjon kun ved > 1 reelle kapitler — aldri døde kontroller.
                    enabled = state.chapters.size > 1,
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.ply_prev_chapter), Modifier.size(34.dp))
                }
                FilledTonalIconButton(
                    onClick = { scope.launch { vm.playPause() } },
                    enabled = playerReady,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) stringResource(R.string.ply_pause) else stringResource(R.string.ply_play),
                        Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = { scope.launch { vm.nextChapter() } },
                    enabled = state.chapters.size > 1 &&
                        state.currentChapterIndex < state.chapters.size - 1,
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.ply_next_chapter), Modifier.size(34.dp))
                }
                IconButton(onClick = { scope.launch { vm.skipForward(skipFwdSec * 1000L) } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            when (skipFwdSec) {
                                5 -> Icons.Default.Forward5
                                10 -> Icons.Default.Forward10
                                else -> Icons.Default.Forward30
                            },
                            contentDescription = stringResource(R.string.ply_skip_forward, skipFwdSec),
                            Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bunnen: ÉN alltid-synlig hurtigrad — store hopp + hastighet.
            // (Søvntimer og kapitler lever utelukkende i toppbaren.)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { scope.launch { vm.skipBack(5 * 60_000L) } },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text(
                        "−5 min",
                        style = ShelfTypography.LabelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
                // Hastighetsknapp: kompakt boks der ikon + «1×» er sentrert i midten.
                // (AssistChip+weight(1f) trakk boksen ut til 1/3 av raden, og
                //  M3s ledende ikon-padding + weight(1f)-label venstrestilte innholdet.)
                Surface(
                    onClick = { showSpeedDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.heightIn(min = 44.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatPlaybackSpeed(state.playbackSpeed),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                TextButton(
                    onClick = { scope.launch { vm.skipForward(5 * 60_000L) } },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                ) {
                    Text(
                        "+5 min",
                        style = ShelfTypography.LabelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }

    if (showSpeedDialog) {
        SpeedDialog(
            current = state.playbackSpeed,
            onPick = { s -> scope.launch { vm.setSpeed(s) } },
            onDismiss = { showSpeedDialog = false }
        )
    }

    if (showChapters) {
        val chs = state.chapters
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    if (chs.size > 1) "${stringResource(R.string.ply_chapters_a11y)} · ${chs.size}" else stringResource(R.string.ply_chapters_a11y),
                    style = ShelfTypography.TitleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                if (chs.isEmpty()) {
                    Text(stringResource(R.string.ply_no_chapters),
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val scroll = rememberScrollState()
                    Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chs.forEachIndexed { i, ch ->
                            val selected = i == state.currentChapterIndex
                            ElevatedCard(
                                onClick = { scope.launch { vm.seekTo(ch.startMs) }; showChapters = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${i + 1}",
                                        style = ShelfTypography.LabelLarge,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(40.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ch.title.ifBlank { stringResource(R.string.ply_chapter_n, i + 1) },
                                            maxLines = 1,
                                            style = ShelfTypography.BodyMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(
                                            formatDuration(ch.startMs / 1000L),
                                            style = ShelfTypography.LabelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showSleep) {
        SleepTimerSheet(
            currentMinutes = state.sleepTimerMinutes,
            remainingMs = state.sleepTimerRemainingMs,
            title = stringResource(R.string.ply_sleep_title),
            presetsLabel = stringResource(R.string.ply_sleep_presets),
            startLabel = stringResource(R.string.ply_sleep_set),
            offLabel = stringResource(R.string.ply_sleep_turn_off_timer),
            minuteUnit = stringResource(R.string.ply_sleep_unit),
            minuteFormatter = { mins -> ctx.getString(R.string.ply_sleep_minutes, mins) },
            onDismiss = { showSleep = false },
            onPick = { mins -> scope.launch { vm.setSleepTimer(mins) }; showSleep = false }
        )
    }
}

/**
 * Dobbel fremgangslinje (QOL):
 *  - «Kapittel»: spoler finmasket INNENFOR nåværende kapittel — lang-drag på
 *    hele boken lar deg ikke lenger hoppe over store tidsdeler.
 *    Under vises en tynn hel-bok-linje + kapittelnummer/prosent, så du alltid
 *    ser hvor du er i boken også.
 *  - «Hele boken»: spoler over hele bokens varighet (som tidligere).
 * Standard: kapittelvisning når boken har > 1 kapittel. Valget huskes per sesjon.
 */
/**
 * GLOBAL boktidslinje (primær) + kompakt KAPITTEL-tidslinje (kun ved > 1 reelle
 * kapitler).
 *
 *  - Global slider: hele bokens varighet. Venstre = global elapsed, høyre =
 *    global total. Alt søk går gjennom ÉN global søk-callback.
 *  - Kapittelraden: finmasket spoling INNENFOR kapittelet (aldri utover
 *    kapittelets grenser) + «Kapittel X av Y». Skjules helt ved én reell
 *    kapittelfil — aldri «1 av 1».
 */
/**
 * GLOBAL boktidslinje (kompakt, øverst — for store hopp i lange bøker) +
 * KAPITTEL-tidslinje (primær, de fleste spoler her) + hastighets-avhengige
 * tellere.
 *
 *  - «HELE BOKEN»: tynn scratch-linje med global elapsed/total ved siden av.
 *  - «I KAPITTELLET»: full slider med egen teller; kan aldri forlate kapittelet.
 */
@Composable
private fun SeekProgressSection(
    state: AudiobookState,
    onSeek: (Long) -> Unit,
) {
    val chapters = state.chapters
    val bookDuration = AudiobookTimeline.globalDurationMs(chapters, state.durationMs)
    val hasChapters = chapters.size > 1

    // While scrubbing we show the drag position (not the 500 ms stale playback
    // position) so the counters move live under the finger. After release we hold
    // the target until playback catches up, so the display does not snap back.
    var globalScrub by remember { mutableStateOf<Float?>(null) }
    var globalPendingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.currentMs, globalPendingMs) {
        val p = globalPendingMs ?: return@LaunchedEffect
        if (kotlin.math.abs(state.currentMs - p) < 1_000L) globalPendingMs = null
    }

    Column(Modifier.fillMaxWidth()) {
        // ── 1. GLOBAL BOKTIDSLINJE — kompakt scratch-linje øverst ──
        val bookLen = bookDuration.coerceAtLeast(1L)
        val bookShownMs = when {
            globalScrub != null -> (globalScrub!! * bookLen).toLong()
            globalPendingMs != null -> globalPendingMs!!.coerceIn(0L, bookDuration.coerceAtLeast(0L))
            else -> state.currentMs
        }
        val bookPct = (bookShownMs.toFloat() / bookLen.toFloat()).coerceIn(0f, 1f)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.ply_whole_book_upper),
                style = ShelfTypography.LabelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "${formatDuration(bookShownMs / 1_000L)} / ${formatDuration(bookDuration / 1_000L)}",
                style = ShelfTypography.LabelSmall.copy(fontFamily = ShelfFonts.Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CompactTimeline(
            fraction = bookPct,
            onScrub = { f ->
                globalPendingMs = null
                globalScrub = f
            },
            onScrubEnd = { f ->
                globalScrub = null
                val target = (f * bookLen).toLong()
                globalPendingMs = target
                onSeek(target)
            },
            onTap = { f -> onSeek((f * bookLen).toLong()) },
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
        )

        // ── 2. KAPITTEL-TIDSLINJE med egen teller (kun > 1 reelle kapitler) ──
        if (hasChapters) {
            val idx = AudiobookTimeline.currentChapterIndex(chapters, state.currentMs)
            val (chStart, chEnd) = AudiobookTimeline.chapterBoundsMs(chapters, idx, bookDuration)
            val chLen = (chEnd - chStart).coerceAtLeast(1L)
            val chElapsed = (state.currentMs - chStart).coerceIn(0L, chLen)
            val chapter = chapters.getOrNull(idx)

            // Tydelig at denne linjen/telleren gjelder KUN inne i kapittelet
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.ply_in_chapter_upper),
                    style = ShelfTypography.LabelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.ply_chapter_of, idx + 1, chapters.size),
                    style = ShelfTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            var chapterScrub by remember { mutableStateOf<Float?>(null) }
            var chapterPendingMs by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(state.currentMs, chapterPendingMs) {
                val p = chapterPendingMs ?: return@LaunchedEffect
                if (kotlin.math.abs(state.currentMs - p) < 1_000L) chapterPendingMs = null
            }
            val chShownMs = when {
                chapterScrub != null -> (chapterScrub!! * chLen).toLong().coerceIn(0L, chLen)
                chapterPendingMs != null -> (chapterPendingMs!! - chStart).coerceIn(0L, chLen)
                else -> chElapsed
            }
            val chPct = (chShownMs.toFloat() / chLen.toFloat()).coerceIn(0f, 1f)
            Slider(
                value = chPct,
                onValueChange = {
                    chapterPendingMs = null
                    chapterScrub = it
                },
                onValueChangeFinished = {
                    // Forblir alltid innenfor kapittelets grenser (start + fraksjon av lengde).
                    val f = chapterScrub ?: chPct
                    chapterScrub = null
                    val target = chStart + (f * chLen).toLong()
                    chapterPendingMs = target
                    onSeek(target)
                }
            )
            // Teller for posisjon INNENFOR kapittelet
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.ply_in_chapter, formatDuration(chShownMs / 1_000L)),
                    style = ShelfTypography.LabelSmall.copy(fontFamily = ShelfFonts.Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "- " + stringResource(R.string.ply_left, formatDuration(max(0L, (chLen - chShownMs) / 1_000L))),
                    style = ShelfTypography.LabelSmall.copy(fontFamily = ShelfFonts.Mono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                chapter?.title.orEmpty(),
                style = ShelfTypography.LabelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Tynn, kompakt søk-linje (tap + horisontal drag) for hele boken.
 * Mindre visuell vekt enn kapittel-slideren — «scratch»-linjen.
 */
@Composable
private fun CompactTimeline(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val shown = fraction.coerceIn(0f, 1f)
    val shownState = rememberUpdatedState(shown)
    val onScrubState = rememberUpdatedState(onScrub)
    val onScrubEndState = rememberUpdatedState(onScrubEnd)
    val onTapState = rememberUpdatedState(onTap)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val thumbColor = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { if (it.width > 0) trackWidthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onTapState.value((offset.x / trackWidthPx).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                var last = shownState.value
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        onScrubState.value(last)
                    },
                    onDragEnd = { onScrubEndState.value(last) },
                    onDragCancel = { onScrubEndState.value(last) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                    onScrubState.value(last)
                }
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .align(Alignment.CenterStart)
        ) {
            val r = size.height / 2f
            drawRoundRect(color = trackColor, cornerRadius = CornerRadius(r, r))
            drawRoundRect(
                color = activeColor,
                size = Size(size.width * shown, size.height),
                cornerRadius = CornerRadius(r, r),
            )
        }
        val thumbPx = with(LocalDensity.current) { 12.dp.toPx() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((trackWidthPx - thumbPx) * shown).roundToInt(), 0) }
                .size(12.dp)
                .background(thumbColor, RoundedCornerShape(6.dp))
        )
    }
}

/**
 * Avspillingshastighet: skikkelig velger (filter-chips) i stedet for sykling.
 * 0,5×–3,0× — ExoPlayer håndterer pitch-korreksjon automatisk.
 */
@Composable
private fun SpeedDialog(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ply_speed_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 3 kolonner: bredere celler, aldri multi-linjehastighets-etiketter.
                speeds.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { s ->
                            FilterChip(
                                selected = kotlin.math.abs(s - current) < 0.001f,
                                onClick = { onPick(s); onDismiss() },
                                label = {
                                    Text(
                                        formatPlaybackSpeed(s),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ply_close)) }
        }
    )
}

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun dbgPlayerUi(hypothesisId: String, msg: String, data: String) {
    Thread {
        try {
            val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
            val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"PlayerScreen","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
            val conn = (URL("http://192.168.1.10:7777/event").openConnection() as HttpURLConnection)
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            runCatching { conn.inputStream.close() }
            conn.disconnect()
        } catch (_: Throwable) {
        }
    }.start()
}

@Composable
private fun WaveformOverlay() {
    val inf = rememberInfiniteTransition(label = "wave")
    val t by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "wt"
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val baseY = h * 0.78f
        val bars = 48
        val barW = w / bars
        for (i in 0 until bars) {
            val phase = (i / bars.toFloat()) * 6.28f
            val barH = (sin(phase + t * 6.28f) * 0.5f + 0.55f) * (h * 0.18f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(i * barW + barW * 0.22f, baseY - barH),
                size = androidx.compose.ui.geometry.Size(barW * 0.56f, barH + 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f)
            )
        }
    }
}

@Composable
fun defaultPlayerVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            val db = ShelfDatabase.getInstance(app)
            PlayerViewModel(
                application = app,
                db = db,
                dispatchers = DefaultDispatcherProvider
            )
        }
    }
}
