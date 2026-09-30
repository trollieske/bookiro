package com.bookrio.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookrio.core.dispatchers.platformIoDispatcher
import com.bookrio.data.local.entity.AudioTrackEntity
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTheme
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.shared.platform.AppPrefs
import com.bookrio.shared.platform.PrefKeys
import com.bookrio.shared.platform.appDatabase
import com.bookrio.shared.platform.rememberLocalCover
import com.bookrio.shared.player.AudioOwner
import com.bookrio.shared.player.AudioPlayers
import com.bookrio.shared.player.AudiobookChapter
import com.bookrio.shared.player.AudiobookPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val PlayerBg = OmarchyColors.Bg
private val PlayerHeaderBg = OmarchyColors.HeaderBg
private val PlayerHairline = OmarchyColors.Hairline
private val PlayerAccent = OmarchyColors.Accent
private val PlayerDim = OmarchyColors.Dim
private val PlayerFg = OmarchyColors.Fg
private val PlayerFgBright = OmarchyColors.FgBright
private val PlayerPanel = OmarchyColors.Panel

/**
 * Scope for audiobook orchestration. The screen may be popped while the single
 * AVPlayer keeps sounding (the shell's now-playing bar still shows it), so
 * [AudiobookPlayback.play] must NOT receive a composition scope that dies on back
 * navigation — otherwise the track watcher and the ~2 s progress ticker stop.
 * Android keeps the same contract in its foreground playback service; one
 * process-wide scope mirrors `AudiobookPlayback`'s own fallback scope.
 */
private val playerPlaybackScope = CoroutineScope(SupervisorJob() + platformIoDispatcher)

/** UI-layer sleep timer state (the common-Kotlin twin of the Android service timer). */
private data class BookrioSleepTimer(
    val totalMinutes: Int,
    val remainingMs: Long,
)

/**
 * iOS-parity audiobook player chrome — the common-Kotlin port of the Android
 * `com.bookrio.player.ui.PlayerScreen` look (HUD theme, cover art + title/author,
 * whole-book scrub, chapter scrub, transport row, ±5 min quick row, speed
 * selector, sleep timer and chapter list), driven by the shared audio owner.
 *
 * Everything plays through the ONE [AudioPlayers.shared] instance: this screen
 * never constructs an AVPlayer/engine of its own. Book, tracks and resume position
 * come from [appDatabase]; playback is started with
 * `AudiobookPlayback.play(db, scope, book, tracks, AudiobookPlayback.resumePosition(db, bookId))`
 * exactly like the Android service does, and chapters are parsed by the shared
 * `BookEntity.chaptersJson` parser (no network lookup).
 *
 * @param bookId audiobook (book) to load and play.
 * @param onBack leave the screen; playback keeps running through the shared owner.
 */
@Composable
fun BookrioPlayerScreen(bookId: Long, onBack: () -> Unit) {
    ShelfTheme {
        PlayerChrome(bookId = bookId, onBack = onBack)
    }
}

