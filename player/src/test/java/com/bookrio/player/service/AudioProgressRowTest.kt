package com.bookrio.player.service

import com.bookrio.data.local.entity.ReadingProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The audiobook autosave used to write a bare `ReadingProgressEntity(bookId, pct)`
 * through `INSERT OR REPLACE`, nulling `position_ms` and the chapter fields on the
 * unique `book_id` row. "Continue listening" then read position 0 and showed the
 * full book duration as still remaining.
 */
class AudioProgressRowTest {

    private fun prior() = ReadingProgressEntity(
        id = 7L,
        bookId = 42L,
        progressPercent = 0.2f,
        positionMs = 3_600_000L,
        chapterIndex = 4,
        chapterPositionMs = 120_000L,
        updatedAt = 111L,
    )

    @Test
    fun `updates progress and live position without dropping stored fields`() {
        val row = audioProgressRow(prior(), bookId = 42L, progressPercent = 0.35f, positionMs = 6_300_000L, now = 999L)

        assertEquals(7L, row.id)
        assertEquals(42L, row.bookId)
        assertEquals(0.35f, row.progressPercent, 0.0001f)
        assertEquals(6_300_000L, row.positionMs)
        assertEquals(4, row.chapterIndex)
        assertEquals(120_000L, row.chapterPositionMs)
        assertEquals(999L, row.updatedAt)
    }

    @Test
    fun `keeps the stored position when no live position is available`() {
        val row = audioProgressRow(prior(), bookId = 42L, progressPercent = 1f, positionMs = null, now = 999L)

        assertEquals(3_600_000L, row.positionMs)
        assertEquals(4, row.chapterIndex)
        assertEquals(1f, row.progressPercent, 0.0001f)
    }

    @Test
    fun `creates a fresh row when the book has no progress yet`() {
        val row = audioProgressRow(null, bookId = 5L, progressPercent = 0.1f, positionMs = 600_000L, now = 42L)

        assertEquals(5L, row.bookId)
        assertEquals(0.1f, row.progressPercent, 0.0001f)
        assertEquals(600_000L, row.positionMs)
        assertEquals(42L, row.updatedAt)
        assertNull(row.chapterIndex)
        assertNull(row.pageIndex)
    }

    @Test
    fun `clamps progress percent into 0-1`() {
        assertEquals(1f, audioProgressRow(prior(), 42L, 1.4f, null).progressPercent, 0.0001f)
        assertEquals(0f, audioProgressRow(prior(), 42L, -0.5f, null).progressPercent, 0.0001f)
    }
}