package com.bookrio.ftp.data

import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.DownloadTaskEntity
import com.bookrio.data.local.entity.TransferCounts
import com.bookrio.ftp.client.FtpEntry
import com.bookrio.ftp.client.FtpEntryType
import com.bookrio.ftp.domain.LocalStaging
import com.bookrio.ftp.domain.TransferStateMachine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class EnqueueReport(
    val added: Int,
    val alreadyQueued: Int,
    val skipped: Int
)

/**
 * The only authoritative persistence for transfer queue, bytes and status.
 *
 * The UI observes this; the worker mutates it. Navigation and ViewModel
 * lifecycle never touch a socket or a coroutine that owns a transfer.
 */
class FtpTransferRepository(
    private val taskDao: DownloadTaskDao,
    private val downloadRootForServer: (Long) -> File
) {

    val transfers: Flow<List<DownloadTaskEntity>> = taskDao.observeAll()
    val activeTransfers: Flow<List<DownloadTaskEntity>> = taskDao.observeActive()

    fun observeForServer(serverId: Long): Flow<List<DownloadTaskEntity>> =
        taskDao.observeForServer(serverId)

    suspend fun getTask(id: Long): DownloadTaskEntity? = taskDao.getById(id)

    suspend fun taskForRemote(serverId: Long, remotePath: String): DownloadTaskEntity? =
        withContext(Dispatchers.IO) { taskDao.getByRemote(serverId, remotePath) }

    suspend fun importing(serverId: Long): List<DownloadTaskEntity> =
        withContext(Dispatchers.IO) { taskDao.importingForServer(serverId) }

    fun downloadRoot(serverId: Long): File = downloadRootForServer(serverId)

    /**
     * Atomically creates queue rows for a folder selection. A remote path that is
     * already queued/running is never queued twice; terminal rows are reset only
     * when the local file is missing or incomplete.
     */
    suspend fun enqueue(
        serverId: Long,
        basePath: String,
        entries: List<FtpEntry>,
        autoImport: Boolean = true,
        priority: Int = 0
    ): EnqueueReport = withContext(Dispatchers.IO) {
        val root = downloadRootForServer(serverId)
        var added = 0
        var alreadyQueued = 0
        var skipped = 0

        entries.filter { it.type == FtpEntryType.FILE }.forEach { entry ->
            val finalFile = LocalStaging.resolveFinal(root, basePath, entry.path)
            val stagingFile = LocalStaging.stagingFor(finalFile)
            val existing = taskDao.getByRemote(serverId, entry.path)

            if (existing == null) {
                val row = DownloadTaskEntity(
                    serverId = serverId,
                    remotePath = entry.path,
                    remoteName = entry.name,
                    localPath = finalFile.absolutePath,
                    sizeBytes = entry.sizeBytes,
                    remoteMtime = entry.modifiedEpochSec,
                    stagingPath = stagingFile.absolutePath,
                    status = DownloadStatusEntity.QUEUED,
                    priority = priority,
                    autoImport = autoImport
                )
                val newId = taskDao.insertIgnore(row)
                if (newId == -1L) alreadyQueued++ else added++
            } else {
                taskDao.updateRemoteMeta(
                    id = existing.id,
                    size = entry.sizeBytes,
                    mtime = entry.modifiedEpochSec,
                    localPath = finalFile.absolutePath
                )
                when {
                    existing.status == DownloadStatusEntity.QUEUED ||
                        existing.status == DownloadStatusEntity.PENDING ||
                        existing.status == DownloadStatusEntity.RUNNING ||
                        existing.status == DownloadStatusEntity.RETRYING ||
                        existing.status == DownloadStatusEntity.WAITING_FOR_NETWORK ||
                        existing.status == DownloadStatusEntity.VERIFYING ||
                        existing.status == DownloadStatusEntity.IMPORTING -> {
                        // Already in flight: do not duplicate, do not disturb.
                        alreadyQueued++
                    }
                    existing.status == DownloadStatusEntity.PAUSED_BY_USER ||
                        existing.status == DownloadStatusEntity.PAUSED -> {
                        alreadyQueued++
                    }
                    existing.status == DownloadStatusEntity.COMPLETED -> {
                        val complete = finalFile.exists() &&
                            (entry.sizeBytes <= 0L || finalFile.length() == entry.sizeBytes)
                        if (complete) {
                            skipped++
                        } else {
                            taskDao.requeue(existing.id)
                            added++
                        }
                    }
                    else -> { // FAILED / CANCELLED
                        taskDao.requeue(existing.id)
                        added++
                    }
                }
            }
        }
        EnqueueReport(added = added, alreadyQueued = alreadyQueued, skipped = skipped)
    }

    /** Moves a task to the front of the queue ("Download next"). */
    suspend fun prioritize(id: Long) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        taskDao.update(task.copy(priority = (task.priority + 10).coerceAtMost(1000)))
        if (TransferStateMachine.normalize(task.status) == DownloadStatusEntity.FAILED) {
            taskDao.requeue(id)
        }
    }

    suspend fun nextRunnable(serverId: Long): DownloadTaskEntity? = withContext(Dispatchers.IO) {
        taskDao.nextRunnable(serverId)
    }

    /** Returns true when this caller won the claim race. */
    suspend fun claim(id: Long): Boolean = withContext(Dispatchers.IO) {
        taskDao.claim(id) == 1
    }

    suspend fun updateProgress(id: Long, bytes: Long, speed: Long) = withContext(Dispatchers.IO) {
        taskDao.updateProgress(id, bytes.coerceAtLeast(0L), speed.coerceAtLeast(0L))
    }

    suspend fun setStagingPath(id: Long, staging: String?) = withContext(Dispatchers.IO) {
        taskDao.setStagingPath(id, staging)
    }

    /** Validated transition; illegal transitions are rejected, not written. */
    suspend fun transition(
        id: Long,
        to: DownloadStatusEntity,
        error: String? = null,
        errorKind: String? = null
    ) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        TransferStateMachine.requireTransition(task.status, to)
        taskDao.setStatus(id, to, error?.take(300), errorKind)
    }

    suspend fun markRetrying(
        id: Long,
        error: String?,
        errorKind: String?,
        nextAttemptAt: Long?
    ) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        TransferStateMachine.requireTransition(task.status, DownloadStatusEntity.RETRYING)
        taskDao.markRetrying(id, error?.take(300), errorKind, nextAttemptAt)
    }

    suspend fun markCompleted(id: Long, importedBookId: Long?) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        TransferStateMachine.requireTransition(task.status, DownloadStatusEntity.COMPLETED)
        taskDao.markCompleted(id, importedBookId)
    }

    suspend fun fail(id: Long, error: String?, errorKind: String?) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        if (TransferStateMachine.canTransition(task.status, DownloadStatusEntity.FAILED)) {
            taskDao.setStatus(id, DownloadStatusEntity.FAILED, error?.take(300), errorKind)
        } else {
            taskDao.setStatus(id, DownloadStatusEntity.CANCELLED, error?.take(300), errorKind)
        }
    }

    suspend fun cancelTask(taskId: Long) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        if (TransferStateMachine.canTransition(task.status, DownloadStatusEntity.CANCELLED)) {
            taskDao.setStatus(taskId, DownloadStatusEntity.CANCELLED, "Cancelled", "cancelled")
        }
    }

    suspend fun retry(taskId: Long) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        if (TransferStateMachine.normalize(task.status) == DownloadStatusEntity.FAILED ||
            TransferStateMachine.normalize(task.status) == DownloadStatusEntity.CANCELLED
        ) {
            taskDao.requeue(taskId)
        }
    }

    suspend fun pause(serverId: Long) = withContext(Dispatchers.IO) {
        taskDao.setPausedForServer(serverId, true)
    }

    suspend fun resume(serverId: Long) = withContext(Dispatchers.IO) {
        taskDao.setPausedForServer(serverId, false)
    }

    suspend fun cancel(serverId: Long) = withContext(Dispatchers.IO) {
        taskDao.cancelForServer(serverId)
    }

    suspend fun rehydrateActive(serverId: Long) = withContext(Dispatchers.IO) {
        taskDao.rehydrateActiveForServer(serverId)
    }

    suspend fun rehydrateAllActive() = withContext(Dispatchers.IO) {
        taskDao.rehydrateAllActive()
    }

    suspend fun queuedCount(serverId: Long): Int = withContext(Dispatchers.IO) {
        taskDao.queuedCountForServer(serverId)
    }

    suspend fun failedCount(serverId: Long): Int = withContext(Dispatchers.IO) {
        taskDao.failedCountForServer(serverId)
    }

    suspend fun counts(serverId: Long): TransferCounts = withContext(Dispatchers.IO) {
        taskDao.countsForServer(serverId)
    }

    suspend fun activeServerIds(): List<Long> = withContext(Dispatchers.IO) {
        taskDao.activeServerIds()
    }

    suspend fun runnableServerIds(): List<Long> = withContext(Dispatchers.IO) {
        taskDao.runnableServerIds()
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        taskDao.clearCompleted()
    }
}