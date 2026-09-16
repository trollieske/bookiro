package com.shelf.reader.ftp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.transfer.ActiveTransfer
import com.shelf.reader.ftp.viewmodel.FtpSourceSummary
import com.shelf.reader.ftp.viewmodel.FtpUiError
import com.shelf.reader.ftp.viewmodel.SourceStatus
import java.util.Locale

@Composable
fun sourceStatusLabel(status: SourceStatus): String = stringResource(
    when (status) {
        SourceStatus.CONNECTED -> R.string.ftpu_state_connected
        SourceStatus.SYNCING -> R.string.ftpu_state_syncing
        SourceStatus.PAUSED -> R.string.ftpu_state_paused
        SourceStatus.RETRYING -> R.string.ftpu_state_retrying
        SourceStatus.NEEDS_AUTH -> R.string.ftpu_state_needs_auth
        SourceStatus.ERROR -> R.string.ftpu_state_error
        SourceStatus.IDLE -> R.string.ftpu_state_idle
        SourceStatus.DISABLED -> R.string.ftpu_state_disabled
    }
)

@Composable
fun sourceStatusColor(status: SourceStatus): Color = when (status) {
    SourceStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    SourceStatus.SYNCING -> MaterialTheme.colorScheme.primary
    SourceStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    SourceStatus.RETRYING -> MaterialTheme.colorScheme.tertiary
    SourceStatus.NEEDS_AUTH -> MaterialTheme.colorScheme.error
    SourceStatus.ERROR -> MaterialTheme.colorScheme.error
    SourceStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    SourceStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun ftpUiErrorText(error: FtpUiError): String = stringResource(
    when (error) {
        FtpUiError.CONNECTION -> R.string.ftpu_err_connection
        FtpUiError.AUTH -> R.string.ftpu_err_auth
        FtpUiError.LISTING -> R.string.ftpu_err_listing
        FtpUiError.UNKNOWN -> R.string.ftpu_err_generic
    }
)

@Composable
fun transferStatusLabel(status: DownloadStatusEntity): String = stringResource(
    when (status) {
        DownloadStatusEntity.COMPLETED -> R.string.ftpu_file_completed
        DownloadStatusEntity.RUNNING, DownloadStatusEntity.VERIFYING, DownloadStatusEntity.IMPORTING ->
            R.string.ftpu_file_running
        DownloadStatusEntity.FAILED -> R.string.ftpu_file_failed
        DownloadStatusEntity.PAUSED, DownloadStatusEntity.PAUSED_BY_USER -> R.string.ftpu_file_paused
        DownloadStatusEntity.RETRYING, DownloadStatusEntity.WAITING_FOR_NETWORK -> R.string.ftpu_file_retrying
        DownloadStatusEntity.CANCELLED -> R.string.ftpu_file_cancelled
        DownloadStatusEntity.QUEUED, DownloadStatusEntity.PENDING -> R.string.ftpu_file_queued
    }
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    val mb = bytesPerSec / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB/s", mb)
    else String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
}

fun formatEta(seconds: Int): String {
    if (seconds <= 0) return ""
    return if (seconds > 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

/**
 * Persistent mini transfer bar. Shown at the bottom of the browser and source
 * details whenever the queue has work; survives navigation because it reads the
 * Room/runtime state, not a screen-local job.
 */
@Composable
fun MiniTransferBar(
    summary: FtpSourceSummary,
    onView: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active: ActiveTransfer? = summary.active.firstOrNull()
    val counts = summary.counts
    val done = counts.completed + counts.failed
    val fraction = if (counts.total > 0) (done.toFloat() / counts.total.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100).toInt()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ftpu_files_progress, done, counts.total, percent),
                    style = ShelfTypography.LabelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (active != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = active.name,
                        style = ShelfTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            TextButton(onClick = onView) {
                Text(stringResource(R.string.ftpu_view), style = ShelfTypography.LabelMedium)
            }
        }
    }
}
