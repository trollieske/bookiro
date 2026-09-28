package com.bookrio.ftp.data

import com.bookrio.data.local.dao.DownloadTaskDao
import com.bookrio.data.local.dao.FtpServerDao
import com.bookrio.data.local.entity.ConnectionSecurityEntity
import com.bookrio.data.local.entity.FtpModeEntity
import com.bookrio.data.local.entity.FtpServerEntity
import com.bookrio.data.local.entity.FtpSourceStateEntity
import com.bookrio.data.local.entity.ProtocolEntity
import com.bookrio.data.local.entity.SyncIntervalEntity
import com.bookrio.ftp.client.FtpErrorKind
import com.bookrio.ftp.client.FtpException
import com.bookrio.ftp.client.FtpProtocol
import com.bookrio.ftp.client.RemoteCredentials
import com.bookrio.ftp.client.RemoteFileClient
import com.bookrio.ftp.client.RemoteFileClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Read model for the Sources UI. Never contains credentials. */
data class FtpSource(
    val id: Long,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val protocol: FtpProtocol,
    val passiveMode: Boolean,
    val basePath: String,
    val state: FtpSourceStateEntity,
    val lastError: String?,
    val syncEnabled: Boolean,
    val syncInterval: SyncIntervalEntity,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val concurrencyOverride: Int,
    val lastSyncAt: Long?,
    val lastConnectedAt: Long?,
    val hasStoredCredentials: Boolean
) {
    val securityLabel: String get() = protocol.displayName
}

/** Write model for add/edit. A blank password on edit means "keep the stored one". */
data class FtpSourceInput(
    val id: Long = 0L,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: FtpProtocol,
    val passiveMode: Boolean = true,
    val basePath: String = "/",
    val syncEnabled: Boolean = false,
    val syncInterval: SyncIntervalEntity = SyncIntervalEntity.MANUAL,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val concurrencyOverride: Int = 0
)

data class ConnectionTestResult(
    val success: Boolean,
    val errorKind: FtpErrorKind? = null,
    val message: String? = null,
    val protocol: FtpProtocol
)

data class LegacyServerInput(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: FtpProtocol,
    val passiveMode: Boolean,
    val basePath: String
)

data class MigrationReport(val inserted: Int, val reused: Int, val failed: Int)

/**
 * The only authoritative persistence for stored FTP/FTPS/SFTP sources.
 *
 * Room is the source of truth. Credentials are encrypted with the Keystore
 * before they ever reach the database, and decrypted only when a connection is
 * about to be opened.
 */
