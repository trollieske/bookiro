package com.bookrio.data.local.entity

import com.bookrio.core.time.nowMillis
import androidx.room.*

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("book_id")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "type") val type: BookmarkTypeEntity = BookmarkTypeEntity.GENERIC,

    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "snippet") val snippet: String? = null,

    @ColumnInfo(name = "page_index") val pageIndex: Int? = null,
    @ColumnInfo(name = "page_offset") val pageOffset: Float? = null,
    @ColumnInfo(name = "anchor_href") val anchorHref: String? = null,
    @ColumnInfo(name = "anchor_cfi") val anchorCfi: String? = null,
    @ColumnInfo(name = "position_ms") val positionMs: Long? = null,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int? = null,

    @ColumnInfo(name = "position_percent") val positionPercent: Float? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = nowMillis()
)

enum class BookmarkTypeEntity { GENERIC, HIGHLIGHT, NOTE, CHAPTER }

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("book_id")]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,

    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "color") val color: Int? = null,

    @ColumnInfo(name = "start_cfi") val startCfi: String? = null,
    @ColumnInfo(name = "end_cfi") val endCfi: String? = null,
    @ColumnInfo(name = "start_page_offset") val startPageOffset: Float? = null,
    @ColumnInfo(name = "end_page_offset") val endPageOffset: Float? = null,
    @ColumnInfo(name = "page_index") val pageIndex: Int? = null,

    @ColumnInfo(name = "position_percent") val positionPercent: Float? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = nowMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = nowMillis()
)
