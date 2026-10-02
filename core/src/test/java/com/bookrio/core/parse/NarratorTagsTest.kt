package com.bookrio.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NarratorTagsTest {

    @Test
    fun `strips norwegian narrator credit in parentheses`() {
        val split = NarratorTags.split("Knut Hamsun (innlest av Per Theodor Haugen)")
        assertEquals("Knut Hamsun", split.author)
        assertEquals("Per Theodor Haugen", split.narrator)
    }

    @Test
    fun `strips trailing read by credit`() {
        val split = NarratorTags.split("Terry Pratchett - read by Nigel Planer")
        assertEquals("Terry Pratchett", split.author)
        assertEquals("Nigel Planer", split.narrator)
    }

    @Test
    fun `strips separated narrated by credit`() {
        val split = NarratorTags.split("Brandon Sanderson, narrated by Michael Kramer")
        assertEquals("Brandon Sanderson", split.author)
        assertEquals("Michael Kramer", split.narrator)
    }

    @Test
    fun `whole string as a credit yields blank author`() {
        val split = NarratorTags.split("Read by Stephen Fry")
        assertEquals("", split.author)
        assertEquals("Stephen Fry", split.narrator)
    }

    @Test
    fun `plain author is untouched`() {
        val split = NarratorTags.split("Terry Pratchett")
        assertEquals("Terry Pratchett", split.author)
        assertNull(split.narrator)
    }

    @Test
    fun `blank input is safe`() {
        assertEquals(NarratorTags.Split("", null), NarratorTags.split(null))
        assertEquals(NarratorTags.Split("", null), NarratorTags.split("   "))
    }

    @Test
    fun `hasNarratorCredit detects only real credits`() {
        assert(NarratorTags.hasNarratorCredit("Knut Hamsun (innlest av Per Theodor Haugen)"))
        assert(!NarratorTags.hasNarratorCredit("Terry Pratchett"))
    }

    @Test
    fun `strips locale variants of the narrator credit`() {
        assertEquals("Knut Hamsun", NarratorTags.split("Knut Hamsun (lest av Per Theodor Haugen)").author)
        assertEquals("Stephen King", NarratorTags.split("Stephen King, gelesen von David Nathan").author)
        assertEquals("Astrid Lindgren", NarratorTags.split("Astrid Lindgren (inläst av Kalle)").author)
    }

    @Test
    fun `does not eat a real author named Read`() {
        val split = NarratorTags.split("John Read: Collected Stories")
        assertEquals("John Read: Collected Stories", split.author)
        assertNull(split.narrator)
    }
}