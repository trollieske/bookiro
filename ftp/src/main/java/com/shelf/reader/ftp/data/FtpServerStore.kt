package com.shelf.reader.ftp.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shelf.reader.ftp.client.FtpProtocol
import org.json.JSONArray

/**
 * Legacy server list once stored as an encrypted-preferences JSON blob.
 *
 * This class is deliberately **read-only**. It exists only so that an existing
 * installation can migrate its servers into Room exactly once. It is no longer a
 * competing persistence mechanism: [save] and [delete] are gone.
 *
 * After a successful, verified migration the blob is cleared and
 * [isMigrationComplete] returns true.
 */
data class FtpSavedServer(
    val id: Long,
    val name: String,
    val server: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: FtpProtocol = FtpProtocol.FTP,
    val usePassiveMode: Boolean = true,
    val defaultRemotePath: String = "/"
) {
    val useTls: Boolean get() = protocol.isSecure
}

class FtpServerStore(context: Context) {

    companion object {
        private const val FILE_NAME = "shelf_ftp_servers_enc.xml"
        private const val KEY_SERVERS = "servers_json"
        private const val KEY_MIGRATION_COMPLETE = "migration_complete_v1"
        private const val PLAIN_FILE_SUFFIX = "_ftp_plain"
    }

    private val appContext = context.applicationContext

    private val prefs by lazy {
        runCatching {
            val master = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                FILE_NAME,
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            appContext.getSharedPreferences(
                "${appContext.packageName}$PLAIN_FILE_SUFFIX",
                Context.MODE_PRIVATE
            )
        }
    }

    /** Reads legacy servers. Safe to call repeatedly; returns an empty list once migrated. */
    fun readLegacy(): List<FtpSavedServer> = runCatching {
        val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        parse(raw)
    }.getOrElse { emptyList() }

    fun isMigrationComplete(): Boolean =
        runCatching { prefs.getBoolean(KEY_MIGRATION_COMPLETE, false) }.getOrDefault(false)

    /** Called only after Room insert could be verified. Never called before that. */
    fun markMigrationComplete() {
        runCatching { prefs.edit().putBoolean(KEY_MIGRATION_COMPLETE, true).apply() }
    }

    /**
     * Removes the legacy credentials blob. Only called after the migration is
     * verified complete, so no data is lost.
     */
    fun clearLegacy() {
        runCatching { prefs.edit().remove(KEY_SERVERS).apply() }
    }

    private fun parse(raw: String): List<FtpSavedServer> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val rawProto = o.optString("protocol", "")
            val legacyTls = o.optBoolean("useTls", false)
            val proto = when {
                rawProto.isNotBlank() -> runCatching { FtpProtocol.valueOf(rawProto) }.getOrDefault(FtpProtocol.FTP)
                legacyTls -> FtpProtocol.FTPS_EXPLICIT
                else -> FtpProtocol.FTP
            }
            FtpSavedServer(
                id = o.optLong("id", 0L),
                name = o.optString("name", ""),
                server = o.optString("server", ""),
                port = o.optInt("port", proto.defaultPort),
                username = o.optString("username", ""),
                password = o.optString("password", ""),
                protocol = proto,
                usePassiveMode = o.optBoolean("usePassiveMode", true),
                defaultRemotePath = o.optString("defaultRemotePath", "/")
            )
        }.filter { it.server.isNotBlank() && it.username.isNotBlank() }
    }.getOrElse { emptyList() }
}