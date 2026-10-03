package com.bookrio.ftp.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.ftp.R
import com.bookrio.ftp.viewmodel.FtpSourceDetailsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpSourceDetailsScreen(
    serverId: Long,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onEdit: () -> Unit,
    onOpenTransfers: () -> Unit,
    vm: FtpSourceDetailsViewModel = viewModel(factory = ftpSourceDetailsVmFactory(serverId))
) {
    val summary by vm.summary.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        summary?.source?.displayName ?: stringResource(R.string.ftpu_source_details_title),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ftpu_back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.ftpu_edit_connection))
                    }
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.ftpu_transfers))
                    }
                }
            )
        },
        bottomBar = {
            summary?.let { current ->
                if (current.counts.total > 0) {
                    MiniTransferBar(summary = current, onView = onOpenTransfers)
                }
            }
        }
    ) { pad ->
        val current = summary
        if (current == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            sourceStatusLabel(current.status),
                            style = ShelfTypography.LabelMedium,
                            fontWeight = FontWeight.Bold,
                            color = sourceStatusColor(current.status)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            current.source.protocol.displayName,
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val preparing = current.preparing
                    if (preparing != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (preparing.scannedFiles > 0) {
                                    stringResource(R.string.ftpu_preparing_scan, preparing.scannedFiles)
                                } else {
                                    stringResource(R.string.ftpu_preparing_start)
                                },
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    StatRow(
                        stringResource(R.string.ftpu_last_sync),
                        current.source.lastSyncAt?.let { formatTime(it) } ?: stringResource(R.string.ftpu_never)
                    )
                    if (current.counts.total > 0) {
                        val done = current.counts.completed + current.counts.failed
                        val percent = (done * 100 / current.counts.total).coerceIn(0, 100)
                        StatRow(
                            stringResource(R.string.ftpu_transfer_label),
                            stringResource(R.string.ftpu_transfer_summary, done, current.counts.total, percent)
                        )
                    }
                    val active = current.active.firstOrNull()
                    if (active != null) {
                        StatRow(stringResource(R.string.ftpu_current_label), active.name)
                        StatRow(stringResource(R.string.ftpu_speed_label), formatSpeed(active.bytesPerSec))
                    }
                    StatRow(stringResource(R.string.ftpu_queued_label), current.counts.queued.toString())
                    StatRow(stringResource(R.string.ftpu_failed_label), current.counts.failed.toString())
                    StatRow(stringResource(R.string.ftpu_start_folder_label), current.source.basePath)
                }
            }

            Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ftpu_browse_files))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::syncNow, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ftpu_sync_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { if (current.hasWork) vm.pause() else vm.syncNow() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (current.hasWork) stringResource(R.string.ftpu_pause)
                        else stringResource(R.string.ftpu_resume),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ftpu_sync_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = onOpenTransfers, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.SwapVert, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ftpu_view_transfers), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            OutlinedButton(
                onClick = { showRemove = true },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ftpu_remove_source))
            }
        }
    }

    if (showSettings && summary != null) {
        SyncSettingsDialog(
            wifiOnly = summary!!.source.wifiOnly,
            chargingOnly = summary!!.source.chargingOnly,
            concurrency = summary!!.source.concurrencyOverride,
            syncEnabled = summary!!.source.syncEnabled,
            onDismiss = { showSettings = false },
            onSave = { wifi, charging, concurrency, syncEnabled ->
                vm.setPolicy(wifi, charging, concurrency)
                vm.setSyncEnabled(syncEnabled)
                showSettings = false
            }
        )
    }

    if (showRemove) {
        var deleteQueue by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text(stringResource(R.string.ftpu_remove_source_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.ftpu_remove_source_msg, summary?.source?.displayName ?: ""))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ftpu_remove_keep_files),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteQueue, onCheckedChange = { deleteQueue = it })
                        Text(stringResource(R.string.ftpu_remove_delete_queue), style = ShelfTypography.BodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.remove(deleteQueue)
                    showRemove = false
                    onBack()
                }) { Text(stringResource(R.string.ftpu_remove_source)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.ftpu_cancel)) }
            }
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = ShelfTypography.LabelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = ShelfTypography.LabelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SyncSettingsDialog(
    wifiOnly: Boolean,
    chargingOnly: Boolean,
    concurrency: Int,
    syncEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (Boolean, Boolean, Int, Boolean) -> Unit
) {
    var wifi by remember { mutableStateOf(wifiOnly) }
    var charging by remember { mutableStateOf(chargingOnly) }
    var concurrencyValue by remember { mutableIntStateOf(concurrency) }
    var enabled by remember { mutableStateOf(syncEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ftpu_sync_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ftpu_auto_sync), modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ftpu_policy_wifi), modifier = Modifier.weight(1f))
                    Switch(checked = wifi, onCheckedChange = { wifi = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ftpu_policy_charging), modifier = Modifier.weight(1f))
                    Switch(checked = charging, onCheckedChange = { charging = it })
                }
                Text(stringResource(R.string.ftpu_concurrency_title), style = ShelfTypography.LabelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = concurrencyValue == 0,
                        onClick = { concurrencyValue = 0 },
                        label = { Text(stringResource(R.string.ftpu_concurrency_auto), style = ShelfTypography.LabelSmall) }
                    )
                    (1..6).forEach { value ->
                        FilterChip(
                            selected = concurrencyValue == value,
                            onClick = { concurrencyValue = value },
                            label = { Text(value.toString(), style = ShelfTypography.LabelSmall) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.ftpu_concurrency_hint),
                    style = ShelfTypography.LabelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(wifi, charging, concurrencyValue, enabled) }) {
                Text(stringResource(R.string.ftpu_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ftpu_cancel)) }
        }
    )
}

private fun formatTime(epochMillis: Long): String {
    return runCatching {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }.getOrDefault("")
}

@Composable
fun ftpSourceDetailsVmFactory(serverId: Long): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer { FtpSourceDetailsViewModel(app, serverId) }
    }
}
