package com.bookrio.webdav.data

import com.bookrio.data.crypto.KeystoreSecretCipher
import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.dao.WebdavServerDao
import com.bookrio.data.local.entity.RemoteSourceStateEntity
import com.bookrio.data.local.entity.SyncIntervalEntity
import com.bookrio.data.local.entity.WebdavAuthTypeEntity
import com.bookrio.data.local.entity.WebdavServerEntity
import com.bookrio.webdav.client.WebdavClientEngine
import com.bookrio.webdav.client.WebdavErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Read model for the WebDAV sources UI. Never contains credentials. */
data class WebdavSource(
    val id: Long,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val authType: String,
    val trustAllCertificates: Boolean,
    val basePath: String,
    val state: RemoteSourceStateEntity,
    val lastError: String?,
    val syncEnabled: Boolean,
    val syncInterval: SyncIntervalEntity,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val lastSyncAt: Long?,
    val lastConnectedAt: Long?,
    val hasStoredCredentials: Boolean
)

/** Write model for add/edit. Blank password/token on edit keeps the stored value. */
data class WebdavSourceInput(
    val id: Long = 0L,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val password: String = "",
    val bearerToken: String = "",
    val authType: String = "BASIC",
    val trustAllCertificates: Boolean = false,
    val basePath: String = "/",
    val syncEnabled: Boolean = false,
    val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false
)

data class WebdavCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
    val bearerToken: String,
    val authType: String,
    val trustAllCertificates: Boolean
)

data class WebdavTestResult(val success: Boolean, val errorKind: WebdavErrorKind? = null)

/**
 * The only authoritative persistence for stored WebDAV sources.
 *
 * Room is the source of truth; the password/bearer token are Keystore ciphertext
 * and are decrypted only when a connection is about to be opened.
 */
