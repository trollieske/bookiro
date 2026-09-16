package com.shelf.reader.smb.worker

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
import com.shelf.reader.smb.R
import com.shelf.reader.smb.client.SmbClientEngine
import com.shelf.reader.smb.client.SmbErrorKind
import com.shelf.reader.smb.data.SmbGraph
import com.shelf.reader.smb.data.SmbSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Single owner of a stored SMB source's transfer work.
 *
 * Exactly one unique work exists per source (`smb-sync-<id>`), so pressing
 * Download repeatedly can never create competing workers or delete an active
 * queue. A source-less invocation is the periodic reconciler that only enqueues
 * per-source work.
 */
class SmbSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = SmbGraph.get(applicationContext)
        runCatching { graph.migrateLegacyIfNeeded() }

        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1L)
        if (sourceId <= 0L) return reconcile(graph)

        val source = graph.sourceRepository.getSource(sourceId) ?: return Result.success()
        ensureChannel()
        updateForeground(source, null)

        val credentials = graph.sourceRepository.credentialsFor(sourceId)
        if (credentials == null) {
            graph.sourceRepository.markState(
                sourceId,
                RemoteSourceStateEntity.NEEDS_AUTH,
                "Sign-in could not be read"
            )
            return Result.success()
        }

        graph.transferRepository.rehydrateActive(sourceId)

        val engine = SmbClientEngine()
        val connect = engine.connectResult(
            host = credentials.host,
            port = credentials.port,
            shareName = credentials.shareName,
            domain = credentials.domain,
            username = credentials.username,
            password = credentials.password,
            smbVersion = credentials.smbVersion,
            enableEncryption = credentials.enableEncryption
        )
        if (!connect.success) {
            val state = when (connect.kind) {
                SmbErrorKind.AUTH -> RemoteSourceStateEntity.NEEDS_AUTH
                else -> RemoteSourceStateEntity.CONNECTION_ERROR
            }
            graph.sourceRepository.markState(sourceId, state, connect.kind?.name)
            return if (connect.kind == SmbErrorKind.AUTH) Result.success() else Result.retry()
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
                    val bytes = downloadWithThrottledProgress(graph, engine, task.id, task.remotePath, finalFile)
                    if (bytes <= 0) {
                        graph.transferRepository.markRetrying(
                            task.id, "Download failed", SmbErrorKind.NETWORK.name,
                            System.currentTimeMillis() + RETRY_BACKOFF_MS
                        )
                        transientFailure = true
                        continue
                    }
                    val expected = task.sizeBytes
                    if (expected > 0 && bytes != expected) {
                        graph.transferRepository.markRetrying(
                            task.id, "Downloaded $bytes of $expected bytes", SmbErrorKind.NETWORK.name,
                            System.currentTimeMillis() + RETRY_BACKOFF_MS
                        )
                        transientFailure = true
                        continue
                    }
                    graph.transferRepository.transition(task.id, DownloadStatusEntity.VERIFYING)
                    graph.transferRepository.transition(task.id, DownloadStatusEntity.IMPORTING)
                    val bookIds = importRepo.importUris(
                        uris = listOf(Uri.fromFile(finalFile)),
                        source = ImportSourceEntity.SMB_DOWNLOAD,
                        serverId = null,
                        remotePath = task.remotePath,
                        filePathOverride = finalFile.absolutePath,
                        consolidate = false
                    )
                    imported.addAll(bookIds)
                    graph.transferRepository.markCompleted(task.id, bookIds.firstOrNull())
                } catch (e: Throwable) {
                    graph.transferRepository.fail(task.id, e.message, SmbErrorKind.UNKNOWN.name)
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

    private suspend fun downloadWithThrottledProgress(
        graph: SmbGraph,
        engine: SmbClientEngine,
        taskId: Long,
        remotePath: String,
        finalFile: File
    ): Long {
        var lastPersist = 0L
        return engine.downloadFile(remotePath, finalFile) { downloaded, _ ->
            val now = System.currentTimeMillis()
            if (now - lastPersist >= PROGRESS_THROTTLE_MS) {
                lastPersist = now
                runCatching { graph.transferRepository.updateProgress(taskId, downloaded, 0L) }
            }
        }
    }

    /** Periodic reconciler: enqueue one worker per sync-enabled source. */
    private suspend fun reconcile(graph: SmbGraph): Result {
        val sources = graph.sourceRepository.syncEnabledSources()
        sources.forEach { enqueue(applicationContext, it) }
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
                        .setName(applicationContext.getString(R.string.smbu_notif_title))
                        .setDescription(applicationContext.getString(R.string.smbu_notif_channel_desc))
                        .build()
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null, null)

    private suspend fun updateForeground(source: SmbSource, detail: String?) {
        runCatching { setForeground(foregroundInfo(source, detail)) }
    }

    private fun foregroundInfo(source: SmbSource?, detail: String?): ForegroundInfo {
        val text = detail?.let { applicationContext.getString(R.string.smbu_notif_detail, it) }
            ?: applicationContext.getString(R.string.smbu_notif_text)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(source?.displayName ?: applicationContext.getString(R.string.smbu_notif_title))
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
        const val CHANNEL_ID = "smb_sync_channel"
        const val NOTIF_ID = 3002
        const val KEY_SOURCE_ID = "smbSourceId"
        private const val UNIQUE_PREFIX = "smb-sync-"
        private const val PERIODIC_NAME = "shelf_smb_periodic_sync"
        private const val RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RUN_ATTEMPTS = 5
        private const val PROGRESS_THROTTLE_MS = 500L

        fun uniqueName(sourceId: Long) = "$UNIQUE_PREFIX$sourceId"

        /** Enqueue-or-keep. Never replaces a running queue. */
        fun enqueue(context: Context, source: SmbSource) {
            val request = OneTimeWorkRequestBuilder<SmbSyncWorker>()
                .setInputData(workDataOf(KEY_SOURCE_ID to source.id))
                .setConstraints(constraintsFor(source))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("smb_sync")
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
                OneTimeWorkRequestBuilder<SmbSyncWorker>().addTag("smb_sync_now").build()
            )
        }

        /** Kept for the Settings screen: schedules the periodic reconciler. */
        fun schedule(context: Context, wifiOnly: Boolean = true, intervalHours: Long = 4) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<SmbSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun constraintsFor(source: SmbSource): Constraints {
            val builder = Constraints.Builder()
                .setRequiredNetworkType(if (source.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
            if (source.chargingOnly) builder.setRequiresCharging(true)
            return builder.build()
        }
    }
}