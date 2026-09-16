package com.shelf.reader.ftp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import com.shelf.reader.ftp.data.FtpGraph
import com.shelf.reader.ftp.transfer.ActiveTransfer
import com.shelf.reader.ftp.worker.FtpSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransferRow(
    val task: DownloadTaskEntity,
    val active: ActiveTransfer?
)

data class FtpTransfersUiState(
    val syncing: List<TransferRow> = emptyList(),
    val queued: List<DownloadTaskEntity> = emptyList(),
    val failed: List<DownloadTaskEntity> = emptyList(),
    val completed: List<DownloadTaskEntity> = emptyList()
) {
    val completedBytes: Long get() = completed.sumOf { it.sizeBytes }
    val hasAnything: Boolean
        get() = syncing.isNotEmpty() || queued.isNotEmpty() || failed.isNotEmpty() || completed.isNotEmpty()
}

/**
 * Global Transfers screen. Observes Room and the live runtime snapshot; works
 * even if the browser was never opened again.
 */
class FtpTransfersViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = FtpGraph.get(application)

    val state: StateFlow<FtpTransfersUiState> = combine(
        graph.transferRepository.transfers,
        FtpGraph.runtime.active
    ) { tasks, active ->
        val activeByTask = active.associateBy { it.taskId }
        val syncing = tasks
            .filter { it.status == DownloadStatusEntity.RUNNING || it.status == DownloadStatusEntity.VERIFYING || it.status == DownloadStatusEntity.IMPORTING }
            .map { TransferRow(it, activeByTask[it.id]) }
        val queued = tasks.filter {
            it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING ||
                it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK ||
                it.status == DownloadStatusEntity.PAUSED || it.status == DownloadStatusEntity.PAUSED_BY_USER
        }
        val failed = tasks.filter { it.status == DownloadStatusEntity.FAILED }
        val completed = tasks.filter { it.status == DownloadStatusEntity.COMPLETED }
        FtpTransfersUiState(syncing, queued, failed, completed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FtpTransfersUiState())

    fun pauseAll() = viewModelScope.launch(Dispatchers.IO) {
        val serverIds = graph.transferRepository.activeServerIds()
        serverIds.forEach { id ->
            graph.transferRepository.pause(id)
            FtpSyncWorker.cancel(getApplication(), id)
        }
    }

    fun pauseServer(serverId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.pause(serverId)
        FtpSyncWorker.cancel(getApplication(), serverId)
    }

    fun retry(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.retry(taskId)
        graph.transferRepository.getTask(taskId)?.serverId?.let { serverId ->
            graph.sourceRepository.getSource(serverId)?.let { FtpSyncWorker.enqueueOrRestart(getApplication(), it) }
        }
    }

    fun retryAllFailed() = viewModelScope.launch(Dispatchers.IO) {
        val failed = state.value.failed
        val serverIds = mutableSetOf<Long>()
        failed.forEach { task ->
            graph.transferRepository.retry(task.id)
            task.serverId?.let { serverIds.add(it) }
        }
        serverIds.forEach { serverId ->
            graph.sourceRepository.getSource(serverId)?.let { FtpSyncWorker.enqueueOrRestart(getApplication(), it) }
        }
    }

    fun cancelTask(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.cancelTask(taskId)
    }

    fun prioritize(taskId: Long) = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.prioritize(taskId)
        graph.transferRepository.getTask(taskId)?.serverId?.let { serverId ->
            graph.sourceRepository.getSource(serverId)?.let { FtpSyncWorker.enqueueOrRestart(getApplication(), it) }
        }
    }

    fun clearCompleted() = viewModelScope.launch(Dispatchers.IO) {
        graph.transferRepository.clearCompleted()
    }
}