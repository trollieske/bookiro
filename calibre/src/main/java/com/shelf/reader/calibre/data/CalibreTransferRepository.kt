package com.shelf.reader.calibre.data

import com.shelf.reader.calibre.client.CalibreDownload
import com.shelf.reader.data.local.dao.DownloadTaskDao
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/** Read model for one queued Calibre download. */
data class CalibreTransfer(
    val id: Long,
    val sourceId: Long,
    val remotePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatusEntity,
    val errorKind: String?,
    val errorMessage: String?,
    val localPath: String?,
    val stagingPath: String?
) {
    val progress: Float
        get() = if (sizeBytes > 0) (downloadedBytes.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
}

/**
 * The Calibre transfer queue, stored in the shared `download_tasks` table under
 * `(source_kind = CALIBRE, source_ref = CALIBRE:<id>)`.
 *
 * The UI only ever observes this; the worker owns downloading.
 */
class CalibreTransferRepository(
    private val taskDao: DownloadTaskDao,
    private val downloadRoot: File
) {

    fun observeForSource(sourceId: Long): Flow<List<CalibreTransfer>> =
        taskDao.observeForSource(CalibreSourceRepository.KIND, CalibreSourceRepository.refFor(sourceId))
            .map { list -> list.map { it.toTransfer(sourceId) } }

    suspend fun enqueue(sourceId: Long, downloads: List<CalibreDownload>): Int =
        withContext(Dispatchers.IO) {
            val ref = CalibreSourceRepository.refFor(sourceId)
            var added = 0
            for (download in downloads) {
                val existing = taskDao.getBySourceRemote(CalibreSourceRepository.KIND, ref, download.href)
                if (existing == null) {
                    val local = File(downloadRoot, "${sourceId}/${sanitize(download.fileName)}")
                    taskDao.insertIgnore(
                        DownloadTaskEntity(
                            remotePath = download.href,
                            remoteName = download.fileName,
                            localPath = local.absolutePath,
                            sizeBytes = download.sizeBytes ?: 0L,
                            status = DownloadStatusEntity.QUEUED,
                            autoImport = true,
                            sourceKind = CalibreSourceRepository.KIND,
                            sourceRef = ref
                        )
                    )
                    added++
                } else if (existing.status == DownloadStatusEntity.FAILED ||
                    existing.status == DownloadStatusEntity.CANCELLED
                ) {
                    taskDao.requeue(existing.id)
                }
            }
            added
        }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        taskDao.cancel(id)
    }

    suspend fun retry(id: Long) = withContext(Dispatchers.IO) {
        taskDao.requeue(id)
    }

    suspend fun counts(sourceId: Long): Int = withContext(Dispatchers.IO) {
        taskDao.runnableCountForSource(CalibreSourceRepository.KIND, CalibreSourceRepository.refFor(sourceId))
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\\\u0000\\r\\n]"), "_").trim().ifBlank { "download.bin" }
}

fun DownloadTaskEntity.toTransfer(sourceId: Long): CalibreTransfer = CalibreTransfer(
    id = id,
    sourceId = sourceId,
    remotePath = remotePath,
    fileName = remoteName,
    sizeBytes = sizeBytes,
    downloadedBytes = downloadedBytes,
    status = status,
    errorKind = errorKind,
    errorMessage = errorMessage,
    localPath = localPath,
    stagingPath = stagingPath
)