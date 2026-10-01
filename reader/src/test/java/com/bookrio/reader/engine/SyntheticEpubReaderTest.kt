package com.bookrio.reader.engine

import androidx.room.Room
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.reader.pageturn.readerThemeColors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM-test som laster en liten SYNTERISK EPUB (frontmatter + TOC + 3 kapitler)
 * gjennom HELE lasteveien `BookLoaderEngine` → `parseEpub` → kapittel-HTML.
 *
 * Dette er den kontrakten den direkte WebView-leseren bygger på: kapitlenes
 * `htmlContent` er ferdig HTML, `inToc` skiller frontmatter fra ekte kapitler,
 * og sidetalls-/scrollmatematikken er ren. Det er IKKE en device-test —
 * faktisk WebView-paginering (kolonner, scrollWidth, tap-soner) må verifiseres
 * på enhet.
 *
 * Robolectric brukes fordi EPUB-parseren bygger på android.util.Xml/android.net.Uri
 * og fordi BookLoaderEngine snakker med Room-databasen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyntheticEpubReaderTest {

    @Test
    fun `syntetisk epub med frontmatter og tre kapitler lastes gjennom BookLoaderEngine`() = runTest {
        val ctx = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(ctx, ShelfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val epubFile = File(ctx.cacheDir, "synthetic-reader-fixture.epub")
            writeSyntheticEpub(epubFile)

            val bookId = db.bookDao().insert(
                BookEntity(
                    title = "Syntetisk Bok",
                    author = "Test Forfatter",
                    type = BookTypeEntity.EBOOK,
                    format = FormatEntity.EPUB,
                    filePath = epubFile.absolutePath,
                    fileSizeBytes = epubFile.length(),
                )
            )

            val state = BookLoaderEngine(ctx, db).loadBook(bookId)

            assertNull("ingen lastefeil for syntetisk EPUB", state.error)
            assertEquals("Syntetisk Bok", state.bookTitle)
            assertEquals("Test Forfatter", state.author)
            assertEquals(FormatEntity.EPUB, state.format)

            // Frontmatter (tittelside) + 3 TOC-drevne kapitler.
            assertEquals("frontmatter + 3 kapitler", 4, state.chapters.size)

            val front = state.chapters[0]
            assertFalse("frontmatter skal ikke være TOC-oppføring", front.inToc)
            assertEquals("Tittelside", front.title)
            assertTrue("frontmatter skal ha HTML", front.htmlContent.contains("Syntetisk Bok"))

            val expectedTitles = listOf("Kapittel en", "Kapittel to", "Kapittel tre")
            val expectedText = listOf(
                "Dette er kapittel en",
                "Dette er kapittel to",
                "Dette er kapittel tre",
            )
            state.chapters.drop(1).forEachIndexed { i, chapter ->
                assertTrue("kapittel ${i + 1} skal være TOC-oppføring", chapter.inToc)
                assertEquals(expectedTitles[i], chapter.title)
                assertTrue(
                    "kapittel ${i + 1} skal ha kapittel-HTML med egen tekst",
                    chapter.htmlContent.contains(expectedText[i]),
                )
                assertTrue("kapittel-HTML skal ikke være tom", chapter.htmlContent.isNotBlank())
                assertTrue(
                    "kapitlet skal være pakket i en section",
                    chapter.htmlContent.contains("<section>"),
                )
            }

            // Hvert kapittels HTML kan pagineres direkte av leserens WebView.
            val html = buildReaderHtml(
                content = state.chapters[1].htmlContent,
                fontSizeSp = 18,
                theme = readerThemeColors("sepia"),
                lang = "no",
                cssQuoteBorder = 3f,
                generation = 1L,
            )
            assertTrue("kapittelteksten skal være med i leser-HTML-en", html.contains("Dette er kapittel en"))
            assertTrue("leser-HTML-en skal ha kolonne-paginering", html.contains("column-width: 100vw"))

            // Paginerings-kontrakten over det lastede kapittelet: tre kolonner på
            // en 1080 px viewport → side 2 ligger på scrollX 2160 og leses tilbake
            // som side 2 (samme formel som JS-en bruker på scrollWidth/scrollLeft).
            val stride = 1080
            val pages = ReaderPagination.pageCount(scrollWidthPx = stride * 3, viewportWidthPx = stride)
            assertEquals(3, pages)
            assertEquals(stride * 2, ReaderPagination.scrollXForPage(page = 2, viewportWidthPx = stride, pageCount = pages))
            assertEquals(2, ReaderPagination.pageFromScrollX(stride * 2, stride, pages))
            assertEquals(0, ReaderPagination.pageFromScrollX(0, stride, pages))
        } finally {
            db.close()
        }
    }

    // ── Syntetisk EPUB-fixture ────────────────────────────────────────────────

    private fun writeSyntheticEpub(target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            // mimetype skal være første entry og ikke komprimert (EPUB OCF).
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)

            zip.writeEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )

            zip.writeEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="pub-id">urn:uuid:synthetic-0001</dc:identifier>
                    <dc:title>Syntetisk Bok</dc:title>
                    <dc:creator>Test Forfatter</dc:creator>
                    <dc:language>nb</dc:language>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="titlepage" href="titlepage.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="titlepage"/>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                    <itemref idref="ch3"/>
                  </spine>
                </package>
                """.trimIndent(),
            )

            zip.writeEntry(
                "OEBPS/nav.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head><title>Innhold</title></head>
                  <body>
                    <nav epub:type="toc">
                      <ol>
                        <li><a href="ch1.xhtml">Kapittel en</a></li>
                        <li><a href="ch2.xhtml">Kapittel to</a></li>
                        <li><a href="ch3.xhtml">Kapittel tre</a></li>
                      </ol>
                    </nav>
                  </body>
                </html>
                """.trimIndent(),
            )

            zip.writeEntry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <head><meta name="dtb:uid" content="urn:uuid:synthetic-0001"/></head>
                  <docTitle><text>Syntetisk Bok</text></docTitle>
                  <navMap>
                    <navPoint id="np1" playOrder="1"><navLabel><text>Kapittel en</text></navLabel><content src="ch1.xhtml"/></navPoint>
                    <navPoint id="np2" playOrder="2"><navLabel><text>Kapittel to</text></navLabel><content src="ch2.xhtml"/></navPoint>
                    <navPoint id="np3" playOrder="3"><navLabel><text>Kapittel tre</text></navLabel><content src="ch3.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """.trimIndent(),
            )

            zip.writeEntry(
                "OEBPS/titlepage.xhtml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Tittelside</title></head>
                  <body><h1>Syntetisk Bok</h1><p>Test Forfatter</p></body>
                </html>
                """.trimIndent(),
            )

            listOf(
                Triple("ch1.xhtml", "Kapittel en", "Dette er kapittel en. Syntetisk tekst for pagineringstest."),
                Triple("ch2.xhtml", "Kapittel to", "Dette er kapittel to. Syntetisk tekst for pagineringstest."),
                Triple("ch3.xhtml", "Kapittel tre", "Dette er kapittel tre. Syntetisk tekst for pagineringstest."),
            ).forEach { (name, heading, paragraph) ->
                zip.writeEntry(
                    "OEBPS/$name",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>$heading</title></head>
                      <body>
                        <h1>$heading</h1>
                        <p>$paragraph</p>
                        <p>Andre avsnitt i $name.</p>
                      </body>
                    </html>
                    """.trimIndent(),
                )
            }
        }
    }

    private fun ZipOutputStream.writeEntry(name: String, body: String) {
        putNextEntry(ZipEntry(name))
        write(body.toByteArray())
        closeEntry()
    }
}