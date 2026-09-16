package com.shelf.reader.calibre.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shelf.reader.calibre.R
import com.shelf.reader.calibre.client.CalibreClient
import com.shelf.reader.calibre.client.CalibreErrorKind
import com.shelf.reader.calibre.client.CalibreException
import com.shelf.reader.calibre.data.CalibreSourceRepository
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.crypto.KeystoreSecretCipher
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.CalibreSourceStateEntity
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.library.data.BookImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Durable, foregound download worker for a single Calibre Content Server.
 *
 * The UI never owns this work; it only observes `download_tasks`. Credentials
 * are never passed as WorkManager input — only `sourceId`.
 */
class CalibreSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "calibre_sync_channel"
        const val NOTIF_ID = 3004
        const val KEY_SOURCE_ID = "calibreSourceId"
        const val UNIQUE_PREFIX = "calibre-sync-"

        fun uniqueName(sourceId: Long) = "$UNIQUE_PREFIX$sourceId"

        fun enqueue(context: Context, sourceId: Long) {
            val request = androidx.work.OneTimeWorkRequestBuilder<CalibreSyncWorker>()
                .setInputData(androidx.work.workDataOf(KEY_SOURCE_ID to sourceId))
                .addTag("calibre_sync")
                .build()
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    uniqueName(sourceId),
                    androidx.work.ExistingWorkPolicy.KEEP,
                    request
                )
        }

        fun cancel(context: Context, sourceId: Long) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(uniqueName(sourceId))
        }

        private const val RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RUN_ATTEMPTS = 5
    }

    override suspend fun doWork(): Result {
        ensureChannel()
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId <= 0L) return Result.success()
        runCatching { setForeground(getForegroundInfo("Preparing…")) }

        val db = ShelfDatabase.getInstance(appContext)
        val cipher = KeystoreSecretCipher(KeystoreSecretCipher.CALIBRE_ALIAS)
        val sourceRepo = CalibreSourceRepository(db.calibreServerDao(), db.downloadTaskDao(), cipher)
        val importRepo = BookImportRepository(appContext, db, DefaultDispatcherProvider)

        val credentials = sourceRepo.credentialsFor(sourceId)
        if (credentials == null) {
            sourceRepo.markState(sourceId, CalibreSourceStateEntity.NEEDS_AUTH, "Sign-in could not be read")
            return Result.success()
        }
        val client = CalibreClient(credentials.baseUrl, credentials.username, credentials.password)
        val ref = CalibreSourceRepository.refFor(sourceId)

        var transientFailure = false
        val imported = mutableListOf<Long>()

        try {
            sourceRepo.markConnected(sourceId)
        } catch (_: Throwable) {
            // Non-fatal: listing below will surface the real error.
        }

        while (true) {
            val task = db.downloadTaskDao().nextRunnableForSource(CalibreSourceRepository.KIND, ref)
                ?: break
            if (db.downloadTaskDao().claim(task.id) == 0) continue

            runCatching { setForeground(getForegroundInfo(task.remoteName)) }

            try {
                val outcome = downloadOne(client, db, task)
                if (outcome != null) {
                    val uri = Uri.fromFile(File(outcome))
                    val bookIds = importRepo.importUris(
                        uris = listOf(uri),
                        source = ImportSourceEntity.CALIBRE_LIBRARY,
                        serverId = null,
                        remotePath = task.remotePath,
                        filePathOverride = outcome,
                        consolidate = false
                    )
                    imported.addAll(bookIds)
                    db.downloadTaskDao().markCompleted(task.id, bookIds.firstOrNull())
                }
            } catch (e: CalibreException) {
                transientFailure = transientFailure || handleFailure(db, sourceRepo, sourceId, task.id, e.kind, e.message)
            } catch (e: Throwable) {
                transientFailure = transientFailure ||
                    handleFailure(db, sourceRepo, sourceId, task.id, CalibreClient.classifyThrowable(e), null)
            }
        }

        if (imported.isNotEmpty()) {
            runCatching { importRepo.consolidateFragmentedAudiobooks() }
            sourceRepo.markLastSync(sourceId)
        }

        return if (transientFailure && runAttemptCount < MAX_RUN_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    /** Downloads, verifies and atomically moves one task. Returns the final path. */
    private suspend fun downloadOne(
        client: CalibreClient,
        db: ShelfDatabase,
        task: com.shelf.reader.data.local.entity.DownloadTaskEntity
    ): String? = withContext(Dispatchers.IO) {
        val taskDao = db.downloadTaskDao()
        val finalFile = task.localPath?.let { File(it) }
            ?: File(appContext.filesDir, "calibre/${task.id}/${sanitize(task.remoteName)}")
        val staging = task.stagingPath?.let { File(it) } ?: File(finalFile.parentFile, finalFile.name + ".part")

        taskDao.setStagingPath(task.id, staging.absolutePath)

        if (finalFile.exists() && task.sizeBytes > 0 && finalFile.length() == task.sizeBytes) {
            return@withContext finalFile.absolutePath
        }

        val offset = if (staging.exists() && staging.length() > 0 &&
            (task.sizeBytes <= 0 || staging.length() <= task.sizeBytes)
        ) {
            staging.length()
        } else {
            if (staging.exists()) staging.delete()
            0L
        }

        val result = client.download(task.remotePath, staging, offset) { downloaded, _ ->
            // Progress is persisted opportunistically; the worker interval is short.
            taskDao.updateProgress(task.id, downloaded, 0L)
        }

        taskDao.updateProgress(task.id, result.bytesOnDisk, 0L)

        val expected = task.sizeBytes
        if (expected > 0 && result.bytesOnDisk != expected) {
            taskDao.markRetrying(
                task.id,
                "Downloaded ${result.bytesOnDisk} of $expected bytes",
                CalibreErrorKind.NETWORK.name,
                System.currentTimeMillis() + RETRY_BACKOFF_MS
            )
            return@withContext null
        }

        taskDao.setStatus(task.id, DownloadStatusEntity.VERIFYING)
        if (!moveIntoPlace(staging, finalFile)) {
            taskDao.setStatus(task.id, DownloadStatusEntity.FAILED, "Could not finalize file", CalibreErrorKind.UNKNOWN.name)
            return@withContext null
        }
        taskDao.setStatus(task.id, DownloadStatusEntity.IMPORTING)
        finalFile.absolutePath
    }

    /** Returns true when the failure is transient and should be retried. */
    private suspend fun handleFailure(
        db: ShelfDatabase,
        sourceRepo: CalibreSourceRepository,
        sourceId: Long,
        taskId: Long,
        kind: CalibreErrorKind,
        message: String?
    ): Boolean {
        val taskDao = db.downloadTaskDao()
        val transient = when (kind) {
            CalibreErrorKind.NETWORK,
            CalibreErrorKind.TIMEOUT,
            CalibreErrorKind.SERVER,
            CalibreErrorKind.RATE_LIMITED -> true
            else -> false
        }
        if (kind == CalibreErrorKind.AUTH) {
            sourceRepo.markState(sourceId, CalibreSourceStateEntity.NEEDS_AUTH, message ?: "Authentication failed")
        }
        if (transient) {
            taskDao.markRetrying(
                taskId,
                message,
                kind.name,
                System.currentTimeMillis() + RETRY_BACKOFF_MS
            )
        } else {
            taskDao.setStatus(taskId, DownloadStatusEntity.FAILED, message, kind.name)
        }
        return transient
    }

    private fun moveIntoPlace(staging: File, finalFile: File): Boolean {
        finalFile.parentFile?.mkdirs()
        if (!staging.exists()) return false
        if (finalFile.exists() && !finalFile.delete()) return false
        if (staging.renameTo(finalFile)) return true
        return runCatching {
            staging.copyTo(finalFile, overwrite = true)
            staging.delete()
        }.isSuccess
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\\\u0000\\r\\n]"), "_").trim().ifBlank { "download.bin" }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(appContext.getString(R.string.calibu_notif_title))
                        .setDescription(appContext.getString(R.string.calibu_notif_channel_desc))
                        .build()
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = getForegroundInfo("")

    private fun getForegroundInfo(detail: String): ForegroundInfo {
        val text = if (detail.isBlank()) {
            appContext.getString(R.string.calibu_notif_text)
        } else {
            appContext.getString(R.string.calibu_notif_detail, detail)
        }
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.calibu_notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}