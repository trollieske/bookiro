package com.shelf.reader.ftp.domain

/**
 * Rate limiter for Room progress writes.
 *
 * Room/SQLite and Compose recomposition must never become the throughput
 * bottleneck; a live in-memory value drives the UI in between.
 */
class ProgressThrottler(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS,
    private val minBytes: Long = DEFAULT_MIN_BYTES
) {
    private var initialized = false
    private var lastPersistedAt = 0L
    private var lastPersistedBytes = 0L

    fun shouldPersist(bytes: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (!initialized) {
            initialized = true
            markPersisted(bytes, now)
            return true
        }
        val enoughTime = now - lastPersistedAt >= minIntervalMs
        val enoughBytes = bytes - lastPersistedBytes >= minBytes
        if (enoughTime || enoughBytes) {
            markPersisted(bytes, now)
            return true
        }
        return false
    }

    /** Always persist the final value on pause/error/cancel/completion. */
    fun markPersisted(bytes: Long, now: Long = System.currentTimeMillis()) {
        lastPersistedAt = now
        lastPersistedBytes = bytes
    }

    fun reset() {
        initialized = false
        lastPersistedAt = 0L
        lastPersistedBytes = 0L
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
        const val DEFAULT_MIN_BYTES = 1024L * 1024L
    }
}