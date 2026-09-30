package com.bookrio.library.data

import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG A repair: only a genuine one-chapter fallback may be rewritten, always
 * into >= 2 real chapters, without touching the book's identity or progress.
 */
class OneChapterRepairPlannerTest {

    private fun track(id: Long, name: String, durationMs: Long = 60_000L) = RepairTrackInput(
        id = id,
        trackNumber = 1, // old fragmented imports stored number 1 on every row
        discNumber = 1,
        title = name,
        durationMs = durationMs,
        filePath = "/torrents/Book/$name",
        fileUri = "file:///torrents/Book/$name"
    )

    private fun plan(
        stored: List<RepairChapterInput> = listOf(RepairChapterInput("Book", 0L, 60_000L)),
        tracks: List<RepairTrackInput> = listOf(track(1, "track 01.mp3")),
        reparsed: List<RepairChapterInput> = emptyList(),
        primaryDurationMs: Long = 0L
    ) = OneChapterRepairPlanner.plan(
        bookTitle = "Book",
        fileNameStem = "track 01",
        stored = stored,
        tracks = tracks,
        reparsed = reparsed,
        primaryDurationMs = primaryDurationMs
    )

    @Test
    fun `multi track fallback becomes N chapters with N sequentially numbered tracks`() {
        val tracks = listOf(
            track(10, "track 01.mp3", 60_000L),
            track(11, "track 02.mp3", 120_000L),
            track(12, "track 03.mp3", 180_000L),
            track(13, "track 04.mp3", 240_000L),
            track(14, "track 05.mp3", 300_000L)
        )

        val result = plan(stored = listOf(RepairChapterInput("Book", 0L, 60_000L)), tracks = tracks)!!

        // One book with N tracks -> N chapters.
        assertEquals(5, result.chapters.size)
        assertEquals(listOf(10L to 1, 11L to 2, 12L to 3, 13L to 4, 14L to 5), result.trackNumbers)
        assertEquals(0L, result.chapters[0].startMs)
        assertEquals(60_000L, result.chapters[0].endMs)
        assertEquals(60_000L, result.chapters[1].startMs)
        assertEquals(180_000L, result.chapters[1].endMs)
        assertEquals(600_000L, result.chapters[4].startMs)
        assertEquals(900_000L, result.chapters[4].endMs)
        assertEquals("file:///torrents/Book/track 01.mp3", result.chapters[0].mediaUri)
        assertEquals(900_000L, result.durationMs)
    }

    @Test
    fun `track order uses natural file names when numbers are ambiguous`() {
        val tracks = listOf(
            track(1, "track 10.mp3"),
            track(2, "track 2.mp3"),
            track(3, "track 1.mp3")
        )
        val result = plan(tracks = tracks)!!
        assertEquals(
            listOf("track 1.mp3", "track 2.mp3", "track 10.mp3"),
            result.chapters.map { it.filePath!!.substringAfterLast('/') }
        )
        assertEquals(listOf(3L to 1, 2L to 2, 1L to 3), result.trackNumbers)
    }

    @Test
    fun `valid multi chapter list is never replaced`() {
        val stored = listOf(
            RepairChapterInput("Chapter 1", 0L, 1_000L),
            RepairChapterInput("Chapter 2", 1_000L, 2_000L)
        )
        assertNull(plan(stored = stored, tracks = listOf(track(1, "a.mp3"), track(2, "b.mp3"))))
    }

    @Test
    fun `a genuine single chapter is never replaced`() {
        val stored = listOf(RepairChapterInput("Chapter 7: The Vault", 0L, 90_000L))
        assertNull(plan(stored = stored, tracks = listOf(track(1, "a.mp3"), track(2, "b.mp3"))))
        // ... also not by a fresh single-file re-parse
        assertNull(
            plan(
                stored = stored,
                tracks = listOf(track(1, "book.m4b")),
                reparsed = listOf(RepairChapterInput("Chapter 7: The Vault", 0L, 90_000L))
            )
        )
    }

