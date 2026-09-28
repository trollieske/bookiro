package com.bookrio.podcast.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastUrlsTest {

    @Test
    fun `normalization prevents duplicate subscriptions`() {
        val a = PodcastUrls.normalize("HTTPS://Example.COM:443/Feed.xml")
        val b = PodcastUrls.normalize("https://example.com/Feed.xml")
        assertEquals(a, b)
        assertEquals("https://example.com/Feed.xml", a)
    }

    @Test
    fun `default ports and fragments are dropped`() {
        assertEquals("http://example.com/feed", PodcastUrls.normalize("http://example.com:80/feed#section"))
        assertEquals("https://example.com/feed?x=1", PodcastUrls.normalize("https://example.com:443/feed?x=1#y"))
        assertEquals("https://example.com/feed?x=1", PodcastUrls.normalize("https://example.com/feed?x=1"))
    }

    @Test
    fun `empty path becomes a single slash`() {
        assertEquals("https://example.com/", PodcastUrls.normalize("https://example.com"))
    }

    @Test
    fun `non http schemes and blanks are rejected`() {
        assertNull(PodcastUrls.normalize("ftp://example.com/feed"))
        assertNull(PodcastUrls.normalize(""))
        assertNull(PodcastUrls.normalize(null))
        assertNull(PodcastUrls.normalize("not a url"))
    }
}