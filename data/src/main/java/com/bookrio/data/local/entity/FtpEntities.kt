package com.bookrio.data.local.entity

import androidx.room.*

enum class ProtocolEntity { FTP, FTPS, SFTP }
enum class FtpModeEntity { PASSIVE, ACTIVE }
enum class SyncIntervalEntity { MANUAL, MIN_15, HOUR_1, HOUR_6, DAILY, ON_APP_OPEN }
enum class ConnectionSecurityEntity { EXPLICIT_TLS, IMPLICIT_TLS, NONE }

/**
 * Lifecycle state of a stored FTP/FTPS/SFTP source.
 *
 * A connection failure must never delete the source or its credentials; it only
 * moves the source into [NEEDS_AUTH] or [CONNECTION_ERROR] so the UI can offer
 * an explicit retry / sign-in instead of an empty login form.
 */
enum class FtpSourceStateEntity { ACTIVE, DISABLED, NEEDS_AUTH, CONNECTION_ERROR }

/**
 * Authoritative per-file transfer state. `PENDING` and `PAUSED` are legacy
 * values kept for rows written by older builds; new code always writes the
 * explicit states below.
 */
enum class DownloadStatusEntity {
    /** Legacy alias for [QUEUED]; tolerated on read only. */
    PENDING,
    /** Legacy alias for [PAUSED_BY_USER]; tolerated on read only. */
    PAUSED,
    QUEUED,
    RUNNING,
    PAUSED_BY_USER,
    WAITING_FOR_NETWORK,
    RETRYING,
    VERIFYING,
    IMPORTING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED
    val isActive: Boolean
        get() = this == RUNNING || this == VERIFYING || this == IMPORTING || this == RETRYING
    val isRunnable: Boolean get() = this == QUEUED || this == PENDING
}

@Entity(tableName = "ftp_servers")
data class FtpServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "protocol") val protocol: ProtocolEntity,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: String? = null,
    @ColumnInfo(name = "private_key_path") val privateKeyPath: String? = null,
    @ColumnInfo(name = "private_key_passphrase_encrypted") val privateKeyPassphraseEncrypted: String? = null,

    @ColumnInfo(name = "base_path") val basePath: String = "/",
    @ColumnInfo(name = "mode") val mode: FtpModeEntity = FtpModeEntity.PASSIVE,
    @ColumnInfo(name = "security") val security: ConnectionSecurityEntity = ConnectionSecurityEntity.NONE,

    @ColumnInfo(name = "encoding") val encoding: String = "UTF-8",
    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int = 30,

    @ColumnInfo(name = "color") val color: Int? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,

    @ColumnInfo(name = "sync_enabled") val syncEnabled: Boolean = false,
    @ColumnInfo(name = "sync_interval") val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    @ColumnInfo(name = "sync_paths_json") val syncPathsJson: String? = null,
    @ColumnInfo(name = "sync_wifi_only") val syncWifiOnly: Boolean = true,
    @ColumnInfo(name = "sync_include_pattern") val syncIncludePattern: String? = null,
    @ColumnInfo(name = "sync_exclude_pattern") val syncExcludePattern: String? = null,
    @ColumnInfo(name = "sync_last_check_at") val syncLastCheckAt: Long? = null,

    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,

    /** Lifecycle state; see [FtpSourceStateEntity]. Never a replacement for [isActive]. */
    @ColumnInfo(name = "state") val state: FtpSourceStateEntity = FtpSourceStateEntity.ACTIVE,
    /** Masked, human-readable last error. Must never contain credentials or full remote paths. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    /** 0 = Auto (transport default). Otherwise a user-pinned lane count. */
    @ColumnInfo(name = "concurrency_override") val concurrencyOverride: Int = 0,
    @ColumnInfo(name = "charging_only") val chargingOnly: Boolean = false,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(entity = FtpServerEntity::class, parentColumns = ["id"], childColumns = ["server_id"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index("server_id"),
        Index("status"),
        Index(value = ["server_id", "remote_path"], unique = true),
        Index(value = ["source_kind", "source_ref", "remote_path"], unique = true, name = "index_download_tasks_source_ref_path")
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "remote_name") val remoteName: String,
    @ColumnInfo(name = "local_path") val localPath: String? = null,

    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0L,

    @ColumnInfo(name = "status") val status: DownloadStatusEntity = DownloadStatusEntity.PENDING,
    @ColumnInfo(name = "priority") val priority: Int = 0,

    @ColumnInfo(name = "auto_import") val autoImport: Boolean = true,
    @ColumnInfo(name = "imported_book_id") val importedBookId: Long? = null,

    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,

    /** Remote size/mtime captured at enqueue time; used for resume validation. */
    @ColumnInfo(name = "remote_mtime") val remoteMtime: Long = 0L,
    /** Explicit staging path (`<local_path>.part`); null until a lane starts. */
    @ColumnInfo(name = "staging_path") val stagingPath: String? = null,
    /** Last time bytes/status were flushed; drives progress rate limiting. */
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    /** Classified failure kind ("auth", "network", "storage", "verify", …). */
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
    /** Per-file observed speed, bytes/s, flushed with progress. */
    @ColumnInfo(name = "bytes_per_sec") val bytesPerSec: Long = 0L,
    /** Earliest time a RETRYING task may run again (exponential backoff). */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,

    /**
     * Which kind of remote source owns this task. `FTP` for the legacy FTP rows
     * (kept for backwards compatibility), `SMB`, `WEBDAV`, `CALIBRE` for the
     * unified queue. Nullable so the v8 -> v9 migration needs no SQL default.
     */
    @ColumnInfo(name = "source_kind") val sourceKind: String? = null,
    /**
     * Stable owner key used for de-duplication when `server_id` is null
     * (SQLite unique indices treat NULLs as distinct). Format: `<KIND>:<id>`.
     */
    @ColumnInfo(name = "source_ref") val sourceRef: String? = null
)

@Entity(
    tableName = "cached_paths",
    foreignKeys = [
        ForeignKey(entity = FtpServerEntity::class, parentColumns = ["id"], childColumns = ["server_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("server_id", "path", unique = true)]
)
data class CachedPathEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "parent_path") val parentPath: String = "/",
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "is_directory") val isDirectory: Boolean,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "modified_time") val modifiedTime: Long = 0L,
    @ColumnInfo(name = "cached_at") val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_history")
data class SyncHistoryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "files_found") val filesFound: Int = 0,
    @ColumnInfo(name = "files_new") val filesNew: Int = 0,
    @ColumnInfo(name = "files_downloaded") val filesDownloaded: Int = 0,
    @ColumnInfo(name = "files_failed") val filesFailed: Int = 0,
    @ColumnInfo(name = "status") val status: DownloadStatusEntity = DownloadStatusEntity.PENDING,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null
)

/** Aggregate queue counters used by the notification and Transfers screen. */
data class TransferCounts(
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
    val retrying: Int = 0
)
