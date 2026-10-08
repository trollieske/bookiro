package com.bookrio.library.cover

import com.bookrio.core.net.FetchedMetadata
import com.bookrio.core.net.MetadataFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM safety audit of the online metadata result selection used by
 * "Refresh metadata & covers" (`MetadataRefreshWorker` → `CoverRepository.
 * refreshOnline` → `MetadataFetcher.enrich` → `pickBestResult`).
 *
 * `enrich` itself needs the network, but the selection/merge step is pure and is
 * reached reflectively here because it is private.
 */
class MetadataResultSafetyTest {

    private fun pickBest(
        results: List<FetchedMetadata>,
        rawTitle: String,
        rawAuthor: String?,
        authorUnknown: Boolean,
        rawIsbn: String? = null
    ): FetchedMetadata? {
        val method = MetadataFetcher::class.java.getDeclaredMethod(
            "pickBestResult",
            List::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(MetadataFetcher, results, rawTitle, rawAuthor, authorUnknown, rawIsbn)
            as FetchedMetadata?
    }

    @Test
    fun `no results yields no metadata update`() {
        assertEquals(null, pickBest(emptyList(), "The Martian", "Andy Weir", false))
    }

    @Test
    fun `an exactly matching result is preferred`() {
        val ok = FetchedMetadata(title = "The Martian", author = "Andy Weir", source = "openlibrary")
        val other = FetchedMetadata(title = "The Color Purple", author = "Alice Walker", source = "google")
        val best = pickBest(listOf(other, ok), "The Martian", "Andy Weir", false)
        assertEquals("The Martian", best?.title)
        assertEquals("Andy Weir", best?.author)
    }

    /**
     * FIXED: the minimum-match gate rejects a candidate whose title does not match
     * the query, even when it carries cover + ISBN + author points. The matching
     * result wins instead, so a bad network hit cannot overwrite real metadata.
     */
    @Test
    fun `a mismatched title no longer wins over the matching result`() {
        val realBook = FetchedMetadata(
            title = "The Martian",
            author = null,
            source = "openlibrary"
        )
        val wrongBook = FetchedMetadata(
            title = "A Completely Different Book",
            author = "Someone Else",
            isbn = "9780000000000",
            coverUrl = "https://example.com/wrong.jpg",
            source = "google"
        )

        val best = pickBest(listOf(realBook, wrongBook), "The Martian", "Andy Weir", false)

        assertNotNull(best)
        assertEquals("The Martian", best?.title)
    }

    @Test
    fun `a lone mismatched result is rejected entirely (retain existing data)`() {
        val wrongBook = FetchedMetadata(
            title = "A Completely Different Book",
            author = "Someone Else",
            isbn = "9780000000000",
            coverUrl = "https://example.com/wrong.jpg",
            source = "google"
        )

        val best = pickBest(listOf(wrongBook), "The Martian", "Andy Weir", false)

        assertEquals(null, best)
    }

    @Test
    fun `a matching ISBN is accepted even when the title is formatted differently`() {
        val candidate = FetchedMetadata(
            title = "Dune (Deluxe Edition)",
            author = "Frank Herbert",
            isbn = "9780441013593",
            source = "openlibrary"
        )

        val best = pickBest(
            listOf(candidate),
            "Dune",
            "Frank Herbert",
            false,
            rawIsbn = "9780441013593"
        )

        assertNotNull(best)
        assertEquals("9780441013593", best?.isbn)
    }

    @Test
    fun `unknown author detection treats blank and placeholder values as unknown`() {
        assertTrue(MetadataFetcher.isAuthorUnknown(null))
        assertTrue(MetadataFetcher.isAuthorUnknown(""))
        assertTrue(MetadataFetcher.isAuthorUnknown("Ukjent forfatter"))
        assertTrue(MetadataFetcher.isAuthorUnknown("unknown"))
        assertFalse(MetadataFetcher.isAuthorUnknown("Andy Weir"))
    }
}