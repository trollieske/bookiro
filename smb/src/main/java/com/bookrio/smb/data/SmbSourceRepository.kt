package com.bookrio.smb.data

import com.bookrio.data.crypto.KeystoreSecretCipher
import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.dao.SmbServerDao
import com.bookrio.data.local.entity.RemoteSourceStateEntity
import com.bookrio.data.local.entity.SmbServerEntity
import com.bookrio.data.local.entity.SmbVersionEntity
import com.bookrio.data.local.entity.SyncIntervalEntity
import com.bookrio.smb.client.SmbClientEngine
import com.bookrio.smb.client.SmbErrorKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Read model for the SMB sources UI. Never contains credentials. */
data class SmbSource(
    val id: Long,
    val displayName: String,
    val host: String,
    val port: Int,
    val shareName: String,
    val domain: String?,
    val username: String,
    val basePath: String,
    val smbVersion: String,
    val enableEncryption: Boolean,
    val state: RemoteSourceStateEntity,
    val lastError: String?,
    val syncEnabled: Boolean,
    val syncInterval: SyncIntervalEntity,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val lastSyncAt: Long?,
    val lastConnectedAt: Long?,
    val hasStoredCredentials: Boolean
) {
    val addressLabel: String get() = if (port == 445) "$host/$shareName" else "$host:$port/$shareName"
}

/** Write model for add/edit. A blank password on edit means "keep the stored one". */
data class SmbSourceInput(
    val id: Long = 0L,
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val domain: String = "",
    val username: String,
    val password: String,
    val smbVersion: String = "AUTO",
    val enableEncryption: Boolean = false,
    val basePath: String = "/",
    val syncEnabled: Boolean = false,
    val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false
)

data class SmbCredentials(
    val host: String,
    val port: Int,
    val shareName: String,
    val domain: String?,
    val username: String,
    val password: String,
    val smbVersion: String,
    val enableEncryption: Boolean
)

data class SmbTestResult(val success: Boolean, val errorKind: SmbErrorKind? = null)

/**
 * The only authoritative persistence for stored SMB sources.
 *
 * Room is the source of truth; the password is Keystore ciphertext and is
 * decrypted only when a connection is about to be opened.
 */
