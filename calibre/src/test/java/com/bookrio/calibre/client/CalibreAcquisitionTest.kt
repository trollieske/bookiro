package com.bookrio.calibre.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreAcquisitionTest {

    private fun entry(vararg links: OpdsLink, title: String = "A Book") = OpdsEntry(
        id = "urn:calibre:1",
        title = title,
        links = links.toList()
    )

    @Test
    fun `prefers epub over pdf`() {
        val result = CalibreAcquisition.best(
            entry(
                OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/1/PDF/", "application/pdf"),
                OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/1/EPUB/", "application/epub+zip")
            )
        )
        assertEquals(CalibreFormat.EPUB, result?.format)
    }

    @Test
    fun `prefers audiobook over pdf`() {
        val result = CalibreAcquisition.best(
            entry(
                OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/1/PDF/", "application/pdf"),
                OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/1/M4B/", "audio/x-m4b")
            )
        )
        assertEquals(CalibreFormat.M4B, result?.format)
    }

    @Test
    fun `ignores buy and borrow links`() {
        val result = CalibreAcquisition.best(
            entry(
                OpdsLink("http://opds-spec.org/acquisition/buy", "http://h/buy/1", "application/epub+zip")
            )
        )
        assertNull(result)
    }

    @Test
    fun `ignores formats shelf cannot import`() {
        val result = CalibreAcquisition.best(
            entry(OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/1/TXT/", "text/plain"))
        )
        assertNull(result)
    }

    @Test
    fun `derives a safe filename when the href has none`() {
        val result = CalibreAcquisition.best(
            entry(
                OpdsLink("http://opds-spec.org/acquisition", "http://h/opds/download/42/EPUB/", "application/epub+zip"),
                title = "The Name of the Wind"
            )
        )
        assertEquals("The Name of the Wind.epub", result?.fileName)
    }

    @Test
    fun `uses the href filename when present`() {
        val result = CalibreAcquisition.best(
            entry(
                OpdsLink(
                    "http://opds-spec.org/acquisition",
                    "http://h/get/epub/42/Some%20Book.epub",
                    "application/epub+zip"
                )
            )
        )
        assertTrue(result!!.fileName.endsWith(".epub"))
    }

    @Test
    fun `sanitizes path separators out of filenames`() {
        assertEquals("a_b_c", CalibreAcquisition.sanitize("a/b\\c"))
    }
}