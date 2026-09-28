package com.bookrio.calibre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.calibre.client.CalibreAcquisition
import com.bookrio.calibre.client.CalibreClient
import com.bookrio.calibre.client.CalibreErrorKind
import com.bookrio.calibre.client.CalibreException
import com.bookrio.calibre.client.OpdsEntry
import com.bookrio.calibre.client.OpdsFeed
import com.bookrio.calibre.data.CalibreGraph
import com.bookrio.data.local.entity.CalibreSourceStateEntity
import com.bookrio.calibre.worker.CalibreSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen browser for one Calibre Content Server. */
class CalibreBrowserViewModel(
    application: Application,
    private val sourceId: Long
) : AndroidViewModel(application) {

    private val graph = CalibreGraph.get(application)

    private val _state = MutableStateFlow(CalibreBrowserState())
    val state: StateFlow<CalibreBrowserState> = _state.asStateFlow()

    /** Navigation stack: (title, feedUrl). */
    private val stack = ArrayDeque<Pair<String, String>>()

    init {
        viewModelScope.launch {
            val source = graph.sourceRepository.getSource(sourceId)
            _state.value = _state.value.copy(source = source)
            loadRoot()
        }
        viewModelScope.launch {
            graph.transferRepository.observeForSource(sourceId).collect { transfers ->
                val queued = transfers.count {
                    it.status == com.bookrio.data.local.entity.DownloadStatusEntity.QUEUED ||
                        it.status == com.bookrio.data.local.entity.DownloadStatusEntity.PENDING ||
                        it.status == com.bookrio.data.local.entity.DownloadStatusEntity.RUNNING
                }
                _state.value = _state.value.copy(queuedCount = queued)
            }
        }
    }

    fun refresh() {
        val (title, url) = stack.lastOrNull() ?: return
        loadFeed(title, url)
    }

    fun loadRoot() = viewModelScope.launch(Dispatchers.IO) {
        val source = graph.sourceRepository.getSource(sourceId) ?: return@launch
        loadFeed(source.displayName.ifBlank { source.baseUrl }, "${source.baseUrl}/opds")
    }

    fun navigate(entry: CalibreBrowserEntry) {
        val url = entry.navigationUrl ?: return
        stack.addLast(entry.title to url)
        loadFeed(entry.title, url)
    }

    fun navigateUp(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        val (title, url) = stack.last()
        loadFeed(title, url)
        return true
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        if (query.isBlank()) {
            _state.value = _state.value.copy(searchActive = false, searchQuery = "")
            return@launch
        }
        val client = graph.sourceRepository.clientFor(sourceId) ?: return@launch
        _state.value = _state.value.copy(loading = true, error = null, searchActive = true, searchQuery = query)
        try {
            val source = graph.sourceRepository.getSource(sourceId)
            val template = if (source != null) "${source.baseUrl}/opds/search/{searchTerms}" else ""
            val feed = withContext(Dispatchers.IO) { client.search(query, template) }
            stack.clear()
            stack.addLast("Search: $query" to "$template".replace("{searchTerms}", query))
            applyFeed("Search: $query", feed)
        } catch (e: CalibreException) {
            graph.sourceRepository.markState(sourceId, CalibreSourceStateEntity.CONNECTION_ERROR, e.message)
            _state.value = _state.value.copy(loading = false, error = e.kind)
        } catch (e: Throwable) {
            _state.value = _state.value.copy(loading = false, error = CalibreClient.classifyThrowable(e))
        }
    }

    fun toggleSelection(entryId: String) {
        val selected = _state.value.selected.toMutableSet()
        if (!selected.add(entryId)) selected.remove(entryId)
        _state.value = _state.value.copy(selected = selected)
    }

    fun clearSelection() { _state.value = _state.value.copy(selected = emptySet()) }

    fun queueSelected() = viewModelScope.launch(Dispatchers.IO) {
        val selected = _state.value.selected
        val downloads = _state.value.entries
            .filter { selected.contains(it.id) }
            .mapNotNull { it.download }
        if (downloads.isEmpty()) return@launch
        graph.transferRepository.enqueue(sourceId, downloads)
        CalibreSyncWorker.enqueue(getApplication(), sourceId)
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun queueFolder() = viewModelScope.launch(Dispatchers.IO) {
        val downloads = _state.value.entries.mapNotNull { it.download }
        if (downloads.isEmpty()) return@launch
        graph.transferRepository.enqueue(sourceId, downloads)
        CalibreSyncWorker.enqueue(getApplication(), sourceId)
    }

    private fun loadFeed(title: String, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val client = graph.sourceRepository.clientFor(sourceId)
            if (client == null) {
                _state.value = _state.value.copy(loading = false, error = CalibreErrorKind.AUTH)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val feed = client.loadFeed(url)
                graph.sourceRepository.markConnected(sourceId)
                stack.clear()
                stack.addLast(title to url)
                applyFeed(title, feed)
            } catch (e: CalibreException) {
                if (e.kind == CalibreErrorKind.AUTH) {
                    graph.sourceRepository.markState(sourceId, CalibreSourceStateEntity.NEEDS_AUTH, e.message)
                }
                _state.value = _state.value.copy(loading = false, error = e.kind)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(loading = false, error = CalibreClient.classifyThrowable(e))
            }
        }
    }

    private fun applyFeed(title: String, feed: OpdsFeed) {
        val entries = feed.entries.map { entry -> entry.toBrowserEntry() }
        _state.value = _state.value.copy(
            currentTitle = feed.title ?: title,
            entries = entries,
            loading = false,
            error = null,
            selected = emptySet()
        )
    }

    private fun OpdsEntry.toBrowserEntry(): CalibreBrowserEntry {
        val navigation = navigationLinks.firstOrNull()?.href
        val download = if (navigation == null) CalibreAcquisition.best(this) else null
        return CalibreBrowserEntry(
            id = id ?: title,
            title = title,
            authors = authors,
            isNavigation = navigation != null,
            navigationUrl = navigation,
            download = download
        )
    }
}