class SmbSourceRepository(
    private val serverDao: SmbServerDao,
    private val taskDao: DownloadTaskDao,
    private val cipher: KeystoreSecretCipher,
    private val engineFactory: () -> SmbClientEngine = { SmbClientEngine() }
) {

    val sources: Flow<List<SmbSource>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSource(id: Long): Flow<SmbSource?> = serverDao.observeById(id).map { it?.toDomain() }

    suspend fun getSource(id: Long): SmbSource? = serverDao.getById(id)?.toDomain()

    suspend fun syncEnabledSources(): List<SmbSource> =
        withContext(Dispatchers.IO) { serverDao.getSyncEnabledServers().map { it.toDomain() } }

    suspend fun upsert(input: SmbSourceInput): Long = withContext(Dispatchers.IO) {
        val host = input.host.trim()
        require(host.isNotBlank()) { "Host is required" }
        val share = input.shareName.trim().trim('/')
        require(share.isNotBlank()) { "Share is required" }
        val username = input.username.trim()
        val displayName = input.displayName.ifBlank { "$host/$share" }
        val port = if (input.port in 1..65535) input.port else 445

        val existing = if (input.id > 0) serverDao.getById(input.id)
        else serverDao.findByNaturalKey(host, port, share, username)

        val encryptedPassword = when {
            input.password.isNotEmpty() -> cipher.encrypt(input.password)
            existing?.passwordEncrypted != null -> existing.passwordEncrypted
            else -> null
        }

        if (existing != null) {
            serverDao.update(
                existing.copy(
                    displayName = displayName,
                    host = host,
                    port = port,
                    shareName = share,
                    domain = input.domain.trim().ifBlank { null },
                    username = username,
                    passwordEncrypted = encryptedPassword,
                    smbVersion = input.smbVersion.toEntity(),
                    enableEncryption = input.enableEncryption,
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
            SmbServerEntity(
                displayName = displayName,
                host = host,
                port = port,
                shareName = share,
                domain = input.domain.trim().ifBlank { null },
                username = username,
                passwordEncrypted = encryptedPassword,
                smbVersion = input.smbVersion.toEntity(),
                enableEncryption = input.enableEncryption,
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

    /** Decrypts credentials for exactly one connection attempt. */
    suspend fun credentialsFor(id: Long): SmbCredentials? = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext null
        val password = cipher.decrypt(entity.passwordEncrypted)
        if (entity.passwordEncrypted != null && password == null) {
            serverDao.updateState(id, RemoteSourceStateEntity.NEEDS_AUTH, "Stored sign-in could not be read")
            return@withContext null
        }
        SmbCredentials(
            host = entity.host,
            port = entity.port,
            shareName = entity.shareName,
            domain = entity.domain,
            username = entity.username,
            password = password.orEmpty(),
            smbVersion = entity.smbVersion.toWire(),
            enableEncryption = entity.enableEncryption
        )
    }

    suspend fun testConnection(input: SmbSourceInput): SmbTestResult = withContext(Dispatchers.IO) {
        val password = when {
            input.password.isNotEmpty() -> input.password
            input.id > 0 -> cipher.decrypt(serverDao.getById(input.id)?.passwordEncrypted).orEmpty()
            else -> ""
        }
        val engine = engineFactory()
        try {
            val result = engine.connectResult(
                host = input.host.trim(),
                port = if (input.port in 1..65535) input.port else 445,
                shareName = input.shareName.trim().trim('/'),
                domain = input.domain.trim().ifBlank { null },
                username = input.username.trim(),
                password = password,
                smbVersion = input.smbVersion,
                enableEncryption = input.enableEncryption
            )
            SmbTestResult(result.success, result.kind)
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

    suspend fun setBasePath(id: Long, path: String) = withContext(Dispatchers.IO) {
        serverDao.updateBasePath(id, path.ifBlank { "/" })
    }

    suspend fun setSyncEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        serverDao.setSyncEnabled(id, enabled)
    }

    suspend fun setSyncPolicy(id: Long, wifiOnly: Boolean, chargingOnly: Boolean, concurrency: Int) =
        withContext(Dispatchers.IO) {
            serverDao.updateSyncPolicy(id, wifiOnly, chargingOnly, concurrency.coerceAtLeast(0))
        }

    suspend fun deleteSource(id: Long, deleteQueuedTasks: Boolean) = withContext(Dispatchers.IO) {
        val ref = com.bookrio.data.local.entity.RemoteTaskSource.ref(KIND, id)
        if (deleteQueuedTasks) taskDao.deleteForSource(KIND, ref) else taskDao.cancelForSource(KIND, ref)
        serverDao.deleteById(id)
    }

    /** One-shot, idempotent migration of the legacy preferences blob. */
    suspend fun migrateLegacy(legacy: List<SmbSavedServer>): Int = withContext(Dispatchers.IO) {
        var migrated = 0
        for (server in legacy) {
            runCatching {
                val existing = serverDao.findByNaturalKey(
                    server.host.trim(),
                    server.port,
                    server.shareName.trim().trim('/'),
                    server.username.trim()
                )
                upsert(
                    SmbSourceInput(
                        id = existing?.id ?: 0L,
                        displayName = server.displayName,
                        host = server.host,
                        port = server.port,
                        shareName = server.shareName,
                        domain = server.domain ?: "",
                        username = server.username,
                        password = server.password,
                        smbVersion = server.smbVersion,
                        enableEncryption = server.enableEncryption,
                        basePath = server.defaultRemotePath
                    )
                )
                migrated++
            }
        }
        migrated
    }

    companion object {
        const val KIND = com.bookrio.data.local.entity.RemoteTaskSource.KIND_SMB
        private const val MAX_ERROR_LENGTH = 300
    }
}

fun SmbServerEntity.toDomain(): SmbSource = SmbSource(
    id = id,
    displayName = displayName,
    host = host,
    port = port,
    shareName = shareName,
    domain = domain,
    username = username,
    basePath = basePath,
    smbVersion = smbVersion.toWire(),
    enableEncryption = enableEncryption,
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

private fun SmbVersionEntity.toWire(): String = when (this) {
    SmbVersionEntity.SMB1 -> "SMB1"
    SmbVersionEntity.SMB2 -> "SMB2"
    SmbVersionEntity.SMB3 -> "SMB3"
    SmbVersionEntity.AUTO -> "AUTO"
}

private fun String.toEntity(): SmbVersionEntity = when (uppercase()) {
    "SMB1" -> SmbVersionEntity.SMB1
    "SMB2" -> SmbVersionEntity.SMB2
    "SMB3" -> SmbVersionEntity.SMB3
    else -> SmbVersionEntity.AUTO
}