package com.bookrio.podcast.data.remote

import com.bookrio.podcast.domain.PodcastCountries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItunesSearchClientTest {

    @Test
    fun `query below two non-space characters makes no request`() = runTest {
        var called = false
        val results = ItunesSearchClient.search(" a ", "NO") { called = true; "{}" }
        assertTrue(results.isEmpty())
        assertFalse(called)
        val empty = ItunesSearchClient.search("", "NO") { called = true; "{}" }
        assertTrue(empty.isEmpty())
        assertFalse(called)
    }

    @Test
    fun `search url includes term media entity country and limit`() {
        val url = ItunesSearchClient.buildSearchUrl("true crime", "NO", 25)
        assertTrue(url.startsWith("https://itunes.apple.com/search?"))
        assertTrue(url.contains("term=true+crime") || url.contains("term=true%20crime"))
        assertTrue(url.contains("media=podcast"))
        assertTrue(url.contains("entity=podcast"))
        assertTrue(url.contains("country=NO"))
        assertTrue(url.contains("limit=25"))
    }

    @Test
    fun `country selection maps correctly`() {
        assertEquals("NO", PodcastCountries.inferFromLanguage("nb"))
        assertEquals("NO", PodcastCountries.inferFromLanguage("no-NO"))
        assertEquals("SE", PodcastCountries.inferFromLanguage("sv"))
        assertEquals("DK", PodcastCountries.inferFromLanguage("da"))
        assertEquals("DE", PodcastCountries.inferFromLanguage("de"))
        assertEquals("FR", PodcastCountries.inferFromLanguage("fr"))
        assertEquals("ES", PodcastCountries.inferFromLanguage("es"))
        assertEquals("US", PodcastCountries.inferFromLanguage("en"))
        assertEquals("US", PodcastCountries.inferFromLanguage("ja"))
        assertTrue(PodcastCountries.isSupported("NO"))
        assertFalse(PodcastCountries.isSupported("XX"))
    }

    @Test
    fun `old in-flight search is cancelled when query changes`() = runTest {
        ItunesSearchCache.clear()
        val job = launch {
            runCatching {
                ItunesSearchClient.search("crime", "NO") {
                    delay(10_000)
                    "{}"
                }
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        // Cancelled request must not have produced a cached response.
        assertEquals(null, ItunesSearchCache.get("crime", "NO"))
    }

    @Test
    fun `search results parse useful fields only`() {
        val json = """
            {"resultCount":1,"results":[
              {"collectionId":123,"trackId":456,"collectionName":"My Show","artistName":"Host",
               "artworkUrl100":"https://img/100.jpg","feedUrl":"https://feed.example/rss",
               "primaryGenreName":"Technology","genres":["Technology","Tech News"],
               "country":"USA","collectionExplicitness":"notExplicit"}
            ]}
        """.trimIndent()
        val results = ItunesSearchClient.parseResults(json)
        assertEquals(1, results.size)
        val r = results.first()
        assertEquals("My Show", r.title)
        assertEquals("Host", r.author)
        assertEquals("https://feed.example/rss", r.feedUrl)
        assertEquals("Technology", r.genre)
        assertEquals(123L, r.collectionId)
        assertFalse(r.explicit)
    }

    @Test
    fun `explicit results are flagged when the source says so`() {
        val json = """{"results":[{"collectionName":"X","collectionExplicitness":"explicit"}]}"""
        assertTrue(ItunesSearchClient.parseResults(json).first().explicit)
    }
}