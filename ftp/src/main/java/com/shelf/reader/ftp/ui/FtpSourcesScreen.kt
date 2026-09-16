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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.viewmodel.FtpSourceSummary
import com.shelf.reader.ftp.viewmodel.FtpSourcesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpSourcesScreen(
    onBack: () -> Unit,
    onAddSource: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onBrowse: (Long) -> Unit,
    onOpenTransfers: () -> Unit,
    vm: FtpSourcesViewModel = viewModel(factory = ftpSourcesVmFactory())
) {
    val summaries by vm.summaries.collectAsState()
    var pendingDelete by remember { mutableStateOf<FtpSourceSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.ftpu_sources_title),
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
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.ftpu_transfers))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddSource,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.ftpu_add_source)) }
            )
        }
    ) { pad ->
        if (summaries.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Dns,
                        null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.ftpu_no_sources),
                        style = ShelfTypography.TitleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.ftpu_no_sources_hint),
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
            ) {
                items(summaries, key = { it.source.id }) { summary ->
                    SourceCard(
                        summary = summary,
                        onOpen = { onOpenSource(summary.source.id) },
                        onBrowse = { onBrowse(summary.source.id) },
                        onSync = { vm.syncNow(summary.source.id) },
                        onPause = { vm.pause(summary.source.id) },
                        onDelete = { pendingDelete = summary }
                    )
                }
            }
        }
    }

    pendingDelete?.let { summary ->
        var deleteQueue by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.ftpu_remove_source_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.ftpu_remove_source_msg, summary.source.displayName))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ftpu_remove_keep_files),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = deleteQueue,
                            onCheckedChange = { deleteQueue = it }
                        )
                        Text(stringResource(R.string.ftpu_remove_delete_queue), style = ShelfTypography.BodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeSource(summary.source.id, deleteQueue)
                    pendingDelete = null
                }) { Text(stringResource(R.string.ftpu_remove_source)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.ftpu_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceCard(
    summary: FtpSourceSummary,
    onOpen: () -> Unit,
    onBrowse: () -> Unit,
    onSync: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit
) {
    val source = summary.source
    val status = summary.status
    val active = summary.active.firstOrNull()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Dns,
                    null,
                    tint = sourceStatusColor(status),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        source.displayName.ifBlank { source.host },
                        style = ShelfTypography.TitleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.ftpu_source_host_line, source.protocol.displayName, source.host),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = sourceStatusColor(status).copy(alpha = 0.15f)
                ) {
                    Text(
                        sourceStatusLabel(status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = ShelfTypography.LabelSmall,
                        fontWeight = FontWeight.Bold,
                        color = sourceStatusColor(status)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ftpu_remove_source))
                }
            }

            if (summary.counts.total > 0) {
                Spacer(Modifier.height(10.dp))
                val done = summary.counts.completed + summary.counts.failed
                val fraction = (done.toFloat() / summary.counts.total.toFloat()).coerceIn(0f, 1f)
                val percent = (fraction * 100).toInt()
                Text(
                    stringResource(R.string.ftpu_files_progress, done, summary.counts.total, percent),
                    style = ShelfTypography.LabelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary
                )
                if (active != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.ftpu_current_speed, active.name, formatSpeed(active.bytesPerSec)),
                        style = ShelfTypography.LabelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (summary.hasWork) onPause else onSync,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            summary.hasWork -> stringResource(R.string.ftpu_pause)
                            status == com.shelf.reader.ftp.viewmodel.SourceStatus.PAUSED -> stringResource(R.string.ftpu_resume)
                            else -> stringResource(R.string.ftpu_sync_now)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onBrowse, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ftpu_browse), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun ftpSourcesVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer { FtpSourcesViewModel(app) }
    }
}
