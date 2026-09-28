package com.bookrio.ftp.fakes

import com.bookrio.ftp.client.DownloadOutcome
import com.bookrio.ftp.client.FtpEntry
import com.bookrio.ftp.client.FtpEntryType
import com.bookrio.ftp.client.FtpErrorKind
import com.bookrio.ftp.client.FtpException
import com.bookrio.ftp.client.RemoteCredentials
import com.bookrio.ftp.client.RemoteFileClient
import com.bookrio.ftp.client.RemoteFileInfo
import com.bookrio.ftp.transfer.FtpImporter
import java.io.File
import java.io.FileOutputStream

/**
 * Deterministic in-memory transport for engine tests.
 */
class FakeRemoteFileClient(
    val files: MutableMap<String, ByteArray> = mutableMapOf(),
    val listing: MutableMap<String, List<FtpEntry>> = mutableMapOf(),
    var connectOk: Boolean = true,
    var downloadFailure: FtpException? = null,
    /** Simulate a connection that drops after this many bytes (per download). */
    var truncateTo: Long? = null
) : RemoteFileClient {

    private var connected = false
    var connectCount = 0
        private set
    var disconnectCount = 0
        private set

    override val isConnected: Boolean get() = connected

    override suspend fun connect(credentials: RemoteCredentials): Boolean {
        connectCount++
        if (!connectOk) throw FtpException(FtpErrorKind.NETWORK, "connect refused")
        connected = true
        return true
    }

    override suspend fun listDirectory(path: String): List<FtpEntry> = listing[path].orEmpty()

    override suspend fun stat(remotePath: String): RemoteFileInfo? =
        files[remotePath]?.let { RemoteFileInfo(remotePath, it.size.toLong(), 0L) }

    override suspend fun download(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome {
        downloadFailure?.let { throw it }
        val data = files[remotePath] ?: throw FtpException(FtpErrorKind.NOT_FOUND, "not found")
        val effective = truncateTo?.let { limit ->
            if (limit < data.size) data.copyOf(limit.toInt()) else data
        } ?: data

        target.parentFile?.mkdirs()
        val start = offset.coerceIn(0L, effective.size.toLong()).toInt()
        FileOutputStream(target, offset > 0L).use { out ->
            val slice = effective.copyOfRange(start, effective.size)
            var written = offset
            var index = 0
            while (index < slice.size) {
                val chunk = minOf(bufferSize, slice.size - index)
                out.write(slice, index, chunk)
                index += chunk
                written += chunk
                onProgress(written)
            }
            out.flush()
        }
        return DownloadOutcome(
            bytesWritten = target.length(),
            resumedFrom = offset,
            totalBytes = effective.size.toLong(),
            serverSupportsResume = offset > 0L
        )
    }

    override suspend fun disconnect() {
        disconnectCount++
        connected = false
    }

    override fun close() {
        connected = false
    }

    fun addFile(path: String, size: Int = 16) {
        files[path] = ByteArray(size) { (it % 251).toByte() }
    }

    fun addFolder(path: String, entries: List<FtpEntry>) {
        listing[path] = entries
    }

    companion object {
        fun fileEntry(name: String, path: String, size: Long) =
            FtpEntry(name, path, FtpEntryType.FILE, size, 0L)

        fun folderEntry(name: String, path: String) =
            FtpEntry(name, path, FtpEntryType.FOLDER, 0L, 0L)
    }
}

class FakeFtpImporter : FtpImporter {
    val imported = mutableListOf<Pair<String, Long>>()
    var bookIdCounter = 100L
    var consolidated = 0

    override suspend fun findImportedBookId(localPath: String): Long? = null

    override suspend fun importBatch(localFiles: List<File>, serverId: Long, remotePath: String?): Long? {
        localFiles.forEach { imported.add(it.absolutePath to serverId) }
        return bookIdCounter++
    }

    override suspend fun consolidateFragmentedAudiobooks(): Int {
        consolidated++
        return 0
    }
}