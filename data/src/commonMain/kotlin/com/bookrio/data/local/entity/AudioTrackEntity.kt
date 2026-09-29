package com.bookrio.data.local.entity

import androidx.room.*

@Entity(
    tableName = "audio_tracks",
    indices = [
        Index("book_id"),
        Index("file_path", unique = true),
        Index("remote_path")
    ],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AudioTrackEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "book_id") val bookId: Long,
    @ColumnInfo(name = "track_number") val trackNumber: Int = 0,
    @ColumnInfo(name = "disc_number") val discNumber: Int = 1,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0L,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "file_uri") val fileUri: String? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long = 0L
)
