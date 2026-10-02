package com.bookrio.library.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the audiobook identity used by fragment consolidation: a specific
 * title + present author is a strong identity that must survive online
 * metadata enrichment, while folder-only/generic identities stay weak.
 */
class AudiobookNormalizerTest {

    @Test
    fun `specific title with author is a strong identity`() {
        assertTrue(
            AudiobookNormalizer.hasStrongIdentity(
                "Death on Ocean Boulevard : Inside the Coronado Mansion Case",
                "Caitlin Rother"
            )
        )
    }

    @Test
    fun `blank author is not a strong identity`() {
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Death on Ocean Boulevard", null))
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Death on Ocean Boulevard", ""))
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Death on Ocean Boulevard", "   "))
    }

    @Test
    fun `generic track title is not a strong identity`() {
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Chapter 1", "Caitlin Rother"))
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Track 12", "Caitlin Rother"))
        assertFalse(AudiobookNormalizer.hasStrongIdentity("001", "Caitlin Rother"))
        assertFalse(AudiobookNormalizer.hasStrongIdentity("Audiobook", "Caitlin Rother"))
    }

    @Test
    fun `fragments of one audiobook share a strong group key`() {
        val title = "Death on Ocean Boulevard : Inside the Coronado Mansion Case"
        val author = "Caitlin Rother"
        val folder = "Death on Ocean Boulevard"
        val a = AudiobookNormalizer.computeGroupKey(title, author, folder)
        val b = AudiobookNormalizer.computeGroupKey(title, author, "/some/path/track-002.mp3")
        assertTrue(a == b)
        assertTrue(AudiobookNormalizer.hasStrongIdentity(title, author))
    }

    @Test
    fun `guesses author from an author-title folder when it differs from the narrator`() {
        assertEquals(
            "Terry Pratchett",
            AudiobookNormalizer.guessAuthorFromName(
                "Terry Pratchett - Discworld 01 - The Colour of Magic",
                "Stephen Briggs"
            )
        )
    }

    @Test
    fun `does not guess when the first segment already is the artist`() {
        assertNull(
            AudiobookNormalizer.guessAuthorFromName("Terry Pratchett - Mort", "Terry Pratchett")
        )
    }

    @Test
    fun `does not guess from a bare title or track prefix`() {
        assertNull(AudiobookNormalizer.guessAuthorFromName("Mort", "Stephen Briggs"))
        assertNull(AudiobookNormalizer.guessAuthorFromName("01 - Mort", "Stephen Briggs"))
        assertNull(AudiobookNormalizer.guessAuthorFromName(null, "Stephen Briggs"))
    }

    @Test
    fun `never treats an article-led title as an author`() {
        assertNull(AudiobookNormalizer.guessAuthorFromName("The Martian - Andy Weir", "Andy Weir"))
        assertNull(
            AudiobookNormalizer.guessAuthorFromName("The Expanse - Leviathan Wakes", "James S. A. Corey")
        )
        assertNull(AudiobookNormalizer.guessAuthorFromName("The Odyssey - Homer", "Homer"))
    }

    @Test
    fun `known-author gate accepts a real author and rejects a series name`() {
        assertTrue(EbookFilenameParser.isKnownAuthor("Terry Pratchett"))
        assertTrue(EbookFilenameParser.isKnownAuthor("pratchett"))
        assertFalse(EbookFilenameParser.isKnownAuthor("Harry Potter"))
        assertFalse(EbookFilenameParser.isKnownAuthor("The Expanse"))
        assertFalse(EbookFilenameParser.isKnownAuthor(null))
    }
}