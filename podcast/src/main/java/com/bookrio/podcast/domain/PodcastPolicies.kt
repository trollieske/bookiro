package com.bookrio.podcast.domain

import com.bookrio.data.local.entity.PodcastDownloadStatus

/**
 * Pure download-state rules shared by the download manager and the repository.
 * Kept free of Android APIs so the rules can be unit tested directly.
 */
object PodcastDownloadPolicy {

    /** A download only counts as usable when a non-empty local output exists. */
    fun completedIsValid(status: PodcastDownloadStatus, localUri: String?, outputLength: Long): Boolean =
        status == PodcastDownloadStatus.DOWNLOADED && !localUri.isNullOrBlank() && outputLength > 0L

    /** Failed downloads can be retried. Queued/downloading/removing cannot. */
    fun retryable(status: PodcastDownloadStatus): Boolean =
        status == PodcastDownloadStatus.NOT_DOWNLOADED || status == PodcastDownloadStatus.FAILED

    /** A completed valid local file always wins over the remote enclosure URL. */
    fun preferLocal(status: PodcastDownloadStatus, localUri: String?, outputLength: Long): Boolean =
        completedIsValid(status, localUri, outputLength)
}

/** Pure seek clamping used by the podcast player and service. */
object PodcastSeek {
    fun clamp(targetMs: Long, durationMs: Long): Long {
        if (durationMs <= 0L) return targetMs.coerceAtLeast(0L)
        return targetMs.coerceIn(0L, durationMs)
    }
}

/** Candidate used by [PodcastResumePolicy]; mirrors the fields the resume query exposes. */
data class PodcastResumeCandidate(
    val episodeId: Long,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastPlayedAt: Long?,
    val feedFollowed: Boolean
)

/**
 * Multi-episode resume rules: only followed, started, unfinished episodes count,
 * newest activity first, tied on stable episode id. Completed episodes are excluded,
 * which keeps podcast resume from leaking into ebook/audiobook resume UI.
 */
object PodcastResumePolicy {
    const val MAX_ITEMS = 5

    fun active(candidates: List<PodcastResumeCandidate>, limit: Int = MAX_ITEMS): List<PodcastResumeCandidate> =
        candidates
            .filter { it.feedFollowed && !it.isCompleted && it.positionMs > 0L && it.lastPlayedAt != null }
            .sortedWith(
                compareByDescending<PodcastResumeCandidate> { it.lastPlayedAt ?: 0L }
                    .thenBy { it.episodeId }
            )
            .take(limit)
}