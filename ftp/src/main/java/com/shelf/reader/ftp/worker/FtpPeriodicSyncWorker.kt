package com.shelf.reader.ftp.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.ftp.data.FtpGraph
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
            if (source.state == com.shelf.reader.data.local.entity.FtpSourceStateEntity.DISABLED) continue
            if (FtpSyncWorker.isRunning(applicationContext, source.id)) continue
            FtpSyncWorker.enqueue(applicationContext, source)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ftp-periodic-sync"

        fun schedule(context: Context) {
            val prefs = UserPreferencesRepository(context)
            val wifiOnly = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpWifiOnly.first() } }
                .getOrDefault(true)
            val chargingOnly = runCatching { kotlinx.coroutines.runBlocking { prefs.ftpChargingOnly.first() } }
                .getOrDefault(false)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .apply { if (chargingOnly) setRequiresCharging(true) }
                .build()

            val request = PeriodicWorkRequestBuilder<FtpPeriodicSyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}