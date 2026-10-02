package com.bookrio.app.ui

import com.bookrio.library.sort.ResumeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure Home logic (no Android dependencies):
 * remaining-time formatting, audio remaining calculation, and the multi-book
 * Continue ordering that replaces the old single-line "+N" strip.
 */
class HomeLogicTest {

    @Test
    fun `remaining parts round up to whole minutes`() {
        assertEquals(RemainingParts(0, 1), remainingParts(1L))
        assertEquals(RemainingParts(0, 1), remainingParts(60_000L))
        assertEquals(RemainingParts(1, 0), remainingParts(3_600_000L))
        assertEquals(RemainingParts(1, 1), remainingParts(3_660_000L))
    }

    @Test
    fun `remaining parts is null when nothing is left`() {
        assertNull(remainingParts(null))
        assertNull(remainingParts(0L))
        assertNull(remainingParts(-1L))
    }

    @Test
    fun `audio remaining needs a known duration and a started position`() {
        assertEquals(3_600_000L, audioRemainingMs(3_600_000L, 7_200_000L))
        // Not started -> no remaining label, even with a duration.
        assertNull(audioRemainingMs(0L, 7_200_000L))
        assertNull(audioRemainingMs(null, 7_200_000L))
        // Unknown duration -> cannot compute.
        assertNull(audioRemainingMs(1_000L, 0L))
        assertNull(audioRemainingMs(1_000L, null))
        // Past the end clamps to zero rather than going negative.
        assertEquals(0L, audioRemainingMs(5_000_000L, 3_600_000L))
    }

    @Test
    fun `continue merges ebooks and audiobooks by most recent activity`() {
        val ebooks = listOf(candidate(id = 1, lastActivity = 100L), candidate(id = 2, lastActivity = 300L))
        val audiobooks = listOf(candidate(id = 3, lastActivity = 200L))
        val merged = mergeContinueOrder(ebooks, audiobooks).map { it.bookId }
        assertEquals(listOf(2L, 3L, 1L), merged)
    }

    @Test
    fun `continue keeps every started book in a large library`() {
        val many = (1L..20L).map { candidate(id = it, lastActivity = it) }
        assertEquals(20, mergeContinueOrder(many, emptyList()).size)
    }

    @Test
    fun `continue breaks ties deterministically by updatedAt then id`() {
        val a = candidate(id = 7, lastActivity = 100L, updatedAt = 5L)
        val b = candidate(id = 3, lastActivity = 100L, updatedAt = 9L)
        val c = candidate(id = 9, lastActivity = 100L, updatedAt = 9L)
        assertEquals(listOf(3L, 9L, 7L), mergeContinueOrder(listOf(a, c, b), emptyList()).map { it.bookId })
    }

    private fun candidate(id: Long, lastActivity: Long, updatedAt: Long = lastActivity) =
        ResumeSelector.ResumeCandidate(
            bookId = id,
            title = "Book $id",
            author = "Author",
            detail = "10%",
            lastActivity = lastActivity,
            updatedAt = updatedAt,
            id = id
        )
}