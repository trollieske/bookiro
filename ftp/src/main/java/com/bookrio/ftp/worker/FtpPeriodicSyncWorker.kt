package com.bookrio.ftp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.ftp.data.FtpGraph
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic discovery only. It enqueues the same unique manual work per server and
 * therefore can never cancel or replace a user-initiated transfer.
 */
class FtpPeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesRepository(applicationContext)
        if (!prefs.ftpSyncEnabled.first()) return Result.success()

        val graph = FtpGraph.get(applicationContext)
        val sources = graph.sourceRepository.sources.first()
        for (source in sources) {
            if (!source.syncEnabled) continue
            if (source.state == com.bookrio.data.local.entity.FtpSourceStateEntity.DISABLED) continue
            if (FtpSyncWorker.isRunning(applicationContext, source.id)) continue
            FtpSyncWorker.enqueue(applicationContext, source)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ftp-periodic-sync"

        fun schedule(context: Context) {
            val app = context.applicationContext
            val prefs = UserPreferencesRepository(app)
            val wm = WorkManager.getInstance(app)

            val enabled = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpSyncEnabled.first() } }
                .getOrDefault(false)
            if (!enabled) {
                // Disabled: make sure no periodic sync lingers.
                wm.cancelUniqueWork(WORK_NAME)
                return
            }

            val wifiOnly = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpWifiOnly.first() } }
                .getOrDefault(true)
            val chargingOnly = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpChargingOnly.first() } }
                .getOrDefault(false)
            val intervalHours = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpIntervalMinutes.first() } }
                .getOrDefault(360)
                .let { (it / 60L).coerceIn(1L, 24L) }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .apply { if (chargingOnly) setRequiresCharging(true) }
                .build()

            val request = PeriodicWorkRequestBuilder<FtpPeriodicSyncWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            // UPDATE (not KEEP) so interval / Wi-Fi / charging changes actually apply.
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}