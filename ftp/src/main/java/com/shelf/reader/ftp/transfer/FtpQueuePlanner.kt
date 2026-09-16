package com.shelf.reader.ftp.transfer

import com.shelf.reader.ftp.data.FtpSource
import com.shelf.reader.ftp.data.FtpSourceRepository
import com.shelf.reader.ftp.data.FtpTransferRepository
import com.shelf.reader.ftp.domain.MediaFormats

/**
 * Turns "Sync now" into durable queue rows.
 *
 * The listing happens on a short-lived connection owned by this planner, never by
 * a screen. The resulting queue is durable, so navigating away cannot lose it.
 */
class FtpQueuePlanner(
    private val sourceRepository: FtpSourceRepository,
    private val transferRepository: FtpTransferRepository,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {

    /** @return number of newly queued media files. */
    suspend fun plan(source: FtpSource): Int {
        val credentials = sourceRepository.credentialsFor(source.id) ?: return 0
        val client = sourceRepository.clientFactory.create()
        return try {
            if (!client.connect(credentials)) {
                return 0
            }
            sourceRepository.markConnected(source.id)
            val base = source.basePath.ifBlank { "/" }
            val entries = client.listDirectoryRecursive(base, maxDepth)
                .filter { MediaFormats.isBook(it.name) }
            if (entries.isEmpty()) return 0
            transferRepository.enqueue(source.id, base, entries).added
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 6
    }
}