package com.bookrio.player.service

import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.FormatEntity

/**
 * Android Auto / Media3 media-library browse tree for the audiobook engine.
 *
 * Pure Kotlin (no Android types) so the tree shape, media-id round-trip and
 * metadata/artwork mapping can be unit-tested on the JVM. The service only
 * converts [LibraryEntry] to Media3 items and resolves the artwork bytes.
 *
 * Tree shape:
 * - `__ROOT__` is a browsable, non-playable folder (`MEDIA_TYPE_FOLDER_MIXED`)
 *   whose children are the audiobooks.
 * - Every audiobook is a playable leaf with the stable media id `book_<id>`:
 *   chapters are intentionally NOT browsable — the engine owns exactly one
 *   chapter timeline per book, and Auto shows one row per book.
 * - Any other parent id is not a node in this tree (caller returns an error).
 */
object AudiobookLibraryTree {

    const val ROOT_MEDIA_ID = "__ROOT__"
    const val BOOK_MEDIA_ID_PREFIX = "book_"

    /** Formats the audiobook engine can actually play (as before). */
    val AUDIO_FORMATS: Set<FormatEntity> = setOf(
        FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3,
        FormatEntity.AAC, FormatEntity.FLAC, FormatEntity.OGG,
        FormatEntity.OPUS, FormatEntity.OGG_OPUS, FormatEntity.WAV
    )

    /**
     * Android-free description of one library item. The service maps it 1:1 to a
     * Media3 `MediaItem`; [bookId] is also the artwork identity
     * ([com.bookrio.data.repository.NowPlayingPolicy.bookArtworkKey]).
     */
    data class LibraryEntry(
        val mediaId: String,
        val bookId: Long,
        val title: String,
        val artist: String,
        val albumTitle: String,
        val subtitle: String,
        val coverPath: String?,
        val isPlayable: Boolean,
        val isBrowsable: Boolean
    )

    fun isAudiobook(book: BookEntity): Boolean = book.format in AUDIO_FORMATS

    fun bookMediaId(bookId: Long): String = "$BOOK_MEDIA_ID_PREFIX$bookId"

    /** Book id encoded in a `book_<id>` media id, or null for anything else. */
    fun bookIdOf(mediaId: String): Long? =
        mediaId.takeIf { it.startsWith(BOOK_MEDIA_ID_PREFIX) }
            ?.removePrefix(BOOK_MEDIA_ID_PREFIX)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    /**
     * Media id of one chapter item inside a book timeline: `<bookId>_<chapterIndex>`.
     * These are the items the service hands the player (never browsed directly).
     */
    fun timelineMediaId(bookId: Long, chapterIndex: Int): String = "${bookId}_$chapterIndex"

    /** Book id encoded in a chapter timeline item id (`<bookId>_<chapterIndex>`), or null. */
    fun timelineBookIdOf(mediaId: String): Long? {
        val separator = mediaId.indexOf('_')
        if (separator <= 0 || separator == mediaId.lastIndex) return null
        val bookId = mediaId.substring(0, separator).toLongOrNull() ?: return null
        val chapterIndex = mediaId.substring(separator + 1).toLongOrNull() ?: return null
        return bookId.takeIf { it > 0L && chapterIndex >= 0L }
    }

    fun bookEntry(book: BookEntity): LibraryEntry = LibraryEntry(
        mediaId = bookMediaId(book.id),
        bookId = book.id,
        title = book.title,
        artist = book.author,
        albumTitle = book.title,
        subtitle = book.author,
        coverPath = book.coverPath,
        isPlayable = true,
        isBrowsable = false
    )

    /** Audiobooks only, most recently opened first, then by title. */
    fun sortedAudiobooks(books: List<BookEntity>): List<BookEntity> =
        books.filter(::isAudiobook).sortedWith(
            compareByDescending<BookEntity> { it.lastOpenedAt ?: 0L }.thenBy { it.title }
        )

    /**
     * Children of [parentId]: audiobooks for the root, none for an existing
     * audiobook (playable leaf), null when [parentId] is not a node in this tree.
     */
    fun childrenOf(parentId: String, books: List<BookEntity>): List<LibraryEntry>? {
        if (parentId == ROOT_MEDIA_ID) return sortedAudiobooks(books).map(::bookEntry)
        val bookId = bookIdOf(parentId) ?: return null
        return if (books.any { it.id == bookId && isAudiobook(it) }) emptyList() else null
    }

    /**
     * Resolves a media id to its library entry (onGetItem). Only `book_<id>` ids of
     * actual audiobooks resolve; the root is built by the service (it needs a
     * localized title) and chapter ids are not library items.
     */
    fun itemForMediaId(mediaId: String, books: List<BookEntity>): LibraryEntry? {
        val bookId = bookIdOf(mediaId) ?: return null
        val book = books.firstOrNull { it.id == bookId && isAudiobook(it) } ?: return null
        return bookEntry(book)
    }

    /**
     * Media3 paging convention: `pageSize == Int.MAX_VALUE` (or non-positive) means
     * "all items", otherwise a single page. Keeps a large library from being
     * returned as one unpaged list when the host asks for pages.
     */
    fun page(entries: List<LibraryEntry>, page: Int, pageSize: Int): List<LibraryEntry> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return entries
        val from = page.coerceAtLeast(0).toLong() * pageSize
        if (from >= entries.size) return emptyList()
        return entries.subList(from.toInt(), minOf(from.toInt() + pageSize, entries.size))
    }
}