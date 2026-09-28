package com.bookrio.ftp.worker

import android.content.Context
import com.bookrio.ftp.data.FtpGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Startup recovery.
 *
 * A previous process may have been killed mid-transfer, leaving rows marked
 * RUNNING with no live work. Recovery re-queues those rows and re-enqueues the
 * unique worker for each affected server. A false RUNNING state can therefore
 * never survive a restart.
 */
object FtpSyncCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching { recover(appContext) }
        }
    }

    suspend fun recover(context: Context): Int {
        val graph = FtpGraph.get(context)
        // Bring any pre-Room servers into Room exactly once.
        runCatching { FtpLegacyMigration.runIfNeeded(context) }

        // Drop the pre-upgrade periodic worker name (it had no server id).
        runCatching {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("shelf_ftp_periodic_sync")
        }

        // Stale RUNNING/VERIFYING/IMPORTING rows from a process death go back to the queue.
        val recovered = graph.transferRepository.rehydrateAllActive()

        // Resume any incomplete queue, not only those with a mid-flight row: a process
        // killed between files has only QUEUED rows and must still continue. Paused,
        // cancelled and failed rows are deliberately not resumed.
        //
        // REPLACE (not KEEP) is important here: if the previous worker was left in
        // WorkManager timing backoff after repeated retries, KEEP would keep waiting
        // for minutes. A user-visible app start is a deliberate resume signal.
        val serverIds = graph.transferRepository.runnableServerIds()
        android.util.Log.i(
            "FtpSyncCoordinator",
            "recover: rehydrated=$recovered runnableServers=${serverIds.size}"
        )
        for (serverId in serverIds) {
            val source = graph.sourceRepository.getSource(serverId) ?: continue
            if (source.state == com.bookrio.data.local.entity.FtpSourceStateEntity.DISABLED) continue
            // Skip only a worker that is actually executing; an ENQUEUED work left in
            // backoff is replaced to clear the delay.
            FtpSyncWorker.enqueueOrRestart(context, source)
        }
        return recovered
    }
}