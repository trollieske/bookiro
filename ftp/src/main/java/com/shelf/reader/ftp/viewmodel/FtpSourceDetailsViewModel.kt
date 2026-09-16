package com.shelf.reader.ftp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.ftp.data.FtpGraph
import com.shelf.reader.ftp.worker.FtpSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Source details: sync actions, policy and history for one stored source. */
class FtpSourceDetailsViewModel(
    application: Application,
    val serverId: Long
) : AndroidViewModel(application) {

    private val graph = FtpGraph.get(application)

    val summary: StateFlow<FtpSourceSummary?> = combine(
        graph.sourceRepository.observeSource(serverId),
        graph.transferRepository.observeForServer(serverId),
        FtpGraph.runtime.active
    ) { source, tasks, active ->
        if (source == null) null
        else FtpSourceSummary(
            source = source,
            counts = countsOf(tasks),
            active = active.filter { it.serverId == serverId }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = viewModelScope.launch(Dispatchers.IO) {
        val source = graph.sourceRepository.getSource(serverId) ?: return@launch
        graph.transferRepository.resume(serverId)
        FtpSyncWorker.enqueue(getApplication(), source)
    }

    fun pause() = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.pause(serverId)
        FtpSyncWorker.cancel(getApplication(), serverId)
    }

    fun cancel() = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancel(serverId)
        FtpSyncWorker.cancel(getApplication(), serverId)
    }

    fun remove(deleteQueue: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        FtpSyncWorker.cancel(getApplication(), serverId)
        graph.sourceRepository.deleteSource(serverId, deleteQueuedTasks = deleteQueue)
    }

    fun setPolicy(wifiOnly: Boolean, chargingOnly: Boolean, concurrencyOverride: Int) =
        viewModelScope.launch(Dispatchers.IO) {
            graph.sourceRepository.setSyncPolicy(serverId, wifiOnly, chargingOnly, concurrencyOverride)
        }

    fun setBasePath(path: String) = viewModelScope.launch(Dispatchers.IO) {
        graph.sourceRepository.setBasePath(serverId, path)
    }

    fun setSyncEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        graph.sourceRepository.setSyncEnabled(serverId, enabled)
    }
}