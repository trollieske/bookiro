package com.shelf.reader.ftp.worker

import android.content.Context
import com.shelf.reader.ftp.data.FtpGraph
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
        val activeServers = graph.transferRepository.activeServerIds()
        val recovered = graph.transferRepository.rehydrateAllActive()
        if (recovered <= 0) return 0

        for (serverId in activeServers) {
            val source = graph.sourceRepository.getSource(serverId) ?: continue
            if (FtpSyncWorker.isRunning(context, serverId)) continue
            FtpSyncWorker.enqueue(context, source)
        }
        return recovered
    }
}