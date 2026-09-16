package com.shelf.reader.webdav.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.data.local.entity.RemoteSourceStateEntity
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.webdav.R
import com.shelf.reader.webdav.client.WebdavClientEngine
import com.shelf.reader.webdav.client.WebdavErrorKind
import com.shelf.reader.webdav.data.WebdavGraph
import com.shelf.reader.webdav.data.WebdavSource
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single owner of a stored WebDAV source's transfer work.
 *
 * Unique work per source (`webdav-sync-<id>`). A source-less invocation is the
 * periodic reconciler that enqueues per-source work only.
 */
class WebdavSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = WebdavGraph.get(applicationContext)
        runCatching { graph.migrateLegacyIfNeeded() }

        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId <= 0L) return reconcile(graph)

        val source = graph.sourceRepository.getSource(sourceId) ?: return Result.success()
        ensureChannel()
        updateForeground(source, null)

        val credentials = graph.sourceRepository.credentialsFor(sourceId)
        if (credentials == null) {
            graph.sourceRepository.markState(sourceId, RemoteSourceStateEntity.NEEDS_AUTH, "Sign-in could not be read")
            return Result.success()
        }

        graph.transferRepository.rehydrateActive(sourceId)

        val engine = WebdavClientEngine()
        val connect = engine.connectResult(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password.ifBlank { null },
            bearerToken = credentials.bearerToken.ifBlank { null },
            authType = credentials.authType,
            trustAllCertificates = credentials.trustAllCertificates,
            userAgent = com.shelf.reader.webdav.data.WebdavSourceRepository.USER_AGENT
        )
        if (!connect.success) {
            val state = when (connect.kind) {
                WebdavErrorKind.AUTH, WebdavErrorKind.FORBIDDEN -> RemoteSourceStateEntity.NEEDS_AUTH
                else -> RemoteSourceStateEntity.CONNECTION_ERROR
            }
            graph.sourceRepository.markState(sourceId, state, connect.kind?.name)
            return if (connect.kind == WebdavErrorKind.AUTH) Result.success() else Result.retry()
        }
        graph.sourceRepository.markConnected(sourceId)

        val db = ShelfDatabase.getInstance(applicationContext)
        val importRepo = BookImportRepository(applicationContext, db, DefaultDispatcherProvider)
        var transientFailure = false
        val imported = mutableListOf<Long>()

        try {
            while (true) {
                val task = graph.transferRepository.nextRunnable(sourceId) ?: break
                if (!graph.transferRepository.claim(task.id)) continue
                updateForeground(source, task.remoteName)

                val finalFile = task.localPath?.let { File(it) }
                    ?: File(graph.downloadRoot(sourceId), sanitize(task.remoteName))
                try {
                    var lastPersist = 0L
                    val bytes = engine.downloadFile(task.remotePath, finalFile) { downloaded, _ ->
                        val now = System.currentTimeMillis()
                        if (now - lastPersist >= PROGRESS_THROTTLE_MS) {
                            lastPersist = now
                            runCatching { graph.transferRepository.updateProgress(task.id, downloaded, 0L) }
                        }
                    }
                    if (bytes <= 0) {
                        graph.transferRepository.markRetrying(
                            task.id, "Download failed", WebdavErrorKind.NETWORK.name,
                            System.currentTimeMillis() + RETRY_BACKOFF_MS
                        )
                        transientFailure = true
                        continue
                    }
                    val expected = task.sizeBytes
                    if (expected > 0 && bytes != expected) {
                        graph.transferRepository.markRetrying(
                            task.id, "Downloaded $bytes of $expected bytes", WebdavErrorKind.NETWORK.name,
                            System.currentTimeMillis() + RETRY_BACKOFF_MS
                        )
                        transientFailure = true
                        continue
                    }
                    graph.transferRepository.transition(task.id, DownloadStatusEntity.VERIFYING)
                    graph.transferRepository.transition(task.id, DownloadStatusEntity.IMPORTING)
                    val bookIds = importRepo.importUris(
                        uris = listOf(Uri.fromFile(finalFile)),
                        source = ImportSourceEntity.WEBDAV_DOWNLOAD,
                        serverId = null,
                        remotePath = task.remotePath,
                        filePathOverride = finalFile.absolutePath,
                        consolidate = false
                    )
                    imported.addAll(bookIds)
                    graph.transferRepository.markCompleted(task.id, bookIds.firstOrNull())
                } catch (e: Throwable) {
                    graph.transferRepository.fail(task.id, e.message, WebdavErrorKind.UNKNOWN.name)
                }
            }
        } finally {
            runCatching { engine.disconnect() }
        }

        if (imported.isNotEmpty()) {
            runCatching { importRepo.consolidateFragmentedAudiobooks() }
            graph.sourceRepository.markLastSync(sourceId)
        }

        return if (transientFailure && runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry()
        else Result.success()
    }

    private suspend fun reconcile(graph: WebdavGraph): Result {
        graph.sourceRepository.syncEnabledSources().forEach { enqueue(applicationContext, it) }
        return Result.success()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\\\u0000\\r\\n]"), "_").trim().ifBlank { "download.bin" }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(applicationContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(applicationContext.getString(R.string.wdav_notif_title))
                        .setDescription(applicationContext.getString(R.string.wdav_notif_channel_desc))
                        .build()
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, null)

    private suspend fun updateForeground(source: WebdavSource, detail: String?) {
        runCatching { setForeground(foregroundInfo(source, detail)) }
    }

    private fun foregroundInfo(source: WebdavSource?, detail: String?): ForegroundInfo {
        val text = detail?.let { applicationContext.getString(R.string.wdav_notif_detail, it) }
            ?: applicationContext.getString(R.string.wdav_notif_text)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(source?.displayName ?: applicationContext.getString(R.string.wdav_notif_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return ForegroundInfo(NOTIF_ID, notification, type)
    }

    companion object {
        const val CHANNEL_ID = "webdav_sync_channel"
        const val NOTIF_ID = 3003
        const val KEY_SOURCE_ID = "webdavSourceId"
        private const val UNIQUE_PREFIX = "webdav-sync-"
        private const val PERIODIC_NAME = "shelf_webdav_periodic_sync"
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RUN_ATTEMPTS = 5
        private const val PROGRESS_THROTTLE_MS = 500L

        fun uniqueName(sourceId: Long) = "$UNIQUE_PREFIX$sourceId"

        fun enqueue(context: Context, source: WebdavSource) {
            val request = OneTimeWorkRequestBuilder<WebdavSyncWorker>()
                .setInputData(workDataOf(KEY_SOURCE_ID to source.id))
                .setConstraints(constraintsFor(source))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("webdav_sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(source.id),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, sourceId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(sourceId))
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<WebdavSyncWorker>().addTag("webdav_sync_now").build()
            )
        }

        fun schedule(context: Context, wifiOnly: Boolean = true, intervalHours: Long = 4) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<WebdavSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun constraintsFor(source: WebdavSource): Constraints {
            val builder = Constraints.Builder()
                .setRequiredNetworkType(if (source.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
            if (source.chargingOnly) builder.setRequiresCharging(true)
            return builder.build()
        }
    }
}