package com.bookrio.calibre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bookrio.calibre.R
import com.bookrio.calibre.client.CalibreErrorKind
import com.bookrio.calibre.data.CalibreSource
import com.bookrio.data.local.entity.CalibreSourceStateEntity
import com.bookrio.calibre.viewmodel.CalibreBrowserEntry
import com.bookrio.calibre.viewmodel.CalibreBrowserState
import com.bookrio.calibre.viewmodel.CalibreBrowserViewModel
import com.bookrio.calibre.viewmodel.CalibreFieldError
import com.bookrio.calibre.viewmodel.CalibreFormState
import com.bookrio.calibre.viewmodel.CalibreViewModel
import com.bookrio.designsystem.theme.OmarchyColors
import com.bookrio.designsystem.theme.ShelfTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreSourcesScreen(
    onBack: () -> Unit,
    onAddSource: () -> Unit,
    onOpenSource: (Long) -> Unit,
    onOpenTransfers: () -> Unit,
    vm: CalibreViewModel = viewModel()
) {
    val sources by vm.sources.collectAsStateCompat()
    var pendingDelete by remember { mutableStateOf<CalibreSource?>(null) }

    Scaffold(
        containerColor = OmarchyColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibre_title), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold, color = OmarchyColors.FgBright) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.calibu_back), tint = OmarchyColors.Fg) }
                },
                actions = {
                    IconButton(onClick = onOpenTransfers) {
                        Icon(Icons.Default.SwapVert, stringResource(R.string.calibu_transfers), tint = OmarchyColors.Fg)
                    }
                    IconButton(onClick = onAddSource) {
                        Icon(Icons.Default.Add, stringResource(R.string.calibu_add_source), tint = OmarchyColors.Fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmarchyColors.Bg)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (sources.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.calibu_no_sources), color = OmarchyColors.Fg, style = ShelfTypography.TitleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.calibu_no_sources_hint), color = OmarchyColors.Dim, style = ShelfTypography.BodySmall)
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sources, key = { it.id }) { source ->
                        CalibreSourceCard(
                            source = source,
                            onClick = { onOpenSource(source.id) },
                            onSync = { vm.syncNow(source.id) },
                            onDelete = { pendingDelete = source }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.calibu_delete_title)) },
            text = { Text(stringResource(R.string.calibu_delete_message, source.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(source.id, deleteQueue = true)
                    pendingDelete = null
                }) { Text(stringResource(R.string.calibu_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.calibu_cancel)) }
            }
        )
    }
}

