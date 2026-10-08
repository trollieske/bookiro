package com.bookrio.library.data

import com.bookrio.data.local.entity.AudioTrackEntity
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression + data-safety audit for the overnight audiobook merge/split/track
 * repair fixes, executed against the real repository on the JVM with a fake DB.
 */
class AudiobookRepairTest {

    private fun audiobook(
        id: Long,
        title: String,
        author: String = "",
        filePath: String? = null,
        fileUri: String? = null,
        duration: Long? = null,
        size: Long = 0L
    ) = BookEntity(
        id = id,
        title = title,
        sortTitle = title,
        author = author,
        sortAuthor = author,
        type = BookTypeEntity.AUDIOBOOK,
        format = FormatEntity.M4B,
        filePath = filePath,
        fileUri = fileUri,
        durationMs = duration,
        fileSizeBytes = size
    )

    private fun track(
        bookId: Long,
        title: String,
        filePath: String? = null,
        fileUri: String? = null,
        duration: Long,
        number: Int = 1,
        size: Long = 0L
    ) = AudioTrackEntity(
        bookId = bookId,
        trackNumber = number,
        title = title,
        durationMs = duration,
        filePath = filePath,
        fileUri = fileUri,
        fileSizeBytes = size
    )

    // ─────────────────────────── consolidation ───────────────────────────

    /**
     * The original bug: every scoped-storage (filePath = null) audiobook shared
     * the constant empty-path group key, so unrelated books collapsed into one.
     */
    @Test
    fun `scoped storage audiobooks without a local path are never consolidated`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        // Generic titles + blank authors all produced the same key when path was blank.
        db.store.addBook(audiobook(1, "Audiobook", fileUri = "content://a"))
        db.store.addBook(audiobook(2, "Audiobook", fileUri = "content://b"))
        db.store.addBook(audiobook(3, "Audiobook", fileUri = "content://c"))
        db.store.addTrack(track(1, "", fileUri = "content://a", duration = 3_600_000, size = 10))
        db.store.addTrack(track(2, "", fileUri = "content://b", duration = 7_200_000, size = 20))
        db.store.addTrack(track(3, "", fileUri = "content://c", duration = 1_800_000, size = 30))

        val merged = repo.consolidateFragmentedAudiobooks()

