package com.bookrio.ftp.viewmodel

import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.DownloadTaskEntity
import com.bookrio.data.local.entity.FtpSourceStateEntity
import com.bookrio.data.local.entity.TransferCounts
import com.bookrio.ftp.data.FtpSource
import com.bookrio.ftp.transfer.ActiveTransfer
import com.bookrio.ftp.transfer.PreparingTransfer

/** Coarse status shown on a source card. */
enum class SourceStatus {
    CONNECTED,
    PREPARING,
    SYNCING,
    PAUSED,
    RETRYING,
    NEEDS_AUTH,
    ERROR,
    IDLE,
    DISABLED
}

data class FtpSourceSummary(
    val source: FtpSource,
    val counts: TransferCounts,
    val active: List<ActiveTransfer>,
    val preparing: PreparingTransfer? = null
) {
    val status: SourceStatus
        get() = when {
            source.state == FtpSourceStateEntity.DISABLED -> SourceStatus.DISABLED
            source.state == FtpSourceStateEntity.NEEDS_AUTH -> SourceStatus.NEEDS_AUTH
            source.state == FtpSourceStateEntity.CONNECTION_ERROR -> SourceStatus.ERROR
            active.isNotEmpty() || counts.running > 0 -> SourceStatus.SYNCING
            preparing != null -> SourceStatus.PREPARING
            counts.paused > 0 -> SourceStatus.PAUSED
            counts.retrying > 0 -> SourceStatus.RETRYING
            counts.queued > 0 -> SourceStatus.CONNECTED
            else -> SourceStatus.CONNECTED
        }

    val hasWork: Boolean
        get() = counts.queued > 0 || counts.running > 0 || active.isNotEmpty() || preparing != null

    val currentFileName: String?
        get() = active.firstOrNull()?.name
}

fun countsOf(tasks: List<DownloadTaskEntity>): TransferCounts {
    if (tasks.isEmpty()) return TransferCounts()
    var queued = 0
    var running = 0
    var completed = 0
    var failed = 0
    var paused = 0
    var retrying = 0
    for (task in tasks) {
        when (task.status) {
            DownloadStatusEntity.QUEUED, DownloadStatusEntity.PENDING -> queued++
            DownloadStatusEntity.RUNNING, DownloadStatusEntity.VERIFYING, DownloadStatusEntity.IMPORTING -> running++
            DownloadStatusEntity.COMPLETED -> completed++
            DownloadStatusEntity.FAILED -> failed++
            DownloadStatusEntity.PAUSED, DownloadStatusEntity.PAUSED_BY_USER -> paused++
            DownloadStatusEntity.RETRYING, DownloadStatusEntity.WAITING_FOR_NETWORK -> retrying++
            DownloadStatusEntity.CANCELLED -> Unit
        }
    }
    return TransferCounts(
        total = tasks.size,
        queued = queued,
        running = running,
        completed = completed,
        failed = failed,
        paused = paused,
        retrying = retrying
    )
}