@Composable
private fun CalibreSourceCard(
    source: CalibreSource,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OmarchyColors.Panel, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = source.statusColor(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.displayName, style = ShelfTypography.BodyLarge, fontWeight = FontWeight.Medium, color = OmarchyColors.FgBright)
            Spacer(Modifier.height(1.dp))
            Text(source.baseUrl, style = ShelfTypography.BodySmall, color = OmarchyColors.Dim)
            if (!source.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(source.lastError, style = ShelfTypography.BodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        IconButton(onClick = onSync) { Icon(Icons.Default.Refresh, stringResource(R.string.calibu_sync_now), tint = OmarchyColors.Dim) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.calibu_delete), tint = OmarchyColors.Dim) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreConnectionScreen(
    editingId: Long,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: CalibreViewModel = viewModel()
) {
    val form by vm.formState.collectAsStateCompat()
    val loaded = remember { mutableStateOf(false) }

    if (editingId > 0L && !loaded.value) {
        loaded.value = true
    }

    Scaffold(
        containerColor = OmarchyColors.Bg,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibu_add_source), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold, color = OmarchyColors.FgBright) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.calibu_back), tint = OmarchyColors.Fg) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmarchyColors.Bg)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = form.displayName,
                onValueChange = vm::updateDisplayName,
                label = { Text(stringResource(R.string.calibu_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = vm::updateBaseUrl,
                label = { Text(stringResource(R.string.calibu_url)) },
                singleLine = true,
                isError = form.fieldError == CalibreFieldError.URL,
                supportingText = { Text(stringResource(R.string.calibu_url_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.username,
                onValueChange = vm::updateUsername,
                label = { Text(stringResource(R.string.calibu_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = vm::updatePassword,
                label = { Text(stringResource(R.string.calibu_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    if (form.hasStoredPassword && form.password.isEmpty()) {
                        Text(stringResource(R.string.calibu_password_keep))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            form.testResult?.let { result ->
                Text(
                    text = if (result.success) stringResource(R.string.calibu_test_ok) else stringResource(R.string.calibu_test_fail),
                    color = if (result.success) OmarchyColors.Accent else MaterialTheme.colorScheme.error,
                    style = ShelfTypography.BodyMedium
                )
            }
            form.fieldError?.let { error ->
                Text(
                    text = stringResource(
                        when (error) {
                            CalibreFieldError.URL -> R.string.calibu_err_url
                            CalibreFieldError.AUTH -> R.string.calibu_err_auth
                            CalibreFieldError.CONNECTION -> R.string.calibu_err_connection
                            CalibreFieldError.UNKNOWN -> R.string.calibu_err_unknown
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = ShelfTypography.BodyMedium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { vm.testConnection() }, enabled = !form.testing) {
                    if (form.testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.calibu_test))
                }
                Button(onClick = { vm.save(onSaved) }, enabled = !form.saving) {
                    Text(stringResource(R.string.calibu_save))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibreBrowserScreen(
    sourceId: Long,
    onBack: () -> Unit,
    onOpenTransfers: () -> Unit,
    vm: CalibreBrowserViewModel = viewModel(factory = calibreBrowserVmFactory(sourceId))
) {
    val state by vm.state.collectAsStateCompat()
    var query by remember { mutableStateOf("") }

    Scaffold(
        containerColor = OmarchyColors.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.source?.displayName ?: stringResource(R.string.calibre_title), style = ShelfTypography.TitleMedium, fontWeight = FontWeight.Bold, color = OmarchyColors.FgBright)
                        Text(state.currentTitle, style = ShelfTypography.BodySmall, color = OmarchyColors.Dim)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!vm.navigateUp()) onBack()
                    }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.calibu_back), tint = OmarchyColors.Fg) }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.calibu_refresh), tint = OmarchyColors.Fg) }
                    IconButton(onClick = onOpenTransfers) { Icon(Icons.Default.SwapVert, stringResource(R.string.calibu_transfers), tint = OmarchyColors.Fg) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OmarchyColors.Bg)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = OmarchyColors.Dim) },
                placeholder = { Text(stringResource(R.string.calibu_search_hint), color = OmarchyColors.Dim) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (state.queuedCount > 0) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LinearProgressIndicator(Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.calibu_queued, state.queuedCount), color = OmarchyColors.Accent, style = ShelfTypography.BodySmall)
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val error = state.error
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorText(error ?: CalibreErrorKind.UNKNOWN), color = MaterialTheme.colorScheme.error, style = ShelfTypography.TitleMedium)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.calibu_retry)) }
                    }
                }
                state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.calibu_empty_folder), color = OmarchyColors.Dim)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(state.entries, key = { it.id }) { entry ->
                        CalibreEntryRow(
                            entry = entry,
                            selected = state.selected.contains(entry.id),
                            onToggle = { vm.toggleSelection(entry.id) },
                            onOpen = { vm.navigate(entry) }
                        )
                    }
                }
            }

            if (state.entries.any { it.download != null }) {
                Row(
                    Modifier.fillMaxWidth().background(OmarchyColors.Panel).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { vm.queueSelected() },
                        enabled = state.selected.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.calibu_queue, state.selected.size))
                    }
                    TextButton(onClick = { vm.queueFolder() }) {
                        Text(stringResource(R.string.calibu_queue_folder))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibreEntryRow(
    entry: CalibreBrowserEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.isNavigation) onOpen() else onToggle() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (entry.isNavigation) {
            Icon(Icons.Default.ChevronRight, null, tint = OmarchyColors.Dim, modifier = Modifier.size(20.dp))
        } else {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = ShelfTypography.BodyLarge, color = OmarchyColors.FgBright)
            val subtitle = buildString {
                if (entry.authors.isNotEmpty()) append(entry.authors.joinToString(", "))
                entry.download?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it.format.extension.uppercase())
                }
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = ShelfTypography.BodySmall, color = OmarchyColors.Dim)
            }
        }
    }
}

@Composable
private fun errorText(kind: CalibreErrorKind): String = stringResource(
    when (kind) {
        CalibreErrorKind.AUTH, CalibreErrorKind.FORBIDDEN -> R.string.calibu_err_auth
        CalibreErrorKind.NETWORK, CalibreErrorKind.TIMEOUT -> R.string.calibu_err_connection
        CalibreErrorKind.PARSE -> R.string.calibu_err_parse
        else -> R.string.calibu_err_unknown
    }
)

@Composable
private fun CalibreSource.statusColor() = when (state) {
    CalibreSourceStateEntity.ACTIVE -> OmarchyColors.Accent
    CalibreSourceStateEntity.NEEDS_AUTH -> MaterialTheme.colorScheme.error
    CalibreSourceStateEntity.CONNECTION_ERROR -> MaterialTheme.colorScheme.error
    CalibreSourceStateEntity.DISABLED -> OmarchyColors.Dim
    else -> OmarchyColors.Dim
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> =
    collectAsState()

fun calibreBrowserVmFactory(sourceId: Long): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                ?: error("Application missing from CreationExtras")
            return CalibreBrowserViewModel(app, sourceId) as T
        }
    }