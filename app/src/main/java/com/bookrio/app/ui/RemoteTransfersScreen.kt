package com.bookrio.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bookrio.R
import com.bookrio.calibre.data.CalibreGraph
import com.bookrio.calibre.data.CalibreSourceRepository
import com.bookrio.calibre.worker.CalibreSyncWorker
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.DownloadTaskEntity
import com.bookrio.data.local.entity.RemoteTaskSource
import com.bookrio.designsystem.theme.ShelfTypography
import com.bookrio.ftp.data.FtpGraph
import com.bookrio.ftp.worker.FtpSyncWorker
import com.bookrio.smb.data.SmbGraph
import com.bookrio.smb.data.SmbSourceRepository
import com.bookrio.smb.worker.SmbSyncWorker
import com.bookrio.webdav.data.WebdavGraph
import com.bookrio.webdav.data.WebdavSourceRepository
import com.bookrio.webdav.worker.WebdavSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RemoteTransferRow(
    val task: DownloadTaskEntity,
    val sourceLabel: String
) {
    val progress: Float
        get() = if (task.sizeBytes > 0) {
            (task.downloadedBytes.toFloat() / task.sizeBytes).coerceIn(0f, 1f)
        } else 0f
}

data class RemoteTransfersUiState(
    val syncing: List<RemoteTransferRow> = emptyList(),
    val queued: List<RemoteTransferRow> = emptyList(),
    val failed: List<RemoteTransferRow> = emptyList(),
    val completed: List<RemoteTransferRow> = emptyList()
)

/**
 * Global Transfers screen. It observes Room only, so an active transfer stays
 * visible from anywhere in the app and after a browser was closed.
 */
class RemoteTransfersViewModel(application: Application) : AndroidViewModel(application) {

    private val db = ShelfDatabase.getInstance(application)
    private val ftpGraph = FtpGraph.get(application)
    private val smbGraph = SmbGraph.get(application)
    private val webdavGraph = WebdavGraph.get(application)
    private val calibreGraph = CalibreGraph.get(application)

