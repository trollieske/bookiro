package com.bookrio.ftp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.ftp.data.FtpGraph
import com.bookrio.ftp.worker.FtpSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sources list. Purely observational over Room + WorkManager; it never owns a
 * connection. A saved source is therefore visible after navigation, recreation
 * and cold start.
 */
class FtpSourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = FtpGraph.get(application)

    val summaries: StateFlow<List<FtpSourceSummary>> = combine(
        graph.sourceRepository.sources,
        graph.transferRepository.transfers,
        FtpGraph.runtime.active
    ) { sources, transfers, active ->
        val byServer = transfers
            .filter { it.serverId != null }
            .groupBy { it.serverId!! }
        sources.map { source ->
            FtpSourceSummary(
                source = source,
                counts = countsOf(byServer[source.id].orEmpty()),
                active = active.filter { it.serverId == source.id }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun syncNow(serverId: Long) = viewModelScope.launch(Dispatchers.IO) {
        val source = graph.sourceRepository.getSource(serverId) ?: return@launch
        // "Continue" resumes paused rows first, then hands off to the worker.
        graph.transferRepository.resume(serverId)
        FtpSyncWorker.enqueueOrRestart(getApplication(), source)
    }

    fun pause(serverId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.pause(serverId)
        FtpSyncWorker.cancel(getApplication(), serverId)
    }

    fun cancel(serverId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancel(serverId)
        FtpSyncWorker.cancel(getApplication(), serverId)
    }

    fun removeSource(serverId: Long, deleteQueue: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        FtpSyncWorker.cancel(getApplication(), serverId)
        graph.sourceRepository.deleteSource(serverId, deleteQueuedTasks = deleteQueue)
    }

    fun setSyncEnabled(serverId: Long, enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        graph.sourceRepository.setSyncEnabled(serverId, enabled)
    }
}