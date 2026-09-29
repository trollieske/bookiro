package com.bookrio.shared.player

import com.bookrio.core.dispatchers.platformIoDispatcher
import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.AudioTrackEntity
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Audiobook orchestration on top of the single shared [AudioPlayer].
 *
 * Chapters come from [BookEntity.chaptersJson] ([AudiobookChapters]); playback
 * follows the book's tracks in order and advances automatically when a track ends.
 * Reading position is persisted to [ReadingProgressEntity] while playing (~every
 * 2 s) and on pause/stop, so the app can resume where the listener left off.
 */
object AudiobookPlayback {

    private const val PROGRESS_TICK_MS = 2_000L

    private val _chapters = MutableStateFlow<List<AudiobookChapter>>(emptyList())
    val chapters: StateFlow<List<AudiobookChapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val player: AudioPlayer get() = AudioPlayers.shared

    /** Used for calls that arrive without a caller scope (e.g. [seekToChapter]). */
    private val fallbackScope = CoroutineScope(SupervisorJob() + platformIoDispatcher)

    @Volatile
    private var session: Session? = null
    private var watcher: Job? = null
    private var ticker: Job? = null

    /** Restores the last stored position for [bookId] (0 when there is none). */
    suspend fun resumePosition(db: ShelfDatabase, bookId: Long): Long {
        val stored = runCatching { db.progressDao().getByBook(bookId) }.getOrNull()
        return stored?.positionMs?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Starts (or resumes) [book] through [AudioPlayers.shared], in track order.
     * [startPositionMs] is a book-global position; the right track and in-track
     * offset are derived from the track durations.
     */
    fun play(
        db: ShelfDatabase,
        scope: CoroutineScope,
        book: BookEntity,
        tracks: List<AudioTrackEntity>,
        startPositionMs: Long,
    ) {
        val resolved = resolveTracks(book, tracks)
        val (startIndex, startOffset) = locateStart(resolved, startPositionMs)

        // Persist the outgoing audiobook before the shared player is handed over.
        val previous = session
        if (previous != null) {
            val previousState = player.state.value
            previous.scope.launch { persistProgress(previous, previousState) }
        }

        val active = Session(db = db, scope = scope, book = book, tracks = resolved, index = startIndex)
        session = active

        watcher?.cancel()
        ticker?.cancel()

        _chapters.value = AudiobookChapters.parse(book.chaptersJson)
        val globalStart = cumulativeDurationOf(resolved, startIndex) + startOffset
        _currentChapterIndex.value = chapterIndexAt(globalStart)

        val track = resolved.getOrNull(startIndex)
        if (track == null) {
            // No playable source (missing file path/uri) — the player reports the error.
            player.play(
                AudioRequest(
                    owner = AudioOwner.AUDIOBOOK,
                    id = book.id,
                    uri = "",
                    title = book.title,
                    artist = book.author.takeIf { it.isNotBlank() },
                )
            )
        } else {
            player.play(
                AudioRequest(
                    owner = AudioOwner.AUDIOBOOK,
                    id = book.id,
                    uri = track.uri,
                    title = track.title,
                    artist = book.author.takeIf { it.isNotBlank() },
                    artworkUrl = book.coverPath?.takeIf { it.isNotBlank() },
                    startPositionMs = startOffset,
                    durationMs = track.durationMs.takeIf { it > 0L },
                )
            )
        }

        watcher = scope.launch { watch(active) }
        ticker = scope.launch { tick(active) }
    }

    /** Seeks to the start of [chapter] (switching track when it has a [AudiobookChapter.mediaUri]). */
    fun seekToChapter(
        db: ShelfDatabase,
        bookId: Long,
        chapter: AudiobookChapter,
        tracks: List<AudioTrackEntity>,
    ) {
        val active = session
        if (active != null && active.book.id == bookId) {
            play(db, active.scope, active.book, tracks, chapterStartPosition(active, chapter))
            return
        }
        fallbackScope.launch {
            val book = runCatching { db.bookDao().getById(bookId) }.getOrNull() ?: return@launch
            play(db, fallbackScope, book, tracks, chapter.startMs.coerceAtLeast(0L))
        }
    }

    fun pause() {
        if (player.state.value.request?.owner != AudioOwner.AUDIOBOOK) return
        player.pause()
        val active = session ?: return
        val snapshot = player.state.value
        active.scope.launch { persistProgress(active, snapshot) }
    }

    fun resume() {
        if (player.state.value.request?.owner != AudioOwner.AUDIOBOOK) return
        player.resume()
    }

    fun stop() {
        val active = session
        val snapshot = player.state.value
        if (active != null) active.scope.launch { persistProgress(active, snapshot) }
        if (snapshot.request?.owner == AudioOwner.AUDIOBOOK) player.stop()
        session = null
        watcher?.cancel()
        watcher = null
        ticker?.cancel()
        ticker = null
        _chapters.value = emptyList()
        _currentChapterIndex.value = 0
    }

    // ---- session internals ---------------------------------------------------------

    private class Session(
        val db: ShelfDatabase,
        val scope: CoroutineScope,
        val book: BookEntity,
        val tracks: List<ResolvedTrack>,
        var index: Int,
    ) {
        fun cumulativeDuration(index: Int): Long {
            var sum = 0L
            val end = index.coerceIn(0, tracks.size)
            for (i in 0 until end) sum += tracks[i].durationMs
            return sum
        }
    }

    private data class ResolvedTrack(
        val uri: String,
        val filePath: String?,
        val fileUri: String?,
        val title: String,
        val durationMs: Long,
    ) {
        fun matchesUri(value: String): Boolean =
            uri == value || filePath == value || fileUri == value
    }

    private suspend fun watch(active: Session) {
        var sawEnded = false
        player.state.collect { state ->
            val request = state.request
            if (request == null || request.owner != AudioOwner.AUDIOBOOK || request.id != active.book.id) {
                return@collect
            }
            _currentChapterIndex.value = chapterIndexAt(globalPosition(active, state))
            if (state.ended) {
                if (!sawEnded) {
                    sawEnded = true
                    onTrackEnded(active)
                }
            } else {
                sawEnded = false
            }
        }
    }

    private suspend fun tick(active: Session) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            if (active !== session) return
            val state = player.state.value
            if (state.request?.id != active.book.id || state.request.owner != AudioOwner.AUDIOBOOK) return
            if (!state.isPlaying) continue
            persistProgress(active, state)
        }
    }

    private fun onTrackEnded(active: Session) {
        if (active !== session) return
        val next = active.index + 1
        if (next < active.tracks.size) {
            active.index = next
            active.scope.launch { startTrack(active) }
        } else {
            active.scope.launch { persistCompletion(active) }
        }
    }

    private fun startTrack(active: Session) {
        if (active !== session) return
        val track = active.tracks.getOrNull(active.index) ?: return
        player.play(
            AudioRequest(
                owner = AudioOwner.AUDIOBOOK,
                id = active.book.id,
                uri = track.uri,
                title = track.title,
                artist = active.book.author.takeIf { it.isNotBlank() },
                artworkUrl = active.book.coverPath?.takeIf { it.isNotBlank() },
                startPositionMs = 0L,
                durationMs = track.durationMs.takeIf { it > 0L },
            )
        )
    }

    private suspend fun persistCompletion(active: Session) {
        val duration = bookDuration(active, player.state.value.durationMs)
        writeProgress(active, positionMs = duration, durationMs = duration)
    }

    private suspend fun persistProgress(active: Session, state: AudioPlayerState) {
        val request = state.request ?: return
        if (request.owner != AudioOwner.AUDIOBOOK || request.id != active.book.id) return
        val position = globalPosition(active, state)
        val duration = bookDuration(active, state.durationMs)
        writeProgress(active, positionMs = position, durationMs = duration)
    }

    private suspend fun writeProgress(active: Session, positionMs: Long, durationMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        val percent = if (durationMs > 0L) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val chapter = AudiobookChapters.chapterAt(_chapters.value, position)
        val chapterPosition = chapter?.let { (position - it.startMs).coerceAtLeast(0L) }
        val updatedAt = nowMillis()
        runCatching {
            active.db.progressDao().upsertForBook(
                bookId = active.book.id,
                updater = { existing ->
                    existing.copy(
                        progressPercent = percent,
                        positionMs = position,
                        chapterIndex = chapter?.index,
                        chapterPositionMs = chapterPosition,
                        updatedAt = updatedAt,
                    )
                },
                creator = {
                    ReadingProgressEntity(
                        bookId = active.book.id,
                        progressPercent = percent,
                        positionMs = position,
                        chapterIndex = chapter?.index,
                        chapterPositionMs = chapterPosition,
                        updatedAt = updatedAt,
                    )
                },
            )
        }
    }

    private fun globalPosition(active: Session, state: AudioPlayerState): Long =
        active.cumulativeDuration(active.index) + state.positionMs.coerceAtLeast(0L)

    private fun bookDuration(active: Session, stateDurationMs: Long): Long {
        active.book.durationMs?.takeIf { it > 0L }?.let { return it }
        val trackTotal = active.tracks.sumOf { it.durationMs }
        if (trackTotal > 0L) return trackTotal
        return active.cumulativeDuration(active.index) + stateDurationMs.coerceAtLeast(0L)
    }

    private fun chapterIndexAt(positionMs: Long): Int =
        AudiobookChapters.chapterAt(_chapters.value, positionMs)?.index ?: 0

    private fun chapterStartPosition(active: Session, chapter: AudiobookChapter): Long {
        val mediaUri = chapter.mediaUri
        if (mediaUri != null) {
            val index = active.tracks.indexOfFirst { it.matchesUri(mediaUri) }
            if (index >= 0) {
                val trackStart = active.cumulativeDuration(index)
                // Chapter starts are book-global when the mediaUri is a per-track source,
                // but stay defensive against in-track startMs values too.
                return if (chapter.startMs >= trackStart) chapter.startMs else trackStart
            }
        }
        return chapter.startMs.coerceAtLeast(0L)
    }

    private fun cumulativeDurationOf(tracks: List<ResolvedTrack>, index: Int): Long {
        var sum = 0L
        val end = index.coerceIn(0, tracks.size)
        for (i in 0 until end) sum += tracks[i].durationMs
        return sum
    }

    /** Picks the track containing a book-global [startPositionMs] plus the in-track offset. */
    private fun locateStart(tracks: List<ResolvedTrack>, startPositionMs: Long): Pair<Int, Long> {
        if (tracks.isEmpty()) return 0 to startPositionMs.coerceAtLeast(0L)
        val position = startPositionMs.coerceAtLeast(0L)
        if (position == 0L) return 0 to 0L
        var cumulative = 0L
        tracks.forEachIndexed { index, track ->
            val duration = track.durationMs
            if (duration > 0L) {
                if (position < cumulative + duration) return index to (position - cumulative)
                cumulative += duration
            }
        }
        if (cumulative == 0L) return 0 to position
        val last = tracks.lastIndex
        val lastDuration = tracks[last].durationMs
        val offset = (position - cumulative).coerceAtLeast(0L)
        return last to if (lastDuration > 0L) offset.coerceAtMost(lastDuration) else offset
    }

    private fun resolveTracks(book: BookEntity, tracks: List<AudioTrackEntity>): List<ResolvedTrack> {
        val ordered = tracks.sortedWith(
            compareBy<AudioTrackEntity>(
                { it.discNumber },
                { it.trackNumber },
                { it.filePath ?: it.fileUri ?: it.remotePath ?: "" },
            )
        )
        val resolved = ordered.mapNotNull { track ->
            val filePath = track.filePath?.takeIf { it.isNotBlank() }
            val fileUri = track.fileUri?.takeIf { it.isNotBlank() }
            val uri = filePath ?: fileUri ?: return@mapNotNull null
            ResolvedTrack(
                uri = uri,
                filePath = filePath,
                fileUri = fileUri,
                title = track.title.ifBlank { book.title },
                durationMs = track.durationMs.coerceAtLeast(0L),
            )
        }
        if (resolved.isNotEmpty()) return resolved

        // Single-file book: fall back to the book's own path.
        val bookUri = book.filePath?.takeIf { it.isNotBlank() }
            ?: book.fileUri?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return listOf(
            ResolvedTrack(
                uri = bookUri,
                filePath = book.filePath,
                fileUri = book.fileUri,
                title = book.title,
                durationMs = book.durationMs?.coerceAtLeast(0L) ?: 0L,
            )
        )
    }
}