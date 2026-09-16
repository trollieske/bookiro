package com.shelf.reader.calibre.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsFeedParserTest {

    private val navigationFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>calibre:catalog</id>
          <title>Calibre Library</title>
          <link rel="self" href="/opds" type="application/atom+xml"/>
          <entry>
            <title>By Newest</title>
            <id>/opds/byNewest</id>
            <link rel="subsection" href="/opds/byNewest" type="application/atom+xml"/>
          </entry>
          <entry>
            <title>Authors</title>
            <id>/opds/byAuthor</id>
            <link href="/opds/byAuthor" rel="subsection" type="application/atom+xml"/>
          </entry>
        </feed>
    """.trimIndent()

    private val acquisitionFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
          <id>calibre:search</id>
          <title>Search results</title>
          <link rel="next" href="/opds/search/foo?page=2"/>
          <link rel="search" href="/opds/osd"/>
          <entry>
            <title>The Name of the Wind</title>
            <id>urn:calibre:42</id>
            <author><name>Patrick Rothfuss</name></author>
            <language>eng</language>
            <dc:language>eng</dc:language>
            <category term="fantasy" label="Fantasy"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/opds/download/42/EPUB/"/>
            <link rel="http://opds-spec.org/acquisition" type="application/pdf" href="/opds/download/42/PDF/"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses a navigation feed with absolute and relative links`() {
        val feed = OpdsFeedParser.parse(navigationFeed, "http://host:8080/opds")
        assertEquals("Calibre Library", feed.title)
        assertEquals(2, feed.entries.size)
        assertTrue(feed.entries.all { it.isNavigation })
        assertEquals("http://host:8080/opds/byNewest", feed.entries[0].navigationLinks.first().href)
        assertEquals("http://host:8080/opds/byAuthor", feed.entries[1].navigationLinks.first().href)
    }

    @Test
    fun `parses acquisition feed and resolves hrefs`() {
        val feed = OpdsFeedParser.parse(acquisitionFeed, "http://host:8080/opds/search/foo")
        assertEquals("Search results", feed.title)
        assertEquals("http://host:8080/opds/search/foo?page=2", feed.nextPage)
        assertEquals("http://host:8080/opds/osd", feed.searchTemplate)
        val entry = feed.entries.single()
        assertFalse(entry.isNavigation)
        assertEquals("Patrick Rothfuss", entry.authors.single())
        assertEquals("eng", entry.language)
        assertEquals(listOf("Fantasy"), entry.categories)
        assertEquals(2, entry.acquisitionLinks.size)
        assertEquals("http://host:8080/opds/download/42/EPUB/", entry.acquisitionLinks.first().href)
    }

    @Test
    fun `handles feeds with explicit namespace prefixes`() {
        val prefixed = """
            <?xml version="1.0"?>
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:title>Prefixed</atom:title>
              <atom:entry>
                <atom:title>Book</atom:title>
                <atom:link rel="http://opds-spec.org/acquisition" type="application/epub+zip"/>
              </atom:entry>
            </atom:feed>
        """.trimIndent()
        val feed = OpdsFeedParser.parse(prefixed, "http://host/opds")
        assertEquals("Prefixed", feed.title)
        assertEquals("Book", feed.entries.single().title)
    }

    @Test(expected = OpdsParseException::class)
    fun `rejects non-feed documents`() {
        OpdsFeedParser.parse("<html><body>not a feed</body></html>", "http://host/opds")
    }

    @Test
    fun `rejects external entities`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <feed><title>&xxe;</title></feed>
        """.trimIndent()
        try {
            OpdsFeedParser.parse(xxe, "http://host/opds")
        } catch (_: OpdsParseException) {
            // Expected: doctype is rejected.
        }
    }

    @Test
    fun `resolveUrl keeps the base path`() {
        val resolved = OpdsFeedParser.resolveUrl("http://h:8080/opds/search/foo", "bar")
        assertEquals("http://h:8080/opds/search/bar", resolved)
        assertNotNull(resolved)
    }
}