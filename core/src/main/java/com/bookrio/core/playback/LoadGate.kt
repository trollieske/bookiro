package com.bookrio.core.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic gate for the two playback engines' load/hand-off arbitration.
 *
 * Every load request takes a token ([begin]). A token may only commit
 * (`setMediaItems` / `setMediaItem`) while it is still current *and* either the user
 * explicitly forced the load or the other engine does not own playback. Retiring
 * playback ([invalidate]) makes every in-flight token stale, so a slow load can
 * never resurrect a second timeline/notification after the other engine won.
 *
 * This class is free of Android and coroutine dependencies so the arbitration can
 * be unit-tested on the JVM; both playback services use it for real.
 */
class LoadGate {

    private val generation = AtomicLong()

    /** Starts a new load and returns the token that load must commit with. */
    fun begin(): Long = generation.incrementAndGet()

    /** Invalidates every in-flight token (retire / hand-off / mini-player close). */
    fun invalidate(): Long = generation.incrementAndGet()

    /** Current generation, for logging/tests. */
    fun current(): Long = generation.get()

    /**
     * Whether the load that captured [token] may commit to the player.
     *
     * @param force explicit user gesture (play / resume / episode tap): takes
     *   ownership back even while the other engine is playing. A stale forced token
     *   still never applies.
     * @param otherEngineIsPlaying true while the other engine owns the audio output;
     *   a passive load must defer instead of preparing a second paused timeline.
     */
    fun shouldApply(token: Long, force: Boolean, otherEngineIsPlaying: Boolean): Boolean =
        token == generation.get() && (force || !otherEngineIsPlaying)

    /** Entry check: a passive load must not even start while the other engine plays. */
    fun shouldDefer(force: Boolean, otherEngineIsPlaying: Boolean): Boolean =
        !force && otherEngineIsPlaying
}
