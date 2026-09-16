package com.shelf.reader.ftp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.viewmodel.FtpTransfersViewModel
import com.shelf.reader.ftp.viewmodel.TransferRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpTransfersScreen(
    onBack: () -> Unit,
    vm: FtpTransfersViewModel = viewModel(factory = ftpTransfersVmFactory())
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.ftpu_transfers_title),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ftpu_back))
                    }
                },
                actions = {
                    if (state.syncing.isNotEmpty()) {
                        IconButton(onClick = vm::pauseAll) {
                            Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.ftpu_pause))
                        }
                    }
                    if (state.completed.isNotEmpty()) {
                        IconButton(onClick = vm::clearCompleted) {
                            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.ftpu_clear_completed))
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (!state.hasAnything) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.SwapVert,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ftpu_no_transfers),
                        style = ShelfTypography.TitleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 32.dp)
        ) {
            if (state.syncing.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ftpu_section_syncing)) }
                items(state.syncing, key = { it.task.id }) { row ->
                    SyncingCard(
                        row = row,
                        onPause = { row.task.serverId?.let { id -> vm.pauseServer(id) } },
                        onCancel = { vm.cancelTask(row.task.id) }
                    )
                }
            }

            if (state.queued.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ftpu_section_queued)) }
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.ftpu_queued_files, state.queued.size),
                                style = ShelfTypography.BodyMedium
                            )
                        }
                    }
                }
            }

            if (state.failed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ftpu_section_failed)) }
                items(state.failed, key = { it.id }) { task ->
                    FailedCard(
                        task = task,
                        onRetry = { vm.retry(task.id) },
                        onDetails = { vm.prioritize(task.id) }
                    )
                }
                item {
                    OutlinedButton(onClick = vm::retryAllFailed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ftpu_retry_all))
                    }
                }
            }

            if (state.completed.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ftpu_section_completed)) }
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                stringResource(R.string.ftpu_completed_summary, state.completed.size, formatBytes(state.completedBytes)),
                                style = ShelfTypography.BodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = ShelfTypography.LabelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SyncingCard(row: TransferRow, onPause: () -> Unit, onCancel: () -> Unit) {
    val task = row.task
    val active = row.active
    val total = if (active != null && active.totalBytes > 0) active.totalBytes else task.sizeBytes
    val downloaded = active?.downloadedBytes ?: task.downloadedBytes
    val fraction = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100).toInt()
    val speed = active?.bytesPerSec ?: task.bytesPerSec

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(task.remoteName, style = ShelfTypography.BodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(formatBytes(downloaded))
                    append(" / ")
                    append(formatBytes(total))
                    append(" · ")
                    append(percent)
                    append("%")
                    if (speed > 0) {
                        append(" · ")
                        append(formatSpeed(speed))
                    }
                },
                style = ShelfTypography.LabelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPause) { Text(stringResource(R.string.ftpu_pause)) }
                TextButton(
                    onClick = onCancel,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Cancel, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ftpu_cancel_label))
                }
            }
        }
    }
}

@Composable
private fun FailedCard(task: DownloadTaskEntity, onRetry: () -> Unit, onDetails: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Error, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(task.remoteName, style = ShelfTypography.BodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            task.errorMessage?.let { message ->
                Spacer(Modifier.height(4.dp))
                Text(message, style = ShelfTypography.LabelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ftpu_retry))
                }
                TextButton(onClick = onDetails) { Text(stringResource(R.string.ftpu_download_next)) }
            }
        }
    }
}

@Composable
fun ftpTransfersVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer { FtpTransfersViewModel(app) }
    }
}
