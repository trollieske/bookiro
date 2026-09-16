package com.shelf.reader.webdav.worker

import com.shelf.reader.webdav.R

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
import com.shelf.reader.core.dispatchers.DefaultDispatcherProvider
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.local.entity.ImportSourceEntity
import com.shelf.reader.library.data.BookImportRepository
import com.shelf.reader.webdav.client.WebdavClientEngine
import com.shelf.reader.webdav.client.WebdavEntryType
import com.shelf.reader.webdav.data.WebdavServerStore
import kotlinx.coroutines.flow.first
import java.io.File

class WebdavSyncWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "webdav_sync_channel"
        const val NOTIF_ID = 3003
        private const val WORK_NAME = "shelf_webdav_periodic_sync"

        fun schedule(context: Context, wifiOnly: Boolean = true, intervalHours: Long = 4) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            val req = androidx.work.PeriodicWorkRequestBuilder<WebdavSyncWorker>(
                intervalHours, java.util.concurrent.TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runNow(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val req = androidx.work.OneTimeWorkRequestBuilder<WebdavSyncWorker>()
                .addTag("webdav_sync_now")
                .build()
            workManager.enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        ensureChannel()
        runCatching { setForeground(getForegroundInfo()) }

        val store = WebdavServerStore(appContext)
        val servers = store.servers.first()
        if (servers.isEmpty()) return Result.success()

        val engine = WebdavClientEngine()
        val db = ShelfDatabase.getInstance(appContext)
        val repo = BookImportRepository(appContext, db, DefaultDispatcherProvider)
        var totalImported = 0

        for (server in servers) {
            val fullUrl = server.baseUrl.trimEnd('/')
            val ok = engine.connect(
                baseUrl = fullUrl,
                username = server.username,
                password = server.password.ifBlank { null },
                bearerToken = server.bearerToken.ifBlank { null },
                authType = server.authType,
                trustAllCertificates = server.trustAllCertificates,
                userAgent = "ShelfReader/1.0"
            )
            if (!ok) continue

            try {
                val entries = engine.listDirectory(server.defaultRemotePath)
                val fileEntries = entries.filter { it.type == WebdavEntryType.FILE }
                if (fileEntries.isEmpty()) continue

                val dirName = server.baseUrl.replace("[:/\\\\.]".toRegex(), "_")
                val downloadDir = File(appContext.filesDir, "webdav/$dirName").apply { mkdirs() }
                val importedUris = mutableListOf<Uri>()

                for (entry in fileEntries) {
                    val basename = entry.name
                    val ext = basename.substringAfterLast('.', "").lowercase()
                    if (ext !in setOf("epub", "pdf", "cbz", "cbr", "fb2", "m4b", "m4a", "mp3", "aac", "flac", "mobi", "azw", "azw3", "txt")) continue

                    val localFile = File(downloadDir, basename)
                    if (localFile.exists() && localFile.length() == entry.sizeBytes) continue

                    val bytes = engine.downloadFile(entry.path, localFile)
                    if (bytes > 0) importedUris.add(Uri.fromFile(localFile))
                }

                if (importedUris.isNotEmpty()) {
                    repo.importUris(importedUris, ImportSourceEntity.WEBDAV_DOWNLOAD)
                    totalImported += importedUris.size
                }
            } catch (_: Exception) {
            } finally {
                runCatching { engine.disconnect() }
            }
        }

        return Result.success()
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(appContext.getString(R.string.wdav_notif_title))
                    .setDescription(appContext.getString(R.string.wdav_notif_channel_desc))
                    .build()
                nm.createNotificationChannel(chan)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.wdav_notif_title))
            .setContentText(appContext.getString(R.string.wdav_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}
