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
     * DATA-SAFETY finding for area 3: there is no minimum-match threshold. A
     * candidate that does not match the queried title at all wins on cover URL +
     * ISBN + author-overlap points over the matching result, and its
     * title/author are returned as the "best" metadata. `MetadataRefreshWorker`
     * scope=ALL persists that result over the real metadata
     * (`enrichMetadataSilently`), with no review step and no stored undo.
     */
    @Test
    fun `a completely mismatched title still wins when it has cover and isbn points`() {
        // The matching result has no author/cover/ISBN (20 title points).
        val realBook = FetchedMetadata(
            title = "The Martian",
            author = null,
            source = "openlibrary"
        )
        // The mismatched result gets isbn 8 + cover 10 + author occurrence 12 = 30.
        val wrongBook = FetchedMetadata(
            title = "A Completely Different Book",
            author = "Someone Else",
            isbn = "9780000000000",
            coverUrl = "https://example.com/wrong.jpg",
            source = "google"
        )

        val best = pickBest(listOf(realBook, wrongBook), "The Martian", "Andy Weir", false)

        assertNotNull(best)
        assertEquals("A Completely Different Book", best?.title)
        assertEquals("Someone Else", best?.author)
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