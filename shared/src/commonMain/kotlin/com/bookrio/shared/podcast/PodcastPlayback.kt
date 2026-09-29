package com.bookrio.shared.podcast

import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.shared.player.AudioOwner
import com.bookrio.shared.player.AudioPlayer
import com.bookrio.shared.player.AudioPlayerState
import com.bookrio.shared.player.AudioPlayers
import com.bookrio.shared.player.AudioRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * Podcast orchestration on top of the single shared [AudioPlayers.shared]
 * (`owner = PODCAST`), mirroring [com.bookrio.shared.player.AudiobookPlayback].
 *
 * Episodes stream from their enclosure URL — no downloads. Position is written
 * to `podcast_playback` while playing (~every 2 s) and on pause/stop; when the
 * player reports `ended` the row is marked completed. Starting an audiobook
 * replaces this playback automatically (there is exactly one audio player), so
 * this object never fights another owner.
 */
object PodcastPlayback {

    private const val PROGRESS_TICK_MS = 2_000L

    private val player: AudioPlayer get() = AudioPlayers.shared

    @Volatile
    private var session: Session? = null
    private var watcher: Job? = null
    private var ticker: Job? = null

    /** The last stored position for [episodeId] (0 when there is none). */
    suspend fun resumePosition(db: ShelfDatabase, episodeId: Long): Long {
        val stored = runCatching { db.podcastPlaybackDao().getByEpisode(episodeId) }.getOrNull()
        return stored?.positionMs?.coerceAtLeast(0L) ?: 0L
    }

    /** Starts (or resumes) [episode] through the shared audio player. */
    fun play(
        db: ShelfDatabase,
        scope: CoroutineScope,
        episode: PodcastEpisodeEntity,
        startPositionMs: Long,
    ) {
        // Persist the outgoing podcast before the single player is handed over.
        val previous = session
        if (previous != null) {
            val previousState = player.state.value
            previous.scope.launch { persistProgress(previous, previousState) }
        }

        val active = Session(db = db, scope = scope, episode = episode)
        session = active

        watcher?.cancel()
        ticker?.cancel()

        player.play(
            AudioRequest(
                owner = AudioOwner.PODCAST,
                id = episode.id,
                uri = episode.enclosureUrl,
                title = episode.title,
                artist = null,
                artworkUrl = episode.artworkUrl?.takeIf { it.isNotBlank() },
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                durationMs = episode.durationMs?.takeIf { it > 0L },
            )
        )

        watcher = scope.launch { watch(active) }
        ticker = scope.launch { tick(active) }
    }

    fun pause() {
        if (player.state.value.request?.owner != AudioOwner.PODCAST) return
        player.pause()
        val active = session ?: return
        val snapshot = player.state.value
        active.scope.launch { persistProgress(active, snapshot) }
    }

    fun resume() {
        if (player.state.value.request?.owner != AudioOwner.PODCAST) return
        player.resume()
    }

    fun stop() {
        val active = session
        val snapshot = player.state.value
        if (active != null) active.scope.launch { persistProgress(active, snapshot) }
        if (snapshot.request?.owner == AudioOwner.PODCAST) player.stop()
        session = null
        watcher?.cancel()
        watcher = null
        ticker?.cancel()
        ticker = null
    }

    // ---- session internals -------------------------------------------------------------

    private class Session(
        val db: ShelfDatabase,
        val scope: CoroutineScope,
        val episode: PodcastEpisodeEntity,
    )

    private suspend fun watch(active: Session) {
        var sawEnded = false
        player.state.collect { state ->
            val request = state.request
            if (request == null || request.owner != AudioOwner.PODCAST ||
                request.id != active.episode.id
            ) {
                return@collect
            }
            if (state.ended) {
                if (!sawEnded) {
                    sawEnded = true
                    onEnded(active)
                }
            } else {
                sawEnded = false
            }
        }
    }

    private fun onEnded(active: Session) {
        if (active !== session) return
        active.scope.launch { persistCompletion(active) }
    }

    private suspend fun tick(active: Session) {
        while (true) {
            delay(PROGRESS_TICK_MS)
            if (active !== session) return
            val state = player.state.value
            val request = state.request ?: return
            if (request.owner != AudioOwner.PODCAST || request.id != active.episode.id) return
            if (!state.isPlaying) continue
            persistProgress(active, state)
        }
    }

    private suspend fun persistProgress(active: Session, state: AudioPlayerState) {
        val request = state.request ?: return
        if (request.owner != AudioOwner.PODCAST || request.id != active.episode.id) return
        if (state.ended) {
            persistCompletion(active)
            return
        }
        val episodeId = active.episode.id
        val existing = runCatching { active.db.podcastPlaybackDao().getByEpisode(episodeId) }
            .getOrNull()
        val duration = state.durationMs.takeIf { it > 0L }
            ?: active.episode.durationMs?.takeIf { it > 0L }
            ?: existing?.durationMs
        runCatching {
            active.db.podcastPlaybackDao().upsert(
                PodcastPlaybackEntity(
                    episodeId = episodeId,
                    positionMs = state.positionMs.coerceAtLeast(0L),
                    durationMs = duration,
                    lastPlayedAt = nowMillis(),
                    isCompleted = false,
                    completedAt = existing?.completedAt,
                    playbackSpeed = existing?.playbackSpeed ?: 1f,
                )
            )
        }
    }

    private suspend fun persistCompletion(active: Session) {
        val episodeId = active.episode.id
        val existing = runCatching { active.db.podcastPlaybackDao().getByEpisode(episodeId) }
            .getOrNull()
        val duration = active.episode.durationMs?.takeIf { it > 0L }
            ?: player.state.value.durationMs.takeIf { it > 0L }
            ?: existing?.durationMs
        val now = nowMillis()
        runCatching {
            active.db.podcastPlaybackDao().upsert(
                PodcastPlaybackEntity(
                    episodeId = episodeId,
                    positionMs = 0L,
                    durationMs = duration,
                    lastPlayedAt = now,
                    isCompleted = true,
                    completedAt = now,
                    playbackSpeed = existing?.playbackSpeed ?: 1f,
                )
            )
            active.db.podcastPlaybackDao().markCompleted(episodeId, now)
        }
    }
}