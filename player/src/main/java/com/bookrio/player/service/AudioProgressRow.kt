package com.bookrio.player.service

import com.bookrio.data.local.entity.ReadingProgressEntity

/**
 * Builds the `reading_progress` row written by the audiobook engine's autosave.
 *
 * The table has a unique `book_id`, and `ReadingProgressDao.insertOrReplace` is an
 * `INSERT OR REPLACE`: writing a bare `ReadingProgressEntity(bookId, pct)` therefore
 * wiped `position_ms` / chapter fields, so "continue listening" reported the whole
 * duration as still remaining. This keeps the previously stored fields and only
 * refreshes the progress, the live playback position and `updatedAt`.
 *
 * @param existing the stored row, or null for a first write.
 * @param progressPercent 0..1, clamped.
 * @param positionMs live playback position on the book timeline, or null to keep
 *   whatever is already stored (e.g. the final "finished" write).
 */
internal fun audioProgressRow(
    existing: ReadingProgressEntity?,
    bookId: Long,
    progressPercent: Float,
    positionMs: Long?,
    now: Long = System.currentTimeMillis(),
): ReadingProgressEntity {
    val base = existing ?: ReadingProgressEntity(bookId = bookId)
    return base.copy(
        progressPercent = progressPercent.coerceIn(0f, 1f),
        positionMs = positionMs ?: base.positionMs,
        updatedAt = now,
    )
}