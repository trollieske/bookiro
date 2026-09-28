package com.bookrio.podcast.domain

import com.bookrio.data.local.entity.PodcastDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastDownloadPolicyTest {

    @Test
    fun `download is not completed when local output is missing or empty`() {
        assertFalse(
            PodcastDownloadPolicy.completedIsValid(
                PodcastDownloadStatus.DOWNLOADED, "file:///x.mp3", 0L
            )
        )
        assertFalse(
            PodcastDownloadPolicy.completedIsValid(
                PodcastDownloadStatus.DOWNLOADED, null, 100L
            )
        )
        assertTrue(
            PodcastDownloadPolicy.completedIsValid(
                PodcastDownloadStatus.DOWNLOADED, "file:///x.mp3", 100L
            )
        )
    }

    @Test
    fun `failed download state permits retry`() {
        assertTrue(PodcastDownloadPolicy.retryable(PodcastDownloadStatus.FAILED))
        assertTrue(PodcastDownloadPolicy.retryable(PodcastDownloadStatus.NOT_DOWNLOADED))
        assertFalse(PodcastDownloadPolicy.retryable(PodcastDownloadStatus.DOWNLOADED))
        assertFalse(PodcastDownloadPolicy.retryable(PodcastDownloadStatus.QUEUED))
        assertFalse(PodcastDownloadPolicy.retryable(PodcastDownloadStatus.DOWNLOADING))
    }

    @Test
    fun `valid local download is preferred over remote`() {
        assertTrue(PodcastDownloadPolicy.preferLocal(PodcastDownloadStatus.DOWNLOADED, "file:///x.mp3", 10L))
        assertFalse(PodcastDownloadPolicy.preferLocal(PodcastDownloadStatus.NOT_DOWNLOADED, null, 0L))
        assertFalse(PodcastDownloadPolicy.preferLocal(PodcastDownloadStatus.FAILED, "file:///x.mp3", 10L))
    }
}

class PodcastResumePolicyTest {

    @Test
    fun `multiple active episodes sort by last played time descending`() {
        val items = listOf(
            candidate(1, position = 100, lastPlayed = 300),
            candidate(2, position = 200, lastPlayed = 500),
            candidate(3, position = 300, lastPlayed = 400)
        )
        val active = PodcastResumePolicy.active(items)
        assertEquals(listOf(2L, 3L, 1L), active.map { it.episodeId })
    }

    @Test
    fun `completed episodes are excluded`() {
        val items = listOf(
            candidate(1, position = 100, lastPlayed = 900, completed = true),
            candidate(2, position = 100, lastPlayed = 500)
        )
        val active = PodcastResumePolicy.active(items)
        assertEquals(listOf(2L), active.map { it.episodeId })
    }

    @Test
    fun `unfollowed and unstarted episodes are excluded`() {
        val items = listOf(
            candidate(1, position = 100, lastPlayed = 900, followed = false),
            candidate(2, position = 0, lastPlayed = 800),
            candidate(3, position = 100, lastPlayed = null),
            candidate(4, position = 50, lastPlayed = 400)
        )
        val active = PodcastResumePolicy.active(items)
        assertEquals(listOf(4L), active.map { it.episodeId })
    }

    @Test
    fun `limit caps the resume list at five`() {
        val items = (1..8).map { candidate(it.toLong(), position = 10, lastPlayed = it.toLong()) }
        assertEquals(PodcastResumePolicy.MAX_ITEMS, PodcastResumePolicy.active(items).size)
    }

    private fun candidate(
        id: Long,
        position: Long,
        lastPlayed: Long?,
        completed: Boolean = false,
        followed: Boolean = true
    ) = PodcastResumeCandidate(
        episodeId = id,
        positionMs = position,
        isCompleted = completed,
        lastPlayedAt = lastPlayed,
        feedFollowed = followed
    )
}

class PodcastSeekTest {

    @Test
    fun `plus or minus thirty seconds clamps to episode duration`() {
        val duration = 60_000L
        assertEquals(0L, PodcastSeek.clamp(-30_000L, duration))
        assertEquals(30_000L, PodcastSeek.clamp(30_000L, duration))
        assertEquals(60_000L, PodcastSeek.clamp(90_000L, duration))
    }

    @Test
    fun `clamp is safe when duration is unknown`() {
        assertEquals(0L, PodcastSeek.clamp(-5L, 0L))
        assertEquals(123L, PodcastSeek.clamp(123L, 0L))
    }
}