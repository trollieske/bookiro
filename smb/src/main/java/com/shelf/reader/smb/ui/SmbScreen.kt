package com.shelf.reader.smb.ui

import com.shelf.reader.smb.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.shelf.reader.designsystem.theme.ShelfTypography
import com.shelf.reader.smb.client.SmbEntry
import com.shelf.reader.smb.client.SmbEntryType
import com.shelf.reader.smb.viewmodel.SmbSourceSummary
import com.shelf.reader.smb.viewmodel.SmbUiState
import com.shelf.reader.smb.viewmodel.SmbViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbScreen(
    serverId: Long = -1L,
    onBack: () -> Unit = {},
    onImport: (() -> Unit)? = null,
    vm: SmbViewModel = viewModel(factory = defaultSmbVmFactory())
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showSaveDialog by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) {
        if (serverId > 0) {
            vm.loadServer(serverId)
            runCatching { vm.connect() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.smbu_title), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.smbu_back))
                    }
                },
                actions = {
                    if (state.host.isNotBlank()) {
                        IconButton(onClick = { showSaveDialog = true }) {
                            Icon(if (state.activeServerId != null) Icons.Default.Edit else Icons.Default.Save, stringResource(R.string.smbu_save_server))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vm.downloadAndImportSelected()
                        scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.smbu_downloading, state.selected.size)) }
                    },
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text(stringResource(R.string.smbu_download, state.selected.size)) }
                )
            }
        }
    ) { pad ->
        val scrollState = rememberScrollState()
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp)
                .then(if (!state.isConnected) Modifier.verticalScroll(scrollState) else Modifier)
        ) {
            if (state.sources.isNotEmpty() && !state.isConnected) {
                SavedSmbServersPanel(
                    saved = state.sources,
                    activeId = state.activeServerId,
                    onLoad = { vm.loadServer(it) },
                    onConnect = { vm.loadServer(it); vm.connect() },
                    onDelete = { id ->
                        vm.deleteSaved(id)
                        scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.smbu_server_deleted)) }
                    },
                    onSync = { vm.syncNow(it) }
                )
                Spacer(Modifier.height(16.dp))
            }

            SmbServerCard(
                state = state,
                showPassword = showPassword,
                onTogglePasswordVisibility = { showPassword = !showPassword },
                onDisplayNameChange = vm::updateDisplayName,
                onHostChange = vm::updateHost,
                onPortChange = { vm.updatePort(it.toIntOrNull() ?: 445) },
                onShareNameChange = vm::updateShareName,
                onDomainChange = vm::updateDomain,
                onUsernameChange = vm::updateUsername,
                onPasswordChange = vm::updatePassword,
                onSmbVersionChange = vm::updateSmbVersion,
                onEnableEncryptionChange = vm::updateEnableEncryption,
                onConnect = { if (state.isConnected) vm.disconnect() else vm.connect() },
                isLoading = state.isLoading
            )

            if (showSaveDialog) {
                SaveServerDialog(
                    initialName = state.sources.firstOrNull { it.source.id == state.activeServerId }?.source?.displayName
                        ?: state.displayName.ifBlank { state.host },
                    onDismiss = { showSaveDialog = false },
                    onSave = { name ->
                        vm.save(name)
                        showSaveDialog = false
                        scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.smbu_saved)) }
                    }
                )
            }

            Spacer(Modifier.height(20.dp))

            if (state.isConnected) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PathBreadcrumb(currentPath = state.currentPath, onNavigateUp = vm::navigateUp)
                    TextButton(onClick = {
                        vm.downloadAndImportCurrentFolder()
                        scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.smbu_syncing)) }
                    }) { Text(stringResource(R.string.smbu_sync_folder)) }
                }

                val selectedCount = state.selected.size
                if (selectedCount > 0) {
                    Text(
                        "$selectedCount valgt",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (state.isLoading && state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (state.entries.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.smbu_folder_empty), style = ShelfTypography.BodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.entries, key = { it.path }) { entry ->
                            SmbEntryRow(
                                entry = entry,
                                isSelected = entry.path in state.selected,
                                downloadProgress = state.downloading[entry.path],
                                onClick = {
                                    when (entry.type) {
                                        SmbEntryType.FOLDER -> vm.navigateTo(entry)
                                        SmbEntryType.FILE -> vm.toggleSelected(entry.path)
                                        else -> {}
                                    }
                                },
                                onLongClick = { vm.toggleSelected(entry.path) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedSmbServersPanel(
    saved: List<SmbSourceSummary>,
    activeId: Long?,
    onLoad: (Long) -> Unit,
    onConnect: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSync: (Long) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.smbu_saved_servers), style = ShelfTypography.TitleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            saved.forEach { summary ->
                val s = summary.source
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.displayName.ifBlank { "${s.host}/${s.shareName}" },
                            style = ShelfTypography.BodyMedium,
                            fontWeight = if (activeId == s.id) FontWeight.Bold else FontWeight.Medium
                        )
                        Text(
                            "${s.host}:${s.port} · ${s.shareName}",
                            style = ShelfTypography.BodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val active = summary.counts.running + summary.counts.queued + summary.counts.retrying
                        if (active > 0 || summary.counts.failed > 0) {
                            Text(
                                "${summary.counts.running} / ${summary.counts.total} · " +
                                    (if (summary.counts.failed > 0) "${summary.counts.failed} feilet" else "${summary.counts.queued} i kø"),
                                style = ShelfTypography.BodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        s.lastError?.let {
                            Text(it, style = ShelfTypography.BodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row {
                        IconButton(onClick = { onSync(s.id) }) {
                            Icon(Icons.Default.Sync, stringResource(R.string.smbu_sync_folder), tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onConnect(s.id) }) {
                            Icon(Icons.Default.PowerSettingsNew, "Koble til", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onLoad(s.id) }) {
                            Icon(Icons.Default.Edit, "Rediger")
                        }
                        IconButton(onClick = { onDelete(s.id) }) {
                            Icon(Icons.Default.Delete, "Slett", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbServerCard(
    state: SmbUiState,
    showPassword: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onShareNameChange: (String) -> Unit,
    onDomainChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSmbVersionChange: (String) -> Unit,
    onEnableEncryptionChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    isLoading: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.smbu_connection), style = ShelfTypography.TitleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.smbu_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = onHostChange,
                    label = { Text(stringResource(R.string.smbu_host)) },
                    singleLine = true,
                    modifier = Modifier.weight(3f)
                )
                OutlinedTextField(
                    value = state.port.toString(),
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.smbu_port)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.shareName,
                onValueChange = onShareNameChange,
                label = { Text(stringResource(R.string.smbu_share_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.domain,
                onValueChange = onDomainChange,
                label = { Text(stringResource(R.string.smbu_domain)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.smbu_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.smbu_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.smbu_version), Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = state.smbVersion,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().width(140.dp),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf("AUTO", "SMB1", "SMB2", "SMB3").forEach { v ->
                            DropdownMenuItem(text = { Text(v) }, onClick = {
                                onSmbVersionChange(v); expanded = false
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.smbu_encryption), Modifier.weight(1f), style = ShelfTypography.BodyMedium)
                Switch(checked = state.enableEncryption, onCheckedChange = onEnableEncryptionChange)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = !isLoading && state.host.isNotBlank() && state.shareName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isConnected) "Koble fra" else "Koble til")
            }
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(smbErrorText(state.error), color = MaterialTheme.colorScheme.error, style = ShelfTypography.BodySmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmbEntryRow(
    entry: SmbEntry,
    isSelected: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 2.dp else 0.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (entry.type) {
                    SmbEntryType.FOLDER -> Icons.Default.Folder
                    SmbEntryType.FILE -> Icons.Default.Description
                    else -> Icons.Default.HelpOutline
                },
                contentDescription = null,
                tint = when (entry.type) {
                    SmbEntryType.FOLDER -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = ShelfTypography.BodyMedium, fontWeight = FontWeight.Medium)
                if (downloadProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                } else if (entry.type == SmbEntryType.FILE) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatSize(entry.sizeBytes),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, "Valgt", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PathBreadcrumb(currentPath: String, onNavigateUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = onNavigateUp, enabled = currentPath != "/") {
            Icon(Icons.Default.ArrowUpward, "Opp")
        }
        Text(
            if (currentPath.isBlank()) "/" else currentPath,
            style = ShelfTypography.BodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun SaveServerDialog(initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smbu_save_server)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.smbu_server_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.smbu_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.smbu_cancel)) } }
    )
}

@Composable
private fun smbErrorText(error: String?): String = stringResource(
    when (error) {
        "AUTH" -> R.string.smbu_err_auth
        "NOT_FOUND" -> R.string.smbu_err_not_found
        "NETWORK" -> R.string.smbu_err_network
        "TIMEOUT" -> R.string.smbu_err_timeout
        else -> R.string.smbu_err_unknown
    }
)

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

@Composable
fun defaultSmbVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            SmbViewModel(app)
        }
    }
}
