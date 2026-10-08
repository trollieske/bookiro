package com.bookrio.app.workers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookrio.MainActivity
import com.bookrio.R
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.net.MetadataFetcher
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.library.cover.CoverRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Opt-in, user-triggered online refresh of book metadata and covers.
 *
 * Every target book is looked up on Open Library / Google Books / iTunes; a
 * corrected title/author and the online cover replace whatever the file carried.
 * Gated on the "Online cover lookup" setting — the worker does nothing when it is
 * disabled, so it never touches the network without consent.
 */
class MetadataRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_SCOPE = "scope"
        const val SCOPE_SUSPECTS = "suspects"
        const val SCOPE_ALL = "all"

        private const val CHANNEL_ID = "metadata_refresh_channel"
        private const val NOTIF_ID = 3005

        fun enqueue(context: Context, scope: String) {
            val request = OneTimeWorkRequestBuilder<MetadataRefreshWorker>()
                .setInputData(workDataOf(KEY_SCOPE to scope))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("metadata_refresh", androidx.work.ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext)
        val onlineEnabled = runCatching { prefs.onlineCoverLookup.first() }.getOrDefault(false)
        if (!onlineEnabled) return Result.success()

        val db = ShelfDatabase.getInstance(applicationContext)
        val books = runCatching { db.bookDao().getAllOnce() }
            .getOrDefault(emptyList())
            .filter { !it.isDeleted }
        val scope = inputData.getString(KEY_SCOPE) ?: SCOPE_SUSPECTS
        val targets = if (scope == SCOPE_ALL) books else books.filter { isSuspect(it) }
        if (targets.isEmpty()) return Result.success()

        ensureChannel()
        runCatching { setForeground(foregroundInfo(0, targets.size)) }

        val covers = CoverRepository(applicationContext, db, DefaultDispatcherProvider)
        var done = 0
        for (book in targets) {
            if (isStopped) break
            runCatching { covers.refreshOnline(book) }
            done++
            if (done % 3 == 0 || done == targets.size) {
                runCatching { setForeground(foregroundInfo(done, targets.size)) }
            }
            delay(200)
        }
        return Result.success()
    }

    private fun isSuspect(book: com.bookrio.data.local.entity.BookEntity): Boolean {
        if (MetadataFetcher.isAuthorUnknown(book.author)) return true
        if (book.title.isBlank()) return true
        val t = book.title.trim()
        if (t.length < 3) return true
        if (t.contains(" - ")) return false
        return !(t.contains(" ") && t.any { it.isUpperCase() } && t.any { it.isLowerCase() })
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(applicationContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannelCompat.Builder(
                    CHANNEL_ID,
                    NotificationManagerCompat.IMPORTANCE_LOW
                )
                    .setName(applicationContext.getString(R.string.metadata_refresh_notif_channel))
                    .setDescription(applicationContext.getString(R.string.metadata_refresh_notif_channel_desc))
                    .build()
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val launch = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(applicationContext.getString(R.string.metadata_refresh_notif_title))
            .setContentText(applicationContext.getString(R.string.metadata_refresh_notif_text, done, total))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, false)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}