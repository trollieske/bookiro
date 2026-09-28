package com.bookrio.ftp.client

import java.io.Closeable
import java.io.File

enum class FtpEntryType { FILE, FOLDER, LINK, UNKNOWN }

data class FtpEntry(
    val name: String,
    val path: String,
    val type: FtpEntryType,
    val sizeBytes: Long,
    val modifiedEpochSec: Long
)

/** Connection parameters. Never logged, never serialized. */
data class RemoteCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val protocol: FtpProtocol,
    val passiveMode: Boolean = true
)

data class RemoteFileInfo(
    val path: String,
    val sizeBytes: Long,
    val modifiedEpochSec: Long
)

data class DownloadOutcome(
    /** Total bytes now present in the target file. */
    val bytesWritten: Long,
    /** Offset the download actually started from (0 = full restart). */
    val resumedFrom: Long,
    /** Remote size if the server reported one, otherwise -1. */
    val totalBytes: Long,
    /** False when a requested resume had to be abandoned. */
    val serverSupportsResume: Boolean
)

enum class FtpErrorKind { AUTH, NETWORK, NOT_FOUND, STORAGE, SERVER, UNSUPPORTED, UNKNOWN }

/**
 * Transport failure with a classifiable [kind] so the engine can decide between
 * retry, re-auth or an honest permanent failure. Messages must never contain
 * credentials.
 */
class FtpException(
    val kind: FtpErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Minimal transport abstraction for FTP/FTPS/SFTP.
 *
 * Implementations are **not** thread-safe: one connection belongs to exactly one
 * transfer lane. `FtpClientEngine` is the production implementation; tests use a
 * deterministic fake.
 */
interface RemoteFileClient : Closeable {

    val isConnected: Boolean

    suspend fun connect(credentials: RemoteCredentials): Boolean

    suspend fun listDirectory(path: String): List<FtpEntry>

    suspend fun stat(remotePath: String): RemoteFileInfo?

    /**
     * Streams [remotePath] into [target], appending from [offset] when the
     * server supports resuming. Never writes outside [target].
     *
     * [onProgress] receives the absolute number of bytes present in [target].
     */
    suspend fun download(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome

    suspend fun disconnect()

    suspend fun listDirectoryRecursive(
        startPath: String,
        maxDepth: Int = 4
    ): List<FtpEntry> {
        val result = mutableListOf<FtpEntry>()
        val visited = mutableSetOf<String>()

        suspend fun crawl(currentPath: String, depth: Int) {
            if (depth > maxDepth) return
            val normalized = if (currentPath.isBlank()) "/" else currentPath
            if (!visited.add(normalized)) return
            val entries = try {
                listDirectory(normalized)
            } catch (_: FtpException) {
                emptyList()
            }
            for (entry in entries) {
                if (entry.type == FtpEntryType.FILE) {
                    result.add(entry)
                } else if (entry.type == FtpEntryType.FOLDER && depth < maxDepth) {
                    crawl(entry.path, depth + 1)
                }
            }
        }

        crawl(startPath, 1)
        return result
    }
}