@Composable
private fun PlayerChrome(bookId: Long, onBack: () -> Unit) {
    val db = remember { appDatabase() }
    val player = remember { AudioPlayers.shared }
    val state by player.state.collectAsState()
    val sharedChapters by AudiobookPlayback.chapters.collectAsState()
    val sharedChapterIndex by AudiobookPlayback.currentChapterIndex.collectAsState()

    var book by remember(bookId) { mutableStateOf<BookEntity?>(null) }
    var tracks by remember(bookId) { mutableStateOf<List<AudioTrackEntity>>(emptyList()) }
    var loading by remember(bookId) { mutableStateOf(true) }
    var loadError by remember(bookId) { mutableStateOf<String?>(null) }

    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var sleepTimer by remember { mutableStateOf<BookrioSleepTimer?>(null) }

    val skipBackSec = remember { AppPrefs.getInt(PrefKeys.AUDIO_SKIP_BACK, 10).coerceAtLeast(1) }
    val skipFwdSec = remember { AppPrefs.getInt(PrefKeys.AUDIO_SKIP_FWD, 30).coerceAtLeast(1) }

    LaunchedEffect(bookId) {
        loading = true
        loadError = null
        runCatching {
            val loadedBook = db.bookDao().getById(bookId) ?: error("Book not found")
            val loadedTracks = db.audioTrackDao().getTracksForBook(bookId)
            val resumeMs = AudiobookPlayback.resumePosition(db, bookId)
            Triple(loadedBook, loadedTracks, resumeMs)
        }.onSuccess { (loadedBook, loadedTracks, resumeMs) ->
            book = loadedBook
            tracks = loadedTracks
            loading = false
            AudiobookPlayback.play(db, playerPlaybackScope, loadedBook, loadedTracks, resumeMs)
            val savedSpeedMillis = AppPrefs.getInt(PrefKeys.AUDIO_SPEED_MILLIS, 1000).coerceIn(500, 3000)
            player.setSpeed(savedSpeedMillis / 1000f)
        }.onFailure { failure ->
            loading = false
            loadError = failure.message ?: "Could not load the audiobook"
        }
    }

    val ownsBook = state.request?.owner == AudioOwner.AUDIOBOOK && state.request?.id == bookId
    val chapters = if (ownsBook) sharedChapters else emptyList()
    val chapterIndex = if (ownsBook) sharedChapterIndex else 0
    val safeChapterIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    val currentChapter = chapters.getOrNull(safeChapterIndex)
    val playerReady = ownsBook && state.error == null
    val errorText = loadError ?: (if (ownsBook) state.error else null)

    // UI-layer sleep timer: ticks once a second and pauses the audiobook at zero.
    val sleepActive = sleepTimer != null
    LaunchedEffect(sleepActive) {
        if (!sleepActive) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            val active = sleepTimer ?: return@LaunchedEffect
            val remaining = (active.remainingMs - 1_000L).coerceAtLeast(0L)
            if (remaining <= 0L) {
                sleepTimer = null
                val current = player.state.value
                val owns = current.request?.owner == AudioOwner.AUDIOBOOK && current.request?.id == bookId
                if (owns && current.isPlaying) AudiobookPlayback.pause()
                return@LaunchedEffect
            }
            sleepTimer = active.copy(remainingMs = remaining)
        }
    }

    val seekTo: (Long) -> Unit = { targetMs ->
        val duration = state.durationMs
        val target = if (duration > 0L) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
        player.seekTo(target)
    }
    val skipBy: (Long) -> Unit = { deltaMs ->
        seekTo(state.positionMs.coerceAtLeast(0L) + deltaMs)
    }
    val seekChapter: (AudiobookChapter) -> Unit = { chapter ->
        AudiobookPlayback.seekToChapter(db, bookId, chapter, tracks)
    }
    val previousChapter: () -> Unit = {
        if (chapters.size > 1) {
            val current = chapters[safeChapterIndex]
            val target = if (state.positionMs - current.startMs > 3_000L) {
                current
            } else {
                chapters.getOrNull(safeChapterIndex - 1) ?: current
            }
            seekChapter(target)
        }
    }
    val nextChapter: () -> Unit = {
        if (chapters.size > 1 && safeChapterIndex < chapters.size - 1) {
            chapters.getOrNull(safeChapterIndex + 1)?.let(seekChapter)
        }
    }

    val bookTitle = book?.title?.takeIf { it.isNotBlank() } ?: state.request?.title.orEmpty()
    val bookAuthor = book?.author?.takeIf { it.isNotBlank() } ?: state.request?.artist.orEmpty()
    val displayTitle = when {
        currentChapter != null && currentChapter.title.isNotBlank() &&
            !currentChapter.title.equals(bookTitle, ignoreCase = true) -> currentChapter.title
        bookTitle.isNotBlank() -> bookTitle
        else -> "Audiobook"
    }
    val chapterLabel = when {
        chapters.size > 1 -> "CHAPTER ${safeChapterIndex + 1} OF ${chapters.size}"
        chapters.size == 1 -> "AUDIOBOOK"
        else -> "AUDIO PLAYER"
    }
    val cover = rememberLocalCover(book?.coverPath ?: state.request?.artworkUrl)

    Column(Modifier.fillMaxSize().background(PlayerBg)) {
        // Flat HUD header: back, title, sleep-timer chip/icon, chapter list.
        Row(
            Modifier
                .fillMaxWidth()
                .background(PlayerHeaderBg)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = PlayerFg,
                )
            }
            Text(
                "Audiobook",
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                color = PlayerFgBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val activeSleep = sleepTimer
            if (activeSleep != null) {
                Row(
                    Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PlayerPanel)
                        .border(1.dp, PlayerHairline, RoundedCornerShape(12.dp))
                        .clickable { showSleep = true }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "☾ ${bookrioFormatCountdown(activeSleep.remainingMs)}",
                        style = ShelfTypography.LabelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PlayerAccent,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            } else {
                IconButton(onClick = { showSleep = true }) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep timer",
                        tint = PlayerFg,
                    )
                }
            }
            IconButton(onClick = { showChapters = true }) {
                Icon(
                    Icons.AutoMirrored.Filled.Toc,
                    contentDescription = "Chapters",
                    tint = PlayerFg,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(PlayerHairline))

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            PlayerCover(
                cover = cover,
                title = displayTitle,
                author = bookAuthor,
                contentDescription = "Book cover",
            )

            Spacer(Modifier.height(12.dp))

            when {
                loading -> PlayerStatusPanel(
                    title = "Preparing playback…",
                    detail = null,
                    isError = false,
                    showSpinner = true,
                )
                errorText != null -> PlayerStatusPanel(
                    title = "Could not prepare the audiobook",
                    detail = errorText,
                    isError = true,
                    showSpinner = false,
                )
                !playerReady -> PlayerStatusPanel(
                    title = "Waiting for media source…",
                    detail = null,
                    isError = false,
                    showSpinner = false,
                )
            }
            if (!playerReady) Spacer(Modifier.height(16.dp))

            Text(
                chapterLabel,
                style = ShelfTypography.LabelMedium.copy(letterSpacing = 1.2.sp),
                fontWeight = FontWeight.Bold,
                color = PlayerAccent,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                displayTitle,
                style = ShelfTypography.TitleLarge,
                fontWeight = FontWeight.Bold,
                color = PlayerFgBright,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (bookAuthor.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    bookAuthor,
                    style = ShelfTypography.BodyMedium,
                    color = PlayerDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))

            WholeBookScrub(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                enabled = playerReady,
                onSeek = seekTo,
            )

            if (chapters.size > 1 && currentChapter != null) {
                Spacer(Modifier.height(10.dp))
                ChapterScrub(
                    chapters = chapters,
                    index = safeChapterIndex,
                    positionMs = state.positionMs,
                    fallbackEndMs = state.durationMs,
                    enabled = playerReady &&
                        chapterBelongsToCurrentItem(currentChapter, tracks, state.request?.uri),
                    onSeek = seekTo,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { skipBy(-skipBackSec * 1000L) }, enabled = playerReady) {
                    Icon(
                        skipBackIcon(skipBackSec),
                        contentDescription = "Back $skipBackSec s",
                        tint = if (playerReady) PlayerFg else PlayerDim,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = previousChapter, enabled = chapters.size > 1) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous chapter",
                        tint = if (chapters.size > 1) PlayerFg else PlayerDim,
                        modifier = Modifier.size(34.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = { player.toggle() },
                    enabled = playerReady,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = if (playerReady) PlayerAccent else PlayerDim,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(
                    onClick = nextChapter,
                    enabled = chapters.size > 1 && safeChapterIndex < chapters.size - 1,
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next chapter",
                        tint = if (chapters.size > 1 && safeChapterIndex < chapters.size - 1) {
                            PlayerFg
                        } else {
                            PlayerDim
                        },
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { skipBy(skipFwdSec * 1000L) }, enabled = playerReady) {
                    Icon(
                        skipForwardIcon(skipFwdSec),
                        contentDescription = "Forward $skipFwdSec s",
                        tint = if (playerReady) PlayerFg else PlayerDim,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Bottom quick row: big jumps + speed — the always-visible Android row.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { skipBy(-300_000L) },
                    enabled = playerReady,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(
                        "−5 min",
                        style = ShelfTypography.LabelMedium,
                        color = if (playerReady) PlayerFg else PlayerDim,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                Row(
                    Modifier
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PlayerPanel)
                        .border(1.dp, PlayerHairline, RoundedCornerShape(8.dp))
                        .clickable { showSpeed = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Speed,
                        contentDescription = null,
                        tint = PlayerFg,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        bookrioFormatSpeed(state.speed),
                        style = ShelfTypography.LabelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PlayerFgBright,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
                TextButton(
                    onClick = { skipBy(300_000L) },
                    enabled = playerReady,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Text(
                        "+5 min",
                        style = ShelfTypography.LabelMedium,
                        color = if (playerReady) PlayerFg else PlayerDim,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showChapters) {
        PlayerChaptersSheet(
            chapters = chapters,
            currentIndex = safeChapterIndex,
            onDismiss = { showChapters = false },
            onSelect = { chapter ->
                seekChapter(chapter)
                showChapters = false
            },
        )
    }
    if (showSpeed) {
        PlayerSpeedDialog(
            current = state.speed,
            onPick = { speed ->
                player.setSpeed(speed)
                AppPrefs.putInt(PrefKeys.AUDIO_SPEED_MILLIS, (speed * 1_000f).roundToInt())
            },
            onDismiss = { showSpeed = false },
        )
    }
    if (showSleep) {
        PlayerSleepSheet(
            currentMinutes = sleepTimer?.totalMinutes,
            remainingMs = sleepTimer?.remainingMs ?: 0L,
            onDismiss = { showSleep = false },
            onPick = { minutes ->
                sleepTimer = if (minutes == null || minutes <= 0) {
                    null
                } else {
                    BookrioSleepTimer(totalMinutes = minutes, remainingMs = minutes * 60_000L)
                }
                showSleep = false
            },
        )
    }
}

/**
 * Cover card, mirroring the Android 200 dp spine card: decoded local cover on top
 * with a bottom gradient when available, drawn HUD fallback with title/author
 * when not.
 */
@Composable
private fun PlayerCover(
    cover: ImageBitmap?,
    title: String,
    author: String,
    contentDescription: String,
) {
    Box(
        Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(PlayerPanel)
            .border(1.dp, PlayerHairline, RoundedCornerShape(16.dp)),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        ),
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1C1C1C), PlayerPanel))),
            )
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title.ifBlank { "Audiobook" },
                    style = ShelfTypography.TitleLarge,
                    fontWeight = FontWeight.Bold,
                    color = PlayerFgBright,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    author,
                    style = ShelfTypography.BodyMedium,
                    color = PlayerDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Status card: preparing / waiting / error — the Android `playerReady` panel. */
@Composable
private fun PlayerStatusPanel(
    title: String,
    detail: String?,
    isError: Boolean,
    showSpinner: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isError) MaterialTheme.colorScheme.errorContainer else PlayerPanel)
            .border(
                width = 1.dp,
                color = if (isError) MaterialTheme.colorScheme.error else PlayerHairline,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSpinner) {
                CircularProgressIndicator(
                    color = PlayerAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                title,
                style = ShelfTypography.TitleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isError) MaterialTheme.colorScheme.error else PlayerFgBright,
            )
        }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = ShelfTypography.BodySmall, color = PlayerDim)
        }
    }
}