    @Test
    fun `single file m4b fallback gains the embedded chapters`() {
        val reparsed = listOf(
            RepairChapterInput("Chapter 1", 0L, 1_000L),
            RepairChapterInput("Chapter 2", 1_000L, 2_500L),
            RepairChapterInput("Chapter 3", 2_500L, null) // last end filled from duration
        )
        val tracks = listOf(
            RepairTrackInput(
                id = 7L,
                trackNumber = 1,
                discNumber = 1,
                title = "Book",
                durationMs = 3_000L,
                filePath = "/torrents/book.m4b",
                fileUri = "file:///torrents/book.m4b"
            )
        )

        val result = plan(stored = listOf(RepairChapterInput("Book", 0L, 3_000L)), tracks = tracks, reparsed = reparsed, primaryDurationMs = 3_000L)!!

        assertEquals(3, result.chapters.size)
        assertEquals(0L, result.chapters[0].startMs)
        assertEquals(2_500L, result.chapters[2].startMs)
        assertEquals(3_000L, result.chapters[2].endMs)
        assertEquals("file:///torrents/book.m4b", result.chapters[0].mediaUri)
        assertEquals(3_000L, result.durationMs)
        assertTrue(result.trackNumbers.isEmpty())
    }

    @Test
    fun `single file without tracks keeps the book-level file reference`() {
        val reparsed = listOf(
            RepairChapterInput("Chapter 1", 0L, 1_000L),
            RepairChapterInput("Chapter 2", 1_000L, 2_000L),
            RepairChapterInput("Chapter 3", 2_000L, 3_000L)
        )
        val result = OneChapterRepairPlanner.plan(
            bookTitle = "Book",
            fileNameStem = "book",
            stored = listOf(RepairChapterInput("Book", 0L, 300_000L)),
            tracks = emptyList(),
            reparsed = reparsed,
            primaryDurationMs = 3_000L,
            fallbackMediaUri = "file:///torrents/book.m4b",
            fallbackFilePath = "/torrents/book.m4b"
        )!!

        assertEquals(3, result.chapters.size)
        assertEquals("file:///torrents/book.m4b", result.chapters[0].mediaUri)
        assertEquals("/torrents/book.m4b", result.chapters[0].filePath)
        assertEquals(3_000L, result.durationMs)
    }

    @Test
    fun `repair replaces the stale fallback duration and partial size, keeping id and progress`() {
        val book = BookEntity(
            id = 77L,
            title = "Book",
            author = "Author",
            type = BookTypeEntity.AUDIOBOOK,
            format = FormatEntity.M4B,
            filePath = "/torrents/book.m4b",
            fileUri = "file:///torrents/book.m4b",
            fileSizeBytes = 5_000_000L, // partial write size
            importSource = ImportSourceEntity.TORRENT_DOWNLOAD,
            coverPath = "/data/covers/book_77.webp",
            lastOpenedAt = 1_700_000_000_000L,
            durationMs = 300_000L, // importer's 5-minute fallback
            chapterCount = 1,
            chaptersJson = "[{\"index\":0,\"title\":\"Book\",\"startMs\":0,\"endMs\":300000}]"
        )
        val progress = ReadingProgressEntity(
            bookId = 77L,
            progressPercent = 0.9f,
            positionMs = 3_240_000L,
            chapterIndex = 0,
            chapterPositionMs = 3_240_000L
        )

        val reparsed = listOf(
            RepairChapterInput("Chapter 1", 0L, 1_800_000L),
            RepairChapterInput("Chapter 2", 1_800_000L, null) // end filled from fresh metadata
        )
        val result = plan(
            stored = listOf(RepairChapterInput("Book", 0L, 300_000L)),
            tracks = listOf(track(1, "book.m4b")),
            reparsed = reparsed,
            primaryDurationMs = 3_600_000L
        )!!
        assertEquals(3_600_000L, result.durationMs)

        val repaired = OneChapterRepairPlanner.repairedBook(
            book = book,
            plan = result,
            chaptersJson = "[\"rebuilt\"]",
            knownFileSizeBytes = 812_000_000L,
            repairedAt = 555L
        )

        assertEquals(3_600_000L, repaired.durationMs)
        assertEquals(812_000_000L, repaired.fileSizeBytes)
        assertEquals(book.copy(
            chaptersJson = "[\"rebuilt\"]",
            chapterCount = 2,
            durationMs = 3_600_000L,
            fileSizeBytes = 812_000_000L,
            lastModifiedAt = 555L
        ), repaired)
        // Identity and reading state are untouched by construction.
        assertEquals(77L, repaired.id)
        assertEquals(book.fileUri, repaired.fileUri)
        assertEquals(book.filePath, repaired.filePath)
        assertEquals(book.coverPath, repaired.coverPath)
        assertEquals(1_700_000_000_000L, repaired.lastOpenedAt)
        assertEquals(77L, progress.bookId)
        assertEquals(0.9f, progress.progressPercent, 0f)
        assertEquals(3_240_000L, progress.positionMs)
    }

