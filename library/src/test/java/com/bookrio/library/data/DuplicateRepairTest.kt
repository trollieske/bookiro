package com.bookrio.library.data

import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.library.testutil.FakeShelfDatabase
import com.bookrio.library.testutil.JvmHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression + data-safety audit for the overnight SAF duplicate fix
 * (`deduplicateLibrary`, `getByFileUri` upsert) executed against the real
 * repository on the JVM.
 */
class DuplicateRepairTest {

    private fun book(
        id: Long,
        title: String,
        author: String = "",
        type: BookTypeEntity = BookTypeEntity.EBOOK,
        format: FormatEntity = FormatEntity.EPUB,
        fileUri: String? = null,
        filePath: String? = null,
        size: Long = 0L,
        isbn: String? = null,
        isDeleted: Boolean = false
    ) = BookEntity(
        id = id,
        title = title,
        sortTitle = title,
        author = author,
        sortAuthor = author,
        type = type,
        format = format,
        fileUri = fileUri,
        filePath = filePath,
        fileSizeBytes = size,
        isbn = isbn,
        isDeleted = isDeleted
    )

    private fun progress(bookId: Long, pct: Float, positionMs: Long) = ReadingProgressEntity(
        bookId = bookId,
        progressPercent = pct,
        positionMs = positionMs
    )

