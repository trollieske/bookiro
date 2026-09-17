package com.shelf.reader.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global "now playing" state for the podcast engine. Deliberately separate from
 * [ActivePlaybackState] (audiobooks). The two are mutually exclusive through
 * [NowPlayingOwnership], so the app's mini-player can never show both, and podcast
 * progress can never leak into the audiobook resume UI.
 */
data class PodcastActiveState(
    val episodeId: Long = 0L,
    val feedId: Long = 0L,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val progressPercent: Float = 0f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sleepTimerRemainingMs: Long = 0L
)

object PodcastPlaybackState {

    private val _state = MutableStateFlow<PodcastActiveState?>(null)
    val state: StateFlow<PodcastActiveState?> = _state.asStateFlow()

    /**
     * Publishes the podcast's now-playing state.
     *
     * - `isPlaying == true` claims the mini-player (unless another engine is actively
     *   playing, which the arbiter must stop first).
     * - `isPlaying == false` only refreshes the bar while this engine already owns the
     *   slot (or the slot is free and was not dismissed). It never clears the audiobook.
     *
     * This is what stops the paused-podcast `onIsPlayingChanged(false)` callback from
     * stealing the bar back right after an audiobook resumes.
     */
    fun update(
        episodeId: Long,
        feedId: Long,
        title: String,
        podcastTitle: String,
        artworkUrl: String?,
        isPlaying: Boolean,
        progressPercent: Float,
        positionMs: Long,
        durationMs: Long,
        sleepTimerRemainingMs: Long = 0L
    ) {
        if (episodeId <= 0L) {
            clear()
            return
        }
        val engine = NowPlayingOwnership.Engine.PODCAST
        if (NowPlayingOwnership.isDismissed(engine) && !isPlaying) return
        if (isPlaying) {
            if (!NowPlayingOwnership.claim(engine)) return
        } else {
            val owner = NowPlayingOwnership.current()
            if (owner != engine && owner != NowPlayingOwnership.Engine.NONE) return
            if (owner == NowPlayingOwnership.Engine.NONE && !NowPlayingOwnership.claim(engine)) return
        }
        _state.value = PodcastActiveState(
            episodeId = episodeId,
            feedId = feedId,
            title = title,
            podcastTitle = podcastTitle,
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            progressPercent = progressPercent.coerceIn(0f, 1f),
            positionMs = positionMs,
            durationMs = durationMs,
            sleepTimerRemainingMs = sleepTimerRemainingMs.coerceAtLeast(0L)
        )
    }

    /** Hides the bar and releases the slot without dismissing it (e.g. arbiter hand-off). */
    fun clear() {
        _state.value = null
        NowPlayingOwnership.release(NowPlayingOwnership.Engine.PODCAST)
    }

    /** User pressed the mini-player close button: hide until playback starts again. */
    fun dismiss() {
        NowPlayingOwnership.dismiss(NowPlayingOwnership.Engine.PODCAST)
    }

    /** Clears only the state during an ownership hand-off; ownership is set by the caller. */
    internal fun retire() {
        _state.value = null
    }
}
