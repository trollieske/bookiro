package com.bookrio.library.sort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for smart multi-book continue (ResumeSelector).
 */
class ResumeSelectorTest {

    private fun rb(
        id: Long,
        title: String = "Book $id",
        isAudio: Boolean = false,
        progress: Float = 0f,
        positionMs: Long = 0L,
        durationMs: Long = 10 * 60_000L,
        lastActivity: Long = 0L,
        updatedAt: Long = 0L,
        dateFinished: Long? = null,
        isDeleted: Boolean = false,
        author: String = ""
    ) = ResumeSelector.ResumeBook(
        id = id,
        title = title,
        author = author,
        progressPercent = progress,
        positionMs = positionMs,
        durationMs = durationMs,
        isAudio = isAudio,
        lastActivity = lastActivity,
        updatedAt = updatedAt,
        dateFinished = dateFinished,
        isDeleted = isDeleted
    )

    // 9. Two active ebooks: newest is primary, +1 is correct
    @Test
    fun `two active ebooks newest is primary plus one`() {
        val candidates = ResumeSelector.select(
            listOf(
                rb(1, progress = 0.2f, lastActivity = 1000L),
                rb(2, progress = 0.1f, lastActivity = 2000L)
            ),
            wantAudio = false
        )
        assertEquals(2, candidates.size)
        assertEquals(2L, candidates[0].bookId)
        assertEquals(1L, candidates[1].bookId)
    }

    // 10. Two active audiobooks: newest is primary, +1 is correct
    @Test
    fun `two active audiobooks newest is primary plus one`() {
        val candidates = ResumeSelector.select(
            listOf(
                rb(1, isAudio = true, progress = 0.4f, lastActivity = 1000L),
                rb(2, isAudio = true, progress = 0.1f, lastActivity = 9000L)
            ),
            wantAudio = true
        )
        assertEquals(2, candidates.size)
        assertEquals(2L, candidates[0].bookId)
        assertEquals(1L, candidates[1].bookId)
    }

    // 11. Audio never appears in Books resume candidates
    @Test
    fun `audio never appears in books candidates`() {
        val candidates = ResumeSelector.select(
            listOf(
                rb(1, isAudio = false, progress = 0.5f, lastActivity = 1000L),
                rb(2, isAudio = true, progress = 0.5f, lastActivity = 9000L)
            ),
            wantAudio = false
        )
        assertEquals(1, candidates.size)
        assertEquals(1L, candidates[0].bookId)
    }

    // 12. Ebook never appears in Audiobooks resume candidates
    @Test
    fun `ebook never appears in audiobooks candidates`() {
        val candidates = ResumeSelector.select(
            listOf(
                rb(1, isAudio = false, progress = 0.5f, lastActivity = 9000L),
                rb(2, isAudio = true, progress = 0.5f, lastActivity = 1000L)
            ),
            wantAudio = true
        )
        assertEquals(1, candidates.size)
        assertEquals(2L, candidates[0].bookId)
    }

    // 13. Completed item is excluded
    @Test
    fun `completed item is excluded`() {
        val candidates = ResumeSelector.select(
            listOf(
                rb(1, progress = 1.0f, lastActivity = 9000L),          // 100%
                rb(2, progress = 0.995f, lastActivity = 8000L),        // above threshold
                rb(3, progress = 0.5f, dateFinished = 7000L),          // manually finished
                rb(4, progress = 0.3f, lastActivity = 6000L)           // active
            ),
            wantAudio = false
        )
        assertEquals(listOf(4L), candidates.map { it.bookId })
    }

    // 14. New activity replaces primary candidate deterministically
    @Test
    fun `new activity replaces primary deterministically`() {
        val before = ResumeSelector.select(
            listOf(
                rb(1, progress = 0.2f, lastActivity = 1000L),
                rb(2, progress = 0.2f, lastActivity = 500L)
            ),
            wantAudio = false
        )
        assertEquals(1L, before[0].bookId)

        // Book 2 is read after book 1 → becomes the new primary.
        val after = ResumeSelector.select(
            listOf(
                rb(1, progress = 0.2f, lastActivity = 1000L),
                rb(2, progress = 0.2f, lastActivity = 5000L)
            ),
            wantAudio = false
        )
        assertEquals(2L, after[0].bookId)

        // Tie on activity: updatedAt desc, then stable book id.
        val tie = ResumeSelector.select(
            listOf(
                rb(5, progress = 0.2f, lastActivity = 1000L, updatedAt = 100L),
                rb(3, progress = 0.2f, lastActivity = 1000L, updatedAt = 100L),
                rb(4, progress = 0.2f, lastActivity = 1000L, updatedAt = 200L)
            ),
            wantAudio = false
        )
        assertEquals(listOf(4L, 3L, 5L), tie.map { it.bookId })
    }

    // Extras: max 5, deleted/zero-progress/missing-activity excluded
    @Test
    fun `selection caps at five and excludes dead entries`() {
        val books = (1..8L).map { rb(it, progress = 0.1f, lastActivity = it * 100) } +
                listOf(
                    rb(9, isDeleted = true, progress = 0.1f, lastActivity = 9000L),
                    rb(10, progress = 0f, lastActivity = 9500L),
                    rb(11, progress = 0f, positionMs = 0L, lastActivity = 9600L),
                    rb(12, progress = 0.1f, lastActivity = 0L) // no genuine activity
                )
        val candidates = ResumeSelector.select(books, wantAudio = false)
        assertEquals(5, candidates.size)
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), candidates.map { it.bookId })
        assertTrue(candidates.none { it.bookId in 9L..12L })
    }
}
