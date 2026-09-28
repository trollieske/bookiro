package com.bookrio.smb.client

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtStatus
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties

enum class SmbEntryType { FILE, FOLDER, UNKNOWN }

/** Classified SMB failure so the UI can distinguish auth from network/not-found. */
enum class SmbErrorKind { AUTH, NOT_FOUND, NETWORK, TIMEOUT, UNKNOWN }

data class SmbConnectResult(val success: Boolean, val kind: SmbErrorKind? = null)

data class SmbEntry(
    val name: String,
    val path: String,
    val type: SmbEntryType,
    val sizeBytes: Long,
    val modifiedEpochSec: Long
)

class SmbClientEngine {

    private var baseContext: CIFSContext? = null
    private var rootUrl: String? = null
    private var connected = false

    val isConnected: Boolean
        get() = connected

    suspend fun connect(
        host: String,
        port: Int = 445,
        shareName: String,
        domain: String? = null,
        username: String,
        password: String,
        smbVersion: String = "AUTO",
        enableEncryption: Boolean = false
    ): Boolean = connectResult(
        host = host,
        port = port,
        shareName = shareName,
        domain = domain,
        username = username,
        password = password,
        smbVersion = smbVersion,
        enableEncryption = enableEncryption
    ).success

    suspend fun connectResult(
        host: String,
        port: Int = 445,
        shareName: String,
        domain: String? = null,
        username: String,
        password: String,
        smbVersion: String = "AUTO",
        enableEncryption: Boolean = false
    ): SmbConnectResult = withContext(Dispatchers.IO) {
        try {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", when (smbVersion) {
                    "SMB1" -> "SMB1"
                    "SMB2" -> "SMB202"
                    "SMB3" -> "SMB300"
                    else -> "SMB202"
                })
                setProperty("jcifs.smb.client.maxVersion", when (smbVersion) {
                    "SMB1" -> "SMB1"
                    "SMB2" -> "SMB210"
                    "SMB3" -> "SMB311"
                    else -> "SMB311"
                })
                setProperty("jcifs.smb.client.dfs.disabled", "false")
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.soTimeout", "30000")
                setProperty("jcifs.smb.client.connTimeout", "15000")
                if (enableEncryption) {
                    setProperty("jcifs.smb.client.encryption", "required")
                }
            }
            val cfg = PropertyConfiguration(props)
            val auth = NtlmPasswordAuthenticator(domain ?: "", username, password)
            baseContext = BaseContext(cfg).withCredentials(auth)

            val hostPart = if (port != 445) "$host:$port" else host
            rootUrl = "smb://$hostPart/$shareName/"
            val testFile = SmbFile(rootUrl, baseContext)
            testFile.exists()
            connected = true
            SmbConnectResult(success = true)
        } catch (e: Throwable) {
            runCatching { disconnect() }
            SmbConnectResult(success = false, kind = classify(e))
        }
    }

    private fun classify(e: Throwable): SmbErrorKind {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 8) {
            when (current) {
                is SmbAuthException -> return SmbErrorKind.AUTH
                is SocketTimeoutException -> return SmbErrorKind.TIMEOUT
                is UnknownHostException, is ConnectException -> return SmbErrorKind.NETWORK
                is SmbException -> {
                    when (current.ntStatus) {
                        NtStatus.NT_STATUS_ACCESS_DENIED,
                        NtStatus.NT_STATUS_LOGON_FAILURE -> return SmbErrorKind.AUTH
                        NtStatus.NT_STATUS_BAD_NETWORK_NAME,
                        NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
                        NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND -> return SmbErrorKind.NOT_FOUND
                        else -> {
                            val message = current.message?.lowercase().orEmpty()
                            if (message.contains("resolve") || message.contains("unknown host") ||
                                message.contains("no route")
                            ) return SmbErrorKind.NETWORK
                            if (message.contains("timed out") || message.contains("timeout")) {
                                return SmbErrorKind.TIMEOUT
                            }
                        }
                    }
                }
                else -> {}
            }
            val cause = current.cause
            if (cause == null || cause === current) break
            current = cause
            depth++
        }
        return SmbErrorKind.UNKNOWN
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { closeNow() }

    /** Synchronous cleanup for callers that cannot suspend (e.g. ViewModel.onCleared). */
    fun closeNow() {
        connected = false
        rootUrl = null
        baseContext = null
    }

    private fun buildUrl(path: String): String {
        val base = rootUrl?.trimEnd('/') ?: return ""
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$base$cleanPath"
    }

    private fun normalizeSmbPath(absUrl: String): String {
        val root = rootUrl ?: return absUrl
        val relative = absUrl.removePrefix(root)
        return if (relative.isEmpty()) "/" else "/" + relative.trimEnd('/')
    }

    suspend fun listDirectory(path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val ctx = baseContext ?: return@withContext emptyList()
        try {
            val url = buildUrl(path)
            val dir = SmbFile(url + if (!url.endsWith("/")) "/" else "", ctx)
            val children = dir.listFiles() ?: return@withContext emptyList()
            val entries = children.mapNotNull { smb ->
                val name = smb.name?.trimEnd('/') ?: return@mapNotNull null
                if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
                val type = when {
                    smb.isDirectory -> SmbEntryType.FOLDER
                    smb.isFile -> SmbEntryType.FILE
                    else -> SmbEntryType.UNKNOWN
                }
                SmbEntry(
                    name = name,
                    path = normalizeSmbPath(smb.url.toString()),
                    type = type,
                    sizeBytes = runCatching { smb.length() }.getOrDefault(0L),
                    modifiedEpochSec = runCatching { smb.lastModified() / 1000L }.getOrDefault(0L)
                )
            }
            entries.sortedWith(compareBy<SmbEntry> { it.type != SmbEntryType.FOLDER }.thenBy { it.name.lowercase() })
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads [remotePath] into [localFile] through a `<localFile>.part`
     * staging file and promotes it to the final name only after the full length
     * has been received. SMB input streams do not expose a reliable byte offset
     * in this stack, so a restart is used rather than pretending to resume.
     */
    suspend fun downloadFile(remotePath: String, localFile: File, onProgress: suspend (Long, Long) -> Unit = { _, _ -> }): Long = withContext(Dispatchers.IO) {
        val ctx = baseContext ?: return@withContext -1L
        try {
            localFile.parentFile?.mkdirs()
            val staging = File(localFile.parentFile, localFile.name + ".part")
            val url = buildUrl(remotePath)
            val smb = SmbFile(url, ctx)
            val total = smb.length()
            var downloaded = 0L
            smb.inputStream.use { input ->
                FileOutputStream(staging, false).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    out.flush()
                }
            }
            if (total > 0 && staging.length() != total) {
                return@withContext -1L
            }
            if (localFile.exists() && !localFile.delete()) return@withContext -1L
            if (!staging.renameTo(localFile)) {
                runCatching {
                    staging.copyTo(localFile, overwrite = true)
                    staging.delete()
                }.getOrElse { return@withContext -1L }
            }
            localFile.length()
        } catch (_: Exception) {
            -1L
        }
    }

    fun getFileUri(remotePath: String): Uri? {
        val ctx = baseContext ?: return null
        val url = buildUrl(remotePath)
        return runCatching { Uri.parse(url) }.getOrNull()
    }

    fun matchesFormat(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "txt", "html", "rtf", "md",
            "m4b", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav", "zip")
    }
}