    @Test
    fun `reparse attempt key follows the file identity`() {
        assertEquals("5:100:7", OneChapterRepairPlanner.reparseAttemptKey(5L, 100L, 7L))
        assertNotEquals(
            OneChapterRepairPlanner.reparseAttemptKey(5L, 100L, 7L),
            OneChapterRepairPlanner.reparseAttemptKey(5L, 101L, 7L)
        )
        assertNotEquals(
            OneChapterRepairPlanner.reparseAttemptKey(5L, 100L, 7L),
            OneChapterRepairPlanner.reparseAttemptKey(5L, 100L, 8L)
        )
    }

    @Test
    fun `single file without more than one embedded chapter keeps the fallback`() {
        val single = listOf(RepairChapterInput("Book", 0L, 1_000L))
        assertNull(plan(tracks = listOf(track(1, "book.m4b")), reparsed = single))
        assertNull(plan(tracks = listOf(track(1, "book.m4b")), reparsed = emptyList()))
    }

    @Test
    fun `blank or malformed stored list is repaired, valid chapter title is not`() {
        val tracks = listOf(track(1, "a.mp3"), track(2, "b.mp3"))
        assertEquals(2, plan(stored = emptyList(), tracks = tracks)!!.chapters.size)
        assertEquals(2, plan(stored = listOf(RepairChapterInput("", 0L, 0L)), tracks = tracks)!!.chapters.size)
        // Album-tag style title that contains the book title is still the fallback.
        assertEquals(
            2,
            plan(stored = listOf(RepairChapterInput("Book: A LitRPG Adventure", 0L, 0L)), tracks = tracks)!!.chapters.size
        )
        // A real titled chapter starting at 0 is left alone.
        assertNull(plan(stored = listOf(RepairChapterInput("Prologue", 0L, 0L)), tracks = tracks))
    }

    @Test
    fun `repairing an old one-chapter torrent import keeps bookId, files and progress`() {
        val book = BookEntity(
            id = 42L,
            title = "Book",
            author = "Author",
            type = BookTypeEntity.AUDIOBOOK,
            format = FormatEntity.MP3,
            filePath = "/torrents/Book/track 01.mp3",
            fileUri = "file:///torrents/Book/track%2001.mp3",
            fileSizeBytes = 33_000_000L,
            importSource = ImportSourceEntity.TORRENT_DOWNLOAD,
            coverPath = "/data/covers/book_42.webp",
            lastOpenedAt = 1_700_000_000_000L,
            durationMs = 120_000L,
            chapterCount = 1,
            chaptersJson = "[{\"index\":0,\"title\":\"Book\",\"startMs\":0,\"endMs\":60000}]"
        )
        val progress = ReadingProgressEntity(
            bookId = 42L,
            progressPercent = 0.41f,
            positionMs = 24_600L,
            chapterIndex = 0,
            chapterPositionMs = 24_600L,
            pageIndex = 3
        )

        val result = plan(
            stored = listOf(RepairChapterInput("Book", 0L, 60_000L)),
            tracks = listOf(track(1, "track 01.mp3"), track(2, "track 02.mp3"))
        )!!
        assertEquals(120_000L, result.durationMs)
        val repaired = OneChapterRepairPlanner.repairedBook(
            book = book,
            plan = result,
            chaptersJson = "[\"rebuilt\"]",
            repairedAt = 123L
        )

        // Data-class equality proves ONLY the three chapter fields changed:
        // id, fileUri/filePath, cover, lastOpenedAt, duration, import source ...
        assertEquals(book.copy(chaptersJson = "[\"rebuilt\"]", chapterCount = 2, lastModifiedAt = 123L), repaired)
        assertEquals(42L, repaired.id)
        assertEquals("file:///torrents/Book/track%2001.mp3", repaired.fileUri)
        assertEquals("/data/covers/book_42.webp", repaired.coverPath)
        assertEquals(1_700_000_000_000L, repaired.lastOpenedAt)
        assertEquals(book.dateAdded, repaired.dateAdded)
        assertEquals(book.series, repaired.series)

        // The progress row is not part of the repair path and still references the same book.
        assertEquals(42L, progress.bookId)
        assertEquals(0.41f, progress.progressPercent, 0f)
        assertEquals(24_600L, progress.positionMs)
        assertEquals(0, progress.chapterIndex)
        assertEquals(3, progress.pageIndex)
    }

    @Test
    fun `chapter parser filename uses a real name with extension`() {
        assertEquals("Book.m4b", chapterParserFileName("/x/Book.m4b", null, "M4B"))
        assertEquals("Book.mp3", chapterParserFileName(null, "file:///x/Book.mp3", "M4B"))
        assertEquals("audio.m4b", chapterParserFileName(null, null, "M4B"))
    }
}