class FtpSourceRepository(
    private val serverDao: FtpServerDao,
    private val taskDao: DownloadTaskDao,
    private val cipher: FtpCredentialCipher,
    val clientFactory: RemoteFileClientFactory
) {

    val sources: Flow<List<FtpSource>> =
        serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeSource(id: Long): Flow<FtpSource?> =
        serverDao.observeById(id).map { it?.toDomain() }

    fun observeEntity(id: Long): Flow<FtpServerEntity?> = serverDao.observeById(id)

    suspend fun getSource(id: Long): FtpSource? = serverDao.getById(id)?.toDomain()

    suspend fun findSource(host: String, port: Int, username: String, protocol: FtpProtocol): FtpSource? =
        withContext(Dispatchers.IO) {
            serverDao.findByNaturalKey(host.trim(), port, username.trim(), protocol.toProtocolEntity())?.toDomain()
        }

    /** Idempotent: the same natural key is never stored twice. */
    suspend fun upsert(input: FtpSourceInput): Long = withContext(Dispatchers.IO) {
        val host = input.host.trim()
        require(host.isNotBlank()) { "Host is required" }
        val port = if (input.port > 0) input.port else input.protocol.defaultPort
        val username = input.username.trim()
        val displayName = input.displayName.ifBlank { host }

        val existingById = if (input.id > 0) serverDao.getById(input.id) else null
        val existingByKey = existingById
            ?: serverDao.findByNaturalKey(host, port, username, input.protocol.toProtocolEntity())

        val encryptedPassword = when {
            input.password.isNotEmpty() -> cipher.encrypt(input.password)
            existingByKey?.passwordEncrypted != null -> existingByKey.passwordEncrypted
            else -> null
        }

        if (existingByKey != null) {
            val updated = existingByKey.copy(
                displayName = displayName,
                protocol = input.protocol.toProtocolEntity(),
                security = input.protocol.toSecurity(),
                host = host,
                port = port,
                username = username,
                passwordEncrypted = encryptedPassword,
                basePath = input.basePath.ifBlank { "/" },
                mode = if (input.passiveMode) FtpModeEntity.PASSIVE else FtpModeEntity.ACTIVE,
                syncEnabled = input.syncEnabled,
                syncInterval = input.syncInterval,
                syncWifiOnly = input.wifiOnly,
                chargingOnly = input.chargingOnly,
                concurrencyOverride = input.concurrencyOverride.coerceAtLeast(0),
                isActive = true,
                updatedAt = System.currentTimeMillis()
            )
            serverDao.update(updated)
            return@withContext updated.id
        }

        val entity = FtpServerEntity(
            displayName = displayName,
            protocol = input.protocol.toProtocolEntity(),
            security = input.protocol.toSecurity(),
            host = host,
            port = port,
            username = username,
            passwordEncrypted = encryptedPassword,
            basePath = input.basePath.ifBlank { "/" },
            mode = if (input.passiveMode) FtpModeEntity.PASSIVE else FtpModeEntity.ACTIVE,
            syncEnabled = input.syncEnabled,
            syncInterval = input.syncInterval,
            syncWifiOnly = input.wifiOnly,
            chargingOnly = input.chargingOnly,
            concurrencyOverride = input.concurrencyOverride.coerceAtLeast(0),
            state = FtpSourceStateEntity.ACTIVE,
            isActive = true
        )
        val id = serverDao.insert(entity)
        // Verify the insert actually landed before reporting success.
        check(serverDao.getById(id) != null) { "Source insert could not be verified" }
        id
    }

    /**
     * One-shot idempotent migration of the legacy preferences blob. Running it
     * more than once never creates duplicates because [upsert] reuses the
     * natural key.
     */
    suspend fun migrateLegacy(legacy: List<LegacyServerInput>): MigrationReport =
        withContext(Dispatchers.IO) {
            var inserted = 0
            var reused = 0
            var failed = 0
            for (server in legacy) {
                try {
                    val preExisting = serverDao.findByNaturalKey(
                        server.host.trim(),
                        if (server.port > 0) server.port else server.protocol.defaultPort,
                        server.username.trim(),
                        server.protocol.toProtocolEntity()
                    )
                    upsert(
                        FtpSourceInput(
                            id = preExisting?.id ?: 0L,
                            displayName = server.name,
                            host = server.host,
                            port = server.port,
                            username = server.username,
                            password = server.password,
                            protocol = server.protocol,
                            passiveMode = server.passiveMode,
                            basePath = server.basePath
                        )
                    )
                    if (preExisting != null) reused++ else inserted++
                } catch (_: Throwable) {
                    failed++
                }
            }
            MigrationReport(inserted = inserted, reused = reused, failed = failed)
        }

    /** Decrypts credentials for exactly one connection attempt. */
    suspend fun credentialsFor(id: Long): RemoteCredentials? = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext null
        val password = cipher.decrypt(entity.passwordEncrypted)
        if (entity.passwordEncrypted != null && password == null) {
            // Ciphertext but Keystore cannot open it: require a fresh sign-in.
            serverDao.updateState(id, FtpSourceStateEntity.NEEDS_AUTH, "Stored sign-in could not be read")
            return@withContext null
        }
        RemoteCredentials(
            host = entity.host,
            port = entity.port,
            username = entity.username,
            password = password.orEmpty(),
            protocol = entity.toProtocol(),
            passiveMode = entity.mode == FtpModeEntity.PASSIVE
        )
    }

    suspend fun testConnection(input: FtpSourceInput): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            val password = when {
                input.password.isNotEmpty() -> input.password
                input.id > 0 -> cipher.decrypt(serverDao.getById(input.id)?.passwordEncrypted).orEmpty()
                else -> ""
            }
            val credentials = RemoteCredentials(
                host = input.host.trim(),
                port = if (input.port > 0) input.port else input.protocol.defaultPort,
                username = input.username.trim(),
                password = password,
                protocol = input.protocol,
                passiveMode = input.passiveMode
            )
            val client = clientFactory.create()
            try {
                val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { client.connect(credentials) } ?: false
                if (!ok) {
                    ConnectionTestResult(false, FtpErrorKind.NETWORK, "connect-failed", input.protocol)
                } else {
                    ConnectionTestResult(true, null, null, input.protocol)
                }
            } catch (e: FtpException) {
                ConnectionTestResult(false, e.kind, e.kind.name, input.protocol)
            } catch (e: Throwable) {
                ConnectionTestResult(false, FtpErrorKind.UNKNOWN, "unknown", input.protocol)
            } finally {
                runCatching { client.close() }
            }
        }

    suspend fun setBasePath(id: Long, path: String) = withContext(Dispatchers.IO) {
        serverDao.updateBasePath(id, path.ifBlank { "/" })
    }

    suspend fun setSyncEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        serverDao.setSyncEnabled(id, enabled)
    }

    suspend fun setSyncPolicy(
        id: Long,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrencyOverride: Int
    ) = withContext(Dispatchers.IO) {
        serverDao.updateSyncPolicy(id, wifiOnly, chargingOnly, concurrencyOverride.coerceAtLeast(0))
    }

    suspend fun markState(id: Long, state: FtpSourceStateEntity, error: String? = null) =
        withContext(Dispatchers.IO) {
            // Never let a raw message leak credentials or full paths into the DB.
            serverDao.updateState(id, state, error?.take(MAX_ERROR_LENGTH))
        }

    suspend fun markConnected(id: Long) = withContext(Dispatchers.IO) {
        val entity = serverDao.getById(id) ?: return@withContext
        serverDao.update(
            entity.copy(
                state = FtpSourceStateEntity.ACTIVE,
                lastError = null,
                lastConnectedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markLastSync(id: Long) = withContext(Dispatchers.IO) {
        serverDao.markLastSync(id)
        serverDao.markSynced(id)
    }

    suspend fun deleteSource(id: Long, deleteQueuedTasks: Boolean) = withContext(Dispatchers.IO) {
        if (deleteQueuedTasks) {
            taskDao.deleteForServer(id)
        } else {
            taskDao.cancelForServer(id)
        }
        serverDao.deleteById(id)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val MAX_ERROR_LENGTH = 300
    }
}

// ------------------------------------------------------------------ mapping

fun FtpServerEntity.toDomain(): FtpSource = FtpSource(
    id = id,
    displayName = displayName,
    host = host,
    port = port,
    username = username,
    protocol = toProtocol(),
    passiveMode = mode == FtpModeEntity.PASSIVE,
    basePath = basePath,
    state = state,
    lastError = lastError,
    syncEnabled = syncEnabled,
    syncInterval = syncInterval,
    wifiOnly = syncWifiOnly,
    chargingOnly = chargingOnly,
    concurrencyOverride = concurrencyOverride,
    lastSyncAt = lastSyncAt,
    lastConnectedAt = lastConnectedAt,
    hasStoredCredentials = !passwordEncrypted.isNullOrBlank()
)

fun FtpServerEntity.toProtocol(): FtpProtocol = when {
    protocol == ProtocolEntity.SFTP -> FtpProtocol.SFTP
    security == ConnectionSecurityEntity.IMPLICIT_TLS -> FtpProtocol.FTPS_IMPLICIT
    protocol == ProtocolEntity.FTPS || security == ConnectionSecurityEntity.EXPLICIT_TLS -> FtpProtocol.FTPS_EXPLICIT
    else -> FtpProtocol.FTP
}

fun FtpProtocol.toProtocolEntity(): ProtocolEntity = when (this) {
    FtpProtocol.FTP -> ProtocolEntity.FTP
    FtpProtocol.FTPS_EXPLICIT, FtpProtocol.FTPS_IMPLICIT -> ProtocolEntity.FTPS
    FtpProtocol.SFTP -> ProtocolEntity.SFTP
}

fun FtpProtocol.toSecurity(): ConnectionSecurityEntity = when (this) {
    FtpProtocol.FTPS_EXPLICIT -> ConnectionSecurityEntity.EXPLICIT_TLS
    FtpProtocol.FTPS_IMPLICIT -> ConnectionSecurityEntity.IMPLICIT_TLS
    else -> ConnectionSecurityEntity.NONE
}