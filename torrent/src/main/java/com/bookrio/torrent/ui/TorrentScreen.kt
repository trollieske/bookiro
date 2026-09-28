package com.bookrio.torrent.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bookrio.torrent.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.TorrentDownloadEntity
import com.bookrio.data.local.entity.TorrentSeedPolicyEntity
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.torrent.engine.TrackerDiagnostic
import com.bookrio.torrent.engine.TrackerState
import com.bookrio.torrent.engine.TorrentRuntimeStats
import com.bookrio.torrent.viewmodel.TorrentUiState
import com.bookrio.torrent.viewmodel.TorrentViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(
    onBack: () -> Unit = {},
    vm: TorrentViewModel = viewModel(factory = defaultTorrentVmFactory())
) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var showAddDialog by remember { mutableStateOf<AddKind?>(null) }

    LaunchedEffect(Unit) {
        vm.toastEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.addTorrentFile(uri)
        }
    }

    var activeFolderPickerTorrentId by remember { mutableStateOf<Long?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val torrentId = activeFolderPickerTorrentId
        if (uri != null && torrentId != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.importCustomTorrentFolder(torrentId, uri)
        }
        activeFolderPickerTorrentId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.toru_title), style = ShelfTypography.HeadlineSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.toru_back))
                    }
                },
                actions = {
                    if (state.activeCount > 0) {
                        IconButton(onClick = { vm.pauseAll() }) {
                            Icon(Icons.Default.Pause, "Pause alle", tint = MaterialTheme.colorScheme.tertiary)
                        }
                        IconButton(onClick = { vm.resumeAll() }) {
                            Icon(Icons.Default.PlayArrow, "Fortsett alle", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = AddKind.FILE },
                    icon = { Icon(Icons.Default.AttachFile, null) },
                    text = { Text(stringResource(R.string.toru_torrent_file)) }
                )
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = AddKind.MAGNET },
                    icon = { Icon(Icons.Default.AddLink, null) },
                    text = { Text(stringResource(R.string.toru_magnet)) }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {

            val openSearches = remember {
                listOf(
                    "Internet Archive" to "https://archive.org/search?query=%s&and[]=mediatype%3A%22texts%22",
                    "Libgen" to "https://libgen.is/search.php?req=%s",
                    "Standard Ebooks (OPDS)" to "https://standardebooks.org/ebooks/?query=%s",
                    "Project Gutenberg" to "https://www.gutenberg.org/ebooks/search/?query=%s"
                )
            }
            var searchQuery by rememberSaveable { mutableStateOf("") }
            var activeFilter by rememberSaveable { mutableStateOf(0) }
            val filterLabels = listOf("Alle", "Aktive", "Ferdig", "Feilet")

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.toru_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )
            Spacer(Modifier.height(10.dp))

            if (searchQuery.isNotBlank() || state.downloads.isEmpty()) {
                Text(
                    "Forhåndsinnstilte kilder",
                    style = ShelfTypography.BodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    openSearches.forEach { (label, url) ->
                        val final = if (searchQuery.isNotBlank()) url.replace("%s", java.net.URLEncoder.encode(searchQuery, "UTF-8")) else url
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(ctx.getString(R.string.toru_opening, label))
                                }
                                runCatching {
                                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(final))
                                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(i)
                                }
                            },
                            label = { Text(label) },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                filterLabels.forEachIndexed { idx, name ->
                    FilterChip(
                        selected = activeFilter == idx,
                        onClick = { activeFilter = idx },
                        label = { Text(name) },
                        leadingIcon = if (activeFilter == idx) {
                            { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val filtered = remember(state.downloads, activeFilter, searchQuery) {
                state.downloads.filter { dl ->
                    val byFilter = when (activeFilter) {
                        1 -> dl.status == DownloadStatusEntity.RUNNING || dl.status == DownloadStatusEntity.PAUSED
                        2 -> dl.status == DownloadStatusEntity.COMPLETED
                        3 -> dl.status == DownloadStatusEntity.FAILED
                        else -> true
                    }
                    val byQuery = searchQuery.isBlank() ||
                        (dl.displayName ?: "").contains(searchQuery, ignoreCase = true) ||
                        (dl.infoHash ?: "").contains(searchQuery, ignoreCase = true)
                    byFilter && byQuery
                }
            }

            if (state.totalDownloadSpeed > 0 || state.totalUploadSpeed > 0) {
                SpeedCard(
                    dl = state.totalDownloadSpeed,
                    ul = state.totalUploadSpeed,
                    activeCount = state.activeCount
                )
                Spacer(Modifier.height(12.dp))
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Downloading,
                            null,
                            Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            if (state.downloads.isEmpty()) "Ingen torrent-nedlastinger"
                            else "Ingen resultater i filteret/for søket ditt",
                            style = ShelfTypography.TitleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.downloads.isEmpty()) "Trykk på Magnet- eller .torrent-knappen for å begynne, eller bruk et forslag nedenfor."
                            else "Prøv et annet filter, eller tøm søkefeltet.",
                            style = ShelfTypography.BodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    "${filtered.size} av ${state.downloads.size} nedlastinger",
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered, key = { it.id }) { dl ->
                        TorrentCard(
                            dl = dl,
                            stats = state.activeStats[dl.id],
                            onStart = { vm.startDownload(dl.id) },
                            onPause = { vm.pauseDownload(dl.id) },
                            onResume = { vm.resumeDownload(dl.id) },
                            onCancel = { vm.cancelDownload(dl.id) },
                            onDelete = {
                                vm.deleteDownload(dl.id, withFiles = false)
                                scope.launch { snackbarHostState.showSnackbar(ctx.getString(R.string.toru_removed)) }
                            },
                            onReimport = { id -> vm.reimportTorrent(id) },
                            onReannounce = { id -> vm.reannounce(id) },
                            onSetSeedPolicy = { id, policy -> vm.setSeedPolicy(id, policy) },
                            onPickFolder = { id ->
                                activeFolderPickerTorrentId = id
                                folderPicker.launch(null)
                            }
                        )
                    }
                }
            }
        }
    }

    when (showAddDialog) {
        AddKind.MAGNET -> {
            AddMagnetDialog(
                initial = state.magnetInput.ifBlank { getClipboardMagnet(ctx) },
                onDismiss = { showAddDialog = null },
                onSubmit = { magnet ->
                    showAddDialog = null
                    vm.updateMagnetInput("")
                    if (magnet.isNotBlank()) vm.addMagnet(magnet)
                }
            )
        }
        AddKind.FILE -> {
            showAddDialog = null
            filePicker.launch(arrayOf("application/x-bittorrent", "*/*"))
        }
        null -> {}
    }
}

