package com.bookrio.player.service

import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.repository.NowPlayingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android Auto browse-tree mapping (pure Kotlin, no Media3/Android).
 *
 * These prove the tree shape, media-id round-trip, item metadata mapping and artwork
 * identity. They cannot prove the MediaSession/Android Auto integration itself — that
 * needs a DHU/parked-car smoke test (see the task report for the exact checklist).
 */
class AudiobookLibraryTreeTest {

    private fun book(
        id: Long,
        title: String,
        author: String = "",
        format: FormatEntity = FormatEntity.M4B,
        type: BookTypeEntity = BookTypeEntity.AUDIOBOOK,
        lastOpenedAt: Long? = null,
        coverPath: String? = null
    ) = BookEntity(
        id = id,
        title = title,
        author = author,
        type = type,
        format = format,
        lastOpenedAt = lastOpenedAt,
        coverPath = coverPath
    )

    @Test
    fun `root children are audio formats only, ordered by last opened then title`() {
        val books = listOf(
            book(1, "B recent", lastOpenedAt = 500L),
            book(2, "Ebook", format = FormatEntity.EPUB, lastOpenedAt = 900L),
            book(3, "A never opened"),
            book(4, "A recent", lastOpenedAt = 500L),
            book(5, "Pdf", format = FormatEntity.PDF, type = BookTypeEntity.EBOOK)
        )

        val children = AudiobookLibraryTree.childrenOf(AudiobookLibraryTree.ROOT_MEDIA_ID, books)!!

        assertEquals(listOf(4L, 1L, 3L), children.map { it.bookId })
    }

    @Test
    fun `root children are playable leaves with round-tripping media ids`() {
        val books = listOf(book(7, "Book", author = "Author", coverPath = "/data/covers/7.webp"))

        val entry = AudiobookLibraryTree.childrenOf(AudiobookLibraryTree.ROOT_MEDIA_ID, books)!!.single()

        assertEquals("book_7", entry.mediaId)
        assertEquals(7L, AudiobookLibraryTree.bookIdOf(entry.mediaId))
        assertEquals("Book", entry.title)
        assertEquals("Author", entry.artist)
        assertEquals("Book", entry.albumTitle)
        assertEquals("Author", entry.subtitle)
        assertEquals("/data/covers/7.webp", entry.coverPath)
        assertTrue(entry.isPlayable)
        assertFalse(entry.isBrowsable)
    }

    @Test
    fun `book parent has no children and unknown parents are not nodes`() {
        val books = listOf(book(1, "Book"))

        assertTrue(AudiobookLibraryTree.childrenOf("book_1", books)!!.isEmpty())
        assertNull(AudiobookLibraryTree.childrenOf("book_999", books))
        assertNull(AudiobookLibraryTree.childrenOf("some_podcast", books))
        assertNull(AudiobookLibraryTree.childrenOf("", books))
        assertTrue(
            AudiobookLibraryTree.childrenOf(AudiobookLibraryTree.ROOT_MEDIA_ID, emptyList())!!.isEmpty()
        )
    }

    @Test
    fun `onGetItem mapping resolves books and rejects non-library ids`() {
        val books = listOf(
            book(42, "Book", author = "Author"),
            book(43, "Ebook", format = FormatEntity.EPUB)
        )

        val entry = AudiobookLibraryTree.itemForMediaId("book_42", books)!!
        assertEquals("book_42", entry.mediaId)
        assertEquals("Book", entry.title)
        assertEquals("Author", entry.subtitle)

        assertNull(AudiobookLibraryTree.itemForMediaId("book_43", books)) // ebook, not audiobook
        assertNull(AudiobookLibraryTree.itemForMediaId("book_999", books)) // unknown book
        assertNull(AudiobookLibraryTree.itemForMediaId("42_1", books)) // engine chapter item
        assertNull(AudiobookLibraryTree.itemForMediaId(AudiobookLibraryTree.ROOT_MEDIA_ID, books))
        assertNull(AudiobookLibraryTree.itemForMediaId("book_", books))
        assertNull(AudiobookLibraryTree.itemForMediaId("book_x", books))
        assertNull(AudiobookLibraryTree.itemForMediaId("book_-3", books))
        assertNull(AudiobookLibraryTree.itemForMediaId("episode_7", books))
    }

    @Test
    fun `page honours the host paging window`() {
        val books = (1L..5L).map { book(it, "Book $it") }
        val entries = AudiobookLibraryTree.childrenOf(AudiobookLibraryTree.ROOT_MEDIA_ID, books)!!

        assertEquals(listOf(1L, 2L), AudiobookLibraryTree.page(entries, page = 0, pageSize = 2).map { it.bookId })
        assertEquals(listOf(3L, 4L), AudiobookLibraryTree.page(entries, page = 1, pageSize = 2).map { it.bookId })
        assertEquals(listOf(5L), AudiobookLibraryTree.page(entries, page = 2, pageSize = 2).map { it.bookId })
        assertTrue(AudiobookLibraryTree.page(entries, page = 9, pageSize = 2).isEmpty())
        assertEquals(entries, AudiobookLibraryTree.page(entries, page = 0, pageSize = Int.MAX_VALUE))
        assertEquals(entries, AudiobookLibraryTree.page(entries, page = -1, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `browse artwork identity is audiobook-only and can never be a podcast key`() {
        val books = listOf(
            book(1, "Book"),
            book(2, "Ebook", format = FormatEntity.EPUB)
        )

        val keys = AudiobookLibraryTree.childrenOf(AudiobookLibraryTree.ROOT_MEDIA_ID, books)!!
            .map { NowPlayingPolicy.bookArtworkKey(it.bookId) }

        assertEquals(setOf("book:1"), keys.toSet())
        assertTrue(keys.none { it.startsWith(NowPlayingPolicy.EPISODE_PREFIX) })
    }
}