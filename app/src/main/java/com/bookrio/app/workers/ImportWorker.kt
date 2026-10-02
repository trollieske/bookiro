package com.bookrio.app.workers

import com.bookrio.R

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.library.data.BookImportRepository
import kotlinx.coroutines.flow.first

class ImportWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ensureChannel()
        // #region debug-point A:worker-start
        dbg(
            "A",
            "import-worker-start",
            "treeUri=${inputData.getString(KEY_TREE_URI) ?: ""} uriCount=${inputData.getStringArray(KEY_URIS)?.size ?: 0} source=${inputData.getString(KEY_SOURCE) ?: ""}"
        )
        // #endregion

        // Android 14+ requires starting foreground early with explicit types
        runCatching {
            setForeground(getForegroundInfo())
        }

        val db = ShelfDatabase.getInstance(appContext)
        val prefs = UserPreferencesRepository(appContext)
        val repo = BookImportRepository(appContext, db, DefaultDispatcherProvider)

        val treeUri = inputData.getString(KEY_TREE_URI)
        val uris = inputData.getStringArray(KEY_URIS) ?: emptyArray()
        val sourceOrDefault = inputData.getString(KEY_SOURCE) ?: ImportSourceEntity.FILE_PICKER.name
        val source = ImportSourceEntity.valueOf(sourceOrDefault)

        // --- Durable SAF permission grants ---
        runCatching {
            if (!treeUri.isNullOrBlank()) {
                val u = Uri.parse(treeUri)
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val resolver = appContext.contentResolver
                runCatching {
                    resolver.takePersistableUriPermission(u, takeFlags)
                    // #region debug-point A:tree-grant
                    dbg("A", "tree-grant-ok", "uri=$u flags=$takeFlags")
                    // #endregion
                }.recoverCatching {
                    // Some pickers return non-persistable grants; fall back to read-only
                    resolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    // #region debug-point A:tree-grant-readonly
                    dbg("A", "tree-grant-readonly-ok", "uri=$u")
                    // #endregion
                }.onFailure {
                    // #region debug-point A:tree-grant-fail
                    dbg("A", "tree-grant-failed", "uri=$u error=${it::class.java.simpleName}:${it.message}")
                    // #endregion
                }
                prefs.setLibraryFolderUri(treeUri)
            }
        }
        uris.forEach { uriStr ->
            runCatching {
                val u = Uri.parse(uriStr)
                if (DocumentsContract.isDocumentUri(appContext, u)) {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        // #region debug-point A:doc-grant
                        dbg("A", "doc-grant-ok", "uri=$u")
                        // #endregion
                    }.onFailure {
                        // #region debug-point A:doc-grant-fail
                        dbg("A", "doc-grant-failed", "uri=$u error=${it::class.java.simpleName}:${it.message}")
                        // #endregion
                    }
                }
            }
        }

        val cnt = when {
            inputData.getBoolean(KEY_SAMPLES, false) -> {
                repo.importAssetsSamples()
            }
            treeUri != null -> {
                repo.importFolderTree(Uri.parse(treeUri))
            }
            else -> {
                repo.importUris(uris.map { Uri.parse(it) }, source).size
            }
        }

        // Kick off MediaScanner scan in the background if folder watch enabled
        runCatching {
            val watch = prefs.watchLibraryFolder.first()
            val hasFolder = prefs.libraryFolderUri.first().isNullOrBlank().not()
            if (watch && hasFolder) {
                MediaScannerWorker.runOnce(appContext)
            }
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appContext.getString(R.string.import_notif_done))
            .setContentText(appContext.getString(R.string.import_notif_books, cnt))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        if (cnt > 0 && canPostNotifications()) {
            postNotification(SCAN_DONE_NOTIF_ID, notification)
        }

        val fg = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appContext.getString(R.string.import_notif_title))
            .setContentText(appContext.getString(R.string.import_notif_books, cnt))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build()
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            setForeground(ForegroundInfo(NOTIFICATION_ID, fg, type))
        }

        val data = Data.Builder()
            .putInt("count", cnt)
            .putString("status", "done")
            .build()
        // #region debug-point A:worker-done
        dbg("A", "import-worker-done", "count=$cnt persistedPermissions=${appContext.contentResolver.persistedUriPermissions.size}")
        // #endregion

        return Result.success(data)
    }

    /** POST_NOTIFICATIONS is a runtime permission on API 33+; only post when granted. */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /** Guarded by [canPostNotifications]; suppression documents that the runtime check exists. */
    @SuppressLint("MissingPermission")
    private fun postNotification(id: Int, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(appContext).notify(id, notification) }
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(appContext.getString(R.string.import_notif_channel))
                .setDescription(appContext.getString(R.string.import_notif_channel_desc))
                .build()
            nm.createNotificationChannel(chan)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.import_notif_working))
            .setContentText(appContext.getString(R.string.import_notif_wait))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            type
        )
    }

    companion object {
        const val KEY_URIS = "KEY_URIS"
        const val KEY_TREE_URI = "KEY_TREE_URI"
        const val KEY_SOURCE = "KEY_SOURCE"
        const val KEY_SAMPLES = "KEY_SAMPLES"

        private const val CHANNEL_ID = "import_channel_id"
        private const val NOTIFICATION_ID = 10001
        private const val SCAN_DONE_NOTIF_ID = 10002

        fun enqueueUris(
            workManager: WorkManager,
            uris: List<String>,
            source: ImportSourceEntity
        ): Operation {
            val data = Data.Builder()
                .putStringArray(KEY_URIS, uris.toTypedArray())
                .putString(KEY_SOURCE, source.name)
                .build()

            val request = OneTimeWorkRequest.Builder(ImportWorker::class.java)
                .setInputData(data)
                .build()

            return workManager.enqueue(request)
        }

        fun enqueueFolder(
            workManager: WorkManager,
            treeUri: String
        ): Operation {
            val data = Data.Builder()
                .putString(KEY_TREE_URI, treeUri)
                .build()

            val request = OneTimeWorkRequest.Builder(ImportWorker::class.java)
                .setInputData(data)
                .build()

            return workManager.enqueue(request)
        }

        fun enqueueSamples(workManager: WorkManager): Operation {
            val data = Data.Builder()
                .putBoolean(KEY_SAMPLES, true)
                .build()

            val request = OneTimeWorkRequest.Builder(ImportWorker::class.java)
                .setInputData(data)
                .build()

            return workManager.enqueue(request)
        }
    }

    // #region debug-point shared:worker-http
    private fun dbg(hypothesisId: String, msg: String, data: String) {
        // Removed before Play: used to POST diagnostics to a hardcoded LAN debug
        // server. Intentionally a no-op now.
    }
    // #endregion
}
