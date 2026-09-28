package com.bookrio.podcast.data.remote

import com.bookrio.podcast.domain.ParsedPodcastFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class PodcastFeedParserTest {

    private fun parse(xml: String, feedUrl: String = "https://example.com/feed.xml"): ParsedPodcastFeed {
        val parser = org.kxml2.io.KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return PodcastFeedParser.parse(parser, feedUrl)
    }

    @Test
    fun `rss 2_0 feed with title guid and mp3 enclosure parses correctly`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Podcast</title>
                <link>https://example.com</link>
                <language>en-us</language>
                <item>
                  <title>Episode One</title>
                  <guid>episode-1</guid>
                  <pubDate>Mon, 02 Jan 2006 15:04:05 GMT</pubDate>
                  <description>First episode</description>
                  <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parse(xml)
        assertEquals("Test Podcast", feed.title)
        assertEquals(1, feed.episodes.size)
        val ep = feed.episodes.first()
        assertEquals("Episode One", ep.title)
        assertEquals("episode-1", ep.guid)
        assertEquals("https://example.com/ep1.mp3", ep.enclosureUrl)
        assertEquals("audio/mpeg", ep.enclosureMimeType)
        assertEquals(12345L, ep.enclosureLengthBytes)
        assertNotNull(ep.publishedAt)
        assertEquals("en-us", feed.language)
    }

    @Test
    fun `itunes author image duration explicit season and episode parse correctly`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Itunes Show</title>
                <itunes:author>Jane Host</itunes:author>
                <itunes:image href="https://example.com/cover.jpg"/>
                <itunes:category text="Technology"/>
                <itunes:explicit>yes</itunes:explicit>
                <item>
                  <title>Episode Two</title>
                  <guid>guid-2</guid>
                  <itunes:duration>1:02:03</itunes:duration>
                  <itunes:explicit>no</itunes:explicit>
                  <itunes:season>3</itunes:season>
                  <itunes:episode>7</itunes:episode>
                  <itunes:episodeType>full</itunes:episodeType>
                  <enclosure url="https://example.com/ep2.m4a" type="audio/mp4" length="999"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parse(xml)
        assertEquals("Jane Host", feed.author)
        assertEquals("https://example.com/cover.jpg", feed.artworkUrl)
        assertTrue(feed.categories.contains("Technology"))
        assertEquals(true, feed.explicit)
        val ep = feed.episodes.first()
        assertEquals(3723000L, ep.durationMs)
        assertEquals(3, ep.seasonNumber)
        assertEquals(7, ep.episodeNumber)
        assertEquals("full", ep.episodeType)
        assertEquals(false, ep.explicit)
        assertEquals("audio/mp4", ep.enclosureMimeType)
    }

    @Test
    fun `atom feed with audio enclosure parses correctly`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Podcast</title>
              <author><name>Atom Author</name></author>
              <entry>
                <id>tag:example.com,2024:1</id>
                <title>Atom Episode</title>
                <updated>2024-05-01T10:00:00Z</updated>
                <summary>An atom episode</summary>
                <link rel="enclosure" type="audio/mpeg" href="https://example.com/atom.mp3"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = parse(xml, "https://example.com/atom.xml")
        assertEquals("Atom Podcast", feed.title)
        assertEquals("Atom Author", feed.author)
        assertEquals(1, feed.episodes.size)
        val ep = feed.episodes.first()
        assertEquals("https://example.com/atom.mp3", ep.enclosureUrl)
        assertNotNull(ep.publishedAt)
    }

    @Test
    fun `item without playable enclosure is ignored`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0">
              <channel>
                <title>Mixed</title>
                <item>
                  <title>Video Episode</title>
                  <guid>v1</guid>
                  <enclosure url="https://example.com/video.mp4" type="video/mp4" length="1"/>
                </item>
                <item>
                  <title>Audio Episode</title>
                  <guid>a1</guid>
                  <enclosure url="https://example.com/audio.mp3" type="audio/mpeg" length="2"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parse(xml)
        assertEquals(1, feed.episodes.size)
        assertEquals("Audio Episode", feed.episodes.first().title)
    }

    @Test
    fun `malformed feed returns recoverable failure and does not crash`() {
        val malformed = "<rss><channel><title>Broken</title><item><title>x"
        val result = runCatching { parse(malformed) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PodcastFeedParseException)
    }

    @Test
    fun `feed with no playable enclosure is rejected`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0">
              <channel><title>Empty</title><item><title>No audio</title></item></channel>
            </rss>
        """.trimIndent()
        val result = runCatching { parse(xml) }
        assertTrue(result.isFailure)
        assertEquals(
            PodcastFeedParseException.NO_AUDIO,
            (result.exceptionOrNull() as PodcastFeedParseException).code
        )
    }

    @Test
    fun `http and https validation behaves correctly`() {
        assertFalse(PodcastUrls.isAcceptable("ftp://example.com/feed.xml"))
        assertTrue(PodcastUrls.isAcceptable("http://example.com/feed.xml"))
        assertTrue(PodcastUrls.isAcceptable("https://example.com/feed.xml"))
        assertTrue(PodcastFeedParser.isPlayableAudio("audio/ogg", "https://x/y"))
        assertFalse(PodcastFeedParser.isPlayableAudio("video/mp4", "https://x/y.mp4"))
        assertTrue(PodcastFeedParser.isPlayableAudio(null, "https://x/y.mp3"))
    }

    @Test
    fun `unplayed relative artwork resolves against feed url`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>Rel</title>
              <item>
                <title>E</title>
                <guid>g</guid>
                <itunes:image href="/img/ep.jpg"/>
                <enclosure url="https://example.com/e.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()
        val feed = parse(xml, "https://example.com/feed.xml")
        assertEquals("https://example.com/img/ep.jpg", feed.episodes.first().artworkUrl)
        assertNull(feed.episodes.first().guid?.takeIf { it.isBlank() })
    }
}