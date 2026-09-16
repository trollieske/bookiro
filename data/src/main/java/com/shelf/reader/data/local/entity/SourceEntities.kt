package com.shelf.reader.data.local.entity

import androidx.room.*

enum class SmbVersionEntity { SMB1, SMB2, SMB3, AUTO }
enum class SmbAuthTypeEntity { NTLM, KERBEROS, GUEST }

/** Lifecycle state shared by the durable SMB/WebDAV sources. */
enum class RemoteSourceStateEntity { ACTIVE, DISABLED, NEEDS_AUTH, CONNECTION_ERROR }

@Entity(tableName = "smb_servers")
data class SmbServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int = 445,
    @ColumnInfo(name = "share_name") val shareName: String,
    @ColumnInfo(name = "domain") val domain: String? = null,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: String? = null,

    @ColumnInfo(name = "smb_version") val smbVersion: SmbVersionEntity = SmbVersionEntity.AUTO,
    @ColumnInfo(name = "auth_type") val authType: SmbAuthTypeEntity = SmbAuthTypeEntity.NTLM,
    @ColumnInfo(name = "enable_encryption") val enableEncryption: Boolean = false,
    @ColumnInfo(name = "enable_signing") val enableSigning: Boolean = true,

    @ColumnInfo(name = "base_path") val basePath: String = "/",
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
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "state") val state: RemoteSourceStateEntity = RemoteSourceStateEntity.ACTIVE,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "concurrency_override") val concurrencyOverride: Int = 0,
    @ColumnInfo(name = "charging_only") val chargingOnly: Boolean = false,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

enum class WebdavAuthTypeEntity { BASIC, DIGEST, BEARER, OAUTH2, NONE }

@Entity(tableName = "webdav_servers")
data class WebdavServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: String? = null,
    @ColumnInfo(name = "bearer_token_encrypted") val bearerTokenEncrypted: String? = null,

    @ColumnInfo(name = "auth_type") val authType: WebdavAuthTypeEntity = WebdavAuthTypeEntity.BASIC,
    @ColumnInfo(name = "trust_all_certificates") val trustAllCertificates: Boolean = false,
    @ColumnInfo(name = "custom_cert_pem") val customCertPem: String? = null,
    @ColumnInfo(name = "chunked_uploads") val chunkedUploads: Boolean = true,

    @ColumnInfo(name = "base_path") val basePath: String = "/remote.php/dav/files/",
    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int = 30,
    @ColumnInfo(name = "user_agent") val userAgent: String? = "ShelfReader/1.0",

    @ColumnInfo(name = "server_type") val serverType: String? = null,
    @ColumnInfo(name = "quota_bytes") val quotaBytes: Long? = null,
    @ColumnInfo(name = "quota_used_bytes") val quotaUsedBytes: Long? = null,

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
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "state") val state: RemoteSourceStateEntity = RemoteSourceStateEntity.ACTIVE,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "concurrency_override") val concurrencyOverride: Int = 0,
    @ColumnInfo(name = "charging_only") val chargingOnly: Boolean = false,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

enum class TorrentSourceTypeEntity { MAGNET, TORRENT_FILE, INFO_HASH, HTTP_URL }
enum class TorrentPriorityEntity { LOW, NORMAL, HIGH, TOP }
enum class TorrentSeedPolicyEntity { STOP_WHEN_DOWNLOADED, SEED_UNTIL_STOPPED, SEED_WHILE_ACTIVE }

@Entity(tableName = "torrent_downloads")
data class TorrentDownloadEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "source_type") val sourceType: TorrentSourceTypeEntity,
    @ColumnInfo(name = "source_data") val sourceData: String,

    @ColumnInfo(name = "info_hash") val infoHash: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "comment") val comment: String? = null,
    @ColumnInfo(name = "created_by") val createdBy: String? = null,

    @ColumnInfo(name = "save_path") val savePath: String,
    @ColumnInfo(name = "total_size_bytes") val totalSizeBytes: Long = 0L,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0L,
    @ColumnInfo(name = "uploaded_bytes") val uploadedBytes: Long = 0L,

    @ColumnInfo(name = "status") val status: DownloadStatusEntity = DownloadStatusEntity.PENDING,
    @ColumnInfo(name = "priority") val priority: TorrentPriorityEntity = TorrentPriorityEntity.NORMAL,
    @ColumnInfo(name = "is_paused") val isPaused: Boolean = false,
    @ColumnInfo(name = "is_sequential") val isSequential: Boolean = true,
    @ColumnInfo(name = "is_first_last_piece_priority") val isFirstLastPiecePriority: Boolean = true,

    @ColumnInfo(name = "peers_connected") val peersConnected: Int = 0,
    @ColumnInfo(name = "peers_total") val peersTotal: Int = 0,
    @ColumnInfo(name = "seeds_connected") val seedsConnected: Int = 0,
    @ColumnInfo(name = "seeds_total") val seedsTotal: Int = 0,
    @ColumnInfo(name = "download_speed_bps") val downloadSpeedBps: Long = 0L,
    @ColumnInfo(name = "upload_speed_bps") val uploadSpeedBps: Long = 0L,

    @ColumnInfo(name = "progress_percent") val progressPercent: Float = 0f,
    @ColumnInfo(name = "eta_seconds") val etaSeconds: Long? = null,
    @ColumnInfo(name = "ratio") val ratio: Float = 0f,

    @ColumnInfo(name = "files_json") val filesJson: String? = null,
    @ColumnInfo(name = "trackers_json") val trackersJson: String? = null,
    @ColumnInfo(name = "web_seeds_json") val webSeedsJson: String? = null,

    @ColumnInfo(name = "auto_import") val autoImport: Boolean = true,
    @ColumnInfo(name = "imported_book_ids_json") val importedBookIdsJson: String? = null,
    @ColumnInfo(name = "import_status") val importStatus: String? = null,

    @ColumnInfo(name = "max_download_speed_kbps") val maxDownloadSpeedKbps: Int = 0,
    @ColumnInfo(name = "max_upload_speed_kbps") val maxUploadSpeedKbps: Int = 0,
    @ColumnInfo(name = "max_connections") val maxConnections: Int = 0,
    @ColumnInfo(name = "max_upload_slots") val maxUploadSlots: Int = 0,

    @ColumnInfo(name = "seed_until_ratio") val seedUntilRatio: Float? = null,
    @ColumnInfo(name = "seed_until_minutes") val seedUntilMinutes: Int? = null,
    @ColumnInfo(name = "seeding_finished_at") val seedingFinishedAt: Long? = null,
    /** User-chosen seeding policy after completion. Null is treated as SEED_UNTIL_STOPPED. */
    @ColumnInfo(name = "seed_policy") val seedPolicy: TorrentSeedPolicyEntity? = null,

    @ColumnInfo(name = "wifi_only") val wifiOnly: Boolean = true,
    @ColumnInfo(name = "charging_only") val chargingOnly: Boolean = false,
    @ColumnInfo(name = "battery_min_percent") val batteryMinPercent: Int = 20,

    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "retry_count") val retryCount: Int = 0,

    @ColumnInfo(name = "session_id") val sessionId: String? = null,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long? = null
)
