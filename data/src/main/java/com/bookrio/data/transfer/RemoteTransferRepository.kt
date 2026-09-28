package com.bookrio.data.transfer

import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.DownloadTaskEntity
import com.bookrio.data.local.entity.RemoteTaskSource
import com.bookrio.data.local.entity.TransferCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class RemoteEnqueueReport(val added: Int, val alreadyQueued: Int, val skipped: Int)

/** Per-source aggregate used by the source cards. */
data class SourceCounts(
    val sourceRef: String,
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
    val retrying: Int = 0
)

/**
 * The single authoritative transfer queue for a remote source kind.
 *
 * One instance is created per protocol (`SMB`, `WEBDAV`, `CALIBRE`) and keys its
 * rows on the shared `download_tasks` table through `(source_kind, source_ref)`.
 * The UI observes this; a Worker mutates it. Navigation and ViewModel lifecycle
 * never touch a socket or a coroutine that owns a transfer.
 */
class RemoteTransferRepository(
    private val taskDao: DownloadTaskDao,
    val kind: String,
    private val downloadRootForSource: (Long) -> File
) {

    fun observeForSource(sourceId: Long): Flow<List<DownloadTaskEntity>> =
        taskDao.observeForSource(kind, ref(sourceId))

    fun observeCounts(): Flow<List<SourceCounts>> = taskDao.observeCountsForKind(kind)

    fun downloadRoot(sourceId: Long): File = downloadRootForSource(sourceId)

    suspend fun enqueue(
        sourceId: Long,
        basePath: String,
        entries: List<RemoteFileRef>,
        autoImport: Boolean = true,
        priority: Int = 0
    ): RemoteEnqueueReport = withContext(Dispatchers.IO) {
        val sourceRef = ref(sourceId)
        val root = downloadRootForSource(sourceId)
        var added = 0
        var alreadyQueued = 0
        var skipped = 0

        entries.filter { RemoteMediaFormats.isBook(it.name) }.forEach { entry ->
            val finalFile = RemoteStaging.resolveFinal(root, basePath, entry.path)
            val stagingFile = RemoteStaging.stagingFor(finalFile)
            val existing = taskDao.getBySourceRemote(kind, sourceRef, entry.path)

            if (existing == null) {
                val row = DownloadTaskEntity(
                    remotePath = entry.path,
                    remoteName = entry.name,
                    localPath = finalFile.absolutePath,
                    sizeBytes = entry.sizeBytes,
                    remoteMtime = entry.modifiedEpochSec,
                    stagingPath = stagingFile.absolutePath,
                    status = DownloadStatusEntity.QUEUED,
                    priority = priority,
                    autoImport = autoImport,
                    sourceKind = kind,
                    sourceRef = sourceRef
                )
                val newId = taskDao.insertIgnore(row)
                if (newId == -1L) alreadyQueued++ else added++
            } else {
                taskDao.updateRemoteMeta(existing.id, entry.sizeBytes, entry.modifiedEpochSec, finalFile.absolutePath)
                when (TransferStatusMachine.normalize(existing.status)) {
                    DownloadStatusEntity.QUEUED,
                    DownloadStatusEntity.RUNNING,
                    DownloadStatusEntity.RETRYING,
                    DownloadStatusEntity.WAITING_FOR_NETWORK,
                    DownloadStatusEntity.VERIFYING,
                    DownloadStatusEntity.IMPORTING,
                    DownloadStatusEntity.PAUSED_BY_USER -> alreadyQueued++

                    DownloadStatusEntity.COMPLETED -> {
                        val complete = finalFile.exists() &&
                            (entry.sizeBytes <= 0L || finalFile.length() == entry.sizeBytes)
                        if (complete) skipped++ else { taskDao.requeue(existing.id); added++ }
                    }
                    else -> { // FAILED / CANCELLED
                        taskDao.requeue(existing.id)
                        added++
                    }
                }
            }
        }
        RemoteEnqueueReport(added, alreadyQueued, skipped)
    }

    suspend fun nextRunnable(sourceId: Long): DownloadTaskEntity? = withContext(Dispatchers.IO) {
        taskDao.nextRunnableForSource(kind, ref(sourceId))
    }

    suspend fun claim(id: Long): Boolean = withContext(Dispatchers.IO) { taskDao.claim(id) == 1 }

    suspend fun getTask(id: Long): DownloadTaskEntity? = taskDao.getById(id)

    suspend fun updateProgress(id: Long, bytes: Long, speed: Long) = withContext(Dispatchers.IO) {
        taskDao.updateProgress(id, bytes.coerceAtLeast(0L), speed.coerceAtLeast(0L))
    }

    suspend fun setStagingPath(id: Long, staging: String?) = withContext(Dispatchers.IO) {
        taskDao.setStagingPath(id, staging)
    }

    suspend fun transition(
        id: Long,
        to: DownloadStatusEntity,
        error: String? = null,
        errorKind: String? = null
    ) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        TransferStatusMachine.requireTransition(task.status, to)
        taskDao.setStatus(id, to, error?.take(300), errorKind)
    }

    suspend fun markRetrying(id: Long, error: String?, errorKind: String?, nextAttemptAt: Long?) =
        withContext(Dispatchers.IO) {
            val task = taskDao.getById(id) ?: return@withContext
            TransferStatusMachine.requireTransition(task.status, DownloadStatusEntity.RETRYING)
            taskDao.markRetrying(id, error?.take(300), errorKind, nextAttemptAt)
        }

    suspend fun markCompleted(id: Long, importedBookId: Long?) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        TransferStatusMachine.requireTransition(task.status, DownloadStatusEntity.COMPLETED)
        taskDao.markCompleted(id, importedBookId)
    }

    suspend fun fail(id: Long, error: String?, errorKind: String?) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(id) ?: return@withContext
        if (TransferStatusMachine.canTransition(task.status, DownloadStatusEntity.FAILED)) {
            taskDao.setStatus(id, DownloadStatusEntity.FAILED, error?.take(300), errorKind)
        } else {
            taskDao.setStatus(id, DownloadStatusEntity.CANCELLED, error?.take(300), errorKind)
        }
    }

    suspend fun cancelTask(taskId: Long) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        if (TransferStatusMachine.canTransition(task.status, DownloadStatusEntity.CANCELLED)) {
            taskDao.setStatus(taskId, DownloadStatusEntity.CANCELLED, "Cancelled", "cancelled")
        }
    }

    suspend fun retry(taskId: Long) = withContext(Dispatchers.IO) {
        val task = taskDao.getById(taskId) ?: return@withContext
        val normalized = TransferStatusMachine.normalize(task.status)
        if (normalized == DownloadStatusEntity.FAILED || normalized == DownloadStatusEntity.CANCELLED) {
            taskDao.requeue(taskId)
        }
    }

    suspend fun pause(sourceId: Long) = withContext(Dispatchers.IO) {
        taskDao.setPausedForSource(kind, ref(sourceId), true)
    }

    suspend fun resume(sourceId: Long) = withContext(Dispatchers.IO) {
        taskDao.setPausedForSource(kind, ref(sourceId), false)
    }

    suspend fun cancel(sourceId: Long) = withContext(Dispatchers.IO) {
        taskDao.cancelForSource(kind, ref(sourceId))
    }

    suspend fun rehydrateActive(sourceId: Long) = withContext(Dispatchers.IO) {
        taskDao.rehydrateActiveForSource(kind, ref(sourceId))
    }

    suspend fun counts(sourceId: Long): TransferCounts = withContext(Dispatchers.IO) {
        taskDao.countsForSource(kind, ref(sourceId))
    }

    suspend fun runnableCount(sourceId: Long): Int = withContext(Dispatchers.IO) {
        taskDao.runnableCountForSource(kind, ref(sourceId))
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) { taskDao.clearCompleted() }

    fun ref(sourceId: Long): String = RemoteTaskSource.ref(kind, sourceId)
}