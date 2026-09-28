package com.bookrio.calibre.data

import com.bookrio.calibre.client.CalibreClient
import com.bookrio.calibre.client.CalibreErrorKind
import com.bookrio.calibre.client.CalibreException
import com.bookrio.data.crypto.KeystoreSecretCipher
import com.bookrio.data.local.dao.CalibreServerDao
import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.entity.CalibreServerEntity
import com.bookrio.data.local.entity.CalibreSourceStateEntity
import com.bookrio.data.local.entity.SyncIntervalEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Read model for the Calibre sources UI. Never contains credentials. */
data class CalibreSource(
    val id: Long,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val state: CalibreSourceStateEntity,
    val lastError: String?,
    val syncEnabled: Boolean,
    val syncInterval: SyncIntervalEntity,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val lastSyncAt: Long?,
    val lastConnectedAt: Long?,
    val hasStoredCredentials: Boolean
)

/** Write model for add/edit. Blank password on edit means "keep the stored one". */
data class CalibreSourceInput(
    val id: Long = 0L,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val password: String,
    val syncEnabled: Boolean = false,
    val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false
)

data class CalibreTestResult(
    val success: Boolean,
    val errorKind: CalibreErrorKind? = null,
    val message: String? = null
)

data class CalibreCredentials(
    val baseUrl: String,
    val username: String,
    val password: String
)

/**
 * Authoritative persistence for Calibre Content Server sources.
 *
 * Room is the source of truth; the password is Keystore ciphertext and is
 * decrypted only when a connection is actually opened.
 */
class CalibreSourceRepository(
    private val serverDao: CalibreServerDao,
    private val taskDao: DownloadTaskDao,
    private val cipher: KeystoreSecretCipher
) {

    val sources: Flow<List<CalibreSource>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSource(id: Long): Flow<CalibreSource?> =
        serverDao.observeById(id).map { it?.toDomain() }

    suspend fun getSource(id: Long): CalibreSource? = serverDao.getById(id)?.toDomain()

    suspend fun upsert(input: CalibreSourceInput): Long = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(input.baseUrl)
        require(baseUrl.isNotBlank()) { "Server URL is required" }
        val displayName = input.displayName.ifBlank { baseUrl }

        val existing = if (input.id > 0) serverDao.getById(input.id) else serverDao.findByBaseUrl(baseUrl)
        val encryptedPassword = when {
            input.password.isNotEmpty() -> cipher.encrypt(input.password)
            existing?.passwordEncrypted != null -> existing.passwordEncrypted
            else -> null
        }

        if (existing != null) {
            serverDao.update(
                existing.copy(
                    displayName = displayName,
                    baseUrl = baseUrl,
                    username = input.username.trim(),
                    passwordEncrypted = encryptedPassword,
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

        val entity = CalibreServerEntity(
            displayName = displayName,
            baseUrl = baseUrl,
            username = input.username.trim(),
            passwordEncrypted = encryptedPassword,
            syncEnabled = input.syncEnabled,
            syncInterval = input.syncInterval,
            syncWifiOnly = input.wifiOnly,
            chargingOnly = input.chargingOnly,
            state = CalibreSourceStateEntity.ACTIVE,
            isActive = true
        )
        serverDao.insert(entity)
    }

    suspend fun credentialsFor(id: Long): CalibreCredentials? = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext null
        val password = cipher.decrypt(entity.passwordEncrypted)
        if (entity.passwordEncrypted != null && password == null) {
            serverDao.updateState(id, CalibreSourceStateEntity.NEEDS_AUTH, "Stored sign-in could not be read")
            return@withContext null
        }
        CalibreCredentials(entity.baseUrl, entity.username, password.orEmpty())
    }

    /** Builds a client for exactly one operation. Callers must not cache it. */
    suspend fun clientFor(id: Long): CalibreClient? {
        val credentials = credentialsFor(id) ?: return null
        return CalibreClient(
            baseUrl = credentials.baseUrl,
            username = credentials.username,
            password = credentials.password
        )
    }

    suspend fun testConnection(input: CalibreSourceInput): CalibreTestResult =
        withContext(Dispatchers.IO) {
            val password = when {
                input.password.isNotEmpty() -> input.password
                input.id > 0 -> cipher.decrypt(serverDao.getById(input.id)?.passwordEncrypted).orEmpty()
                else -> ""
            }
            val client = CalibreClient(
                baseUrl = normalizeBaseUrl(input.baseUrl),
                username = input.username.trim(),
                password = password
            )
            try {
                client.probe()
                CalibreTestResult(success = true)
            } catch (e: CalibreException) {
                CalibreTestResult(false, e.kind, e.message)
            } catch (e: Throwable) {
                CalibreTestResult(false, CalibreClient.classifyThrowable(e), null)
            }
        }

    suspend fun markConnected(id: Long) = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext
        serverDao.update(
            entity.copy(
                state = CalibreSourceStateEntity.ACTIVE,
                lastError = null,
                lastConnectedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markState(id: Long, state: CalibreSourceStateEntity, error: String? = null) =
        withContext(Dispatchers.IO) {
            serverDao.updateState(id, state, error?.take(MAX_ERROR_LENGTH))
        }

    suspend fun markLastSync(id: Long) = withContext(Dispatchers.IO) {
        serverDao.markLastSync(id)
    }

    suspend fun setSyncEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext
        serverDao.update(entity.copy(syncEnabled = enabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSource(id: Long, deleteQueuedTasks: Boolean) = withContext(Dispatchers.IO) {
        val ref = refFor(id)
        if (deleteQueuedTasks) taskDao.deleteForSource(KIND, ref) else taskDao.cancelForSource(KIND, ref)
        serverDao.deleteById(id)
    }

    companion object {
        const val KIND = "CALIBRE"
        private const val MAX_ERROR_LENGTH = 300

        fun refFor(id: Long): String = com.bookrio.data.local.entity.RemoteTaskSource.ref(KIND, id)

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (url.isBlank()) return url
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                url = "http://$url"
            }
            // Users pasting the OPDS URL is common; strip it so we always have the root.
            if (url.endsWith("/opds", ignoreCase = true)) {
                url = url.substring(0, url.length - "/opds".length)
            }
            return url
        }
    }
}

fun CalibreServerEntity.toDomain(): CalibreSource = CalibreSource(
    id = id,
    displayName = displayName,
    baseUrl = baseUrl,
    username = username,
    state = state,
    lastError = lastError,
    syncEnabled = syncEnabled,
    syncInterval = syncInterval,
    wifiOnly = syncWifiOnly,
    chargingOnly = chargingOnly,
    lastSyncAt = lastSyncAt,
    lastConnectedAt = lastConnectedAt,
    hasStoredCredentials = !passwordEncrypted.isNullOrBlank()
)