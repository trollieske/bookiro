package com.bookrio.library.util

import org.junit.Assert.assertFalse
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
}