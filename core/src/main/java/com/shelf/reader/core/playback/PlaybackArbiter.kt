package com.shelf.reader.core.playback

import android.util.Log

/**
 * Process-wide arbiter that keeps Shelf's two independent audio engines from
 * ever playing at the same time.
 *
 * Audiobooks and podcasts each own a dedicated playback service with its own
 * MediaSession id. Neither service is aware of the other's internals; they only
 * register a "stop for other media" hook here. When one engine starts, it asks
 * the arbiter to stop every other registered engine.
 *
 * Registration uses a stable media-session id (e.g. "shelf_audio",
 * "shelf_podcast"). Hooks are expected to be idempotent, thread-safe, and to
 * stop their engine **synchronously when invoked on the main thread** so the
 * audio output is released before the new engine starts playing.
 */
object PlaybackArbiter {

    private const val TAG = "PlaybackArbiter"

    private val stoppers = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** Media session id for the audiobook engine. */
    const val ID_AUDIOBOOK = "shelf_audio"

    /** Media session id for the podcast engine. */
    const val ID_PODCAST = "shelf_podcast"

    fun register(id: String, stopForOtherMedia: () -> Unit) {
        stoppers[id] = stopForOtherMedia
    }

    fun unregister(id: String) {
        stoppers.remove(id)
    }

    /**
     * Stops every registered engine except [exceptId]. Safe to call from any thread.
     *
     * @return the ids whose hook was invoked, so callers can log/verify a hand-off.
     */
    fun stopOthers(exceptId: String): List<String> {
        val invoked = ArrayList<String>(stoppers.size)
        stoppers.forEach { (id, stop) ->
            if (id == exceptId) return@forEach
            invoked.add(id)
            runCatching { stop() }.onFailure { t ->
                // A throwing stopper must never prevent the other engine from starting,
                // but it must be visible instead of silently swallowed.
                Log.w(TAG, "stop hook for '$id' failed", t)
            }
        }
        return invoked
    }
}
