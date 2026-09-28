package com.bookrio.ftp.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.bookrio.ftp.data.FtpGraph
import com.bookrio.ftp.data.FtpSource
import com.bookrio.ftp.transfer.FtpQueuePlanner
import com.bookrio.ftp.transfer.FtpTransferEngine
import com.bookrio.ftp.transfer.FtpWorkNaming
import com.bookrio.ftp.transfer.NetworkTransport
import com.bookrio.ftp.transfer.StopReason

/**
 * Single owner of a server's active transfer work.
 *
 * Exactly one unique work exists per server (`ftp-sync-server-<id>`), so pressing
 * Sync repeatedly can never create competing workers or delete an active queue.
 */
class FtpSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getLong(KEY_SERVER_ID, -1L)
        if (serverId <= 0L) return Result.success()

        val graph = FtpGraph.get(applicationContext)
        val source = graph.sourceRepository.getSource(serverId) ?: return Result.success()

        FtpNotifications.ensureChannel(applicationContext)
        updateForeground(source)

        // "Sync now" discovery: only when there is no runnable or paused work and
        // nothing failed that the user has not explicitly retried.
        val before = graph.transferRepository.counts(serverId)
        if (before.queued == 0 && before.running == 0 && before.paused == 0 && before.failed == 0) {
            runCatching {
                FtpQueuePlanner(graph.sourceRepository, graph.transferRepository).plan(source)
            }
        }

        val engine = FtpTransferEngine(
            sourceRepository = graph.sourceRepository,
            transferRepository = graph.transferRepository,
            importer = graph.importer,
            runtime = FtpGraph.runtime,
            transportProvider = { NetworkTransport.detect(applicationContext) },
            onUpdate = { updateForeground(graph.sourceRepository.getSource(serverId) ?: source) },
            powerProvider = { com.bookrio.ftp.transfer.PowerState.isCharging(applicationContext) }
        )

        val result = try {
            engine.run(serverId) { isStopped }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            graph.transferRepository.rehydrateActive(serverId)
            throw cancelled
        } catch (_: Throwable) {
            // A crash must never leave rows stuck in RUNNING.
            graph.transferRepository.rehydrateActive(serverId)
            return Result.retry()
        }

        graph.transferRepository.rehydrateActive(serverId)
        graph.sourceRepository.markLastSync(serverId)

        return when (result.reason) {
            StopReason.AUTH_FAILURE -> {
                // Do not retry bad credentials forever; the UI asks for a fix.
                Result.success()
            }
            StopReason.STORAGE_FULL -> Result.success()
            StopReason.CONNECTION_LOST -> Result.retry()
            StopReason.USER_CANCELLED -> Result.success()
            StopReason.QUEUE_EMPTY -> Result.success()
        }
    }

    private suspend fun updateForeground(source: FtpSource) {
        runCatching {
            val counts = FtpGraph.get(applicationContext).transferRepository.counts(source.id)
            val active = FtpGraph.runtime.snapshot().filter { it.serverId == source.id }
            val notification = FtpNotifications.build(
                context = applicationContext,
                serverId = source.id,
                sourceName = source.displayName,
                active = active,
                queued = counts.queued,
                running = counts.running,
                total = counts.total,
                completed = counts.completed,
                failed = counts.failed
            )
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else 0
            setForeground(ForegroundInfo(FtpNotifications.NOTIF_ID, notification, type))
        }
    }

    companion object {
        const val KEY_SERVER_ID = FtpWorkNaming.INPUT_KEY_SERVER_ID
        private const val TAG = "ftp-sync"

        fun uniqueName(serverId: Long): String = FtpWorkNaming.uniqueName(serverId)

        /** Enqueue-or-keep. Never replaces a running queue unless asked. */
        fun enqueue(
            context: Context,
            source: FtpSource,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP
        ) {
            val request = OneTimeWorkRequestBuilder<FtpSyncWorker>()
                .setInputData(workDataOf(KEY_SERVER_ID to source.id))
                .setConstraints(constraintsFor(source))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, java.util.concurrent.TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(source.id),
                policy,
                request
            )
        }

        fun cancel(context: Context, serverId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(serverId))
        }

        suspend fun isRunning(context: Context, serverId: Long): Boolean {
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueName(serverId))
                .await()
            return info.any { !it.state.isFinished }
        }

        /** True only while the worker is actually executing (not ENQUEUED/backoff). */
        suspend fun isExecuting(context: Context, serverId: Long): Boolean {
            val info = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueName(serverId))
                .await()
            return info.any { it.state == androidx.work.WorkInfo.State.RUNNING }
        }

        /**
         * User-initiated start. If the worker is not actually running (for example it
         * is parked in WorkManager timing backoff after retries) the existing work is
         * replaced so the tap takes effect immediately.
         */
        suspend fun enqueueOrRestart(context: Context, source: FtpSource) {
            val policy = if (isExecuting(context, source.id)) {
                ExistingWorkPolicy.KEEP
            } else {
                ExistingWorkPolicy.REPLACE
            }
            enqueue(context, source, policy)
        }

        private fun constraintsFor(source: FtpSource): Constraints {
            val builder = Constraints.Builder()
                .setRequiredNetworkType(if (source.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
            if (source.chargingOnly) builder.setRequiresCharging(true)
            return builder.build()
        }
    }
}