        assertEquals(0, merged)
        assertEquals(setOf(1L, 2L, 3L), db.store.activeBooks.map { it.id }.toSet())
        assertEquals(3, db.store.tracks.size)
    }

    /** Same title+author on SAF books is also left alone (conservative). */
    @Test
    fun `strong identity does not consolidate scoped storage copies`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Project Hail Mary", "Andy Weir", fileUri = "content://a"))
        db.store.addBook(audiobook(2, "Project Hail Mary", "Andy Weir", fileUri = "content://b"))
        db.store.addTrack(track(1, "Project Hail Mary", fileUri = "content://a", duration = 3_600_000))
        db.store.addTrack(track(2, "Project Hail Mary", fileUri = "content://b", duration = 3_600_000))

        assertEquals(0, repo.consolidateFragmentedAudiobooks())
        assertEquals(2, db.store.activeBooks.size)
    }

    /**
     * Real local fragments of one book must still heal, in track order, exactly
     * once, with progress moved to the canonical row.
     */
    @Test
    fun `local fragments merge into one book in natural track order and merge once`() = runBlocking {
        val folder = "/audiobooks/project-hail-mary"
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(
            audiobook(1, "Project Hail Mary", "Andy Weir", filePath = folder, fileUri = "file://$folder", duration = 3_600_000, size = 100)
        )
        db.store.addBook(
            audiobook(2, "Project Hail Mary", "Andy Weir", filePath = folder, fileUri = "file://$folder", duration = 3_600_000, size = 100)
        )
        db.store.addTrack(
            track(1, "Chapter 1", filePath = "$folder/01.mp3", duration = 3_600_000, number = 1, size = 100)
        )
        db.store.addTrack(
            track(2, "Chapter 2", filePath = "$folder/02.mp3", duration = 3_600_000, number = 2, size = 100)
        )
        db.store.progress[2L] = ReadingProgressEntity(bookId = 2, progressPercent = 0.5f, positionMs = 1_800_000L)

        val merged = repo.consolidateFragmentedAudiobooks()

        assertEquals(1, merged)
        assertEquals(listOf(1L), db.store.activeBooks.map { it.id })
        val tracks = db.store.tracks.values.filter { it.bookId == 1L }.sortedBy { it.trackNumber }
        assertEquals(2, tracks.size)
        assertEquals(listOf(1, 2), tracks.map { it.trackNumber })
        assertEquals(listOf("Chapter 1", "Chapter 2"), tracks.map { it.title })
        val canonical = db.store.activeBooks.single()
        assertEquals(7_200_000L, canonical.durationMs)
        assertEquals(200L, canonical.fileSizeBytes)
        assertEquals(2, canonical.chapterCount)
        assertNotNull(canonical.chaptersJson)
        // Progress from the fragment was moved to the canonical row.
        assertEquals(0.5f, db.store.progress.values.single { it.bookId == 1L }.progressPercent, 0.0001f)

        // Idempotent: a second run must not re-append the tracks (the old bug
        // multiplied rows on every run).
        assertEquals(0, repo.consolidateFragmentedAudiobooks())
        assertEquals(2, db.store.tracks.values.count { it.bookId == 1L })
        assertEquals(7_200_000L, db.store.books[1L]!!.durationMs)
    }

    /**
     * DATA-SAFETY finding: consolidation hard-deletes the duplicate book rows
     * (`bookDao.delete`), unlike deduplicateLibrary which soft-deletes. Progress
     * is copied only when better; bookmarks/highlights/notes are not migrated.
     * This test documents the hard delete.
     */
    @Test
    fun `consolidation soft-deletes duplicate fragments so the merge is recoverable`() = runBlocking {
        val folder = "/audiobooks/phm"
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Project Hail Mary", "Andy Weir", filePath = folder, duration = 1_000, size = 10))
        db.store.addBook(audiobook(2, "Project Hail Mary", "Andy Weir", filePath = folder, duration = 1_000, size = 10))
        db.store.addTrack(track(1, "01", filePath = "$folder/01.mp3", duration = 1_000, size = 10))
        db.store.addTrack(track(2, "02", filePath = "$folder/02.mp3", duration = 1_000, size = 10))

        assertEquals(1, repo.consolidateFragmentedAudiobooks())
        // The row is preserved but hidden, so the merge is reversible from the table.
        assertTrue(db.store.books.containsKey(2L))
        assertTrue(db.store.books[2L]!!.isDeleted)
        assertTrue(db.store.softDeleted.contains(2L))
        assertEquals(1, db.store.activeBooks.size)
    }

    @Test
    fun `divergent metadata under a weak group key is not merged`() = runBlocking {
        val folder = "/audiobooks/audiobook"
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Audiobook", filePath = folder, duration = 3_600_000, size = 100))
        db.store.addBook(audiobook(2, "Audiobook", filePath = folder, duration = 36_000_000, size = 100))

        val merged = repo.consolidateFragmentedAudiobooks()

        assertEquals(0, merged)
        assertEquals(2, db.store.activeBooks.size)
        assertTrue(android.util.Log.recordedLines().any { it.contains("[CONSOLIDATE_SKIP]") })
    }

    // ─────────────────────── duplicate track repair ───────────────────────

    @Test
    fun `duplicate audio tracks are deduped, renumbered and totals recomputed`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Solo Book", "Author", duration = 0, size = 0))
        // The old consolidation re-appended the canonical's tracks on every run.
        // Survivors keep first-occurrence order (query order = disc/track/path).
        db.store.addTrack(track(1, "One", fileUri = "content://one", duration = 1_000, number = 1, size = 10))
        db.store.addTrack(track(1, "One", fileUri = "content://one", duration = 1_000, number = 2, size = 10))
        db.store.addTrack(track(1, "Two", fileUri = "content://two", duration = 2_000, number = 3, size = 20))
        db.store.addTrack(track(1, "Three", fileUri = "content://three", duration = 3_000, number = 4, size = 30))

        val fixed = repo.repairDuplicateAudioTracks()

        assertEquals(1, fixed)
        val tracks = db.store.tracks.values.filter { it.bookId == 1L }.sortedBy { it.trackNumber }
        assertEquals(3, tracks.size)
        assertEquals(listOf(1, 2, 3), tracks.map { it.trackNumber })
        assertEquals(listOf("One", "Two", "Three"), tracks.map { it.title })
        val book = db.store.books[1L]!!
        assertEquals(6_000L, book.durationMs)
        assertEquals(60L, book.fileSizeBytes)
        assertEquals(3, book.chapterCount)

        assertEquals(0, repo.repairDuplicateAudioTracks())
        assertEquals(3, db.store.tracks.values.count { it.bookId == 1L })
    }

    @Test
    fun `single-track and non-audiobook rows are untouched by track repair`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Single", duration = 5_000, size = 5))
        db.store.addTrack(track(1, "Only", fileUri = "content://only", duration = 5_000, number = 1, size = 5))
        val ebook = BookEntity(id = 2, title = "Ebook", type = BookTypeEntity.EBOOK, format = FormatEntity.EPUB)
        db.store.addBook(ebook)

        assertEquals(0, repo.repairDuplicateAudioTracks())
        assertEquals(5_000L, db.store.books[1L]!!.durationMs)
    }

    // ────────────────────────── merged-book split ──────────────────────────

    @Test
    fun `a wrongly merged audiobook with unrelated standalone titles is split, idempotently`() =
        runBlocking {
            val db = FakeShelfDatabase()
            val repo = JvmHarness.repository(db)
            db.store.addBook(audiobook(1, "The Martian", "Andy Weir"))
            // A historical cross-folder merge: unrelated books ended up under one row,
            // and their local tracks live in different folders.
            db.store.addTrack(track(1, "The Martian", filePath = "/books/martian/track.mp3", duration = 10 * 3_600_000L))
            db.store.addTrack(track(1, "Project Hail Mary", filePath = "/books/phm/track.mp3", duration = 9 * 3_600_000L))
            db.store.addTrack(track(1, "His and Hers", filePath = "/books/hh/track.mp3", duration = 8 * 3_600_000L))
            db.store.addTrack(track(1, "And Then There Were None", filePath = "/books/attwn/track.mp3", duration = 7 * 3_600_000L))

            val created = repo.splitMergedAudiobooks()

            assertEquals(3, created)
            assertEquals(4, db.store.activeBooks.size)
            // The group whose title matches the book keeps the original row.
            val keep = db.store.books[1L]!!
            assertEquals("The Martian", keep.title)
            assertEquals(1, db.store.tracks.values.count { it.bookId == 1L })
            assertEquals(10 * 3_600_000L, keep.durationMs)

            val newTitles = db.store.activeBooks.filter { it.id != 1L }.map { it.title }.toSet()
            assertEquals(setOf("Project Hail Mary", "His and Hers", "And Then There Were None"), newTitles)
            // Every new book has exactly one track in its own right, and a fresh 0% progress row.
            db.store.activeBooks.forEach { b ->
                assertEquals(1, db.store.tracks.values.count { it.bookId == b.id })
            }
            db.store.activeBooks.filter { it.id != 1L }.forEach { b ->
                val p = db.store.progress.values.firstOrNull { it.bookId == b.id }
                assertNotNull(p)
                assertEquals(0f, p!!.progressPercent, 0.0001f)
            }
            // No track or duration invented: the four durations are preserved.
            val total = db.store.tracks.values.sumOf { it.durationMs }
            assertEquals(34 * 3_600_000L, total)

            // Re-running must not create anything (each split book now has 1 file).
            assertEquals(0, repo.splitMergedAudiobooks())
            assertEquals(4, db.store.activeBooks.size)
            assertEquals(4, db.store.tracks.size)
        }

    @Test
    fun `a real multi-file audiobook with chapter labels is not split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "The Fellowship of the Ring", "J. R. R. Tolkien"))
        db.store.addTrack(track(1, "Chapter 01 - A Long-Expected Party", filePath = "/books/lotr/01.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Chapter 02 - The Shadow of the Past", filePath = "/books/lotr/02.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Chapter 03 - Three is Company", filePath = "/books/lotr/03.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Chapter 04 - A Shortcut to Mushrooms", filePath = "/books/lotr/04.mp3", duration = 3_600_000))

        assertEquals(0, repo.splitMergedAudiobooks())
        assertEquals(1, db.store.activeBooks.size)
        assertEquals(4, db.store.tracks.size)
    }

    @Test
    fun `tracks containing the book title are not split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "The Martian", "Andy Weir"))
        db.store.addTrack(track(1, "The Martian - Part 01", filePath = "/books/martian/01.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "The Martian - Part 02", filePath = "/books/martian/02.mp3", duration = 3_600_000))

        assertEquals(0, repo.splitMergedAudiobooks())
        assertEquals(1, db.store.activeBooks.size)
    }

    @Test
    fun `a single-file audiobook is never split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "The Martian", "Andy Weir"))
        db.store.addTrack(track(1, "The Martian", fileUri = "content://a", duration = 10 * 3_600_000L))

        assertEquals(0, repo.splitMergedAudiobooks())
        assertEquals(1, db.store.activeBooks.size)
    }

    /**
     * FIXED false positive: a correct multi-file audiobook in ONE folder whose
     * per-file titles are chapter names (without the literal word "Chapter") is
     * now left untouched, because splitting requires the tracks to span multiple
     * parent folders.
     */
    @Test
    fun `chapter-name file titles in one folder are NOT split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "The Fellowship of the Ring", "J. R. R. Tolkien", filePath = "/books/lotr"))
        db.store.addTrack(track(1, "A Long-Expected Party", filePath = "/books/lotr/01.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "The Shadow of the Past", filePath = "/books/lotr/02.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Three is Company", filePath = "/books/lotr/03.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "A Shortcut to Mushrooms", filePath = "/books/lotr/04.mp3", duration = 3_600_000))

        val created = repo.splitMergedAudiobooks()

        assertEquals(0, created)
        assertEquals(1, db.store.activeBooks.size)
        assertEquals(4, db.store.tracks.size)
    }

    @Test
    fun `numbered chapter files are not split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Some Book", "Some Author", filePath = "/books/some"))
        db.store.addTrack(track(1, "Chapter 01", filePath = "/books/some/01.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Chapter 02", filePath = "/books/some/02.mp3", duration = 3_600_000))
        db.store.addTrack(track(1, "Chapter 03", filePath = "/books/some/03.mp3", duration = 3_600_000))

        assertEquals(0, repo.splitMergedAudiobooks())
        assertEquals(1, db.store.activeBooks.size)
    }

    @Test
    fun `SAF imports with no local folder are not split`() = runBlocking {
        val db = FakeShelfDatabase()
        val repo = JvmHarness.repository(db)
        db.store.addBook(audiobook(1, "Some Book", "Some Author"))
        db.store.addTrack(track(1, "A Long-Expected Party", fileUri = "content://a", duration = 3_600_000))
        db.store.addTrack(track(1, "The Shadow of the Past", fileUri = "content://b", duration = 3_600_000))

        assertEquals(0, repo.splitMergedAudiobooks())
        assertEquals(1, db.store.activeBooks.size)
    }
}