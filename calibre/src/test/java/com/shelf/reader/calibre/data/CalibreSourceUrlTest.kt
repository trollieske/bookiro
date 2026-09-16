package com.shelf.reader.calibre.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibreSourceUrlTest {

    @Test
    fun `adds http scheme when missing`() {
        assertEquals("http://192.168.1.10:8080", CalibreSourceRepository.normalizeBaseUrl("192.168.1.10:8080"))
    }

    @Test
    fun `keeps https and removes trailing slash`() {
        assertEquals("https://cloud.example.com", CalibreSourceRepository.normalizeBaseUrl("https://cloud.example.com/"))
    }

    @Test
    fun `strips a pasted opds path`() {
        assertEquals("http://host:8080", CalibreSourceRepository.normalizeBaseUrl("http://host:8080/opds"))
        assertEquals("http://host:8080", CalibreSourceRepository.normalizeBaseUrl("http://host:8080/opds/"))
    }

    @Test
    fun `builds a stable source reference`() {
        assertEquals("CALIBRE:7", CalibreSourceRepository.refFor(7))
    }
}