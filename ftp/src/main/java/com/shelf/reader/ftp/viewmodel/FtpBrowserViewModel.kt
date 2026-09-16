package com.shelf.reader.ftp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.FtpSourceStateEntity
import com.shelf.reader.ftp.client.FtpEntry
import com.shelf.reader.ftp.client.FtpEntryType
import com.shelf.reader.ftp.client.FtpErrorKind
import com.shelf.reader.ftp.client.FtpException
import com.shelf.reader.ftp.client.FtpProtocol
import com.shelf.reader.ftp.client.RemoteFileClient
import com.shelf.reader.ftp.data.FtpGraph
import com.shelf.reader.ftp.data.FtpSource
import com.shelf.reader.ftp.worker.FtpSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FtpSort { NAME, DATE, SIZE, TYPE }

enum class FtpUiError { CONNECTION, AUTH, LISTING, UNKNOWN }

data class FtpBrowserUiState(
    val serverId: Long = 0L,
    val sourceName: String = "",
    val protocol: FtpProtocol = FtpProtocol.SFTP,
    val path: String = "/",
    val query: String = "",
    val sort: FtpSort = FtpSort.NAME,
    val entries: List<FtpEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isConnected: Boolean = false,
    val error: FtpUiError? = null,
    val taskStatus: Map<String, DownloadStatusEntity> = emptyMap(),
    val folderBusy: Boolean = false,
    val lastQueuedCount: Int = 0
) {
    val visibleEntries: List<FtpEntry>
        get() {
            val filtered = if (query.isBlank()) entries
            else entries.filter { it.name.contains(query, ignoreCase = true) }
            val comparator: Comparator<FtpEntry> = when (sort) {
                FtpSort.NAME -> compareBy { it.name.lowercase() }
                FtpSort.DATE -> compareByDescending { it.modifiedEpochSec }
                FtpSort.SIZE -> compareByDescending { it.sizeBytes }
                FtpSort.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            }
            return filtered.sortedWith(
                compareBy<FtpEntry> { it.type != FtpEntryType.FOLDER }.then(comparator)
            )
        }

    val breadcrumbs: List<Pair<String, String>>
        get() {
            val segments = path.split('/').filter { it.isNotBlank() }
            val result = mutableListOf("/" to "/")
            var build = ""
            segments.forEach { seg ->
                build = "$build/$seg"
                result.add(seg to build)
            }
            return result
        }
}

/**
 * Full-screen browser.
 *
 * The connection used for listing is a **short-lived session** owned by this
 * ViewModel and closed in [onCleared]. It is completely separate from the
 * transfer engine, so navigating away never cancels a download.
 */
