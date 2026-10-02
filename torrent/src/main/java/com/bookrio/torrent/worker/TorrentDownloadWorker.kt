package com.bookrio.torrent.worker

import com.bookrio.torrent.R

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.torrent.engine.TorrentEngine
import kotlinx.coroutines.delay

class TorrentDownloadWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "torrent_channel"
        const val NOTIF_ID = 3004
        private const val WORK_NAME = "shelf_torrent_worker"

        fun schedule(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val wifiOnly = true
            val chargingOnly = false
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) androidx.work.NetworkType.UNMETERED
                    else androidx.work.NetworkType.CONNECTED
                )
                .setRequiresStorageNotLow(true)
                .setRequiresCharging(chargingOnly)
                .build()

            val req = androidx.work.PeriodicWorkRequestBuilder<TorrentDownloadWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun runNow(context: Context) {
            val workManager = androidx.work.WorkManager.getInstance(context)
            val wifiOnly = true
            val chargingOnly = false
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) androidx.work.NetworkType.UNMETERED
                    else androidx.work.NetworkType.CONNECTED
                )
                .setRequiresStorageNotLow(true)
                .setRequiresCharging(chargingOnly)
                .build()
            val req = androidx.work.OneTimeWorkRequestBuilder<TorrentDownloadWorker>()
                .setConstraints(constraints)
                .addTag("torrent_run_now")
                .build()
            // Unique so repeated app launches can never queue a backlog of workers.
            workManager.enqueueUniqueWork(
                "torrent_run_now",
                androidx.work.ExistingWorkPolicy.KEEP,
                req
            )
        }
    }

    override suspend fun doWork(): Result {
        val db = ShelfDatabase.getInstance(appContext)

        // Nothing to do: avoid spinning up the native session and a foreground
        // notification on every app launch. Only continue when a download is
        // actually pending, running or resumable.
        val hasWork = runCatching {
            db.torrentDownloadDao().getAllOnce().any {
                it.status == DownloadStatusEntity.PENDING ||
                    it.status == DownloadStatusEntity.RUNNING ||
                    it.status == DownloadStatusEntity.PAUSED
            }
        }.getOrDefault(false)
        if (!hasWork) return Result.success()

        ensureChannel()
        runCatching { setForeground(getForegroundInfo()) }

        val engine = TorrentEngine.getInstance(appContext)
        engine.start()

        val timeoutMs = 10 * 60 * 1000L
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val wifiOnly = true
            val chargingOnly = false
            val minBattery = 15

            // Check mid-flight runtime constraints
            val batteryOk = isBatteryOk(appContext, minBattery)
            val chargingOk = !chargingOnly || isCharging(appContext)
            val networkOk = !wifiOnly || !isMetered(appContext)

            if (!batteryOk || !chargingOk || !networkOk) {
                val why = when {
                    !batteryOk -> "Batteri for lavt (<$minBattery%)"
                    !chargingOk -> "Lader ikke (kreves på grunn av innstilling)"
                    !networkOk -> "Målt nettverk (trenger Wi-Fi)"
                    else -> null
                }
                Log.w("TorrentWorker", "Avslutter torrent-worker tidlig: $why")
                pauseActiveDownloads(db, engine)
                break
            }

            val hasActive = try {
                val running = db.torrentDownloadDao().getRunning()
                val nextPending = db.torrentDownloadDao().getNextPending()
                running.isNotEmpty() || nextPending != null
            } catch (_: Exception) { false }

            if (!hasActive) {
                break
            }
            delay(5000L)
        }

        return Result.success()
    }

    private fun isBatteryOk(ctx: Context, minPct: Int): Boolean {
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            level >= minPct.coerceAtLeast(1)
        }.getOrDefault(true)
    }

    private fun isCharging(ctx: Context): Boolean {
        return runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val plugged = bm.isCharging
            plugged
        }.getOrDefault(true)
    }

    private fun isMetered(ctx: Context): Boolean {
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.isActiveNetworkMetered
        }.getOrDefault(false)
    }

    private suspend fun pauseActiveDownloads(db: ShelfDatabase, engine: TorrentEngine) {
        // Use the engine's public pause path so the native libtorrent handle is
        // actually paused (the old reflection looked up a method that does not
        // exist and silently left downloads running).
        runCatching {
            val active = db.torrentDownloadDao().getRunning()
            for (dl in active) {
                runCatching { engine.pauseDownload(dl.id) }
            }
        }
    }

    private fun ensureChannel() {
        runCatching {
            val nm = NotificationManagerCompat.from(appContext)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val chan = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(appContext.getString(R.string.toru_notif_channel))
                    .setDescription(appContext.getString(R.string.toru_notif_channel_desc))
                    .build()
                nm.createNotificationChannel(chan)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appContext.getString(R.string.toru_notif_channel))
            .setContentText(appContext.getString(R.string.toru_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

        return ForegroundInfo(NOTIF_ID, notification, type)
    }
}
