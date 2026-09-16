package com.shelf.reader.ftp.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shelf.reader.ftp.data.FtpGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the Pause/Cancel actions on the sync notification.
 *
 * Pause writes PAUSED_BY_USER to Room *before* cancelling the worker, so the
 * transfer stops honestly instead of being silently requeued by rehydration.
 */
class FtpNotificationActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
        if (serverId <= 0L) return
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_CANCEL -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        FtpGraph.get(appContext).transferRepository.cancel(serverId)
                    } finally {
                        FtpSyncWorker.cancel(appContext, serverId)
                        pending.finish()
                    }
                }
            }
            ACTION_PAUSE -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        FtpGraph.get(appContext).transferRepository.pause(serverId)
                    } finally {
                        FtpSyncWorker.cancel(appContext, serverId)
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.shelf.reader.ftp.action.PAUSE_SYNC"
        const val ACTION_CANCEL = "com.shelf.reader.ftp.action.CANCEL_SYNC"
        const val EXTRA_SERVER_ID = "serverId"
    }
}