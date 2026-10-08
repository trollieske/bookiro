package com.bookrio.reader.readium

import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.BookmarkTypeEntity
import com.bookrio.data.local.entity.HighlightEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner

/**
 * JVM tests for the pure Readium reader logic added on the RC branch: resume/position
 * migration ([ReadiumResume]), the preference mapping ([ReadiumReaderPreferences]) and
 * the highlight/locator bridge ([ReadiumHighlights]).
 *
 * These are the parts that decide whether a partially-read or bookmarked legacy book
 * resumes at the nearest text instead of silently at the start, so they must be exact.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumReaderLogicTest {
    private fun locatorJson(
        href: String = "/OEBPS/ch1.xhtml",
        progression: Double? = 0.5,
        totalProgression: Double? = 0.25,
        highlight: String? = "hello world",
    ): String {
        val locations = JSONObject().apply {
            progression?.let { put("progression", it) }
            totalProgression?.let { put("totalProgression", it) }
        }
        val text = JSONObject().apply { highlight?.let { put("highlight", it) } }
        return JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", locations)
            .put("text", text)
            .toString()
    }

    private fun locator(json: String = locatorJson()): Locator =
        Locator.fromJSON(JSONObject(json))!!

    // ── resume intent ────────────────────────────────────────────────────────────

    @Test
    fun `no progress row opens fresh`() {
        assertEquals(ResumeIntent.StartFresh, ReadiumResume.progressIntent(null))
    }

    @Test
    fun `stored locator json is restored exactly and is not approximate`() {
        val json = locatorJson()
        val intent = ReadiumResume.progressIntent(
            ReadingProgressEntity(bookId = 1, anchorCfi = json),
        )
        assertEquals(ResumeIntent.StoredLocator(json), intent)
    }

    @Test
    fun `legacy chapter index and fraction map to a best-effort chapter locator`() {
        val intent = ReadiumResume.progressIntent(
            ReadingProgressEntity(bookId = 1, chapterIndex = 2, progressPercent = 0.4f),
        ) as ResumeIntent.LegacyChapter
        assertEquals(2, intent.chapterIndex)
        assertEquals(0.4, intent.chapterProgression, 0.0001)
    }

    @Test
    fun `legacy page index on a started book is never reset to the start`() {
        val intent = ReadiumResume.progressIntent(
            ReadingProgressEntity(bookId = 1, pageIndex = 7, chapterIndex = 1, progressPercent = 0f),
        )
        assertEquals(ResumeIntent.LegacyChapter(1, 0.0), intent)
    }
    @Test
    fun `legacy global percent without a chapter is honestly unresolved`() {
        val intent = ReadiumResume.progressIntent(
            ReadingProgressEntity(bookId = 1, progressPercent = 0.5f),
        )
        assertEquals(ResumeIntent.LegacyUnresolved(0.5), intent)
    }

    @Test
    fun `never-started zero row opens fresh`() {
        assertEquals(
            ResumeIntent.StartFresh,
            ReadiumResume.progressIntent(ReadingProgressEntity(bookId = 1, progressPercent = 0f)),
        )
    }

    @Test
    fun `bookmark with locator json is restored, legacy bookmark maps by chapter`() {
        val withCfi = BookmarkEntity(bookId = 1, anchorCfi = locatorJson())
        assertTrue(ReadiumResume.bookmarkIntent(withCfi) is ResumeIntent.StoredLocator)

        val legacy = BookmarkEntity(bookId = 1, chapterIndex = 3, positionPercent = 0.2f)
        val legacyIntent = ReadiumResume.bookmarkIntent(legacy) as ResumeIntent.LegacyChapter
        assertEquals(3, legacyIntent.chapterIndex)
        assertEquals(0.2, legacyIntent.chapterProgression, 0.0001)

        val percentOnly = BookmarkEntity(bookId = 1, positionPercent = 0.6f)
        val percentIntent = ReadiumResume.bookmarkIntent(percentOnly) as ResumeIntent.LegacyUnresolved
        assertEquals(0.6, percentIntent.progressPercent, 0.0001)

        // A legacy page-only bookmark has no defensible text position.
        assertEquals(
            ResumeIntent.StartFresh,
            ReadiumResume.bookmarkIntent(BookmarkEntity(bookId = 1, pageIndex = 12)),
        )
    }

    @Test
    fun `locator json round-trips`() {
        val original = locator()
        val json = ReadiumResume.locatorJson(original)!!
        val restored = Locator.fromJSON(JSONObject(json))!!
        assertEquals(original.href.toString(), restored.href.toString())
        assertEquals(original.locations.progression, restored.locations.progression)
        assertEquals(original.locations.totalProgression, restored.locations.totalProgression)
        assertEquals(original.text.highlight, restored.text.highlight)
    }

    // ── preferences ──────────────────────────────────────────────────────────────

    @Test
    fun `defaults map to dark theme, 100 percent font, default margins and no forced line height`() {
        val prefs = ReadiumReaderPreferences.DEFAULT
        val epub = prefs.toEpubPreferences()
        assertEquals(1.0, epub.fontSize!!, 0.0)
        assertEquals(Theme.DARK, epub.theme)
        assertEquals(1.0, epub.pageMargins!!, 0.0)
        assertNull(epub.lineHeight)
        // No line height submitted -> the publisher keeps its spacing.
        assertNull(epub.publisherStyles)
        assertFalse(epub.scroll == true)
    }

    @Test
    fun `submitting an explicit line height enables readium advanced settings`() {
        val prefs = ReadiumReaderPreferences.DEFAULT.copy(lineHeightFactor = 1.6)
        val epub = prefs.toEpubPreferences()
        assertEquals(1.6, epub.lineHeight!!, 0.0)
        assertEquals(false, epub.publisherStyles)
    }

    @Test
    fun `font scale and margins are clamped to the supported range`() {
        val epub = ReadiumReaderPreferences.DEFAULT
            .copy(fontScale = 99.0, pageMarginFactor = -5.0)
            .toEpubPreferences()
        assertEquals(ReadiumReaderPreferences.MAX_FONT_SCALE, epub.fontSize!!, 0.0)
        assertEquals(ReadiumReaderPreferences.MIN_PAGE_MARGIN_FACTOR, epub.pageMargins!!, 0.0)
    }

    @Test
    fun `legacy sp and theme names bridge to readium values`() {
        assertEquals(1.0, ReadiumReaderPreferences.fontScaleForLegacySp(16), 0.0)
        assertEquals(2.0, ReadiumReaderPreferences.fontScaleForLegacySp(32), 0.0)
        assertEquals(16, ReadiumReaderPreferences.legacySpForFontScale(1.0))
        assertEquals(Theme.LIGHT, ReadiumReaderPreferences.themeForStorageName("light"))
        assertEquals(Theme.SEPIA, ReadiumReaderPreferences.themeForStorageName("sepia"))
        assertEquals(Theme.DARK, ReadiumReaderPreferences.themeForStorageName("black"))
        assertEquals(Theme.DARK, ReadiumReaderPreferences.themeForStorageName(null))
        assertEquals("dark", ReadiumReaderPreferences.storageNameForTheme(Theme.DARK))
    }

    // ── highlights ───────────────────────────────────────────────────────────────

    @Test
    fun `highlight entity round-trips through its decoration id`() {
        assertEquals(42L, ReadiumHighlights.entityId(ReadiumHighlights.decorationId(42L)))
        assertNull(ReadiumHighlights.entityId("not-a-highlight"))
    }

    @Test
    fun `a selection with text becomes a persistent highlight with its locator`() {
        val entity = ReadiumHighlights.toEntity(bookId = 7, locator = locator())!!
        assertEquals(7L, entity.bookId)
        assertEquals("hello world", entity.text)
        assertNull(entity.endCfi)
        assertEquals(0.25f, entity.positionPercent!!, 0.0001f)
        assertTrue(entity.startCfi!!.isNotBlank())
        // The stored start locator is a real Readium locator that can be re-opened.
        assertEquals("hello world", ReadiumHighlights.locatorOf(entity)!!.text.highlight)
    }

    @Test
    fun `a selection without a quote is not persisted`() {
        assertNull(ReadiumHighlights.toEntity(7, locator(locatorJson(highlight = null))))
    }

    @Test
    fun `a highlight row without a locator has no decoration`() {
        assertNull(ReadiumHighlights.toDecoration(HighlightEntity(bookId = 1, text = "x")))
        val decorated = ReadiumHighlights.toDecoration(
            HighlightEntity(bookId = 1, text = "hello", startCfi = locatorJson()),
        )
        assertEquals(ReadiumHighlights.GROUP, ReadiumHighlights.GROUP)
        assertTrue(decorated != null)
    }

    // ── search snippet ───────────────────────────────────────────────────────────

    @Test
    fun `search snippet collapses whitespace and keeps the highlight`() {
        val locator = Locator.fromJSON(
            JSONObject()
                .put("href", "/OEBPS/ch1.xhtml")
                .put("type", "application/xhtml+xml")
                .put("text", JSONObject().apply {
                    put("before", "the quick\n brown   ")
                    put("highlight", "fox jumps")
                    put("after", "over the lazy dog")
                }),
        )!!
        val snippet = searchSnippet(locator)
        assertEquals("the quick brown fox jumps over the lazy dog", snippet)
    }
}