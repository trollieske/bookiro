package com.bookrio.reader.readium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner

/**
 * Pure JVM tests for TOC flattening / spine fallback / Locator -> active chapter.
 * These do NOT prove real viewport or on-screen behavior — only the mapping logic.
 * Robolectric supplies `android.net.Uri`, which Readium's `Url` relies on.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumChapterMappingTest {

    private fun url(value: String): Url = requireNotNull(Url.fromDecodedPath(value)) { "bad url $value" }

    private fun link(href: String, title: String? = null, children: List<Link> = emptyList()) =
        Link(href = url(href), title = title, children = children)

    @Test
    fun `hierarchical toc keeps nesting depth and titles`() {
        val toc = listOf(
            link(
                "ch1.xhtml", "Chapter 1",
                children = listOf(link("ch1a.xhtml", "Section 1.1")),
            ),
            link("ch2.xhtml", "Chapter 2"),
        )
        val entries = flattenTocEntries(toc) { it.href.toString() }
        assertEquals(3, entries.size)
        assertEquals(listOf(0, 1, 0), entries.map { it.depth })
        assertEquals(listOf("Chapter 1", "Section 1.1", "Chapter 2"), entries.map { it.title })
        assertTrue(entries.none { it.unnamed })
    }

    @Test
    fun `blank toc title falls back to filename and is marked unnamed`() {
        val toc = listOf(link("OEBPS/my_chapter.xhtml", title = "   "))
        val entries = flattenTocEntries(toc) { it.href.toString() }
        assertEquals(1, entries.size)
        assertEquals("my chapter", entries[0].title)
        assertTrue(entries[0].unnamed)
    }

    @Test
    fun `spine fallback is unnamed sections`() {
        val spine = listOf(link("cover.xhtml"), link("c1.xhtml"))
        val entries = spineFallbackEntries(spine) { it.href.toString() }
        assertEquals(listOf("", ""), entries.map { it.title })
        assertTrue(entries.all { it.unnamed })
    }

    @Test
    fun `spine fallback keeps real titles when present`() {
        val spine = listOf(link("c1.xhtml", "Real Title"))
        val entries = spineFallbackEntries(spine) { it.href.toString() }
        assertEquals("Real Title", entries[0].title)
        assertFalse(entries[0].unnamed)
    }

    @Test
    fun `locator matches entry by exact resource key`() {
        val entries = listOf(
            ChapterEntry(link("c1.xhtml"), "One", 0, false, "OEBPS/c1.xhtml"),
            ChapterEntry(link("c2.xhtml"), "Two", 0, false, "OEBPS/c2.xhtml"),
        )
        assertEquals(0, chapterIndexForLocator(entries, url("OEBPS/c1.xhtml")))
        assertEquals(1, chapterIndexForLocator(entries, url("OEBPS/c2.xhtml")))
    }

    @Test
    fun `locator matches entry when only the path suffix matches`() {
        val entries = listOf(
            ChapterEntry(link("c2.xhtml"), "Two", 0, false, "OEBPS/text/c2.xhtml"),
        )
        assertEquals(0, chapterIndexForLocator(entries, url("c2.xhtml")))
    }

    @Test
    fun `locator on a nested section selects that nested entry`() {
        val entries = listOf(
            ChapterEntry(link("c1.xhtml"), "Chapter 1", 0, false, "OEBPS/c1.xhtml"),
            ChapterEntry(link("c1a.xhtml"), "Section 1.1", 1, false, "OEBPS/c1a.xhtml"),
        )
        assertEquals(1, chapterIndexForLocator(entries, url("OEBPS/c1a.xhtml")))
    }

    @Test
    fun `unknown locator href yields no active chapter`() {
        val entries = listOf(ChapterEntry(link("c1.xhtml"), "One", 0, false, "OEBPS/c1.xhtml"))
        assertEquals(-1, chapterIndexForLocator(entries, url("OEBPS/other.xhtml")))
    }
}