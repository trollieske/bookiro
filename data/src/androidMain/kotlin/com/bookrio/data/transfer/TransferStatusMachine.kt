package com.bookrio.data.transfer

import com.bookrio.data.local.entity.DownloadStatusEntity

/**
 * Single authority for legal transfer-state transitions, shared by every remote
 * file source. Invalid transitions are rejected loudly instead of being written
 * to Room (the cause of "false RUNNING forever" states).
 */
object TransferStatusMachine {

    private val allowed: Map<DownloadStatusEntity, Set<DownloadStatusEntity>> = mapOf(
        DownloadStatusEntity.QUEUED to setOf(
            DownloadStatusEntity.RUNNING,
            DownloadStatusEntity.PAUSED_BY_USER,
            DownloadStatusEntity.CANCELLED,
            DownloadStatusEntity.WAITING_FOR_NETWORK
        ),
        DownloadStatusEntity.RUNNING to setOf(
            DownloadStatusEntity.VERIFYING,
            DownloadStatusEntity.RETRYING,
            DownloadStatusEntity.PAUSED_BY_USER,
            DownloadStatusEntity.FAILED,
            DownloadStatusEntity.CANCELLED,
            DownloadStatusEntity.WAITING_FOR_NETWORK
        ),
        DownloadStatusEntity.RETRYING to setOf(
            DownloadStatusEntity.QUEUED,
            DownloadStatusEntity.FAILED,
            DownloadStatusEntity.CANCELLED
        ),
        DownloadStatusEntity.WAITING_FOR_NETWORK to setOf(
            DownloadStatusEntity.QUEUED,
            DownloadStatusEntity.CANCELLED
        ),
        DownloadStatusEntity.PAUSED_BY_USER to setOf(
            DownloadStatusEntity.QUEUED,
            DownloadStatusEntity.CANCELLED
        ),
        DownloadStatusEntity.VERIFYING to setOf(
            DownloadStatusEntity.IMPORTING,
            DownloadStatusEntity.RETRYING,
            DownloadStatusEntity.FAILED,
            DownloadStatusEntity.CANCELLED
        ),
        DownloadStatusEntity.IMPORTING to setOf(
            DownloadStatusEntity.COMPLETED,
            DownloadStatusEntity.FAILED,
            DownloadStatusEntity.RETRYING
        ),
        DownloadStatusEntity.COMPLETED to emptySet(),
        DownloadStatusEntity.FAILED to setOf(
            DownloadStatusEntity.QUEUED,
            DownloadStatusEntity.CANCELLED
        ),
        DownloadStatusEntity.CANCELLED to setOf(
            DownloadStatusEntity.QUEUED
        )
    )

    fun canTransition(from: DownloadStatusEntity, to: DownloadStatusEntity): Boolean {
        val normalizedFrom = normalize(from)
        val normalizedTo = normalize(to)
        if (normalizedFrom == normalizedTo) return true
        return allowed[normalizedFrom]?.contains(normalizedTo) == true
    }

    fun requireTransition(from: DownloadStatusEntity, to: DownloadStatusEntity) {
        require(canTransition(from, to)) {
            "Illegal transfer transition ${normalize(from)} -> ${normalize(to)}"
        }
    }

    fun normalize(status: DownloadStatusEntity): DownloadStatusEntity = when (status) {
        DownloadStatusEntity.PENDING -> DownloadStatusEntity.QUEUED
        DownloadStatusEntity.PAUSED -> DownloadStatusEntity.PAUSED_BY_USER
        else -> status
    }
}