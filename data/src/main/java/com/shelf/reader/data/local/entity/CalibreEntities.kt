package com.shelf.reader.data.local.entity

import androidx.room.*

/** Stable kind/ref helpers for the shared transfer queue. */
object RemoteTaskSource {
    const val KIND_FTP = "FTP"
    const val KIND_SMB = "SMB"
    const val KIND_WEBDAV = "WEBDAV"
    const val KIND_CALIBRE = "CALIBRE"

    fun ref(kind: String, id: Long): String = "$kind:$id"
}

/** Lifecycle state of a stored Calibre Content Server source. */
enum class CalibreSourceStateEntity { ACTIVE, DISABLED, NEEDS_AUTH, CONNECTION_ERROR }

/**
 * Authoritative persistence for a Calibre Content Server. Credentials are only
 * ever stored as Keystore ciphertext.
 */
@Entity(tableName = "calibre_servers")
data class CalibreServerEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Base URL including scheme and port, e.g. http://192.168.1.10:8080 */
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "username") val username: String = "",
    @ColumnInfo(name = "password_encrypted") val passwordEncrypted: String? = null,

    @ColumnInfo(name = "timeout_seconds") val timeoutSeconds: Int = 30,

    @ColumnInfo(name = "state") val state: CalibreSourceStateEntity = CalibreSourceStateEntity.ACTIVE,
    /** Masked, human-readable last error. Never contains credentials. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,

    @ColumnInfo(name = "sync_enabled") val syncEnabled: Boolean = false,
    @ColumnInfo(name = "sync_interval") val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    @ColumnInfo(name = "sync_wifi_only") val syncWifiOnly: Boolean = true,
    @ColumnInfo(name = "charging_only") val chargingOnly: Boolean = false,
    @ColumnInfo(name = "sync_last_check_at") val syncLastCheckAt: Long? = null,

    @ColumnInfo(name = "last_connected_at") val lastConnectedAt: Long? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)