class FtpBrowserViewModel(
    application: Application,
    private val serverId: Long
) : AndroidViewModel(application) {

    private val graph = FtpGraph.get(application)
    private val _state = MutableStateFlow(FtpBrowserUiState(serverId = serverId))
    val state: StateFlow<FtpBrowserUiState> = _state.asStateFlow()

    private var session: RemoteFileClient? = null
    private var source: FtpSource? = null
    private var basePath: String = "/"

    /** Live summary for the persistent mini transfer bar. */
    val summary: StateFlow<FtpSourceSummary?> = combine(
        graph.sourceRepository.observeSource(serverId),
        graph.transferRepository.observeForServer(serverId),
        FtpGraph.runtime.active
    ) { src, tasks, active ->
        src?.let {
            FtpSourceSummary(
                source = it,
                counts = countsOf(tasks),
                active = active.filter { transfer -> transfer.serverId == serverId }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = graph.sourceRepository.getSource(serverId)
            if (loaded != null) {
                source = loaded
                basePath = loaded.basePath.ifBlank { "/" }
                _state.value = _state.value.copy(
                    sourceName = loaded.displayName,
                    protocol = loaded.protocol,
                    path = basePath
                )
            }
            // List the configured base path, not the default root.
            loadDirectory(_state.value.path.ifBlank { basePath })
        }
        viewModelScope.launch(Dispatchers.IO) {
            graph.transferRepository.observeForServer(serverId).collect { tasks ->
                val map = tasks.associate { it.remotePath to it.status }
                _state.value = _state.value.copy(taskStatus = map)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            graph.sourceRepository.observeSource(serverId).collect { updated ->
                if (updated != null) {
                    source = updated
                    _state.value = _state.value.copy(
                        sourceName = updated.displayName,
                        protocol = updated.protocol
                    )
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        loadDirectory(_state.value.path.ifBlank { basePath })
    }

    fun navigateTo(path: String) = viewModelScope.launch(Dispatchers.IO) {
        loadDirectory(if (path.isBlank()) "/" else path)
    }

    fun navigateUp() {
        val current = _state.value.path
        if (current == "/" || current.isBlank()) return
        val trimmed = current.trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        navigateTo(parent.ifBlank { "/" })
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun setSort(sort: FtpSort) {
        _state.value = _state.value.copy(sort = sort)
    }

    fun toggleSelect(entry: FtpEntry) {
        if (entry.type == FtpEntryType.FOLDER) return
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (entry.name in current) current - entry.name else current + entry.name
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun syncSelected() = viewModelScope.launch(Dispatchers.IO) {
        val current = _state.value
        val base = getSourceBasePath()
        val selectedEntries = current.entries.filter { it.name in current.selected && it.type == FtpEntryType.FILE }
        if (selectedEntries.isEmpty()) return@launch
        val report = graph.transferRepository.enqueue(serverId, base, selectedEntries)
        source?.let { FtpSyncWorker.enqueue(getApplication(), it) }
        _state.value = _state.value.copy(
            selected = emptySet(),
            lastQueuedCount = report.added + report.alreadyQueued
        )
    }

    fun syncFolder() = viewModelScope.launch(Dispatchers.IO) {
        val current = _state.value
        val base = getSourceBasePath()
        _state.value = current.copy(folderBusy = true, error = null)
        try {
            val client = ensureSession()
            val all = client.listDirectoryRecursive(current.path, maxDepth = FOLDER_SYNC_DEPTH)
                .filter { com.shelf.reader.ftp.domain.MediaFormats.isBook(it.name) }
            val report = graph.transferRepository.enqueue(serverId, base, all)
            source?.let { FtpSyncWorker.enqueue(getApplication(), it) }
            _state.value = _state.value.copy(folderBusy = false, lastQueuedCount = report.added + report.alreadyQueued)
        } catch (e: FtpException) {
            _state.value = _state.value.copy(folderBusy = false, error = mapError(e))
        } catch (_: Throwable) {
            _state.value = _state.value.copy(folderBusy = false, error = FtpUiError.UNKNOWN)
        }
    }

    fun prioritize(entry: FtpEntry) = viewModelScope.launch(Dispatchers.IO) {
        val task = graph.transferRepository.taskForRemote(serverId, entry.path) ?: return@launch
        graph.transferRepository.prioritize(task.id)
        source?.let { FtpSyncWorker.enqueue(getApplication(), it) }
    }

    private fun getSourceBasePath(): String = source?.basePath?.ifBlank { "/" } ?: basePath

    private suspend fun loadDirectory(path: String) {
        val normalized = if (path.isBlank()) "/" else path
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val client = ensureSession()
            val entries = client.listDirectory(normalized)
            _state.value = _state.value.copy(
                path = normalized,
                entries = entries,
                selected = emptySet(),
                isLoading = false,
                isConnected = true,
                error = null
            )
        } catch (e: FtpException) {
            _state.value = _state.value.copy(isLoading = false, error = mapError(e))
        } catch (_: Throwable) {
            _state.value = _state.value.copy(isLoading = false, error = FtpUiError.UNKNOWN)
        }
    }

    private suspend fun ensureSession(): RemoteFileClient {
        session?.takeIf { it.isConnected }?.let { return it }
        val creds = graph.sourceRepository.credentialsFor(serverId)
            ?: throw FtpException(FtpErrorKind.AUTH, "Sign-in required")
        val client = graph.sourceRepository.clientFactory.create()
        val connected = try {
            client.connect(creds)
        } catch (e: FtpException) {
            runCatching { client.close() }
            if (e.kind == FtpErrorKind.AUTH) {
                graph.sourceRepository.markState(serverId, FtpSourceStateEntity.NEEDS_AUTH, "Sign-in required")
            }
            throw e
        }
        if (!connected) {
            runCatching { client.close() }
            graph.sourceRepository.markState(serverId, FtpSourceStateEntity.CONNECTION_ERROR, "Could not connect")
            throw FtpException(FtpErrorKind.NETWORK, "Could not connect")
        }
        graph.sourceRepository.markConnected(serverId)
        session = client
        return client
    }

    private fun mapError(e: FtpException): FtpUiError = when (e.kind) {
        FtpErrorKind.AUTH -> FtpUiError.AUTH
        FtpErrorKind.NETWORK, FtpErrorKind.NOT_FOUND -> FtpUiError.CONNECTION
        FtpErrorKind.SERVER, FtpErrorKind.UNSUPPORTED -> FtpUiError.LISTING
        else -> FtpUiError.UNKNOWN
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val FOLDER_SYNC_DEPTH = 6
    }
}