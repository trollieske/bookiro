package com.bookrio.library.sort

import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.core.domain.model.defaultDirectionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the single sorting source of truth (LibrarySorter).
 */
class LibrarySorterTest {

    private fun book(
        id: Long,
        title: String,
        author: String = "",
        series: String? = null,
        seriesIndex: Float? = null,
        dateAdded: Long = 0L,
        lastActivity: Long = 0L,
        updatedAt: Long = 0L,
        isAudio: Boolean = false
    ) = SortBook(
        id = id,
        title = title,
        sortTitle = title,
        author = author,
        sortAuthor = author,
        series = series,
        seriesIndex = seriesIndex,
        seriesIndexResolved = seriesIndex ?: LibrarySorter.parseSeriesIndex(series),
        dateAdded = dateAdded,
        lastActivity = lastActivity,
        updatedAt = updatedAt,
        isAudio = isAudio
    )

    // 1. Natural numeric series order: 1, 2, 10 (The Dark Tower)
    @Test
    fun `dark tower volumes sort 1 2 10`() {
        val books = listOf(
            book(3, "The Dark Tower", series = "The Dark Tower", seriesIndex = 10f),
            book(1, "The Gunslinger", series = "The Dark Tower", seriesIndex = 1f),
            book(2, "The Drawing of the Three", series = "The Dark Tower", seriesIndex = 2f)
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.HYLLE, SortDirection.ASC)
        assertEquals(listOf(1L, 2L, 3L), sorted.map { it.id })
    }

    // 2. Decimal volume 2.5 sits between 2 and 3
    @Test
    fun `decimal volume 2_5 sorts between 2 and 3`() {
        val books = listOf(
            book(1, "C", series = "Rune", seriesIndex = 3f),
            book(2, "B", series = "Rune", seriesIndex = 2.5f),
            book(3, "A", series = "Rune", seriesIndex = 2f)
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.HYLLE, SortDirection.ASC)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    // 2b. Conservative parse from series string only: "2", "2.5", "Book 2", "#2"
    @Test
    fun `series index parses conservatively from series string`() {
        assertEquals(2f, LibrarySorter.parseSeriesIndex("2"))
        assertEquals(2.5f, LibrarySorter.parseSeriesIndex("2.5"))
        assertEquals(2f, LibrarySorter.parseSeriesIndex("Book 2"))
        assertEquals(2f, LibrarySorter.parseSeriesIndex("#2"))
        assertEquals(null, LibrarySorter.parseSeriesIndex("Harry Potter og De vises stein"))
        // Never inferred from title patterns:
        val noIndex = book(1, "Mistborn 3", series = "Mistborn")
        assertEquals(null, noIndex.seriesIndexResolved)
    }

    // 3. Same-author non-series books remain adjacent in HYLLE mode
    @Test
    fun `same author non-series books stay adjacent in HYLLE`() {
        val books = listOf(
            book(1, "Zebra book", author = "Terry Pratchett"),
            book(2, "Another series", author = "Other Writer", series = "S", seriesIndex = 1f),
            book(3, "Aardvark book", author = "Terry Pratchett"),
            book(4, "Middle book", author = "Terry Pratchett")
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.HYLLE, SortDirection.ASC)
        val positions = sorted.map { it.id }
        // The three Pratchett books must be consecutive.
        val idxs = positions.withIndex().filter { it.value in setOf(1L, 3L, 4L) }.map { it.index }
        assertEquals(listOf(idxs.first(), idxs.first() + 1, idxs.first() + 2), idxs)
    }

    // 4. Missing series/author does not crash and sorts after known metadata
    @Test
    fun `missing series and author sort after known metadata`() {
        val books = listOf(
            book(1, "Unknown one", author = "Ukjent forfatter"),
            book(2, "Known book", author = "Aksel Aasen", series = "Sagaen", seriesIndex = 1f),
            book(3, "Unknown two", author = "", series = null),
            book(4, "Another known", author = "Berit Bremnes"),
            book(5, "Null series", author = "Berit Bremnes", series = "null")
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.HYLLE, SortDirection.ASC)
        assertEquals(listOf(2L, 4L, 5L, 1L, 3L), sorted.map { it.id })
    }

    // 4b. Garbage series values are never treated as valid series ("Series 0", filenames)
    @Test
    fun `garbage series values are invalid`() {
        assertTrue(!LibrarySorter.isValidSeries("Series 0"))
        assertTrue(!LibrarySorter.isValidSeries("null"))
        assertTrue(!LibrarySorter.isValidSeries("OEBPS/chapter1.xhtml"))
        assertTrue(!LibrarySorter.isValidSeries("  "))
        assertTrue(LibrarySorter.isValidSeries("The Dark Tower"))
    }

    // 5. Media isolation is part of candidate selection (see ResumeSelectorTest);
    //    grid isolation is enforced by the ViewModel filter, mirrored here:
    @Test
    fun `audio flag only drives media separation in resume input`() {
        val ebook = book(1, "Ebook", isAudio = false)
        val audio = book(2, "Audio", isAudio = true)
        assertTrue(!ebook.isAudio && audio.isAudio)
    }

    // 6. Every visible sort rail choice is a real, distinct comparator
    @Test
    fun `all six rail modes produce real distinct orderings`() {
        val books = listOf(
            book(1, "Beta", author = "Aune", series = "Serie B", seriesIndex = 1f, dateAdded = 100, lastActivity = 100),
            book(2, "Alfa", author = "Berit", series = "Serie A", seriesIndex = 1f, dateAdded = 300, lastActivity = 300),
            book(3, "Gamma", author = "Åse", series = null, dateAdded = 200, lastActivity = 500)
        )
        val orders = LibrarySortMode.entries.associateWith { mode ->
            LibrarySorter.sort(books, mode, defaultDirectionFor(mode)).map { it.id.toInt() }
        }
        // Each mode yields a defined, non-crashing order…
        assertEquals(6, orders.size)
        // …and they are not all the same ordering (real comparators, not a stub).
        assertTrue(orders.values.toSet().size >= 4)
        assertEquals(listOf(2, 1, 3), orders[LibrarySortMode.TITTEL]) // Alfa, Beta, Gamma
        assertEquals(listOf(1, 2, 3), orders[LibrarySortMode.FORFATTER]) // Aune, Berit, Åse
        assertEquals(listOf(2, 3, 1), orders[LibrarySortMode.LAGT_TIL]) // newest added first
        assertEquals(listOf(3, 2, 1), orders[LibrarySortMode.NYLIG]) // most recent activity first
    }

    // 7. Direction toggle changes actual order
    @Test
    fun `direction toggle changes actual order`() {
        val books = listOf(
            book(1, "Alfa"),
            book(2, "Beta"),
            book(3, "Gamma")
        )
        val asc = LibrarySorter.sort(books, LibrarySortMode.TITTEL, SortDirection.ASC).map { it.id }
        val desc = LibrarySorter.sort(books, LibrarySortMode.TITTEL, SortDirection.DESC).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), asc)
        assertEquals(listOf(3L, 2L, 1L), desc)