class WebdavSourceRepository(
    private val serverDao: WebdavServerDao,
    private val taskDao: DownloadTaskDao,
    private val cipher: KeystoreSecretCipher,
    private val engineFactory: () -> WebdavClientEngine = { WebdavClientEngine() }
) {

    val sources: Flow<List<WebdavSource>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSource(id: Long): Flow<WebdavSource?> = serverDao.observeById(id).map { it?.toDomain() }

    suspend fun getSource(id: Long): WebdavSource? = serverDao.getById(id)?.toDomain()

    suspend fun syncEnabledSources(): List<WebdavSource> =
        withContext(Dispatchers.IO) { serverDao.getSyncEnabledServers().map { it.toDomain() } }

    suspend fun upsert(input: WebdavSourceInput): Long = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(input.baseUrl)
        require(baseUrl.isNotBlank()) { "Server URL is required" }
        val username = input.username.trim()
        val displayName = input.displayName.ifBlank { baseUrl }

        val existing = if (input.id > 0) serverDao.getById(input.id)
        else serverDao.findByNaturalKey(baseUrl, username)

        val encryptedPassword = when {
            input.password.isNotEmpty() -> cipher.encrypt(input.password)
            existing?.passwordEncrypted != null -> existing.passwordEncrypted
            else -> null
        }
        val encryptedToken = when {
            input.bearerToken.isNotEmpty() -> cipher.encrypt(input.bearerToken)
            existing?.bearerTokenEncrypted != null -> existing.bearerTokenEncrypted
            else -> null
        }

        if (existing != null) {
            serverDao.update(
                existing.copy(
                    displayName = displayName,
                    baseUrl = baseUrl,
                    username = username,
                    passwordEncrypted = encryptedPassword,
                    bearerTokenEncrypted = encryptedToken,
                    authType = input.authType.toEntity(),
                    trustAllCertificates = input.trustAllCertificates,
                    basePath = input.basePath.ifBlank { "/" },
                    syncEnabled = input.syncEnabled,
                    syncInterval = input.syncInterval,
                    syncWifiOnly = input.wifiOnly,
                    chargingOnly = input.chargingOnly,
                    isActive = true,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext existing.id
        }

        serverDao.insert(
            WebdavServerEntity(
                displayName = displayName,
                baseUrl = baseUrl,
                username = username,
                passwordEncrypted = encryptedPassword,
                bearerTokenEncrypted = encryptedToken,
                authType = input.authType.toEntity(),
                trustAllCertificates = input.trustAllCertificates,
                basePath = input.basePath.ifBlank { "/" },
                syncEnabled = input.syncEnabled,
                syncInterval = input.syncInterval,
                syncWifiOnly = input.wifiOnly,
                chargingOnly = input.chargingOnly,
                state = RemoteSourceStateEntity.ACTIVE,
                isActive = true
            )
        )
    }

    suspend fun credentialsFor(id: Long): WebdavCredentials? = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext null
        val password = cipher.decrypt(entity.passwordEncrypted)
        val token = cipher.decrypt(entity.bearerTokenEncrypted)
        if (entity.passwordEncrypted != null && password == null && entity.bearerTokenEncrypted == null) {
            serverDao.updateState(id, RemoteSourceStateEntity.NEEDS_AUTH, "Stored sign-in could not be read")
            return@withContext null
        }
        WebdavCredentials(
            baseUrl = entity.baseUrl,
            username = entity.username,
            password = password.orEmpty(),
            bearerToken = token.orEmpty(),
            authType = entity.authType.toWire(),
            trustAllCertificates = entity.trustAllCertificates
        )
    }

    suspend fun testConnection(input: WebdavSourceInput): WebdavTestResult = withContext(Dispatchers.IO) {
        val existing = if (input.id > 0) serverDao.getById(input.id) else null
        val password = if (input.password.isNotEmpty()) input.password
        else cipher.decrypt(existing?.passwordEncrypted).orEmpty()
        val token = if (input.bearerToken.isNotEmpty()) input.bearerToken
        else cipher.decrypt(existing?.bearerTokenEncrypted).orEmpty()

        val engine = engineFactory()
        try {
            val result = engine.connectResult(
                baseUrl = normalizeBaseUrl(input.baseUrl),
                username = input.username.trim(),
                password = password.ifBlank { null },
                bearerToken = token.ifBlank { null },
                authType = input.authType,
                trustAllCertificates = input.trustAllCertificates,
                userAgent = USER_AGENT
            )
            WebdavTestResult(result.success, result.kind)
        } finally {
            runCatching { engine.disconnect() }
        }
    }

    suspend fun markConnected(id: Long) = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext
        serverDao.update(
            entity.copy(
                state = RemoteSourceStateEntity.ACTIVE,
                lastError = null,
                lastConnectedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markState(id: Long, state: RemoteSourceStateEntity, error: String? = null) =
        withContext(Dispatchers.IO) { serverDao.updateState(id, state, error?.take(MAX_ERROR_LENGTH)) }

    suspend fun markLastSync(id: Long) = withContext(Dispatchers.IO) { serverDao.markLastSync(id) }

    suspend fun deleteSource(id: Long, deleteQueuedTasks: Boolean) = withContext(Dispatchers.IO) {
        val ref = com.bookrio.data.local.entity.RemoteTaskSource.ref(KIND, id)
        if (deleteQueuedTasks) taskDao.deleteForSource(KIND, ref) else taskDao.cancelForSource(KIND, ref)
        serverDao.deleteById(id)
    }

    /** One-shot, idempotent migration of the legacy preferences blob. */
    suspend fun migrateLegacy(legacy: List<WebdavSavedServer>): Int = withContext(Dispatchers.IO) {
        var migrated = 0
        for (server in legacy) {
            runCatching {
                val existing = serverDao.findByNaturalKey(normalizeBaseUrl(server.baseUrl), server.username.trim())
                upsert(
                    WebdavSourceInput(
                        id = existing?.id ?: 0L,
                        displayName = server.displayName,
                        baseUrl = server.baseUrl,
                        username = server.username,
                        password = server.password,
                        bearerToken = server.bearerToken,
                        authType = server.authType,
                        trustAllCertificates = server.trustAllCertificates,
                        basePath = server.defaultRemotePath
                    )
                )
                migrated++
            }
        }
        migrated
    }

    companion object {
        const val KIND = com.bookrio.data.local.entity.RemoteTaskSource.KIND_WEBDAV
        const val USER_AGENT = "Vierel/1.0 (Android; WebDAV)"
        private const val MAX_ERROR_LENGTH = 300

        /** Keeps the scheme/path the user entered; only trims a trailing slash. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isBlank()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "https://$url"
            }
            return url
        }
    }
}

fun WebdavServerEntity.toDomain(): WebdavSource = WebdavSource(
    id = id,
    displayName = displayName,
    baseUrl = baseUrl,
    username = username,
    authType = authType.toWire(),
    trustAllCertificates = trustAllCertificates,
    basePath = basePath,
    state = state,
    lastError = lastError,
    syncEnabled = syncEnabled,
    syncInterval = syncInterval,
    wifiOnly = syncWifiOnly,
    chargingOnly = chargingOnly,
    lastSyncAt = lastSyncAt,
    lastConnectedAt = lastConnectedAt,
    hasStoredCredentials = !passwordEncrypted.isNullOrBlank() || !bearerTokenEncrypted.isNullOrBlank()
)

private fun WebdavAuthTypeEntity.toWire(): String = name

private fun String.toEntity(): WebdavAuthTypeEntity =
    runCatching { WebdavAuthTypeEntity.valueOf(uppercase()) }.getOrDefault(WebdavAuthTypeEntity.BASIC)