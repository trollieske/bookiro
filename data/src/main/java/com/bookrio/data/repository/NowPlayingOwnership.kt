package com.bookrio.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-owner arbitration for the app's one and only "now playing" mini-player.
 *
 * Audiobooks ([ActivePlaybackState]) and podcasts ([PodcastPlaybackState]) each expose
 * their own state flow, but only one engine may occupy the mini-player slot at a time.
 *
 * Previously the two flows retired each other on *every* update. Because the audiobook
 * player republishes its progress every 500 ms and the podcast service every 1 s, two
 * live engines could evict each other in a loop and the bar visibly flickered between
 * the book and the episode.
 *
 * Ownership fixes that with one rule: **an engine may take the slot for itself, but it
 * may never steal it from another engine that is currently playing.** A genuine
 * hand-over always goes through [com.bookrio.core.playback.PlaybackArbiter], which
 * stops (and retires) the previous engine first, freeing the slot. Paused/progress-only
 * refreshes are therefore harmless no-ops while the other engine owns the slot.
 */
object NowPlayingOwnership {

    enum class Engine { NONE, AUDIOBOOK, PODCAST }

    private val lock = Any()
    private val _owner = MutableStateFlow(Engine.NONE)
    val owner: StateFlow<Engine> = _owner.asStateFlow()

    /** Engines the user explicitly dismissed; they stay hidden until they play again. */
    private val dismissed = mutableSetOf<Engine>()

    fun current(): Engine = _owner.value

    fun isOwner(engine: Engine): Boolean = _owner.value == engine

    fun isDismissed(engine: Engine): Boolean = engine in dismissed

    /**
     * Tries to give the mini-player to [engine].
     *
     * Returns `false` when another engine currently owns the slot *and is actively
     * playing* (that engine must be stopped first, normally by the arbiter) or when
     * the new owner's state cannot be represented. On success, the previous owner's
     * state is retired and [engine] becomes the sole owner.
     */
    fun claim(engine: Engine): Boolean {
        if (engine == Engine.NONE) return false
        synchronized(lock) {
            val current = _owner.value
            if (current == engine) {
                dismissed.remove(engine)
                return true
            }
            if (current != Engine.NONE && isPlaying(current)) return false
            when (current) {
                Engine.AUDIOBOOK -> ActivePlaybackState.retire()
                Engine.PODCAST -> PodcastPlaybackState.retire()
                Engine.NONE -> Unit
            }
            _owner.value = engine
            dismissed.remove(engine)
            return true
        }
    }

    /** Releases the slot if [engine] currently holds it. */
    fun release(engine: Engine) {
        if (_owner.value == engine) _owner.value = Engine.NONE
    }

    /**
     * True when an engine other than [engine] owns the slot *and is playing*.
     *
     * The two playback services use this before preparing/starting anything: a
     * deferring load must not create a second prepared-but-paused timeline while
     * the other engine is the active audio output, because Media3 would publish a
     * second MediaStyle notification (with the deferred engine's artwork) next to
     * the playing one. A *paused* owner does not count — the other engine may load
     * and claim the slot on an explicit play press.
     */
    fun otherEngineIsPlaying(engine: Engine): Boolean {
        val owner = _owner.value
        if (owner == Engine.NONE || owner == engine) return false
        return isPlaying(owner)
    }

    /** User dismissed the bar for [engine]: hide it until that engine plays again. */
    fun dismiss(engine: Engine) {
        synchronized(lock) {
            dismissed.add(engine)
            when (engine) {
                Engine.AUDIOBOOK -> ActivePlaybackState.retire()
                Engine.PODCAST -> PodcastPlaybackState.retire()
                Engine.NONE -> Unit
            }
            if (_owner.value == engine) _owner.value = Engine.NONE
        }
    }

    /** Clears ownership and dismissal (used when the process/tests reset global state). */
    fun reset() {
        synchronized(lock) {
            ActivePlaybackState.retire()
            PodcastPlaybackState.retire()
            _owner.value = Engine.NONE
            dismissed.clear()
        }
    }

    private fun isPlaying(engine: Engine): Boolean = when (engine) {
        Engine.AUDIOBOOK -> ActivePlaybackState.state.value?.isPlaying == true
        Engine.PODCAST -> PodcastPlaybackState.state.value?.isPlaying == true
        Engine.NONE -> false
    }
}
