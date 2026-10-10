package com.bookrio.app.backup

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookrio.MainActivity
import com.bookrio.R
import com.bookrio.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "BackupWorker"

/** WorkManager names + input keys shared by the worker, scheduler and UI. */
object BackupWork {
    const val EXPORT = "bookiro_backup_export"
    const val IMPORT = "bookiro_backup_import"
    const val PERIODIC = "bookiro_backup_periodic"

    const val KEY_OPERATION = "operation"
    const val KEY_DESTINATION = "destination"
    const val KEY_OPTIONS = "options"
    const val KEY_TREE = "tree"
    const val KEY_SCHEDULED = "scheduled"
    const val KEY_RETENTION = "retention"

    const val OP_EXPORT = "export"
    const val OP_IMPORT = "import"

    // Progress keys (observed by the UI).
    const val P_LABEL = "label"
    const val P_DONE = "done"
    const val P_TOTAL = "total"
    const val P_ENTRIES = "entries"
    const val P_ENTRIES_TOTAL = "entries_total"
    const val P_CURRENT = "current"

    const val OUT_ERROR = "error"
    const val OUT_BOOKS = "books"
    const val OUT_MEDIA = "media"

    const val CHANNEL_ID = "backup_channel"
    const val NOTIFICATION_ID = 7777

    const val ACTION_PAUSE = "com.bookrio.app.backup.PAUSE"
    const val ACTION_RESUME = "com.bookrio.app.backup.RESUME"
    const val ACTION_CANCEL = "com.bookrio.app.backup.CANCEL"
    const val EXTRA_WORK_NAME = "work_name"
}

