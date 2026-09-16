package com.shelf.reader.webdav.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import com.shelf.reader.data.local.entity.RemoteSourceStateEntity
import com.shelf.reader.data.local.entity.RemoteTaskSource
import com.shelf.reader.data.local.entity.TransferCounts
import com.shelf.reader.data.transfer.RemoteFileRef
import com.shelf.reader.webdav.client.WebdavClientEngine
import com.shelf.reader.webdav.client.WebdavEntry
import com.shelf.reader.webdav.client.WebdavEntryType
import com.shelf.reader.webdav.client.WebdavErrorKind
import com.shelf.reader.webdav.data.WebdavCredentials
import com.shelf.reader.webdav.data.WebdavGraph
import com.shelf.reader.webdav.data.WebdavSource
import com.shelf.reader.webdav.data.WebdavSourceInput
import com.shelf.reader.webdav.data.WebdavSourceRepository
import com.shelf.reader.webdav.worker.WebdavSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WebdavSourceStatus { CONNECTED, SYNCING, PAUSED, RETRYING, NEEDS_AUTH, ERROR, IDLE, DISABLED }

data class WebdavSourceSummary(val source: WebdavSource, val counts: TransferCounts) {
    val status: WebdavSourceStatus
        get() = when {
            source.state == RemoteSourceStateEntity.DISABLED -> WebdavSourceStatus.DISABLED
            source.state == RemoteSourceStateEntity.NEEDS_AUTH -> WebdavSourceStatus.NEEDS_AUTH
            source.state == RemoteSourceStateEntity.CONNECTION_ERROR -> WebdavSourceStatus.ERROR
            counts.running > 0 -> WebdavSourceStatus.SYNCING
            counts.paused > 0 -> WebdavSourceStatus.PAUSED
            counts.retrying > 0 -> WebdavSourceStatus.RETRYING
            else -> WebdavSourceStatus.CONNECTED
        }
}

data class WebdavUiState(
    val displayName: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val bearerToken: String = "",
    val authType: String = "BASIC",
    val trustAllCertificates: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val basePath: String = "/",

    val currentPath: String = "/",
    val entries: List<WebdavEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isConnected: Boolean = false,
    val sources: List<WebdavSourceSummary> = emptyList(),
    val activeServerId: Long? = null,
    val transfers: List<DownloadTaskEntity> = emptyList(),
    val counts: TransferCounts = TransferCounts(),
    val downloading: Map<String, Float> = emptyMap()
)

class WebdavViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val graph = WebdavGraph.get(application)
    private val repo = graph.sourceRepository

    private var engine: WebdavClientEngine? = null

    private val formState = MutableStateFlow(WebdavUiState())
    private val activeSourceId = MutableStateFlow<Long?>(null)

    private val transfersFlow = activeSourceId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else graph.transferRepository.observeForSource(id)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { runCatching { graph.migrateLegacyIfNeeded() } }
    }

    private val sourcesFlow = repo.sources.combine(graph.transferRepository.observeCounts()) { sources, counts ->
        sources.map { source ->
            val ref = RemoteTaskSource.ref(WebdavSourceRepository.KIND, source.id)
            val row = counts.firstOrNull { it.sourceRef == ref }
            WebdavSourceSummary(
                source = source,
                counts = if (row == null) TransferCounts() else TransferCounts(
                    total = row.total, queued = row.queued, running = row.running,
                    completed = row.completed, failed = row.failed,
                    paused = row.paused, retrying = row.retrying
                )
            )
        }
    }

    val state: StateFlow<WebdavUiState> = combine(
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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WebdavUiState())

    fun updateDisplayName(v: String) { formState.value = formState.value.copy(displayName = v) }
    fun updateBaseUrl(v: String) { formState.value = formState.value.copy(baseUrl = v, activeServerId = null) }
    fun updateUsername(v: String) { formState.value = formState.value.copy(username = v) }
    fun updatePassword(v: String) { formState.value = formState.value.copy(password = v) }
    fun updateBearerToken(v: String) { formState.value = formState.value.copy(bearerToken = v) }
    fun updateAuthType(v: String) { formState.value = formState.value.copy(authType = v) }
    fun updateTrustAllCerts(v: Boolean) { formState.value = formState.value.copy(trustAllCertificates = v) }
    fun updateBasePath(v: String) { formState.value = formState.value.copy(basePath = v) }

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
            baseUrl = source.baseUrl,
            username = source.username,
            password = "",
            bearerToken = "",
            authType = source.authType,
            trustAllCertificates = source.trustAllCertificates,
            hasStoredPassword = source.hasStoredCredentials,
            basePath = source.basePath,
            currentPath = source.basePath.ifBlank { "/" },
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
        val client = WebdavClientEngine()
        engine = client
        val result = client.connectResult(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password.ifBlank { null },
            bearerToken = credentials.bearerToken.ifBlank { null },
            authType = credentials.authType,
            trustAllCertificates = credentials.trustAllCertificates,
            userAgent = WebdavSourceRepository.USER_AGENT
        )
        if (!result.success) {
            formState.value = formState.value.copy(
                isConnected = false, isLoading = false, entries = emptyList(), error = result.kind?.name
            )
            activeSourceId.value?.let { repo.markState(it, result.kind.toSourceState(), result.kind?.name) }
            return@launch
        }
        activeSourceId.value?.let { repo.markConnected(it) }
        val path = formState.value.currentPath.ifBlank { "/" }
        val entries = client.listDirectory(path)
        formState.value = formState.value.copy(isConnected = true, isLoading = false, entries = entries, error = null)
    }

    fun disconnect() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { engine?.disconnect() }
        engine = null
        formState.value = formState.value.copy(isConnected = false, entries = emptyList(), selected = emptySet())
    }

    fun navigateTo(entry: WebdavEntry) = viewModelScope.launch(Dispatchers.IO) {
        if (entry.type != WebdavEntryType.FOLDER) return@launch
        val client = engine ?: return@launch
        formState.value = formState.value.copy(isLoading = true, error = null)
        val entries = client.listDirectory(entry.path)
        formState.value = formState.value.copy(
            currentPath = entry.path, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    fun navigateUp() = viewModelScope.launch(Dispatchers.IO) {
        val current = formState.value.currentPath
        if (current == "/" || current.isBlank()) return@launch
        val client = engine ?: return@launch
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        formState.value = formState.value.copy(isLoading = true)
        val entries = client.listDirectory(parent)
        formState.value = formState.value.copy(
            currentPath = parent, entries = entries, isLoading = false, selected = emptySet()
        )
    }

    suspend fun saveCurrentAs(name: String? = null): Long? {
        val form = formState.value
        if (form.baseUrl.isBlank()) return null
        val id = repo.upsert(
            WebdavSourceInput(
                id = form.activeServerId ?: 0L,
                displayName = name?.ifBlank { null } ?: form.displayName,
                baseUrl = form.baseUrl,
                username = form.username,
                password = form.password,
                bearerToken = form.bearerToken,
                authType = form.authType,
                trustAllCertificates = form.trustAllCertificates,
                basePath = form.currentPath.ifBlank { form.basePath }
            )
        )
        activeSourceId.value = id
        formState.value = formState.value.copy(
            activeServerId = id,
            displayName = form.displayName.ifBlank { form.baseUrl },
            hasStoredPassword = form.hasStoredPassword || form.password.isNotEmpty() || form.bearerToken.isNotEmpty()
        )
        return id
    }

    fun save(name: String? = null) = viewModelScope.launch(Dispatchers.IO) { saveCurrentAs(name) }

    fun deleteSaved(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        WebdavSyncWorker.cancel(getApplication(), id)
        repo.deleteSource(id, deleteQueuedTasks = true)
        if (formState.value.activeServerId == id) {
            activeSourceId.value = null
            formState.value = formState.value.copy(activeServerId = null)
        }
    }

    fun syncNow(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val source = repo.getSource(id) ?: return@launch
        graph.transferRepository.resume(id)
        WebdavSyncWorker.enqueue(getApplication(), source)
    }

    fun pause(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.pause(id)
        WebdavSyncWorker.cancel(getApplication(), id)
    }

    fun cancelTransfers(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancel(id)
        WebdavSyncWorker.cancel(getApplication(), id)
    }

    fun retryTask(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.retry(taskId)
        formState.value.activeServerId?.let { id ->
            repo.getSource(id)?.let { WebdavSyncWorker.enqueue(getApplication(), it) }
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
        val files = formState.value.entries
            .filter { it.type == WebdavEntryType.FILE }
            .map { it.path }
        if (files.isEmpty()) return@launch
        queuePaths(files.toSet())
    }

    private suspend fun queuePaths(paths: Set<String>) {
        val id = formState.value.activeServerId ?: saveCurrentAs() ?: return
        val refs = formState.value.entries
            .filter { it.type == WebdavEntryType.FILE && paths.contains(it.path) }
            .map { RemoteFileRef(it.path, it.name, it.sizeBytes, it.modifiedEpochSec) }
        if (refs.isEmpty()) return
        graph.transferRepository.enqueue(id, formState.value.currentPath, refs)
        repo.getSource(id)?.let { WebdavSyncWorker.enqueue(getApplication(), it) }
        formState.value = formState.value.copy(selected = emptySet())
    }

    private suspend fun currentCredentials(): WebdavCredentials? {
        val activeId = formState.value.activeServerId
        if (activeId != null && formState.value.password.isEmpty() && formState.value.bearerToken.isEmpty()) {
            repo.credentialsFor(activeId)?.let { return it }
        }
        val form = formState.value
        if (form.baseUrl.isBlank()) return null
        return WebdavCredentials(
            baseUrl = WebdavSourceRepository.normalizeBaseUrl(form.baseUrl),
            username = form.username.trim(),
            password = form.password,
            bearerToken = form.bearerToken,
            authType = form.authType,
            trustAllCertificates = form.trustAllCertificates
        )
    }

    private fun WebdavErrorKind?.toSourceState(): RemoteSourceStateEntity = when (this) {
        WebdavErrorKind.AUTH, WebdavErrorKind.FORBIDDEN -> RemoteSourceStateEntity.NEEDS_AUTH
        else -> RemoteSourceStateEntity.CONNECTION_ERROR
    }

    override fun onCleared() {
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