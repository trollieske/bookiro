package com.bookrio.app.workers

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.library.data.BookImportRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Modern replacement for MediaScannerService.
 * Periodically scans the library folder in the background.
 */
class MediaScannerWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext)
        
        val watch = try {
            prefs.watchLibraryFolder.first()
        } catch (_: Throwable) { false }
        
        val folder = try {
            prefs.libraryFolderUri.first()
        } catch (_: Throwable) { null }

        if (!watch || folder.isNullOrBlank()) {
            return Result.success()
        }

        return try {
            val db = ShelfDatabase.getInstance(applicationContext)
            val repo = BookImportRepository(applicationContext, db, DefaultDispatcherProvider)
            repo.importFolderTree(Uri.parse(folder))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "periodic_media_scan"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresStorageNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<MediaScannerWorker>(
                1, TimeUnit.HOURS // Scan once an hour to be battery friendly
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
        
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<MediaScannerWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