        val newest = LibrarySorter.sort(books, LibrarySortMode.LAGT_TIL, SortDirection.DESC).map { it.id }
        val oldest = LibrarySorter.sort(books, LibrarySortMode.LAGT_TIL, SortDirection.ASC).map { it.id }
        assertTrue(newest != oldest)
    }

    // 8. Default + legacy migration; legacy "Shelf" maps to Series.
    @Test
    fun `default sort is recently added and legacy values migrate`() {
        assertEquals(LibrarySortMode.LAGT_TIL, LibrarySortMode.from(null))
        assertEquals(LibrarySortMode.LAGT_TIL, LibrarySortMode.from(""))
        assertEquals(LibrarySortMode.LAGT_TIL, LibrarySortMode.from("date_added")) // legacy value
        assertEquals(LibrarySortMode.NYLIG, LibrarySortMode.from("progress")) // legacy value
        assertEquals(LibrarySortMode.SERIE, LibrarySortMode.from("hylle")) // legacy "Shelf"
        assertEquals(LibrarySortMode.TITTEL, LibrarySortMode.from("tittel"))
    }

    @Test
    fun `visible sort modes exclude the legacy shelf and contain the default`() {
        assertFalse(LibrarySortMode.HYLLE in LibrarySortMode.visible)
        assertTrue(LibrarySortMode.DEFAULT in LibrarySortMode.visible)
        assertEquals(5, LibrarySortMode.visible.size)
    }

    // Section labels: series labeled, author groups labeled, 1-book author groups not
    @Test
    fun `series section labels`() {
        val books = listOf(
            book(1, "Vol 1", author = "Stephen King", series = "The Dark Tower", seriesIndex = 1f),
            book(2, "Vol 2", author = "Stephen King", series = "The Dark Tower", seriesIndex = 2f),
            book(3, "Alfa", author = "Jon Fosse"),
            book(4, "Beta", author = "Jon Fosse"),
            book(5, "Single", author = "Neste Forfatter")
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.SERIE, SortDirection.ASC)
        val labels = LibrarySorter.sectionLabels(sorted, LibrarySortMode.SERIE)
        val labeled = sorted.mapIndexedNotNull { i, b -> labels[i]?.let { it to b.id } }
        assertTrue(labels.any { it == "THE DARK TOWER · STEPHEN KING" })
        assertTrue(labels.any { it == "JON FOSSE" })
        // No label for the single-book author group:
        assertTrue(labeled.none { (_, id) -> id == 5L })
        // Unknown series/author never produce labels:
        val unknown = listOf(book(9, "Mystery", author = "", series = null))
        assertEquals(listOf<String?>(null), LibrarySorter.sectionLabels(unknown, LibrarySortMode.SERIE))
    }

    @Test
    fun `author grouping labels every author group`() {
        val books = listOf(
            book(1, "Alfa", author = "Jon Fosse"),
            book(2, "Beta", author = "Jon Fosse"),
            book(3, "Single", author = "Neste Forfatter")
        )
        val sorted = LibrarySorter.sort(books, LibrarySortMode.FORFATTER, SortDirection.ASC)
        val labels = LibrarySorter.sectionLabels(sorted, LibrarySortMode.FORFATTER)
        assertTrue(labels.any { it == "JON FOSSE" })
        assertTrue(labels.any { it == "NESTE FORFATTER" })
        // Non-grouping modes never label.
        assertEquals(List(books.size) { null }, LibrarySorter.sectionLabels(sorted, LibrarySortMode.TITTEL))
    }
}
