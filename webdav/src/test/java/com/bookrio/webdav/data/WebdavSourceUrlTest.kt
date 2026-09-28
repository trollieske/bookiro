package com.bookrio.webdav.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WebdavSourceUrlTest {

    @Test
    fun `keeps an explicit scheme`() {
        assertEquals("https://cloud.example.com", WebdavSourceRepository.normalizeBaseUrl("https://cloud.example.com/"))
        assertEquals("http://localhost:8080", WebdavSourceRepository.normalizeBaseUrl("http://localhost:8080"))
    }

    @Test
    fun `defaults to https when no scheme is given`() {
        assertEquals("https://cloud.example.com", WebdavSourceRepository.normalizeBaseUrl("cloud.example.com"))
    }

    @Test
    fun `keeps the dav path and only trims a trailing slash`() {
        assertEquals(
            "https://host/remote.php/dav/files/alice",
            WebdavSourceRepository.normalizeBaseUrl("https://host/remote.php/dav/files/alice/")
        )
    }
}