    val state: StateFlow<RemoteTransfersUiState> = combine(
        db.downloadTaskDao().observeAll(),
        db.ftpServerDao().observeAll(),
        db.smbServerDao().observeAll(),
        db.webdavServerDao().observeAll(),
        db.calibreServerDao().observeAll()
    ) { tasks, ftp, smb, webdav, calibre ->
        val labels = buildMap {
            ftp.forEach { put(RemoteTaskSource.ref(RemoteTaskSource.KIND_FTP, it.id), it.displayName) }
            smb.forEach { put(RemoteTaskSource.ref(SmbSourceRepository.KIND, it.id), it.displayName) }
            webdav.forEach { put(RemoteTaskSource.ref(WebdavSourceRepository.KIND, it.id), it.displayName) }
            calibre.forEach { put(RemoteTaskSource.ref(CalibreSourceRepository.KIND, it.id), it.displayName) }
        }
        fun labelOf(task: DownloadTaskEntity): String {
            val ref = task.sourceRef
                ?: task.serverId?.let { RemoteTaskSource.ref(RemoteTaskSource.KIND_FTP, it) }
            val name = ref?.let { labels[it] }
            val kind = task.sourceKind?.takeIf { it != "LEGACY" } ?: "FTP"
            return if (name.isNullOrBlank()) kind else "$kind · $name"
        }
        val rows = tasks.map { RemoteTransferRow(it, labelOf(it)) }
        RemoteTransfersUiState(
            syncing = rows.filter {
                it.task.status == DownloadStatusEntity.RUNNING ||
                    it.task.status == DownloadStatusEntity.VERIFYING ||
                    it.task.status == DownloadStatusEntity.IMPORTING
            },
            queued = rows.filter {
                it.task.status == DownloadStatusEntity.QUEUED || it.task.status == DownloadStatusEntity.PENDING ||
                    it.task.status == DownloadStatusEntity.RETRYING ||
                    it.task.status == DownloadStatusEntity.WAITING_FOR_NETWORK ||
                    it.task.status == DownloadStatusEntity.PAUSED ||
                    it.task.status == DownloadStatusEntity.PAUSED_BY_USER
            },
            failed = rows.filter { it.task.status == DownloadStatusEntity.FAILED },
            completed = rows.filter { it.task.status == DownloadStatusEntity.COMPLETED }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteTransfersUiState())

    fun cancelTask(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        db.downloadTaskDao().cancel(taskId)
    }

    fun retry(task: DownloadTaskEntity) = viewModelScope.launch(Dispatchers.IO) {
        requeueAndRun(task)
    }

    fun pause(task: DownloadTaskEntity) = viewModelScope.launch(Dispatchers.IO) {
        val kind = task.sourceKind ?: RemoteTaskSource.KIND_FTP
        val ref = task.sourceRef ?: task.serverId?.let { RemoteTaskSource.ref(RemoteTaskSource.KIND_FTP, it) }
            ?: return@launch
        db.downloadTaskDao().setPausedForSource(kind, ref, true)
        cancelWorker(kind, ref)
    }

    fun resume(task: DownloadTaskEntity) = viewModelScope.launch(Dispatchers.IO) {
        requeueAndRun(task)
    }

    fun clearCompleted() = viewModelScope.launch(Dispatchers.IO) {
        db.downloadTaskDao().clearCompleted()
    }

    private suspend fun requeueAndRun(task: DownloadTaskEntity) {
        val kind = task.sourceKind ?: RemoteTaskSource.KIND_FTP
        val ref = task.sourceRef ?: task.serverId?.let { RemoteTaskSource.ref(RemoteTaskSource.KIND_FTP, it) }
            ?: return
        db.downloadTaskDao().setPausedForSource(kind, ref, false)
        db.downloadTaskDao().requeue(task.id)
        enqueueWorker(kind, ref)
    }

    private fun cancelWorker(kind: String, ref: String) {
        val id = ref.substringAfter(':').toLongOrNull() ?: return
        when (kind) {
            RemoteTaskSource.KIND_FTP -> FtpSyncWorker.cancel(getApplication(), id)
            SmbSourceRepository.KIND -> SmbSyncWorker.cancel(getApplication(), id)
            WebdavSourceRepository.KIND -> WebdavSyncWorker.cancel(getApplication(), id)
            CalibreSourceRepository.KIND -> CalibreSyncWorker.cancel(getApplication(), id)
        }
    }

    private suspend fun enqueueWorker(kind: String, ref: String) {
        val id = ref.substringAfter(':').toLongOrNull() ?: return
        when (kind) {
            RemoteTaskSource.KIND_FTP -> {
                ftpGraph.sourceRepository.getSource(id)?.let { FtpSyncWorker.enqueueOrRestart(getApplication(), it) }
            }
            SmbSourceRepository.KIND -> {
                smbGraph.sourceRepository.getSource(id)?.let { SmbSyncWorker.enqueue(getApplication(), it) }
            }
            WebdavSourceRepository.KIND -> {
                webdavGraph.sourceRepository.getSource(id)?.let { WebdavSyncWorker.enqueue(getApplication(), it) }
            }
            CalibreSourceRepository.KIND -> CalibreSyncWorker.enqueue(getApplication(), id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTransfersScreen(
    onBack: () -> Unit,
    vm: RemoteTransfersViewModel = viewModel(factory = remoteTransfersVmFactory())
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current

    Scaffold(
        containerColor = com.bookrio.designsystem.theme.OmarchyColors.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.transfers_title),
                        style = ShelfTypography.HeadlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.completed.isNotEmpty()) {
                        IconButton(onClick = vm::clearCompleted) {
                            Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.transfers_clear_completed))
                        }
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.syncing.isEmpty() && state.queued.isEmpty() && state.failed.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.transfers_empty),
                        style = ShelfTypography.BodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.syncing.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.transfers_syncing)) }
                items(state.syncing, key = { it.task.id }) { row ->
                    TransferCard(
                        row = row,
                        onPause = { vm.pause(row.task) },
                        onCancel = { vm.cancelTask(row.task.id) },
                        onRetry = null
                    )
                }
            }
            if (state.queued.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.transfers_queued)) }
                items(state.queued, key = { it.task.id }) { row ->
                    val paused = row.task.status == DownloadStatusEntity.PAUSED_BY_USER ||
                        row.task.status == DownloadStatusEntity.PAUSED
                    TransferCard(
                        row = row,
                        onPause = if (paused) null else { { vm.pause(row.task) } },
                        onCancel = { vm.cancelTask(row.task.id) },
                        onRetry = if (paused) { { vm.resume(row.task) } } else null
                    )
                }
            }
            if (state.failed.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.transfers_failed)) }
                items(state.failed, key = { it.task.id }) { row ->
                    TransferCard(
                        row = row,
                        onPause = null,
                        onCancel = { vm.cancelTask(row.task.id) },
                        onRetry = { vm.retry(row.task) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = ShelfTypography.TitleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun TransferCard(
    row: RemoteTransferRow,
    onPause: (() -> Unit)?,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                row.sourceLabel,
                style = ShelfTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                row.task.remoteName,
                style = ShelfTypography.BodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.task.status == DownloadStatusEntity.RUNNING ||
                row.task.status == DownloadStatusEntity.VERIFYING ||
                row.task.status == DownloadStatusEntity.IMPORTING
            ) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { row.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(row.progress * 100).toInt()}% · ${formatBytes(row.task.downloadedBytes)} / ${formatBytes(row.task.sizeBytes)}",
                    style = ShelfTypography.BodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            row.task.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = ShelfTypography.BodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onPause?.let {
                    OutlinedButton(onClick = it) {
                        Icon(Icons.Default.Pause, null, Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.transfers_pause))
                    }
                }
                onRetry?.let {
                    OutlinedButton(onClick = it) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.width(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.transfers_resume))
                    }
                }
                OutlinedButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, null, Modifier.width(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.transfers_cancel), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}

@Composable
fun remoteTransfersVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
    val app = LocalContext.current.applicationContext as Application
    return viewModelFactory { initializer { RemoteTransfersViewModel(app) } }
}