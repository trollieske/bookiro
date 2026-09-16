package com.shelf.reader.ftp.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * In-memory only view of what is transferring right now, so the notification and
 * progress UI can be live without writing every chunk to Room.
 */
class FtpTransferRuntime {

    private val map = ConcurrentHashMap<Long, ActiveTransfer>()
    private val _active = MutableStateFlow<List<ActiveTransfer>>(emptyList())
    val active: StateFlow<List<ActiveTransfer>> = _active.asStateFlow()

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
        publish()
    }

    fun snapshot(): List<ActiveTransfer> = map.values.toList()

    private fun publish() {
        _active.value = map.values.sortedBy { it.name }
    }
}