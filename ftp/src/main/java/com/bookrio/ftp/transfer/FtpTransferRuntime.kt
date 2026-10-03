package com.bookrio.ftp.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ActiveTransfer(
    val taskId: Long,
    val serverId: Long,
    val name: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSec: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * A source is being prepared: the remote folder is being listed before any
 * download starts. Shown so "Sync now" never looks like it did nothing while a
 * large library is scanned.
 */
data class PreparingTransfer(
    val scannedFiles: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdateAt: Long = System.currentTimeMillis()
)

/**
 * In-memory only view of what is transferring right now, so the notification and
 * progress UI can be live without writing every chunk to Room.
 */
class FtpTransferRuntime {

    private val map = ConcurrentHashMap<Long, ActiveTransfer>()
    private val _active = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val active: StateFlow<List<ActiveTransfer>> = _active.asStateFlow()

    private val _preparing = MutableStateFlow<Map<Long, PreparingTransfer>>(emptyMap())
    val preparing: StateFlow<Map<Long, PreparingTransfer>> = _preparing.asStateFlow()

    /** Auto-clears a stale "preparing" state if the worker never started or died. */
    private val prepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun update(transfer: ActiveTransfer) {
        map[transfer.taskId] = transfer
        publish()
    }

    fun remove(taskId: Long) {
        map.remove(taskId)
        publish()
    }

    fun clearServer(serverId: Long) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.serverId == serverId) iterator.remove()
        }
        endPreparing(serverId)
        publish()
    }

    fun snapshot(): List<ActiveTransfer> = map.values.toList()

    fun beginPreparing(serverId: Long) {
        val now = System.currentTimeMillis()
        _preparing.update { it + (serverId to PreparingTransfer(startedAt = now, lastUpdateAt = now)) }
        prepScope.launch {
            delay(PREPARING_TIMEOUT_MS)
            _preparing.update { current ->
                val value = current[serverId] ?: return@update current
                if (System.currentTimeMillis() - value.lastUpdateAt >= PREPARING_TIMEOUT_MS) {
                    current - serverId
                } else {
                    current
                }
            }
        }
    }

    fun progressPreparing(serverId: Long, scannedFiles: Int) {
        val now = System.currentTimeMillis()
        _preparing.update { current ->
            val value = current[serverId] ?: return@update current
            current + (serverId to value.copy(scannedFiles = scannedFiles, lastUpdateAt = now))
        }
    }

    fun endPreparing(serverId: Long) {
        _preparing.update { it - serverId }
    }

    private fun publish() {
        _active.value = map.values.sortedBy { it.name }
    }

    private companion object {
        const val PREPARING_TIMEOUT_MS = 180_000L
    }
}