private enum class AddKind { MAGNET, FILE }

@Composable
private fun SpeedCard(dl: Long, ul: Long, activeCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowDownward, "Nedlastning", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(formatBps(dl), style = ShelfTypography.TitleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ArrowUpward, "Opplasting", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(4.dp))
                    Text(formatBps(ul), style = ShelfTypography.BodyMedium, fontWeight = FontWeight.Medium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.toru_active_count, activeCount), style = ShelfTypography.HeadlineMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.toru_downloads_word), style = ShelfTypography.BodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun TorrentCard(
    dl: TorrentDownloadEntity,
    stats: TorrentRuntimeStats?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onReimport: (Long) -> Unit,
    onReannounce: (Long) -> Unit,
    onSetSeedPolicy: (Long, TorrentSeedPolicyEntity) -> Unit,
    onPickFolder: (Long) -> Unit
) {
    val progress = stats?.progressPercent ?: dl.progressPercent
    val dlBytes = stats?.downloadedBytes ?: dl.downloadedBytes
    val totalBytes = stats?.totalBytes.takeIf { it != null && it > 0 } ?: dl.totalSizeBytes
    val dlSpeed = stats?.downloadSpeedBps ?: dl.downloadSpeedBps
    val seeds = stats?.seedsConnected ?: dl.seedsConnected
    val peers = stats?.peersConnected ?: dl.peersConnected
    val etaSec = stats?.etaSeconds ?: dl.etaSeconds

    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        dl.displayName ?: "Torrent ${dl.id}",
                        style = ShelfTypography.BodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${if (dl.status == DownloadStatusEntity.COMPLETED) "Fullført · Seeder (Aktiv)" else statusLabel(dl.status)} · ${formatSize(dlBytes)} / ${formatSize(totalBytes)}",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "↓ ${formatBps(dlSpeed)} · ⤒ Seeds: $seeds · ⤓ Peers: $peers" +
                            (if (etaSec != null && dl.status != DownloadStatusEntity.COMPLETED) " · ${formatEta(etaSec)}" else ""),
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    val trStatus = stats?.trackerStatus ?: "Søker..."
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tracker: $trStatus",
                        style = ShelfTypography.BodySmall,
                        color = if (trStatus.contains("feil") || trStatus.contains("error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "Flere")
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                val infoHash = dl.infoHash
                if (infoHash != null) {
                    Text(
                        "Hash: ${infoHash.take(16)}…",
                        style = ShelfTypography.BodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "Lagret: ${dl.savePath}",
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                TrackerDetailsSection(
                    dl = dl,
                    stats = stats,
                    onReannounce = { onReannounce(dl.id) },
                    onSetSeedPolicy = { policy -> onSetSeedPolicy(dl.id, policy) }
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (dl.status == DownloadStatusEntity.COMPLETED || dl.status == DownloadStatusEntity.RUNNING) {
                        Button(
                            onClick = { onReimport(dl.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.LibraryAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.toru_import))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onPickFolder(dl.id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.toru_choose_folder), maxLines = 1)
                        }

                        if (dl.status == DownloadStatusEntity.PENDING) {
                            Button(onClick = onStart) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.toru_start))
                            }
                        } else if (dl.status == DownloadStatusEntity.RUNNING) {
                            OutlinedButton(onClick = onPause) {
                                Icon(Icons.Default.Pause, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.toru_pause))
                            }
                        } else if (dl.status == DownloadStatusEntity.PAUSED) {
                            Button(onClick = onResume) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.toru_resume))
                            }
                        }

                        OutlinedButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.toru_delete), color = MaterialTheme.colorScheme.error, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackerDetailsSection(
    dl: TorrentDownloadEntity,
    stats: TorrentRuntimeStats?,
    onReannounce: () -> Unit,
    onSetSeedPolicy: (TorrentSeedPolicyEntity) -> Unit
) {
    val isPrivate = stats?.isPrivate ?: false
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.toru_connection_details),
            style = ShelfTypography.TitleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.toru_privacy) + ": " +
                stringResource(if (isPrivate) R.string.toru_private else R.string.toru_public),
            style = ShelfTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val dht = stats?.dhtEnabled ?: !isPrivate
        val pex = stats?.pexEnabled ?: !isPrivate
        val lsd = stats?.lsdEnabled ?: !isPrivate
        val onLabel = stringResource(R.string.toru_on)
        val offLabel = stringResource(R.string.toru_off)
        Text(
            listOf(
                stringResource(R.string.toru_dht) to dht,
                stringResource(R.string.toru_pex) to pex,
                stringResource(R.string.toru_lsd) to lsd
            ).joinToString(" · ") { (name, on) -> "$name: " + if (on) onLabel else offLabel },
            style = ShelfTypography.BodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val trackers = stats?.trackers.orEmpty()
        if (trackers.isEmpty()) {
            Text(
                stringResource(R.string.toru_no_trackers),
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            trackers.forEach { tracker -> TrackerRow(tracker) }
        }

        OutlinedButton(onClick = onReannounce) {
            Text(stringResource(R.string.toru_reannounce))
        }

        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.toru_seed_policy),
            style = ShelfTypography.TitleSmall,
            fontWeight = FontWeight.SemiBold
        )
        val current = dl.seedPolicy ?: TorrentSeedPolicyEntity.SEED_UNTIL_STOPPED
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            SeedPolicyChip(current, TorrentSeedPolicyEntity.STOP_WHEN_DOWNLOADED, R.string.toru_seed_stop, onSetSeedPolicy)
            SeedPolicyChip(current, TorrentSeedPolicyEntity.SEED_UNTIL_STOPPED, R.string.toru_seed_until_stopped, onSetSeedPolicy)
            SeedPolicyChip(current, TorrentSeedPolicyEntity.SEED_WHILE_ACTIVE, R.string.toru_seed_while_active, onSetSeedPolicy)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeedPolicyChip(
    current: TorrentSeedPolicyEntity,
    value: TorrentSeedPolicyEntity,
    labelRes: Int,
    onSet: (TorrentSeedPolicyEntity) -> Unit
) {
    FilterChip(
        selected = current == value,
        onClick = { onSet(value) },
        label = { Text(stringResource(labelRes)) }
    )
}

