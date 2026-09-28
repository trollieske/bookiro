package com.bookrio.webdav.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class WebdavSavedServer(
    val id: Long,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val bearerToken: String,
    val authType: String = "BASIC",
    val trustAllCertificates: Boolean = false,
    val defaultRemotePath: String = "/"
)

class WebdavServerStore(context: Context) {

    companion object {
        private const val FILE_NAME = "shelf_webdav_servers_enc.xml"
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

    private val _servers = MutableStateFlow<List<WebdavSavedServer>>(emptyList())
    val servers: StateFlow<List<WebdavSavedServer>> = _servers.asStateFlow()

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

    fun save(server: WebdavSavedServer): WebdavSavedServer {
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

    fun get(id: Long): WebdavSavedServer? {
        ensureLoaded()
        return _servers.value.firstOrNull { it.id == id }
    }

    private fun persist(list: List<WebdavSavedServer>) {
        runCatching {
            val json = JSONArray()
            list.forEach { s ->
                json.put(JSONObject().apply {
                    put("id", s.id)
                    put("displayName", s.displayName)
                    put("baseUrl", s.baseUrl)
                    put("username", s.username)
                    put("password", s.password)
                    put("bearerToken", s.bearerToken)
                    put("authType", s.authType)
                    put("trustAllCertificates", s.trustAllCertificates)
                    put("defaultRemotePath", s.defaultRemotePath)
                })
            }
            prefs?.edit()?.putString(KEY_SERVERS, json.toString())?.apply()
        }
        _servers.value = list
    }

    private fun parse(raw: String): List<WebdavSavedServer> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WebdavSavedServer(
                id = o.optLong("id", 0L),
                displayName = o.optString("displayName", ""),
                baseUrl = o.optString("baseUrl", ""),
                username = o.optString("username", ""),
                password = o.optString("password", ""),
                bearerToken = o.optString("bearerToken", ""),
                authType = o.optString("authType", "BASIC"),
                trustAllCertificates = o.optBoolean("trustAllCertificates", false),
                defaultRemotePath = o.optString("defaultRemotePath", "/")
            )
        }.filter { it.baseUrl.isNotBlank() }
    }.getOrElse { emptyList() }
}
