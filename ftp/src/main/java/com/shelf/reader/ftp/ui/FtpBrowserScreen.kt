package com.shelf.reader.ftp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.ftp.R
import com.shelf.reader.ftp.client.FtpEntry
import com.shelf.reader.ftp.client.FtpEntryType
import com.shelf.reader.ftp.viewmodel.FtpBrowserViewModel
import com.shelf.reader.ftp.viewmodel.FtpSort
import com.shelf.reader.ftp.viewmodel.FtpSourceSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpBrowserScreen(
    serverId: Long,
    onBack: () -> Unit,
    onOpenTransfers: () -> Unit,
    vm: FtpBrowserViewModel = viewModel(factory = ftpBrowserVmFactory(serverId))
) {
    val state by vm.state.collectAsState()
    val summary by vm.summary.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.sourceName.ifBlank { stringResource(R.string.ftpu_browser_title) },
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            state.path,
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ftpu_back))
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ftpu_refresh))
                    }
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.ftpu_transfers))
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Search + sort
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                label = { Text(stringResource(R.string.ftpu_search_files)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SortChip(stringResource(R.string.ftpu_sort_name), state.sort == FtpSort.NAME) { vm.setSort(FtpSort.NAME) }
                SortChip(stringResource(R.string.ftpu_sort_date), state.sort == FtpSort.DATE) { vm.setSort(FtpSort.DATE) }
                SortChip(stringResource(R.string.ftpu_sort_size), state.sort == FtpSort.SIZE) { vm.setSort(FtpSort.SIZE) }
                SortChip(stringResource(R.string.ftpu_sort_type), state.sort == FtpSort.TYPE) { vm.setSort(FtpSort.TYPE) }
            }

            // Breadcrumbs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.breadcrumbs.forEach { (label, path) ->
                    AssistChip(
                        onClick = { vm.navigateTo(path) },
                        label = { Text(label, style = ShelfTypography.LabelSmall) }
                    )
                }
            }

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null -> {
                    BrowserError(state.error!!, vm::refresh)
                }
                state.visibleEntries.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.ftpu_empty_folder),
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.visibleEntries, key = { it.path }) { entry ->
                            BrowserEntryRow(
                                entry = entry,
                                selected = entry.name in state.selected,
                                status = state.taskStatus[entry.path],
                                onClick = {
                                    if (entry.type == FtpEntryType.FOLDER) vm.navigateTo(entry.path)
                                    else vm.toggleSelect(entry)
                                },
                                onDownloadNext = { vm.prioritize(entry) }
                            )
                        }
                    }
                }
            }

            // Bottom actions
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth()) {
                    if (state.selected.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.ftpu_selected_count, state.selected.size),
                                style = ShelfTypography.LabelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = vm::clearSelection) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ftpu_cancel))
                            }
                            Button(onClick = { vm.syncSelected() }) {
                                Text(stringResource(R.string.ftpu_download_selected))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { vm.syncFolder() },
                            enabled = !state.folderBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (state.folderBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Sync, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ftpu_sync_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    summary?.let { current ->
                        if (current.counts.total > 0) {
                            MiniTransferBar(summary = current, onView = onOpenTransfers)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserError(error: com.shelf.reader.ftp.viewmodel.FtpUiError, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                ftpUiErrorText(error),
                style = ShelfTypography.BodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.ftpu_retry)) }
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = ShelfTypography.LabelSmall) }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserEntryRow(
    entry: FtpEntry,
    selected: Boolean,
    status: DownloadStatusEntity?,
    onClick: () -> Unit,
    onDownloadNext: () -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDownloadNext),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.type != FtpEntryType.FOLDER) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            Icon(
                if (entry.type == FtpEntryType.FOLDER) Icons.Default.Folder else Icons.Default.Description,
                null,
                tint = if (entry.type == FtpEntryType.FOLDER) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = ShelfTypography.BodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.type != FtpEntryType.FOLDER) {
                        Text(
                            formatBytes(entry.sizeBytes),
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    status?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            transferStatusLabel(it),
                            style = ShelfTypography.LabelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            if (entry.type == FtpEntryType.FOLDER) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.ftpu_open_folder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (selected) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ftpBrowserVmFactory(serverId: Long): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer { FtpBrowserViewModel(app, serverId) }
    }
}
