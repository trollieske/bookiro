package com.shelf.reader.smb.viewmodel

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import com.shelf.reader.data.local.entity.RemoteSourceStateEntity
import com.shelf.reader.data.local.entity.TransferCounts
import com.shelf.reader.data.transfer.RemoteFileRef
import com.shelf.reader.smb.client.SmbClientEngine
import com.shelf.reader.smb.client.SmbEntry
import com.shelf.reader.smb.client.SmbEntryType
import com.shelf.reader.smb.client.SmbErrorKind
import com.shelf.reader.smb.data.SmbGraph
import com.shelf.reader.smb.data.SmbSource
import com.shelf.reader.smb.data.SmbSourceInput
import com.shelf.reader.smb.data.SmbCredentials
import com.shelf.reader.smb.worker.SmbSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Coarse status shown on a source card. */
enum class SmbSourceStatus { CONNECTED, SYNCING, PAUSED, RETRYING, NEEDS_AUTH, ERROR, IDLE, DISABLED }

data class SmbSourceSummary(val source: SmbSource, val counts: TransferCounts) {
    val status: SmbSourceStatus
        get() = when {
            source.state == RemoteSourceStateEntity.DISABLED -> SmbSourceStatus.DISABLED
            source.state == RemoteSourceStateEntity.NEEDS_AUTH -> SmbSourceStatus.NEEDS_AUTH
            source.state == RemoteSourceStateEntity.CONNECTION_ERROR -> SmbSourceStatus.ERROR
            counts.running > 0 -> SmbSourceStatus.SYNCING
            counts.paused > 0 -> SmbSourceStatus.PAUSED
            counts.retrying > 0 -> SmbSourceStatus.RETRYING
            else -> SmbSourceStatus.CONNECTED
        }
}

data class SmbUiState(
    val displayName: String = "",
    val host: String = "",
    val port: Int = 445,
    val shareName: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val hasStoredPassword: Boolean = false,
    val smbVersion: String = "AUTO",
    val enableEncryption: Boolean = false,

    val currentPath: String = "/",
    val entries: List<SmbEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
    val sources: List<SmbSourceSummary> = emptyList(),
    val activeServerId: Long? = null,
    val transfers: List<DownloadTaskEntity> = emptyList(),
    val counts: TransferCounts = TransferCounts(),
    val downloading: Map<String, Float> = emptyMap()
)

class SmbViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val graph = SmbGraph.get(application)
    private val repo = graph.sourceRepository

    private var engine: SmbClientEngine? = null

    private val formState = MutableStateFlow(SmbUiState())
    private val activeSourceId = MutableStateFlow<Long?>(null)

    private val transfersFlow = activeSourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.transferRepository.observeForSource(id)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { graph.migrateLegacyIfNeeded() } }
    }

    private val sourcesFlow = repo.sources.combine(
        graph.transferRepository.observeCounts()
    ) { sources, counts ->
        sources.map { source ->
            val ref = com.shelf.reader.data.local.entity.RemoteTaskSource.ref(
                com.shelf.reader.smb.data.SmbSourceRepository.KIND,
                source.id
            )
            val row = counts.firstOrNull { it.sourceRef == ref }
            SmbSourceSummary(
                source = source,
                counts = if (row == null) TransferCounts() else TransferCounts(
                    total = row.total, queued = row.queued, running = row.running,
                    completed = row.completed, failed = row.failed,
                    paused = row.paused, retrying = row.retrying
                )
            )
        }
    }

    val state: StateFlow<SmbUiState> = combine(
        formState,
        sourcesFlow,
        transfersFlow
    ) { form, sources, transfers ->
        form.copy(
            sources = sources,
            transfers = transfers,
            counts = countsOf(transfers),
            downloading = transfers
                .filter { !it.status.isTerminal && it.downloadedBytes > 0 }
                .associate { it.remotePath to progressOf(it) }
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SmbUiState())

    fun updateDisplayName(v: String) { formState.value = formState.value.copy(displayName = v) }
    fun updateHost(v: String) { formState.value = formState.value.copy(host = v, activeServerId = null) }
    fun updatePort(v: Int) { formState.value = formState.value.copy(port = v.coerceIn(1, 65535)) }
    fun updateShareName(v: String) { formState.value = formState.value.copy(shareName = v) }
    fun updateDomain(v: String) { formState.value = formState.value.copy(domain = v) }
    fun updateUsername(v: String) { formState.value = formState.value.copy(username = v) }
    fun updatePassword(v: String) { formState.value = formState.value.copy(password = v) }
    fun updateSmbVersion(v: String) { formState.value = formState.value.copy(smbVersion = v) }
    fun updateEnableEncryption(v: Boolean) { formState.value = formState.value.copy(enableEncryption = v) }

    fun toggleSelected(path: String) {
        val current = formState.value.selected.toMutableSet()
        if (!current.add(path)) current.remove(path)
        formState.value = formState.value.copy(selected = current)
    }

    fun clearSelection() { formState.value = formState.value.copy(selected = emptySet()) }

    fun loadServer(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val source = repo.getSource(id) ?: return@launch
        activeSourceId.value = id
        formState.value = formState.value.copy(
            displayName = source.displayName,
            host = source.host,
            port = source.port,
            shareName = source.shareName,
            domain = source.domain ?: "",
            username = source.username,
            password = "",
            hasStoredPassword = source.hasStoredCredentials,
            smbVersion = source.smbVersion,
            enableEncryption = source.enableEncryption,
            currentPath = source.basePath,
            activeServerId = source.id
        )
    }

    fun connect() = viewModelScope.launch(Dispatchers.IO) {
        formState.value = formState.value.copy(isLoading = true, error = null)
        val credentials = currentCredentials()
        if (credentials == null) {
            formState.value = formState.value.copy(isLoading = false, error = "Sign-in unavailable")
            return@launch
        }
        val smb = SmbClientEngine()
        engine = smb
        val result = smb.connectResult(
            host = credentials.host,
            port = credentials.port,
            shareName = credentials.shareName,
            domain = credentials.domain,
            username = credentials.username,
            password = credentials.password,
            smbVersion = credentials.smbVersion,
            enableEncryption = credentials.enableEncryption
        )
        if (!result.success) {
            formState.value = formState.value.copy(
                isConnected = false,
                isLoading = false,
                entries = emptyList(),
                error = result.kind?.name
            )
            activeSourceId.value?.let { repo.markState(it, result.kind.toSourceState(), result.kind?.name) }
            return@launch
        }
        activeSourceId.value?.let { repo.markConnected(it) }
        val path = formState.value.currentPath.ifBlank { "/" }
        val entries = smb.listDirectory(path)
        formState.value = formState.value.copy(
            isConnected = true,
            isLoading = false,
            entries = entries,
            error = null
        )
    }

    fun disconnect() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { engine?.disconnect() }
        engine = null
        formState.value = formState.value.copy(isConnected = false, entries = emptyList(), selected = emptySet())
    }

    fun navigateTo(entry: SmbEntry) = viewModelScope.launch(Dispatchers.IO) {
        if (entry.type != SmbEntryType.FOLDER) return@launch
        val smb = engine ?: return@launch
        formState.value = formState.value.copy(isLoading = true, error = null)
        val entries = smb.listDirectory(entry.path)
        formState.value = formState.value.copy(
            currentPath = entry.path, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    fun navigateUp() = viewModelScope.launch(Dispatchers.IO) {
        val current = formState.value.currentPath
        if (current == "/" || current.isBlank()) return@launch
        val smb = engine ?: return@launch
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        formState.value = formState.value.copy(isLoading = true)
        val entries = smb.listDirectory(parent)
        formState.value = formState.value.copy(
            currentPath = parent, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    /** Persists the current form as a Room source (idempotent) and returns its id. */
    suspend fun saveCurrentAs(name: String? = null): Long? {
        val form = formState.value
        if (form.host.isBlank() || form.shareName.isBlank()) return null
        val id = repo.upsert(
            SmbSourceInput(
                id = form.activeServerId ?: 0L,
                displayName = name?.ifBlank { null } ?: form.displayName,
                host = form.host,
                port = form.port,
                shareName = form.shareName,
                domain = form.domain,
                username = form.username,
                password = form.password,
                smbVersion = form.smbVersion,
                enableEncryption = form.enableEncryption,
                basePath = form.currentPath
            )
        )
        activeSourceId.value = id
        formState.value = formState.value.copy(
            activeServerId = id,
            displayName = form.displayName.ifBlank { "${form.host}/${form.shareName}" },
            hasStoredPassword = form.hasStoredPassword || form.password.isNotEmpty()
        )
        return id
    }

    fun save(name: String? = null) = viewModelScope.launch(Dispatchers.IO) { saveCurrentAs(name) }

    fun deleteSaved(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        SmbSyncWorker.cancel(getApplication(), id)
        repo.deleteSource(id, deleteQueuedTasks = true)
        if (formState.value.activeServerId == id) {
            activeSourceId.value = null
            formState.value = formState.value.copy(activeServerId = null)
        }
    }

    fun syncNow(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val source = repo.getSource(id) ?: return@launch
        graph.transferRepository.resume(id)
        SmbSyncWorker.enqueue(getApplication(), source)
    }

    fun pause(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.pause(id)
        SmbSyncWorker.cancel(getApplication(), id)
    }

    fun cancelTransfers(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancel(id)
        SmbSyncWorker.cancel(getApplication(), id)
    }

    fun retryTask(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.retry(taskId)
        formState.value.activeServerId?.let { id ->
            repo.getSource(id)?.let { SmbSyncWorker.enqueue(getApplication(), it) }
        }
    }

    fun cancelTask(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancelTask(taskId)
    }

    fun downloadAndImportSelected() = viewModelScope.launch(Dispatchers.IO) {
        val selected = formState.value.selected
        if (selected.isEmpty()) return@launch
        queuePaths(selected)
    }

    fun downloadAndImportCurrentFolder() = viewModelScope.launch(Dispatchers.IO) {
        val refs = formState.value.entries
            .filter { it.type == SmbEntryType.FILE }
            .map { it.path }
        if (refs.isEmpty()) return@launch
        queuePaths(refs.toSet())
    }

    private suspend fun queuePaths(paths: Set<String>) {
        val id = formState.value.activeServerId ?: saveCurrentAs() ?: return
        val refs = formState.value.entries
            .filter { it.type == SmbEntryType.FILE && paths.contains(it.path) }
            .map { RemoteFileRef(it.path, it.name, it.sizeBytes, it.modifiedEpochSec) }
        if (refs.isEmpty()) return
        graph.transferRepository.enqueue(id, formState.value.currentPath, refs)
        repo.getSource(id)?.let { SmbSyncWorker.enqueue(getApplication(), it) }
        formState.value = formState.value.copy(selected = emptySet())
    }

    private suspend fun currentCredentials(): SmbCredentials? {
        val activeId = formState.value.activeServerId
        if (activeId != null && formState.value.password.isEmpty()) {
            repo.credentialsFor(activeId)?.let { return it }
        }
        val form = formState.value
        if (form.host.isBlank() || form.shareName.isBlank()) return null
        return SmbCredentials(
            host = form.host.trim(),
            port = form.port,
            shareName = form.shareName.trim().trim('/'),
            domain = form.domain.trim().ifBlank { null },
            username = form.username.trim(),
            password = form.password,
            smbVersion = form.smbVersion,
            enableEncryption = form.enableEncryption
        )
    }

    private fun SmbErrorKind?.toSourceState(): RemoteSourceStateEntity = when (this) {
        SmbErrorKind.AUTH -> RemoteSourceStateEntity.NEEDS_AUTH
        else -> RemoteSourceStateEntity.CONNECTION_ERROR
    }

    override fun onCleared() {
        // Close the short-lived listing connection; transfers are owned by the worker.
        runCatching { engine?.closeNow() }
        engine = null
        super.onCleared()
    }
}

private fun progressOf(task: DownloadTaskEntity): Float =
    if (task.sizeBytes > 0) (task.downloadedBytes.toFloat() / task.sizeBytes).coerceIn(0f, 1f) else 0f

private fun countsOf(tasks: List<DownloadTaskEntity>): TransferCounts {
    if (tasks.isEmpty()) return TransferCounts()
    return TransferCounts(
        total = tasks.size,
        queued = tasks.count { it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING },
        running = tasks.count { it.status.isActive },
        completed = tasks.count { it.status == DownloadStatusEntity.COMPLETED },
        failed = tasks.count { it.status == DownloadStatusEntity.FAILED },
        paused = tasks.count { it.status == DownloadStatusEntity.PAUSED || it.status == DownloadStatusEntity.PAUSED_BY_USER },
        retrying = tasks.count { it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK }
    )
}