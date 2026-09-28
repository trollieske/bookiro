package com.bookrio.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fokuserte tester for kanonisk kapitteloppfriskning (Part A).
 * Alle reglene kjøres via [ChapterRefresh.evaluateStoredChapters] — den samme
 * funksjonen AudiobookEngine.ensureFreshChapters bruker.
 */
class ChapterRefreshTest {

    private fun row(title: String, startMs: Long) = StoredChapterRow(title, startMs)

    @Test
    fun `1 blank chaptersJson triggers discovery`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = true,
            parsedChapters = emptyList(),
            bookTitle = "KL: A History of the Nazi Concentration Camps",
            storedChapterCount = null,
        )
        assertEquals(ChapterStaleReason.BLANK_JSON, reason)
        assertNotNull(reason) // ikke-null => discovery trigges
    }

    @Test
    fun `2 one synthetic stub entry triggers discovery`() {
        // buildStubChapters lager nøyaktig denne formen: tittel = boktittel, startMs = 0
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("KL: A History of the Nazi Concentration Camps", 0L)),
            bookTitle = "KL: A History of the Nazi Concentration Camps",
            storedChapterCount = 1,
        )
        assertEquals(ChapterStaleReason.SINGLE_STUB, reason)
    }

    @Test
    fun `2c author-prefixed single entry counts as stub`() {
        // Ekte tilfelle: 'Andy Weir - Project Hail Mary' mot boktittel 'Project Hail Mary'
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Andy Weir - Project Hail Mary", 0L)),
            bookTitle = "Project Hail Mary",
            storedChapterCount = 1,
        )
        assertEquals(ChapterStaleReason.SINGLE_STUB, reason)
    }

    @Test
    fun `2b blank-titled single entry counts as stub`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("", 0L)),
            bookTitle = "Noen bok",
            storedChapterCount = 1,
        )
        assertEquals(ChapterStaleReason.SINGLE_STUB, reason)
    }

    @Test
    fun `3 valid multi-chapter list is never overwritten`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(
                row("Del én", 0L),
                row("Del to", 600_000L),
                row("Del tre", 1_200_000L),
            ),
            bookTitle = "Noen bok",
            storedChapterCount = 3,
        )
        assertNull(reason) // null => behold uendret, ingen re-parse
    }

    @Test
    fun `3b chapter count disagreement with metadata triggers refresh`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Del én", 0L), row("Del to", 600_000L)),
            bookTitle = "Noen bok",
            storedChapterCount = 12, // DB chapterCount sier 12, JSON har 2
        )
        assertEquals(ChapterStaleReason.COUNT_MISMATCH, reason)
    }

    @Test
    fun `6 genuine single chapter is kept as-is`() {
        // Én ekte kapittelfil: tittel skiller seg fra boktittel og/eller startMs > 0
        val genuine = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Full opplesning", 5_000L)),
            bookTitle = "Noen bok",
            storedChapterCount = 1,
        )
        assertNull(genuine)

        val genuineSameTitleButOffset = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Noen bok", 5_000L)),
            bookTitle = "Noen bok",
            storedChapterCount = 1,
        )
        assertNull(genuineSameTitleButOffset)
    }

    @Test
    fun `6b genuine single chapter with offset is kept even for chapter-capable container`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Full opplesning", 5_000L)),
            bookTitle = "Noen bok",
            storedChapterCount = 1,
            mayEmbedChapters = true,
        )
        assertNull(reason)
    }

    @Test
    fun `7 subtitle-suffixed book title with filename fallback triggers discovery`() {
        // Ekte FTP-tilfelle: fallback-tittel «Dungeon Crawler Carl» mot
        // boktittel «Dungeon Crawler Carl: A LitRPG/Gamelit Adventure».
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Dungeon Crawler Carl", 0L)),
            bookTitle = "Dungeon Crawler Carl: A LitRPG/Gamelit Adventure",
            storedChapterCount = 1,
        )
        assertEquals(ChapterStaleReason.SINGLE_STUB, reason)
    }

    @Test
    fun `7b punctuation-only difference in single fallback triggers discovery`() {
        // Ekte FTP-tilfelle: «Protocols An Operating Manual…» mot
        // «Protocols: An Operating Manual…» (bare kolon skiller).
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Protocols An Operating Manual for the Human Body", 0L)),
            bookTitle = "Protocols: An Operating Manual for the Human Body",
            storedChapterCount = 1,
        )
        assertEquals(ChapterStaleReason.SINGLE_STUB, reason)
    }

    @Test
    fun `7c unverified single entry in chapter-capable container is rescanned`() {
        val reason = ChapterRefresh.evaluateStoredChapters(
            jsonBlank = false,
            parsedChapters = listOf(row("Prologue", 0L)),
            bookTitle = "Noen bok",
            storedChapterCount = 1,
            mayEmbedChapters = true,
        )
        assertEquals(ChapterStaleReason.UNVERIFIED_SINGLE, reason)
    }

    @Test
    fun `7d parser filename keeps a real extension for discovery`() {
        assertEquals(
            "Dungeon Crawler Carl.m4b",
            ChapterRefresh.chapterParserFileName("/x/Dungeon Crawler Carl.m4b", null, "M4B"),
        )
        assertTrue(
            ChapterRefresh.chapterParserFileName(null, "file:///x/Book.mp3", "M4B").endsWith(".mp3")
        )
        assertEquals("audio.m4b", ChapterRefresh.chapterParserFileName(null, null, "M4B"))
    }

    @Test
    fun `4 multi-track files sort naturally 1 2 10`() {
        val names = listOf("Chapter 10", "Chapter 1", "Chapter 2", "Chapter 20", "Chapter 3")
        val sorted = ChapterRefresh.sortedNaturally(names) { it }
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 10", "Chapter 20"), sorted)

        assertEquals(0, ChapterRefresh.naturalCompare("del1.mp3", "Del1.MP3"))
        assertTrue(ChapterRefresh.naturalCompare("kap2", "kap10") < 0)
    }

    @Test
    fun `5 discovered M4B chapters build contiguous real chapters with ends`() {
        val discovered = listOf(
            com.bookrio.core.domain.model.ChapterInfo(index = 0, title = "Part One", startMs = 0L, endMs = null, href = null),
            com.bookrio.core.domain.model.ChapterInfo(index = 1, title = "Part Two", startMs = 3_600_000L, endMs = null, href = null),
            com.bookrio.core.domain.model.ChapterInfo(index = 2, title = "Part Three", startMs = 7_200_000L, endMs = null, href = null),
        )
        val chapters = chaptersFromDiscovered(discovered, durationMs = 9_000_000L, sourceUri = "file:///bok.m4b")
        assertEquals(3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(3_600_000L, chapters[0].endMs)
        assertEquals(3_600_000L, chapters[1].startMs)
        assertEquals(7_200_000L, chapters[1].endMs)
        assertEquals(7_200_000L, chapters[2].startMs)
        assertEquals(9_000_000L, chapters[2].endMs) // totalvarighet fyller siste slutt
        assertEquals("file:///bok.m4b", chapters[0].mediaUri)
    }
}
