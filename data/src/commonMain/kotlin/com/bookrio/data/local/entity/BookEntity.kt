package com.bookrio.data.local.entity

import androidx.room.*
import com.bookrio.core.domain.model.*

enum class BookTypeEntity { EBOOK, AUDIOBOOK, MIXED }
enum class FormatEntity {
    EPUB, PDF, MOBI, AZW, AZW3, FB2, CBZ, CBR, TXT, HTML, RTF, DOCX, MD,
    M4B, M4A, MP3, AAC, FLAC, OGG, OPUS, OGG_OPUS, WAV, ZIP, UNKNOWN
}
enum class ImportSourceEntity { FILE_PICKER, FOLDER_IMPORT, SHARE_INTENT, DRAG_DROP, FTP_DOWNLOAD, SMB_DOWNLOAD, WEBDAV_DOWNLOAD, TORRENT_DOWNLOAD, CALIBRE_LIBRARY, OPDS_CATALOG, SAMPLE }
enum class SyncStatusEntity { NOT_SYNCED, SYNCED, SYNCING, ERROR }

@Entity(
    tableName = "books",
    indices = [
        Index("title"), Index("author"), Index("series"),
        Index("date_added"), Index("last_opened_at"),
        Index("type"), Index("format")
    ]
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "sort_title") val sortTitle: String = title,
    @ColumnInfo(name = "author") val author: String = "",
    @ColumnInfo(name = "sort_author") val sortAuthor: String = author,
    @ColumnInfo(name = "series") val series: String? = null,
    @ColumnInfo(name = "series_index") val seriesIndex: Float? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,
    @ColumnInfo(name = "published_date") val publishedDate: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "isbn") val isbn: String? = null,

    @ColumnInfo(name = "type") val type: BookTypeEntity,
    @ColumnInfo(name = "format") val format: FormatEntity,

    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "file_uri") val fileUri: String? = null,
    @ColumnInfo(name = "file_size_bytes") val fileSizeBytes: Long = 0L,
    @ColumnInfo(name = "file_hash") val fileHash: String? = null,
    @ColumnInfo(name = "persistable_uri_permission") val persistableUriPermission: Boolean = false,

    @ColumnInfo(name = "cover_path") val coverPath: String? = null,
    @ColumnInfo(name = "cover_color") val coverColor: Int? = null,
    @ColumnInfo(name = "spine_color") val spineColor: Int? = null,

    @ColumnInfo(name = "import_source") val importSource: ImportSourceEntity = ImportSourceEntity.FILE_PICKER,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String? = null,

    @ColumnInfo(name = "date_added") val dateAdded: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_modified_at") val lastModifiedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long? = null,
    @ColumnInfo(name = "date_finished") val dateFinished: Long? = null,

    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "rating") val rating: Int = 0,
    @ColumnInfo(name = "tags") val tags: String = "",
    @ColumnInfo(name = "notes") val notes: String? = null,

    @ColumnInfo(name = "page_count") val pageCount: Int? = null,
    @ColumnInfo(name = "word_count") val wordCount: Long? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,

    @ColumnInfo(name = "chapter_count") val chapterCount: Int? = null,
    @ColumnInfo(name = "chapters_json") val chaptersJson: String? = null,

    @ColumnInfo(name = "sync_status") val syncStatus: SyncStatusEntity = SyncStatusEntity.NOT_SYNCED,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,

    @ColumnInfo(name = "is_sample") val isSample: Boolean = false,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false
)
