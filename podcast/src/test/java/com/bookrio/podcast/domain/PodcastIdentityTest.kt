package com.bookrio.podcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PodcastIdentityTest {

    private val feed = "https://example.com/feed.xml"

    @Test
    fun `guid identity is stable and feed scoped`() {
        val a = PodcastIdentity.stableIdentity(feed, "guid-1", "https://cdn/1.mp3")
        val b = PodcastIdentity.stableIdentity(feed, "guid-1", "https://cdn/1.mp3")
        assertEquals(a, b)
        // GUID is the identity; the enclosure URL must not change it.
        assertEquals(a, PodcastIdentity.stableIdentity(feed, "guid-1", "https://cdn/2.mp3"))
        assertNotEquals(a, PodcastIdentity.stableIdentity("https://other.com/feed", "guid-1", "https://cdn/1.mp3"))
        assertNotEquals(a, PodcastIdentity.stableIdentity(feed, "guid-2", "https://cdn/1.mp3"))
    }

    @Test
    fun `missing guid falls back to feed url plus enclosure url`() {
        val identity = PodcastIdentity.stableIdentity(feed, null, "https://cdn/1.mp3")
        assertEquals(PodcastIdentity.fallbackIdentity(feed, "https://cdn/1.mp3"), identity)
        assertNotEquals(identity, PodcastIdentity.stableIdentity(feed, null, "https://cdn/2.mp3"))
    }

    @Test
    fun `blank guid falls back too`() {
        val identity = PodcastIdentity.stableIdentity(feed, "   ", "https://cdn/1.mp3")
        assertEquals(PodcastIdentity.fallbackIdentity(feed, "https://cdn/1.mp3"), identity)
    }
}