@Composable
private fun TrackerRow(tracker: TrackerDiagnostic) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(trackerStateColor(tracker.state), RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tracker.url,
                style = ShelfTypography.BodySmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            stringResource(trackerStateLabel(tracker.state)) +
                (tracker.reason?.let { " – $it" } ?: ""),
            style = ShelfTypography.BodySmall,
            color = trackerStateColor(tracker.state)
        )
        tracker.peers?.let {
            Text(
                stringResource(R.string.toru_peers_from_tracker, it),
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        tracker.lastAttemptAt?.let {
            Text(
                stringResource(R.string.toru_last_attempt, formatAgo(it)),
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun trackerStateLabel(state: TrackerState): Int = when (state) {
    TrackerState.OK -> R.string.toru_tr_ok
    TrackerState.ANNOUNCING -> R.string.toru_tr_announcing
    TrackerState.AUTH_REJECTED -> R.string.toru_tr_auth
    TrackerState.POLICY_REJECTED -> R.string.toru_tr_policy
    TrackerState.TLS_ERROR -> R.string.toru_tr_tls
    TrackerState.TIMEOUT -> R.string.toru_tr_timeout
    TrackerState.DNS_ERROR -> R.string.toru_tr_dns
    TrackerState.WARNING -> R.string.toru_tr_warning
    TrackerState.ERROR -> R.string.toru_tr_error
}

@Composable
private fun trackerStateColor(state: TrackerState) = when (state) {
    TrackerState.OK -> MaterialTheme.colorScheme.primary
    TrackerState.ANNOUNCING -> MaterialTheme.colorScheme.onSurfaceVariant
    TrackerState.WARNING -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

private fun formatAgo(epochMs: Long): String {
    val seconds = ((System.currentTimeMillis() - epochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        else -> "${seconds / 3600}t"
    }
}

@Composable
private fun AddMagnetDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.toru_add_magnet_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.toru_magnet_placeholder)) },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    minLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lim inn magnet-lenken du kopierte fra torrent-nettsiden din",
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(value) },
                enabled = value.trim().startsWith("magnet:")
            ) { Text(stringResource(R.string.toru_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.toru_cancel)) } }
    )
}

private fun statusLabel(status: DownloadStatusEntity): String = when (status) {
    DownloadStatusEntity.PENDING -> "Venter"
    DownloadStatusEntity.RUNNING -> "Laster ned"
    DownloadStatusEntity.PAUSED -> "Pause"
    DownloadStatusEntity.COMPLETED -> "Ferdig"
    DownloadStatusEntity.FAILED -> "Feilet"
    DownloadStatusEntity.CANCELLED -> "Avbrutt"
    else -> "Venter"
}

private fun formatBps(bps: Long): String {
    if (bps <= 0) return "0 B/s"
    val kb = bps / 1024.0
    if (kb < 1024) return "%.1f KB/s".format(kb)
    val mb = kb / 1024.0
    return "%.2f MB/s".format(mb)
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "–"
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    if (m < 60) return "${m}m ${seconds % 60}s"
    val h = m / 60
    return "${h}t ${m % 60}m"
}

private fun getClipboardMagnet(context: android.content.Context): String {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    val text = clip.getItemAt(0).text?.toString() ?: return ""
    return if (text.startsWith("magnet:")) text else ""
}

@Composable
fun defaultTorrentVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    return viewModelFactory {
        initializer {
            TorrentViewModel(app)
        }
    }
}
