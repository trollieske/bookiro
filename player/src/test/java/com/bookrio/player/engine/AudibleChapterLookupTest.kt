package com.bookrio.player.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tester for nettbasert kapitteloppslag (Audible-metadata via audnexus):
 * generisk-tittel-deteksjon, tittel-merging ved likt antall, og kapittel-
 * bygging fra varigheter med ±5 % varighetsvalidering.
 */
class AudibleChapterLookupTest {

    @Test
    fun `generic titles are detected`() {
        assertTrue(AudibleChapterLookup.looksGeneric("Chapter 01"))
        assertTrue(AudibleChapterLookup.looksGeneric("Chapter 1"))
        assertTrue(AudibleChapterLookup.looksGeneric("Kapittel 12"))
        assertTrue(AudibleChapterLookup.looksGeneric("Track 3"))
        assertTrue(AudibleChapterLookup.looksGeneric(""))
        // Ekte titler er IKKE generiske
        assertFalse(AudibleChapterLookup.looksGeneric("Opening Credits"))
        assertFalse(AudibleChapterLookup.looksGeneric("Problem Solved"))
        assertFalse(AudibleChapterLookup.looksGeneric("Part One: 1933"))
    }

    @Test
    fun `titles merge only when counts match exactly`() {
        val ours = listOf(
            AudiobookChapter(0, "Chapter 01", 0L, 1_800_000L),
            AudiobookChapter(1, "Chapter 02", 1_800_000L, 3_600_000L),
        )
        val fetched = listOf(
            Triple("Opening Credits", 0L, 1_800_000L),
            Triple("Problem Solved", 1_800_000L, 1_800_000L),
        )
        val merged = AudibleChapterLookup.mergeTitlesIfCountMatches(ours, fetched)
        assertNotNull(merged)
        assertEquals("Opening Credits", merged!![0].title)
        assertEquals("Problem Solved", merged[1].title)
        // Våre tidsstempler røres aldri
        assertEquals(0L, merged[0].startMs)
        assertEquals(1_800_000L, merged[1].startMs)
    }

    @Test
    fun `titles do not merge when counts differ`() {
        val ours = listOf(AudiobookChapter(0, "Chapter 01", 0L, 1_800_000L))
        val fetched = listOf(
            Triple("A", 0L, 900_000L),
            Triple("B", 900_000L, 900_000L),
        )
        assertNull(AudibleChapterLookup.mergeTitlesIfCountMatches(ours, fetched))
    }

    @Test
    fun `titles do not merge when fetched titles are all generic`() {
        val ours = listOf(
            AudiobookChapter(0, "Chapter 01", 0L, 1_800_000L),
            AudiobookChapter(1, "Chapter 02", 1_800_000L, 3_600_000L),
        )
        val fetched = listOf(
            Triple("Chapter 1", 0L, 1_800_000L),
            Triple("Chapter 2", 1_800_000L, 1_800_000L),
        )
        assertNull(AudibleChapterLookup.mergeTitlesIfCountMatches(ours, fetched))
    }

    @Test
    fun `chapters can be created from fetched durations when we have none`() {
        val fetched = listOf(
            Triple("Opening Credits", 0L, 60_000L),
            Triple("Chapter 1", 60_000L, 3_000_000L),
            Triple("Chapter 2", 3_060_000L, 2_940_000L),
        )
        val built = AudibleChapterLookup.buildChaptersFromFetched(fetched, "file:///bok.mp3", ourDurationMs = 6_000_000L)
        assertNotNull(built)
        assertEquals(3, built!!.size)
        assertEquals(0L, built[0].startMs)
        assertEquals(60_000L, built[1].startMs)
        assertEquals(3_060_000L, built[2].startMs)
        assertEquals(6_000_000L, built[2].endMs)
        assertEquals("file:///bok.mp3", built[0].mediaUri)
    }

    @Test
    fun `chapters are not created when total duration diverges`() {
        val fetched = listOf(
            Triple("A", 0L, 3_000_000L),
            Triple("B", 3_000_000L, 3_000_000L),
        )
        // 6 000 000 mot vår 10 000 000 → 40 % avvik > 5 % toleranse
        assertNull(AudibleChapterLookup.buildChaptersFromFetched(fetched, null, ourDurationMs = 10_000_000L))
        // Ukjent egen varighet → aldri bygge
        assertNull(AudibleChapterLookup.buildChaptersFromFetched(fetched, null, ourDurationMs = 0L))
    }

    @Test
    fun `search query strips author prefix, series number and brackets`() {
        val queries = AudibleChapterLookup.buildSearchQueries(
            "Stephen R. Donaldson - Thomas Covenant 01 - Lord Fouls Bane [2008-ScottBrick]",
            "Stephen R. Donaldson",
        )
        // Beste søk først: ren tittel + forfatter (gir nøyaktig riktig ASIN)
        assertEquals("Lord Fouls Bane Stephen R. Donaldson", queries.first())
        // Aldri den forurensede råstrengen som første forsøk
        assertFalse(queries.first().startsWith("Stephen R. Donaldson - "))
        // Den rene tittelen finnes også uten forfatter
        assertTrue(queries.contains("Lord Fouls Bane"))
    }

    @Test
    fun `search query keeps colon subtitle as a candidate`() {
        val queries = AudibleChapterLookup.buildSearchQueries(
            "Dungeon Crawler Carl: A LitRPG/Gamelit Adventure",
            "Matt Dinniman",
        )
        assertTrue(queries.contains("Dungeon Crawler Carl Matt Dinniman"))
        assertTrue(queries.contains("Dungeon Crawler Carl"))
    }

    @Test
    fun `search query strips trailing book number and leading track number`() {
        val queries = AudibleChapterLookup.buildSearchQueries("The First Law, Book 3", "Joe Abercrombie")
        assertTrue(queries.contains("The First Law Joe Abercrombie"))

        val leading = AudibleChapterLookup.buildSearchQueries("01 - The Blade Itself", "Joe Abercrombie")
        assertTrue(leading.contains("The Blade Itself Joe Abercrombie"))
    }

    @Test
    fun `search query handles blank title`() {
        assertTrue(AudibleChapterLookup.buildSearchQueries("   ", "Someone").isEmpty())
    }
}
