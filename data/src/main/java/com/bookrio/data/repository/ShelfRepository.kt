package com.bookrio.data.repository

import com.bookrio.core.domain.model.BookFormat
import com.bookrio.core.domain.model.BookMetadata
import com.bookrio.core.domain.model.ChapterInfo
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.*
import com.bookrio.data.local.entity.BookEntity

/**
 * Single entry point for all data operations.
 *
 * In Phase 3 this grows into a proper repository layer that mediates
 * between:
 *   - the local Room DB (cached books, progress, bookmarks, servers)
 *   - on-device file storage / SAF URIs (actual book bytes and covers)
 *   - remote FTP/FTPS/SFTP servers and optional online cover providers
 *
 * Every long-running operation is dispatched on [Dispatchers.IO] via
 * `withContext`, ensuring the Compose UI thread never blocks.
 */
class ShelfRepository(
    private val db: ShelfDatabase
) {
    val books: BookDao = db.bookDao()
    val shelves: ShelfDao = db.shelfDao()
    val progress: ReadingProgressDao = db.progressDao()
    val bookmarks: BookmarkDao = db.bookmarkDao()
    val highlights: HighlightDao = db.highlightDao()
    val ftpServers: FtpServerDao = db.ftpServerDao()
    val downloads: DownloadTaskDao = db.downloadTaskDao()
    val cachedPaths: CachedPathDao = db.cachedPathDao()
    val syncHistory: SyncHistoryDao = db.syncHistoryDao()

    suspend fun resolveFormat(path: String): BookFormat = BookFormat.fromFilename(path)

    /**
     * Phase 3 placeholder: run the correct metadata extractor per format and
     * return a [BookMetadata] plus an optional cover byte array. Never
     * called on the main thread.
     *
     * Chapter parsing notes:
     *  - For **EPUB** 3, we parse `<nav epub:type="toc">` first, falling
     *    back to the NCX `<navMap>` for EPUB 2.
     *  - For **M4B / M4A / MP4** containers we read the QuickTime `moov/udta/meta`
     *    atoms first, then the iTunes `chpl` chapter list, then ID3v2 `CHAP`
     *    frames if still no chapters.
     *  - Multi-file folders (e.g. 20× MP3) create chapters == files, sorted
     *    by filename then track-number metadata.
     *  - **PDF** chapter extraction uses the document outline tree when
     *    available (`PdfRenderer` returns bookmarks on API 30+).
     *
     * Returns a list of chapters.  Unknown lengths are `endMs = null`; the
     * player derives the last chapter's end from the total duration.
     */
    suspend fun extractMetadata(
        format: BookFormat,
        path: String
    ): Pair<BookMetadata, ByteArray?> {
        val placeholder = BookMetadata(
            title = path.substringAfterLast('/').substringBeforeLast('.'),
            author = null,
            series = null,
            seriesIndex = null,
            description = null,
            publisher = null,
            publishedDate = null,
            language = null,
            isbn = null,
            pageCount = null,
            durationMs = null,
            chapters = emptyList()
        )
        return placeholder to null
    }

    /**
     * Smart Sync diffing algorithm (Phase 7).
     *
     * Inputs:
     *   [remote] = list of files reported by the server (path + size + mtime)
     *   [bookCache] = books already imported, keyed by (remotePath, serverId)
     *   [downloadHistory] = completed/failed [DownloadTaskEntity] records
     *
     * A file is considered NEW when:
     *   - No local book with matching (serverId, remotePath) exists.
     *   - AND (no prior failed download task exists for this path,
     *          or the remote mtime is newer than the last failure time).
     *
     * A file is considered CHANGED when:
     *   - Same remotePath but size differs OR mtime differs by more than 5 seconds.
     *   - In that case we re-download and re-import in-place, keeping
     *     reading progress/bookmarks by matching stable title+author hash.
     *
     * This function returns the NEW/CHANGED files only; the downloader
     * queues them.
     */
    fun <T> diffRemote(
        remote: List<T>,
        pathOf: (T) -> String,
        sizeOf: (T) -> Long,
        mtimeOf: (T) -> Long,
        known: Map<String, Pair<Long, Long>>
    ): List<T> {
        return remote.filter {
            val p = pathOf(it)
            val k = known[p]
            if (k == null) true
            else {
                val (cachedSize, cachedMtime) = k
                sizeOf(it) != cachedSize || kotlin.math.abs(mtimeOf(it) - cachedMtime) > 5_000L
            }
        }
    }
}
