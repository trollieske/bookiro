package com.bookrio.app.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative pause for the currently running backup/restore.
 *
 * The archive writer pauses at **entry boundaries** (between files), so a paused
 * operation can always resume exactly where it stopped while the process lives.
 * Cancellation itself is handled by WorkManager (`cancelUniqueWork`); this object
 * only carries the user's pause/resume gesture from the UI/notification to the
 * worker. It is intentionally process-local.
 */
object BackupControl {

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val active = AtomicBoolean(false)

    /** Claims the single archive slot; false when another export/import is running. */
    fun tryBegin(): Boolean = active.compareAndSet(false, true)

    fun end() {
        active.set(false)
        _paused.value = false
    }

    fun reset() {
        _paused.value = false
    }

    fun pause() {
        _paused.value = true
    }

    fun resume() {
        _paused.value = false
    }

    fun toggle() {
        _paused.value = !_paused.value
    }

    /**
     * Suspends while the operation is paused and throws if the coroutine was
     * cancelled, so a paused worker does not keep reading a large file.
     */
    suspend fun checkpoint() {
        _paused.first { !it }
        currentCoroutineContext().ensureActive()
    }
}
