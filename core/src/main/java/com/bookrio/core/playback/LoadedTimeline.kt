package com.bookrio.core.playback

import java.util.concurrent.atomic.AtomicLong

/**
 * Identity of the timeline the player actually holds, and the rule that keeps
 * progress writes keyed to it.
 *
 * The regression this prevents: a load sets the *requested* book/episode id at
 * request time while the player still holds the previous timeline; a progress tick
 * in that window would read the old position/duration and persist it under the new
 * id (clamped to 100% = the new book/episode falsely finished).
 *
 * ([id], [token]) are written together, only by [committed], on the player's main
 * thread, right after `setMediaItems` / `setMediaItem`. Persistence and snapshots
 * must read them, never the requested id of an in-flight load.
 */
class LoadedTimeline {

    /** Id committed to the player plus the load generation that committed it. */
    data class Snapshot(val id: Long, val token: Long)

    private val loadedId = AtomicLong(-1L)
    private val loadedToken = AtomicLong(0L)

    /** Id of the timeline currently committed to the player (-1 when none). */
    val id: Long get() = loadedId.get()

    fun snapshot(): Snapshot = Snapshot(loadedId.get(), loadedToken.get())

    /** The player no longer holds one of our timelines (e.g. an external item). */
    fun cleared() {
        loadedId.set(-1L)
        loadedToken.set(0L)
    }

    /** Called on the main thread right after the player accepted a timeline. */
    fun committed(id: Long, token: Long) {
        loadedId.set(id)
        loadedToken.set(token)
    }

    /**
     * Target id for a progress snapshot captured while [captured] described the
     * loaded timeline, or null when the write must be dropped.
     *
     * A write stays valid while the player has not committed a *different* timeline
     * (different id) and the timeline was not re-loaded by a newer generation
     * (same id, newer token — rapid A -> B -> A). Retiring keeps both values, so the
     * final hand-off persist is preserved; a rapid switch drops the late write
     * instead of stamping it onto the new item.
     */
    fun progressTarget(captured: Snapshot): Long? =
        captured.id.takeIf {
            it > 0L &&
                it == loadedId.get() &&
                captured.token == loadedToken.get()
        }

    /** Convenience for callers that only have the raw values. */
    fun progressTarget(capturedId: Long, capturedToken: Long): Long? =
        progressTarget(Snapshot(capturedId, capturedToken))
}
