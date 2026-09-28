package com.bookrio.data.local.entity

import androidx.room.*

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("book_id", unique = true)]
)
data class ReadingProgressEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,

    @ColumnInfo(name = "progress_percent") val progressPercent: Float = 0f,

    @ColumnInfo(name = "position_ms") val positionMs: Long? = null,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int? = null,
    @ColumnInfo(name = "chapter_position_ms") val chapterPositionMs: Long? = null,

    @ColumnInfo(name = "page_index") val pageIndex: Int? = null,
    @ColumnInfo(name = "page_offset") val pageOffset: Float? = null,
    @ColumnInfo(name = "anchor_href") val anchorHref: String? = null,
    @ColumnInfo(name = "anchor_cfi") val anchorCfi: String? = null,

    @ColumnInfo(name = "scroll_pct") val scrollPct: Float? = null,

    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