    @Test
    fun `the same SAF file_uri collapses to one row and is soft-deleted, never hard-deleted`() =
        runBlocking {
            val db = FakeShelfDatabase()
            val repo = JvmHarness.repository(db)
            val uri = "content://com.android.providers.downloads.documents/tree/1/document/2"

            db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = uri, size = 100))
            db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = uri, size = 100))

            val removed = repo.deduplicateLibrary()

            assertEquals(1, removed)
            assertEquals(listOf(1L), db.store.activeBooks.map { it.id })
            // Soft delete: the row is still in the table, flagged is_deleted.
            val deletedRow = db.store.books[2L]
            assertNotNull(deletedRow)
            assertTrue(deletedRow!!.isDeleted)
            assertEquals(listOf(2L), db.store.softDeleted)
        }

    @Test
    fun `the same local file_path collapses to one row`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(book(1, "Dune", "Frank Herbert", fileUri = null, filePath = "/books/dune.epub"))
        db.store.addBook(book(2, "Dune", "Frank Herbert", fileUri = null, filePath = "/books/dune.epub"))

        assertEquals(1, repo.deduplicateLibrary())
        assertEquals(listOf(1L), db.store.activeBooks.map { it.id })
    }

    @Test
    fun `dedup is idempotent - the second run changes nothing`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        val uri = "content://tree/document/9"
        db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = uri))
        db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = uri))
        db.store.addBook(book(3, "The Martian", "Andy Weir", fileUri = uri))

        assertEquals(2, repo.deduplicateLibrary())
        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(1, db.store.activeBooks.size)
        assertEquals(2, db.store.softDeleted.size)
        assertEquals(3, db.store.books.size) // nothing hard-deleted
    }

    @Test
    fun `progress of the dropped copy is moved to the survivor when the survivor has none`() =
        runBlocking {
            val db = FakeShelfDatabase()
            val repo = JvmHarness.repository(db)
            val uri = "content://tree/document/10"
            db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = uri))
            db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = uri))
            db.store.progress[1L] = progress(2, 0.6f, 6_000_000L)

            repo.deduplicateLibrary()

            val merged = db.store.progress.values.single { it.bookId == 1L }
            assertEquals(0.6f, merged.progressPercent, 0.0001f)
            assertEquals(6_000_000L, merged.positionMs)
            // Soft delete keeps the duplicate's progress row in the table (the book
            // row is hidden, not the progress); it is orphaned but not migrated.
            assertNotNull(db.store.progress.values.firstOrNull { it.bookId == 2L })
        }

    /**
     * DATA-SAFETY finding: when both rows carry progress, only the survivor's
     * progress is kept — the more advanced position of the soft-deleted copy is
     * NOT migrated. Documented by test; see REGRESSION_AUDIT.md area 1.
     */
    @Test
    fun `when both copies have progress the dropped copy's position is not migrated`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        val uri = "content://tree/document/11"
        db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = uri))
        db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = uri))
        db.store.progress[1L] = progress(1, 0.2f, 2_000_000L)
        db.store.progress[2L] = progress(2, 0.9f, 9_000_000L)

        repo.deduplicateLibrary()

        val kept = db.store.progress.values.single { it.bookId == 1L }
        assertEquals(0.2f, kept.progressPercent, 0.0001f)
        assertEquals(2_000_000L, kept.positionMs)
    }

    @Test
    fun `same title and author in different types are never merged`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(
            book(1, "The Martian", "Andy Weir", type = BookTypeEntity.EBOOK, fileUri = "content://a")
        )
        db.store.addBook(
            book(
                2, "The Martian", "Andy Weir",
                type = BookTypeEntity.AUDIOBOOK, format = FormatEntity.M4B, fileUri = "content://b"
            )
        )

        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(setOf(1L, 2L), db.store.activeBooks.map { it.id }.toSet())
    }

    @Test
    fun `same title with different authors or no author is never merged`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = "content://a"))
        db.store.addBook(book(2, "The Martian", "Someone Else", fileUri = "content://b"))
        db.store.addBook(book(3, "The Martian", "", fileUri = "content://c"))

        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(3, db.store.activeBooks.size)
    }

    @Test
    fun `the same book in several formats is preserved (identity not proven)`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        // Same work, different files and formats, no ISBN: must NOT be collapsed.
        db.store.addBook(
            book(1, "Project Hail Mary", "Andy Weir", format = FormatEntity.EPUB, fileUri = "content://epub", size = 10)
        )
        db.store.addBook(
            book(2, "Project Hail Mary", "Andy Weir", format = FormatEntity.PDF, fileUri = "content://pdf", size = 10_000)
        )
        db.store.addBook(
            book(3, "Project Hail Mary", "Andy Weir", format = FormatEntity.TXT, fileUri = "content://txt", size = 1)
        )

        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(setOf(1L, 2L, 3L), db.store.activeBooks.map { it.id }.toSet())
    }

    @Test
    fun `different EPUB files with the same ISBN are NOT merged`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(
            book(1, "Dune", "Frank Herbert", format = FormatEntity.EPUB, isbn = "9780441013593", fileUri = "content://a", size = 500)
        )
        db.store.addBook(
            book(2, "Dune", "Frank Herbert", format = FormatEntity.EPUB, isbn = "9780441013593", fileUri = "content://b", size = 100)
        )

        // Matching ISBN + type + format is NOT proof of identical file content.
        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(2, db.store.activeBooks.size)
        assertFalse(db.store.books[2L]!!.isDeleted)
    }

    @Test
    fun `same ISBN but different format is preserved`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(
            book(1, "Dune", "Frank Herbert", format = FormatEntity.EPUB, isbn = "9780441013593", fileUri = "content://a")
        )
        db.store.addBook(
            book(2, "Dune", "Frank Herbert", format = FormatEntity.PDF, isbn = "9780441013593", fileUri = "content://b")
        )

        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(2, db.store.activeBooks.size)
    }

    @Test
    fun `two different EPUB files with identical title and author are NOT collapsed`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(
            book(1, "The Hobbit", "J. R. R. Tolkien", fileUri = "content://edition-a", size = 500)
        )
        db.store.addBook(
            book(2, "The Hobbit", "J. R. R. Tolkien", fileUri = "content://edition-b", size = 500)
        )

        assertEquals(0, repo.deduplicateLibrary())
        assertEquals(2, db.store.activeBooks.size)
    }

    @Test
    fun `rows with no file identity and different titles are left alone`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(book(1, "Audiobook", "", type = BookTypeEntity.AUDIOBOOK, format = FormatEntity.M4B))
        db.store.addBook(book(2, "Audiobook", "", type = BookTypeEntity.AUDIOBOOK, format = FormatEntity.M4B))

        // No proven identity at all: even the same title must stay separate now.
        db.store.addBook(book(3, "Different Book", "", type = BookTypeEntity.AUDIOBOOK, format = FormatEntity.M4B))
        repo.deduplicateLibrary()

        assertTrue(db.store.activeBooks.any { it.id == 3L })
        assertEquals(3, db.store.activeBooks.size)
    }

    @Test
    fun `bookmarks and highlights of a confirmed duplicate are re-pointed to the survivor`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        val uri = "content://tree/document/55"
        db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = uri))
        db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = uri))
        db.store.addBookmark(
            com.bookrio.data.local.entity.BookmarkEntity(bookId = 2, title = "page 3")
        )
        db.store.addHighlight(
            com.bookrio.data.local.entity.HighlightEntity(bookId = 2, text = "a quote")
        )

        repo.deduplicateLibrary()

        assertEquals(1, db.store.activeBooks.size)
        assertEquals(1, db.store.bookmarks.values.count { it.bookId == 1L })
        assertEquals(0, db.store.bookmarks.values.count { it.bookId == 2L })
        assertEquals(1, db.store.highlights.values.count { it.bookId == 1L })
        assertEquals(0, db.store.highlights.values.count { it.bookId == 2L })
    }

    @Test
    fun `the repair is observable in the log`() = runBlocking {
        android.util.Log.clear()
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(book(1, "The Martian", "Andy Weir", fileUri = "content://x"))
        db.store.addBook(book(2, "The Martian", "Andy Weir", fileUri = "content://x"))

        repo.deduplicateLibrary()

        assertTrue(
            android.util.Log.recordedLines().any { it.contains("[DEDUP] removed 1 duplicate") }
        )
        assertFalse(android.util.Log.recordedLines().any { it.contains("not mocked") })
    }
}