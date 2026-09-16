package com.shelf.reader.webdav.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebdavMultiStatusParserTest {

    private val multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/remote.php/dav/files/alice/Books/</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>Books</d:displayname>
                <d:resourcetype><d:collection/></d:resourcetype>
              </d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/Books/Some%20Book.epub</d:href>
            <d:propstat>
              <d:prop>
                <d:displayname>Some Book.epub</d:displayname>
                <d:getcontentlength>12345</d:getcontentlength>
                <d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified>
                <d:getcontenttype>application/epub+zip</d:getcontenttype>
                <d:getetag>"abc123"</d:getetag>
                <d:resourcetype/>
              </d:prop>
            </d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/alice/Books/Nordic%20%C3%A6%C3%B8%C3%A5.m4b</d:href>
            <d:propstat>
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength>999</d:getcontentlength>
              </d:prop>
            </d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun `parses collections and files with namespaces`() {
        val entries = WebdavMultiStatusParser.parse(
            multistatus,
            "http://host/remote.php/dav/files/alice",
            "/remote.php/dav/files/alice/"
        )
        assertEquals(3, entries.size)
        val folder = entries.first { it.type == WebdavEntryType.FOLDER }
        val file = entries.first { it.path.endsWith("Some Book.epub") }
        assertEquals("Books", folder.name)
        assertEquals("/remote.php/dav/files/alice/Books/Some Book.epub", file.path)
        assertEquals(12345L, file.sizeBytes)
        assertEquals("application/epub+zip", file.contentType)
        assertEquals("abc123", file.etag)
    }

    @Test
    fun `decodes utf8 and space percent encodings`() {
        val entries = WebdavMultiStatusParser.parse(
            multistatus,
            "http://host/remote.php/dav/files/alice",
            "/remote.php/dav/files/alice/"
        )
        val nordic = entries.first { it.path.contains("Nordic") }
        assertEquals("/remote.php/dav/files/alice/Books/Nordic æøå.m4b", nordic.path)
    }

    @Test
    fun `normalizePath strips the base and trailing slash`() {
        assertEquals(
            "/remote.php/dav/files/alice/Books",
            WebdavMultiStatusParser.normalizePath(
                "http://host/remote.php/dav/files/alice",
                "/remote.php/dav/files/alice/Books/"
            )
        )
    }

    @Test
    fun `percentDecode does not convert plus to space`() {
        assertEquals("a+b c", WebdavMultiStatusParser.percentDecode("a+b%20c"))
    }

    @Test
    fun `rejects non multistatus documents`() {
        assertTrue(WebdavMultiStatusParser.parse("<html></html>", "http://h", "/").isEmpty())
    }

    @Test
    fun `handles uppercase and lp1 namespace prefixes`() {
        val lp1 = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/file.pdf</D:href>
                <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>5</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = WebdavMultiStatusParser.parse(lp1, "http://host", "/dav")
        assertEquals(1, entries.size)
        assertEquals(WebdavEntryType.FILE, entries.single().type)
    }
}