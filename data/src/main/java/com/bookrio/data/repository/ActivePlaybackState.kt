package com.bookrio.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveAudioState(
    val bookId: Long = 0L,
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val progressPercent: Float = 0f,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L
) {
    /** Deterministic artwork identity of this snapshot — see [NowPlayingPolicy]. */
    val artworkKey: String get() = NowPlayingPolicy.bookArtworkKey(bookId)
}

/**
 * Global "now playing" state for the audiobook engine.
 *
 * Deliberately separate from [PodcastPlaybackState] (podcasts), but coordinated through
 * [NowPlayingOwnership] so the app's mini-player can never show both. Only the current
 * owner may publish; a paused/backgrounded audiobook refreshing its progress can no
 * longer evict a podcast that is actually playing (the old cause of the flickering bar).
 */
object ActivePlaybackState {

    private val _state = MutableStateFlow<ActiveAudioState?>(null)
    val state: StateFlow<ActiveAudioState?> = _state.asStateFlow()

    /**
     * Publishes the audiobook's now-playing state.
     *
     * - `isPlaying == true` claims the mini-player (unless another engine is actively
     *   playing, which the arbiter must stop first).
     * - `isPlaying == false` only refreshes the bar while this engine already owns the
     *   slot (or the slot is free and was not dismissed). It never clears a podcast.
     */
    fun update(
        bookId: Long,
        title: String,
        author: String,
        isPlaying: Boolean,
        progressPercent: Float,
        sleepTimerMinutes: Int?,
        sleepTimerRemainingMs: Long
    ) {
        if (bookId <= 0L) {
            clear()
            return
        }
        val engine = NowPlayingOwnership.Engine.AUDIOBOOK
        if (NowPlayingOwnership.isDismissed(engine) && !isPlaying) return
        if (isPlaying) {
            if (!NowPlayingOwnership.claim(engine)) return
        } else {
            val owner = NowPlayingOwnership.current()
            if (owner != engine && owner != NowPlayingOwnership.Engine.NONE) return
            if (owner == NowPlayingOwnership.Engine.NONE && !NowPlayingOwnership.claim(engine)) return
        }
        _state.value = ActiveAudioState(
            bookId = bookId,
            title = title,
            author = author,
            isPlaying = isPlaying,
            progressPercent = progressPercent.coerceIn(0f, 1f),
            sleepTimerMinutes = sleepTimerMinutes,
            sleepTimerRemainingMs = sleepTimerRemainingMs
        )
    }

    /** Hides the bar and releases the slot without dismissing it (e.g. arbiter hand-off). */
    fun clear() {
        _state.value = null
        NowPlayingOwnership.release(NowPlayingOwnership.Engine.AUDIOBOOK)
    }

    /** User pressed the mini-player close button: hide until playback starts again. */
    fun dismiss() {
        NowPlayingOwnership.dismiss(NowPlayingOwnership.Engine.AUDIOBOOK)
    }

    /** Clears only the state during an ownership hand-off; ownership is set by the caller. */
    internal fun retire() {
        _state.value = null
    }
}
