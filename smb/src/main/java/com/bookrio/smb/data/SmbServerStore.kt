package com.bookrio.smb.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class SmbSavedServer(
    val id: Long,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val domain: String? = null,
    val username: String,
    val password: String,
    val smbVersion: String = "AUTO",
    val enableEncryption: Boolean = false,
    val defaultRemotePath: String = "/"
)

class SmbServerStore(context: Context) {

    companion object {
        private const val FILE_NAME = "shelf_smb_servers_enc.xml"
        private const val KEY_SERVERS = "servers_json"
    }

    private val appContext = context.applicationContext

    /**
     * Encrypted storage is optional: if the Keystore-backed preferences cannot
     * be created, saved servers are kept in memory for this session only. They
     * are never written to plaintext SharedPreferences.
     */
    private val prefs: android.content.SharedPreferences? by lazy {
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
        }.getOrNull()
    }

    private val _servers = MutableStateFlow<List<SmbSavedServer>>(emptyList())
    val servers: StateFlow<List<SmbSavedServer>> = _servers.asStateFlow()

    @Volatile private var loaded = false
    private val loadLock = Any()

    init {
        ensureLoaded()
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            try {
                val raw = prefs?.getString(KEY_SERVERS, "[]") ?: "[]"
                _servers.value = parse(raw)
            } catch (_: Throwable) {
                _servers.value = emptyList()
            }
            loaded = true
        }
    }

    fun save(server: SmbSavedServer): SmbSavedServer {
        ensureLoaded()
        val current = _servers.value.toMutableList()
        val finalServer = if (server.id == 0L) server.copy(id = (current.maxOfOrNull { it.id } ?: 0L) + 1) else server
        val existing = current.indexOfFirst { it.id == finalServer.id }
        if (existing >= 0) current[existing] = finalServer else current.add(finalServer)
        persist(current)
        return finalServer
    }

    fun delete(id: Long) {
        ensureLoaded()
        val current = _servers.value.filter { it.id != id }
        persist(current)
    }

    fun get(id: Long): SmbSavedServer? {
        ensureLoaded()
        return _servers.value.firstOrNull { it.id == id }
    }

    private fun persist(list: List<SmbSavedServer>) {
        runCatching {
            val json = JSONArray()
            list.forEach { s ->
                json.put(JSONObject().apply {
                    put("id", s.id)
                    put("displayName", s.displayName)
                    put("host", s.host)
                    put("port", s.port)
                    put("shareName", s.shareName)
                    put("domain", s.domain ?: "")
                    put("username", s.username)
                    put("password", s.password)
                    put("smbVersion", s.smbVersion)
                    put("enableEncryption", s.enableEncryption)
                    put("defaultRemotePath", s.defaultRemotePath)
                })
            }
            prefs?.edit()?.putString(KEY_SERVERS, json.toString())?.apply()
        }
        _servers.value = list
    }

    private fun parse(raw: String): List<SmbSavedServer> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmbSavedServer(
                id = o.optLong("id", 0L),
                displayName = o.optString("displayName", ""),
                host = o.optString("host", ""),
                port = o.optInt("port", 445),
                shareName = o.optString("shareName", ""),
                domain = o.optString("domain").ifBlank { null },
                username = o.optString("username", ""),
                password = o.optString("password", ""),
                smbVersion = o.optString("smbVersion", "AUTO"),
                enableEncryption = o.optBoolean("enableEncryption", false),
                defaultRemotePath = o.optString("defaultRemotePath", "/")
            )
        }.filter { it.host.isNotBlank() && it.shareName.isNotBlank() }
    }.getOrElse { emptyList() }
}