/**
 * Runs one export or import as foreground WorkManager work.
 *
 * Foreground means the user can leave the app and the operation keeps running,
 * with progress and pause/cancel controls in the notification. Cancelling via
 * `WorkManager.cancelUniqueWork` cancels the coroutine, which the engine cleans
 * up after (staging dir + any document this worker created).
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private var createdDocument: Uri? = null
    private var lastNotifyAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(buildNotification(applicationContext.getString(R.string.backup_preparing), 0f, false))

    override suspend fun doWork(): Result {
        if (!BackupControl.tryBegin()) {
            // Never let a scheduled run overlap a manual one: both touch the same
            // database and media files.
            return if (inputData.getBoolean(BackupWork.KEY_SCHEDULED, false)) {
                Result.retry()
            } else {
                Result.failure(workDataOf(BackupWork.OUT_ERROR to "Another backup is already running"))
            }
        }
        return try {
            doWorkInner()
        } finally {
            BackupControl.end()
        }
    }

    private suspend fun doWorkInner(): Result {
        val operation = inputData.getString(BackupWork.KEY_OPERATION) ?: return Result.failure()
        val destinationStr = inputData.getString(BackupWork.KEY_DESTINATION) ?: return Result.failure()
        val options = BackupOptions.fromJson(inputData.getString(BackupWork.KEY_OPTIONS))
        val scheduled = inputData.getBoolean(BackupWork.KEY_SCHEDULED, false)
        val retention = inputData.getInt(BackupWork.KEY_RETENTION, 3)

        BackupControl.reset()
        // A user-initiated run is in the foreground, so this always succeeds. A
        // scheduled run may be started while the app is backgrounded, where Android
        // 12+ can refuse a foreground-service start; fall back to plain background
        // work (still governed by WorkManager) instead of crashing.
        runCatching {
            setForeground(foreground(buildNotification(applicationContext.getString(R.string.backup_preparing), 0f, false)))
        }.onFailure { Log.w(TAG, "foreground unavailable", it) }

        val destination = Uri.parse(destinationStr)
        val tree = inputData.getBoolean(BackupWork.KEY_TREE, false)
        val target = if (operation == BackupWork.OP_EXPORT && tree) {
            createDocument(destination, BackupFormat.backupFileName(System.currentTimeMillis()))
                ?.also { createdDocument = it }
                ?: return Result.failure(workDataOf(BackupWork.OUT_ERROR to "Could not create the backup file"))
        } else {
            // Track an immediate export too, so cancelling removes the partial file.
            if (operation == BackupWork.OP_EXPORT) createdDocument = destination
            destination
        }

        val engine = LibraryBackupEngine(applicationContext)
        val outcome = try {
            if (operation == BackupWork.OP_EXPORT) {
                engine.exportLibrary(target, options, BackupControl) { progress ->
                    report(progress, operation)
                }
            } else {
                engine.importLibrary(target, BackupControl) { progress ->
                    report(progress, operation)
                }
            }
        } catch (t: Throwable) {
            // Cancellation (or any failure) removes a document this worker created so
            // the user never sees a corrupt archive.
            createdDocument?.let { uri ->
                runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, uri) }
            }
            createdDocument = null
            throw t
        }

        return when (outcome) {
            is BackupOutcome.Exported -> {
                UserPreferencesRepository(applicationContext).setBackupLast(
                    at = System.currentTimeMillis(),
                    size = outcome.bytes,
                    name = target.lastPathSegment,
                    status = "ok",
                )
                if (scheduled) pruneOldBackups(destination, retention)
                Result.success(
                    workDataOf(
                        "bytes" to outcome.bytes,
                        "entries" to outcome.entries,
                    )
                )
            }
            is BackupOutcome.Imported -> {
                // Survives process death: the next launch (or the live UI) restarts
                // so no ViewModel keeps the swapped database.
                UserPreferencesRepository(applicationContext).setBackupRestorePending(true)
                Result.success(
                    workDataOf(
                        BackupWork.OUT_BOOKS to outcome.bookCount,
                        BackupWork.OUT_MEDIA to outcome.mediaFiles,
                    )
                )
            }
            is BackupOutcome.Failed -> Result.failure(
                workDataOf(BackupWork.OUT_ERROR to outcome.message)
            )
        }
    }

    @SuppressLint("MissingPermission") // guarded by canPostNotifications()
    private fun report(progress: BackupProgress, operation: String) {
        setProgressAsync(
            workDataOf(
                BackupWork.P_LABEL to progress.label,
                BackupWork.P_DONE to progress.processedBytes,
                BackupWork.P_TOTAL to progress.totalBytes,
                BackupWork.P_ENTRIES to progress.entriesDone,
                BackupWork.P_ENTRIES_TOTAL to progress.entriesTotal,
                BackupWork.P_CURRENT to (progress.current ?: ""),
            )
        )
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 900L && progress.fraction < 1f) return
        lastNotifyAt = now
        val title = if (operation == BackupWork.OP_EXPORT) {
            applicationContext.getString(R.string.backup_exporting)
        } else {
            applicationContext.getString(R.string.backup_importing)
        }
        if (canPostNotifications()) {
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(BackupWork.NOTIFICATION_ID, buildNotification(title, progress.fraction, BackupControl.paused.value))
            }
        }
    }

    /** POST_NOTIFICATIONS is a runtime permission on API 33+; only post when granted. */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun foreground(notification: Notification): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(BackupWork.NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(title: String, fraction: Float, paused: Boolean): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("target_route", "backup")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val workName = if (inputData.getString(BackupWork.KEY_OPERATION) == BackupWork.OP_IMPORT) {
            BackupWork.IMPORT
        } else {
            BackupWork.EXPORT
        }
        val toggle = PendingIntent.getBroadcast(
            applicationContext,
            1,
            Intent(applicationContext, BackupActionReceiver::class.java).apply {
                action = if (paused) BackupWork.ACTION_RESUME else BackupWork.ACTION_PAUSE
                putExtra(BackupWork.EXTRA_WORK_NAME, workName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getBroadcast(
            applicationContext,
            2,
            Intent(applicationContext, BackupActionReceiver::class.java).apply {
                action = BackupWork.ACTION_CANCEL
                putExtra(BackupWork.EXTRA_WORK_NAME, workName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(applicationContext, BackupWork.CHANNEL_ID)
            .setSmallIcon(com.bookrio.designsystem.R.drawable.ic_stat_bookrio)
            .setContentTitle(title)
            .setContentText(if (paused) applicationContext.getString(R.string.backup_paused) else "${(fraction * 100).toInt()}%")
            .setProgress(1000, (fraction * 1000).toInt().coerceIn(0, 1000), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                0,
                applicationContext.getString(if (paused) R.string.backup_resume else R.string.backup_pause),
                toggle,
            )
            .addAction(0, applicationContext.getString(R.string.backup_cancel), cancel)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(BackupWork.CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                BackupWork.CHANNEL_ID,
                applicationContext.getString(R.string.backup_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(R.string.backup_channel_desc)
                setShowBadge(false)
            }
        )
    }

    // ── SAF tree helpers ──────────────────────────────────────────────────────

    private fun createDocument(treeUri: Uri, name: String): Uri? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        DocumentsContract.createDocument(
            applicationContext.contentResolver,
            parent,
            "application/zip",
            name,
        )
    }.getOrNull()

    private fun pruneOldBackups(treeUri: Uri, retention: Int) {
        if (retention <= 0) return
        runCatching {
            val resolver = applicationContext.contentResolver
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val entries = mutableListOf<Pair<String, String>>() // (documentId, displayName)
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val display = cursor.getString(1) ?: continue
                    if (display.startsWith(BackupFormat.FILE_PREFIX) && display.endsWith(BackupFormat.FILE_EXTENSION)) {
                        entries += id to display
                    }
                }
            }
            entries.sortedBy { it.second }.dropLast(retention).forEach { (id, _) ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                    )
                }
            }
        }
    }
}

