package com.shelf.reader.ftp.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.transfer.ActiveTransfer

/**
 * Foreground notification for long-running transfers. Precise, not generic:
 * source, current file, n-of-m, percent, speed and ETA, plus Pause/Cancel.
 */
object FtpNotifications {

    const val CHANNEL_ID = "ftp_sync_channel"
    const val NOTIF_ID = 3001

    fun ensureChannel(context: Context) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.ftpu_notif_channel))
                    .setDescription(context.getString(R.string.ftpu_notif_channel_desc))
                    .build()
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun build(
        context: Context,
        serverId: Long,
        sourceName: String,
        active: List<ActiveTransfer>,
        queued: Int,
        running: Int,
        total: Int,
        completed: Int,
        failed: Int
    ): android.app.Notification {
        val current = active.firstOrNull()
        val doneCount = (completed + failed).coerceAtLeast(0)
        val percent = if (total > 0) (doneCount * 100 / total).coerceIn(0, 100) else 0

        val title = if (current != null) {
            context.getString(R.string.ftpu_notif_title_file, sourceName, current.name)
        } else {
            context.getString(R.string.ftpu_notif_sync_title, sourceName)
        }

        val etaText = if (current != null) formatEta(context, current) else ""
        val speedText = if (current != null && current.bytesPerSec > 0) {
            formatSpeed(current.bytesPerSec)
        } else ""

        val text = if (total > 0) {
            listOf(
                context.getString(R.string.ftpu_notif_progress, doneCount, total, percent),
                speedText,
                etaText
            ).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            context.getString(R.string.ftpu_notif_text)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                0,
                context.getString(R.string.ftpu_notif_pause),
                actionIntent(context, FtpNotificationActionReceiver.ACTION_PAUSE, serverId)
            )
            .addAction(
                0,
                context.getString(R.string.ftpu_notif_cancel),
                actionIntent(context, FtpNotificationActionReceiver.ACTION_CANCEL, serverId)
            )

        if (total > 0) {
            builder.setProgress(100, percent, false)
        } else if (running > 0 || queued > 0) {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    private fun actionIntent(context: Context, action: String, serverId: Long): PendingIntent {
        val intent = Intent(context, FtpNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(FtpNotificationActionReceiver.EXTRA_SERVER_ID, serverId)
        }
        return PendingIntent.getBroadcast(
            context,
            (action + serverId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun formatEta(context: Context, transfer: ActiveTransfer): String {
        if (transfer.bytesPerSec <= 0L || transfer.totalBytes <= transfer.downloadedBytes) return ""
        val remaining = transfer.totalBytes - transfer.downloadedBytes
        val seconds = (remaining / transfer.bytesPerSec).toInt()
        return when {
            seconds > 60 -> context.getString(R.string.ftpu_eta_min_sec, seconds / 60, seconds % 60)
            seconds > 0 -> context.getString(R.string.ftpu_eta_seconds, seconds)
            else -> ""
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(java.util.Locale.US, "%.1f MB/s", mb)
        else String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
    }
}