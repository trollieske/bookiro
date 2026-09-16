package com.shelf.reader.ftp.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production FTP / FTPS / SFTP transport.
 *
 * One instance == one connection == one transfer lane. It is **not** thread
 * safe and must never be shared between concurrent coroutines.
 *
 * Design notes:
 *  - Credentials are only used in memory here and never logged.
 *  - Resume uses FTP `REST` / SFTP offset reads.
 *  - Audio formats (mp3, m4a, m4b, …) are already compressed; SSH compression
 *    is not enabled.
 */
class FtpClientEngine(
    private val hostKeyVerifierFactory: (String, Int) -> HostKeyVerifier = { host, port ->
        // Trust-on-first-use: remember the key, then reject changes.
        TofuHostKeyVerifier(host, port, SshHostKeyStore)
    },
    private val acceptAllFtpCertificates: Boolean = true
) : RemoteFileClient {

    private var ftpClient: FTPClient? = null
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var activeProtocol: FtpProtocol = FtpProtocol.FTP
    private val closed = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = (ftpClient?.isConnected == true) ||
            (sshClient?.isConnected == true && sftpClient != null)

    override suspend fun connect(credentials: RemoteCredentials): Boolean = withContext(Dispatchers.IO) {
        disconnectInternal()
        closed.set(false)
        activeProtocol = credentials.protocol
        if (credentials.protocol == FtpProtocol.SFTP) {
            connectSftp(credentials)
        } else {
            connectFtp(credentials)
        }
    }

    private fun connectFtp(credentials: RemoteCredentials): Boolean {
        val protocol = credentials.protocol
        return try {
            val isImplicit = protocol == FtpProtocol.FTPS_IMPLICIT
            val isExplicit = protocol == FtpProtocol.FTPS_EXPLICIT

            val client: FTPClient = if (isImplicit || isExplicit) {
                FTPSClient(isImplicit).apply {
                    if (acceptAllFtpCertificates) {
                        // Seedbox/EVO deployments commonly present self-signed
                        // certificates. Certificate pinning is a future hardening
                        // step; the user is warned that FTP is unencrypted.
                        trustManager = TrustManagerUtils.getAcceptAllTrustManager()
                    }
                }
            } else {
                FTPClient()
            }

            client.setConnectTimeout(CONNECT_TIMEOUT_MS)
            client.defaultTimeout = CONNECT_TIMEOUT_MS
            client.setDataTimeout(java.time.Duration.ofMillis(DATA_TIMEOUT_MS.toLong()))
            client.controlEncoding = "UTF-8"
            client.bufferSize = DEFAULT_BUFFER_SIZE

            ftpClient = client
            client.connect(credentials.host, credentials.port)

            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                runCatching { client.disconnect() }
                ftpClient = null
                throw FtpException(FtpErrorKind.NETWORK, "FTP server refused connection (code $reply)")
            }

            val loginOk = client.login(credentials.username, credentials.password)
            if (!loginOk) {
                runCatching { client.disconnect() }
                ftpClient = null
                throw FtpException(FtpErrorKind.AUTH, "FTP login rejected")
            }

            if (client is FTPSClient) {
                runCatching {
                    client.execPBSZ(0)
                    client.execPROT("P")
                }
            }

            if (credentials.passiveMode) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
            runCatching { client.setPassiveNatWorkaroundStrategy(null) }
            client.setFileType(FTP.BINARY_FILE_TYPE)
            true
        } catch (e: FtpException) {
            throw e
        } catch (e: Exception) {
            disconnectInternal()
            throw FtpException(FtpErrorKind.NETWORK, "FTP connect failed", e)
        }
    }

    private fun connectSftp(credentials: RemoteCredentials): Boolean {
        return try {
            val ssh = SSHClient()
            ssh.connectTimeout = CONNECT_TIMEOUT_MS
            ssh.timeout = CONNECT_TIMEOUT_MS
            // SSH compression stays off: audio formats are already compressed.
            ssh.addHostKeyVerifier(hostKeyVerifierFactory(credentials.host, credentials.port))

            ssh.connect(credentials.host, credentials.port)
            ssh.authPassword(credentials.username, credentials.password)

            if (!ssh.isAuthenticated) {
                runCatching { ssh.disconnect() }
                throw FtpException(FtpErrorKind.AUTH, "SFTP authentication rejected")
            }

            val sftp = ssh.newSFTPClient()
            sshClient = ssh
            sftpClient = sftp
            true
        } catch (e: FtpException) {
            throw e
        } catch (e: Exception) {
            disconnectInternal()
            val message = (e.message ?: "").lowercase()
            if (message.contains("host key") || message.contains("fingerprint") || message.contains("verification")) {
                throw FtpException(FtpErrorKind.SERVER, "SFTP host key changed or could not be verified", e)
            }
            throw FtpException(FtpErrorKind.NETWORK, "SFTP connect failed", e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sshClient?.disconnect() }
        runCatching {
            ftpClient?.let {
                if (it.isConnected) {
                    runCatching { it.logout() }
                    it.disconnect()
                }
            }
        }
        sftpClient = null
        sshClient = null
        ftpClient = null
    }

    override fun close() {
        disconnectInternal()
    }

    override suspend fun listDirectory(path: String): List<FtpEntry> = withContext(Dispatchers.IO) {
        if (closed.get()) throw FtpException(FtpErrorKind.NETWORK, "Connection is closed")
        val targetPath = if (path.isBlank()) "/" else path
        if (activeProtocol == FtpProtocol.SFTP) {
            listDirectorySftp(targetPath)
        } else {
            listDirectoryFtp(targetPath)
        }
    }

    override suspend fun stat(remotePath: String): RemoteFileInfo? = withContext(Dispatchers.IO) {
        if (activeProtocol == FtpProtocol.SFTP) {
            val sftp = sftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "SFTP not connected")
            runCatching {
                val attrs = sftp.stat(remotePath)
                RemoteFileInfo(remotePath, attrs.size, attrs.mtime)
            }.getOrNull()
        } else {
            val client = ftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "FTP not connected")
            runCatching {
                val files = client.listFiles(remotePath)
                val file = files.firstOrNull { it.name != null && remotePath.endsWith(it.name) }
                    ?: files.firstOrNull()
                file?.let {
                    RemoteFileInfo(remotePath, it.size, (it.timestamp?.time?.time ?: 0L) / 1000L)
                }
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- listing

    private fun listDirectoryFtp(path: String): List<FtpEntry> {
        val client = ftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "FTP not connected")
        val targetPath = if (path.isBlank()) "/" else path

        if (targetPath != "/") {
            runCatching { client.changeWorkingDirectory(targetPath) }
        } else {
            runCatching { client.changeWorkingDirectory("/") }
        }

        val files: Array<FTPFile>? = try {
            client.setDataTimeout(java.time.Duration.ofMillis(LIST_TIMEOUT_MS.toLong()))
            client.listFiles()
        } catch (e: Exception) {
            runCatching {
                client.enterLocalActiveMode()
                client.setDataTimeout(java.time.Duration.ofMillis(LIST_TIMEOUT_MS.toLong()))
                client.listFiles()
            }.getOrNull()
        } ?: runCatching { client.listFiles(targetPath) }.getOrNull()

        if (files == null) {
            throw FtpException(FtpErrorKind.SERVER, "Server returned no directory listing")
        }

        val basePath = when {
            targetPath == "/" || targetPath.isBlank() -> "/"
            targetPath.endsWith("/") -> targetPath
            else -> "$targetPath/"
        }

        return files.mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            if (name == "." || name == "..") return@mapNotNull null
            val type = when {
                file.isDirectory -> FtpEntryType.FOLDER
                file.isFile -> FtpEntryType.FILE
                file.isSymbolicLink -> FtpEntryType.LINK
                else -> FtpEntryType.UNKNOWN
            }
            FtpEntry(
                name = name,
                path = if (basePath == "/") "/$name" else "$basePath$name",
                type = type,
                sizeBytes = file.size,
                modifiedEpochSec = (file.timestamp?.time?.time ?: 0L) / 1000L
            )
        }.sortedWith(compareBy<FtpEntry> { it.type != FtpEntryType.FOLDER }.thenBy { it.name.lowercase() })
    }

    private fun listDirectorySftp(path: String): List<FtpEntry> {
        val sftp = sftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "SFTP not connected")
        val resources: List<RemoteResourceInfo> = try {
            sftp.ls(path)
        } catch (e: Exception) {
            throw FtpException(FtpErrorKind.SERVER, "SFTP listing failed", e)
        }
        val basePath = when {
            path == "/" || path.isBlank() -> "/"
            path.endsWith("/") -> path
            else -> "$path/"
        }
        return resources.mapNotNull { res ->
            val name = res.name ?: return@mapNotNull null
            if (name == "." || name == "..") return@mapNotNull null
            val type = when (res.attributes.type) {
                FileMode.Type.DIRECTORY -> FtpEntryType.FOLDER
                FileMode.Type.REGULAR -> FtpEntryType.FILE
                FileMode.Type.SYMLINK -> FtpEntryType.LINK
                else -> FtpEntryType.UNKNOWN
            }
            FtpEntry(
                name = name,
                path = if (basePath == "/") "/$name" else "$basePath$name",
                type = type,
                sizeBytes = res.attributes.size,
                modifiedEpochSec = res.attributes.mtime
            )
        }.sortedWith(compareBy<FtpEntry> { it.type != FtpEntryType.FOLDER }.thenBy { it.name.lowercase() })
    }

    // --------------------------------------------------------------- download

    override suspend fun download(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (closed.get()) throw FtpException(FtpErrorKind.NETWORK, "Connection is closed")
        target.parentFile?.mkdirs()
        val safeOffset = offset.coerceAtLeast(0L)
        val effectiveBuffer = bufferSize.coerceIn(MIN_BUFFER_SIZE, MAX_BUFFER_SIZE)
        if (activeProtocol == FtpProtocol.SFTP) {
            downloadSftp(remotePath, target, safeOffset, expectedSize, effectiveBuffer, onProgress)
        } else {
            downloadFtp(remotePath, target, safeOffset, expectedSize, effectiveBuffer, onProgress)
        }
    }

    private suspend fun downloadFtp(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome {
        val client = ftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "FTP not connected")
        client.bufferSize = bufferSize
        client.setDataTimeout(java.time.Duration.ofMillis(TRANSFER_TIMEOUT_MS.toLong()))

        var startOffset = offset
        var resumeSupported = offset == 0L

        if (startOffset > 0L) {
            client.setRestartOffset(startOffset)
        } else {
            client.setRestartOffset(0)
        }

        var stream: InputStream? = try {
            client.retrieveFileStream(remotePath)
        } catch (e: Exception) {
            FtpException(FtpErrorKind.NETWORK, "FTP retrieve failed", e)
            null
        }

        if (stream == null && startOffset > 0L) {
            // Server does not support REST (or the partial did not match):
            // be honest and restart from zero.
            startOffset = 0L
            resumeSupported = false
            runCatching { client.setRestartOffset(0) }
            stream = try {
                client.retrieveFileStream(remotePath)
            } catch (e: Exception) {
                throw FtpException(FtpErrorKind.NETWORK, "FTP retrieve failed", e)
            }
        }

        if (stream == null) {
            val reply = client.replyCode
            if (reply == 550) throw FtpException(FtpErrorKind.NOT_FOUND, "Remote file not found")
            throw FtpException(FtpErrorKind.NETWORK, "FTP server returned no data stream (code $reply)")
        }

        val append = startOffset > 0L
        var written = startOffset
        try {
            FileOutputStream(target, append).use { out ->
                val buffer = ByteArray(bufferSize)
                stream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written)
                    }
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
        } catch (e: FtpException) {
            throw e
        } catch (e: Exception) {
            throw FtpException(FtpErrorKind.STORAGE, "Writing staging file failed", e)
        } finally {
            runCatching { stream.close() }
        }

        val completed = runCatching { client.completePendingCommand() }.getOrDefault(false)
        if (!completed) {
            throw FtpException(FtpErrorKind.NETWORK, "FTP transfer did not complete cleanly")
        }

        return DownloadOutcome(
            bytesWritten = if (target.exists()) target.length() else written,
            resumedFrom = startOffset,
            totalBytes = if (expectedSize > 0) expectedSize else -1L,
            serverSupportsResume = resumeSupported
        )
    }

    private suspend fun downloadSftp(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome {
        val sftp = sftpClient ?: throw FtpException(FtpErrorKind.NETWORK, "SFTP not connected")
        val remote = try {
            sftp.open(remotePath, EnumSet.of(OpenMode.READ))
        } catch (e: Exception) {
            throw FtpException(FtpErrorKind.NOT_FOUND, "SFTP could not open remote file", e)
        }

        try {
            val remoteLength = runCatching { remote.length() }.getOrDefault(expectedSize)
            val startOffset = offset.coerceIn(0L, if (remoteLength > 0) remoteLength else offset)
            val append = startOffset > 0L
            var position = startOffset
            try {
                FileOutputStream(target, append).use { out ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        val read = remote.read(position, buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        position += read
                        onProgress(position)
                    }
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            } catch (e: Exception) {
                throw FtpException(FtpErrorKind.STORAGE, "Writing staging file failed", e)
            }
            return DownloadOutcome(
                bytesWritten = if (target.exists()) target.length() else position,
                resumedFrom = startOffset,
                totalBytes = remoteLength,
                serverSupportsResume = offset > 0L
            )
        } finally {
            runCatching { remote.close() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val DATA_TIMEOUT_MS = 30_000
        private const val TRANSFER_TIMEOUT_MS = 60_000
        private const val LIST_TIMEOUT_MS = 20_000
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
        const val MIN_BUFFER_SIZE = 16 * 1024
        const val MAX_BUFFER_SIZE = 1024 * 1024
    }
}

/** Factory so instrumentation tests can substitute a fake transport. */
fun interface RemoteFileClientFactory {
    fun create(): RemoteFileClient
}

/**
 * Trust-on-first-use host key verification. The first key seen for a host:port
 * is remembered; later changes are rejected (possible MITM or server reprovision).
 */
internal class TofuHostKeyVerifier(
    private val host: String,
    private val port: Int,
    private val store: SshHostKeyStore
) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val presentable = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
        return store.verify(host, port, presentable)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        PromiscuousVerifier().findExistingAlgorithms(hostname, port)
}