/** Notification transport controls for the running backup. */
class BackupActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BackupWork.ACTION_PAUSE -> BackupControl.pause()
            BackupWork.ACTION_RESUME -> BackupControl.resume()
            BackupWork.ACTION_CANCEL -> {
                val name = intent.getStringExtra(BackupWork.EXTRA_WORK_NAME) ?: BackupWork.EXPORT
                WorkManager.getInstance(context).cancelUniqueWork(name)
            }
        }
    }
}

/** Enqueues immediate work and (re)configures the periodic schedule. */
object BackupScheduler {

    fun enqueueExport(context: Context, destination: Uri, options: BackupOptions, tree: Boolean) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(
                workDataOf(
                    BackupWork.KEY_OPERATION to BackupWork.OP_EXPORT,
                    BackupWork.KEY_DESTINATION to destination.toString(),
                    BackupWork.KEY_OPTIONS to options.toJson(),
                    BackupWork.KEY_TREE to tree,
                    BackupWork.KEY_SCHEDULED to false,
                )
            )
            .addTag("bookiro_backup")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BackupWork.EXPORT, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueImport(context: Context, source: Uri) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(
                workDataOf(
                    BackupWork.KEY_OPERATION to BackupWork.OP_IMPORT,
                    BackupWork.KEY_DESTINATION to source.toString(),
                )
            )
            .addTag("bookiro_backup")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BackupWork.IMPORT, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic(
        context: Context,
        treeUri: Uri,
        options: BackupOptions,
        intervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        retention: Int,
    ) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(chargingOnly)
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(
            intervalHours.coerceAtLeast(6).toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    BackupWork.KEY_OPERATION to BackupWork.OP_EXPORT,
                    BackupWork.KEY_DESTINATION to treeUri.toString(),
                    BackupWork.KEY_OPTIONS to options.toJson(),
                    BackupWork.KEY_TREE to true,
                    BackupWork.KEY_SCHEDULED to true,
                    BackupWork.KEY_RETENTION to retention,
                )
            )
            .addTag("bookiro_backup")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BackupWork.PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BackupWork.PERIODIC)
    }

    fun cancelExport(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(BackupWork.EXPORT)
        runCatching { BackupControl.resume() }
    }
}