/**
 * Whole-book scrub: the thin Android «scratch» line over `state.positionMs /
 * state.durationMs`, with live counters while dragging and a pending hold after
 * release so the display does not snap back to the stale position.
 *
 * Note: `AudioPlayerState` is item-relative, so for a book spread over several
 * track files this line covers the current AVPlayer item (single-file audiobooks
 * — the common m4b case — are exact). See `contracts/player.md`.
 */
@Composable
private fun WholeBookScrub(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val lengthMs = durationMs.coerceAtLeast(0L)
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var pendingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(positionMs, pendingMs) {
        val pending = pendingMs ?: return@LaunchedEffect
        if (abs(positionMs - pending) < 1_000L) pendingMs = null
    }
    val shownMs = when {
        scrubFraction != null -> (scrubFraction!! * lengthMs).toLong().coerceIn(0L, lengthMs)
        pendingMs != null -> pendingMs!!.coerceIn(0L, lengthMs)
        else -> positionMs.coerceIn(0L, lengthMs)
    }
    val fraction = if (lengthMs > 0L) {
        (shownMs.toFloat() / lengthMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val seek: (Float) -> Unit = { raw ->
        val target = (raw.coerceIn(0f, 1f) * lengthMs).toLong()
        pendingMs = target
        onSeek(target)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WHOLE BOOK",
                style = ShelfTypography.LabelSmall,
                fontWeight = FontWeight.Bold,
                color = PlayerAccent,
            )
            Text(
                "${bookrioFormatDuration(shownMs / 1_000L)} / ${bookrioFormatDuration(lengthMs / 1_000L)}",
                style = ShelfTypography.LabelSmall,
                color = PlayerDim,
            )
        }
        HudScrubLine(
            fraction = fraction,
            onScrub = { f ->
                if (enabled) {
                    pendingMs = null
                    scrubFraction = f
                }
            },
            onScrubEnd = { f ->
                if (enabled) {
                    scrubFraction = null
                    seek(f)
                }
            },
            onTap = { f -> if (enabled) seek(f) },
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
    }
}

/**
 * Chapter scrub, mirroring the Android «IN CHAPTER» row: fine-grained seek inside
 * the current chapter only, «Chapter X of Y», in-chapter elapsed/left counters and
 * the chapter title. Only rendered for books with more than one real chapter.
 */
@Composable
private fun ChapterScrub(
    chapters: List<AudiobookChapter>,
    index: Int,
    positionMs: Long,
    fallbackEndMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val current = chapters.getOrNull(index) ?: return
    val startMs = current.startMs.coerceAtLeast(0L)
    val naturalEnd = current.endMs ?: chapters.getOrNull(index + 1)?.startMs
    val endMs = (naturalEnd ?: fallbackEndMs).coerceAtLeast(startMs + 1L)
    val lengthMs = endMs - startMs

    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var pendingMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(positionMs, pendingMs) {
        val pending = pendingMs ?: return@LaunchedEffect
        if (abs(positionMs - pending) < 1_000L) pendingMs = null
    }
    val elapsedMs = (positionMs - startMs).coerceIn(0L, lengthMs)
    val shownMs = when {
        scrubFraction != null -> (scrubFraction!! * lengthMs).toLong().coerceIn(0L, lengthMs)
        pendingMs != null -> (pendingMs!! - startMs).coerceIn(0L, lengthMs)
        else -> elapsedMs
    }
    val fraction = (shownMs.toFloat() / lengthMs.toFloat()).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "IN CHAPTER",
                style = ShelfTypography.LabelSmall,
                fontWeight = FontWeight.Bold,
                color = PlayerAccent,
            )
            Text(
                "Chapter ${index + 1} of ${chapters.size}",
                style = ShelfTypography.LabelSmall,
                color = PlayerDim,
            )
        }
        Slider(
            value = fraction,
            onValueChange = { f ->
                if (enabled) {
                    pendingMs = null
                    scrubFraction = f
                }
            },
            onValueChangeFinished = {
                if (enabled) {
                    val f = scrubFraction ?: fraction
                    scrubFraction = null
                    val target = startMs + (f * lengthMs).toLong()
                    pendingMs = target
                    onSeek(target)
                }
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = PlayerAccent,
                activeTrackColor = PlayerAccent,
                inactiveTrackColor = PlayerHairline,
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "In chapter: ${bookrioFormatDuration(shownMs / 1_000L)}",
                style = ShelfTypography.LabelSmall,
                color = PlayerDim,
            )
            Text(
                "- ${bookrioFormatDuration(((lengthMs - shownMs) / 1_000L).coerceAtLeast(0L))} left",
                style = ShelfTypography.LabelSmall,
                color = PlayerDim,
            )
        }
        Text(
            current.title.ifBlank { "Chapter ${index + 1}" },
            style = ShelfTypography.LabelSmall,
            color = PlayerDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Thin, compact tap+drag scrub line for the whole book — the common-Kotlin twin
 * of the Android `CompactTimeline`, recoloured with the HUD palette.
 */
@Composable
private fun HudScrubLine(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var trackWidthPx by remember { mutableStateOf(1f) }
    val shown = fraction.coerceIn(0f, 1f)
    val shownState = rememberUpdatedState(shown)
    val onScrubState = rememberUpdatedState(onScrub)
    val onScrubEndState = rememberUpdatedState(onScrubEnd)
    val onTapState = rememberUpdatedState(onTap)

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
            },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .align(Alignment.CenterStart),
        ) {
            val radius = size.height / 2f
            drawRoundRect(color = PlayerHairline, cornerRadius = CornerRadius(radius, radius))
            drawRoundRect(
                color = PlayerAccent,
                size = Size(size.width * shown, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
        val thumbPx = with(LocalDensity.current) { 12.dp.toPx() }
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((trackWidthPx - thumbPx) * shown).roundToInt(), 0) }
                .size(12.dp)
                .background(PlayerAccent, RoundedCornerShape(6.dp)),
        )
    }
}

private fun skipBackIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Default.Replay5
    10 -> Icons.Default.Replay10
    else -> Icons.Default.Replay30
}

private fun skipForwardIcon(seconds: Int): ImageVector = when (seconds) {
    5 -> Icons.Default.Forward5
    10 -> Icons.Default.Forward10
    else -> Icons.Default.Forward30
}

/**
 * In-chapter scrubbing only makes sense while the chapter lives in the track that
 * is currently loaded (`player.seekTo` cannot cross files). Chapter files are
 * switched through [AudiobookPlayback.seekToChapter] from the chapter list.
 */
private fun chapterBelongsToCurrentItem(
    chapter: AudiobookChapter,
    tracks: List<AudioTrackEntity>,
    currentUri: String?,
): Boolean {
    val mediaUri = chapter.mediaUri ?: return true
    val current = currentUri ?: return false
    if (current == mediaUri) return true
    val chapterTrack = tracks.firstOrNull { it.filePath == mediaUri || it.fileUri == mediaUri } ?: return false
    return chapterTrack.filePath == current || chapterTrack.fileUri == current
}