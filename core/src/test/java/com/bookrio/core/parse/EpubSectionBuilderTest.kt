package com.bookrio.core.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tester EpubSectionBuilder med en liten fixture:
 * cover.xhtml, titlepage.xhtml, copyright.xhtml, nav.xhtml ("Chapter One"/"Chapter Two"),
 * chapter1.xhtml, chapter2.xhtml.
 *
 * Forventninger:
 *  - Cover/frontmatter er IKKE eksponert som falske "Kapittel 1/2"
 *  - TOC gir nøyaktig "Chapter One" og "Chapter Two" (inToc)
 *  - Leser kan rendre alle frontmatter-sidene (innholdet er med i flyten)
 */
class EpubSectionBuilderTest {

    private fun navPoint(title: String, href: String) = NavPoint(title, href, 0)

    private val spineHrefs = listOf(
        "OEBPS/cover.xhtml",
        "OEBPS/titlepage.xhtml",
        "OEBPS/copyright.xhtml",
        "OEBPS/nav.xhtml",
        "OEBPS/chapter1.xhtml",
        "OEBPS/chapter2.xhtml",
    )

    private val navPoints = listOf(
        navPoint("Chapter One", "OEBPS/chapter1.xhtml"),
        navPoint("Chapter Two", "OEBPS/chapter2.xhtml"),
    )

    private fun htmlFor(href: String): String? = when {
        href.endsWith("cover.xhtml") -> "<img src=\"cover.jpg\"/>"
        href.endsWith("titlepage.xhtml") -> "<p>THE BOOK</p>"
        href.endsWith("copyright.xhtml") -> "<p>Copyright 2026</p>"
        href.endsWith("nav.xhtml") -> "<nav><ol><li><a href=\"chapter1.xhtml\">Chapter One</a></li></ol></nav>"
        href.endsWith("chapter1.xhtml") -> "<h1>Chapter One</h1><p>It was a dark and stormy night.</p>"
        href.endsWith("chapter2.xhtml") -> "<h1>Chapter Two</h1><p>The morning after.</p>"
        else -> null
    }

    @Test
    fun `frontmatter er ikke falske kapitler - to TOC-oppføringer`() {
        val sections = EpubSectionBuilder.build(spineHrefs, navPoints) { htmlFor(it) }

        assertEquals("Frontmatter-seksjonen skal være ÉN sammensatt seksjon", 3, sections.size)
        val front = sections.first()
        assertEquals(SectionKind.FRONT_MATTER, front.kind)
        assertFalse("Frontmatter skal ikke være TOC-oppføring", front.inToc)
        assertFalse("Frontmatter skal ikke kalles Kapittel 1", front.title.startsWith("Kapittel"))
        assertTrue("Omslaget skal være en side i flyten", front.htmlContent.contains("cover.jpg"))
        assertTrue("Tittelside skal være med", front.htmlContent.contains("THE BOOK"))
        assertTrue("Opphavsrett skal være med", front.htmlContent.contains("Copyright 2026"))
        assertTrue("Nav-dokumentet skal være med i flyten", front.htmlContent.contains("nav"))
    }

    @Test
    fun `TOC-nav gir nøyaktig Chapter One og Chapter Two som inToc-seksjoner`() {
        val sections = EpubSectionBuilder.build(spineHrefs, navPoints) { htmlFor(it) }
        val toc = sections.filter { it.inToc }
        assertEquals(2, toc.size)
        assertEquals("Chapter One", toc[0].title)
        assertEquals("Chapter Two", toc[1].title)
        assertTrue(toc[0].htmlContent.contains("dark and stormy"))
        assertTrue(toc[1].htmlContent.contains("Chapter Two"))
    }

    @Test
    fun `ingen falske Kapittel-N-titler i noen seksjon`() {
        val sections = EpubSectionBuilder.build(spineHrefs, navPoints) { htmlFor(it) }
        sections.forEach { s ->
            if (!s.inToc) {
                assertFalse("Frontmatter skal ikke hete Kapittel-N: ${s.title}", s.title.startsWith("Kapittel"))
            }
        }
    }

    @Test
    fun `byte-regnskap er sammenhengende`() {
        val sections = EpubSectionBuilder.build(spineHrefs, navPoints) { htmlFor(it) }
        var cumulative = 0
        sections.forEach { s ->
            assertEquals(cumulative, s.startByte)
            cumulative += s.byteLength
        }
    }

    @Test
    fun `uten nav - front grupperes og innhold blir kontinuerlig seksjon`() {
        val contentSpine = listOf(
            "OEBPS/cover.xhtml",
            "OEBPS/forwardmatter.xhtml",
            "OEBPS/story.xhtml",
        )
        val sections = EpubSectionBuilder.build(contentSpine, emptyList()) { href ->
            when {
                href.endsWith("cover.xhtml") -> "<img src=\"c.jpg\"/>"
                href.endsWith("forwardmatter.xhtml") -> "<p>Forord-teksst.</p>"
                else -> "<h1>Den Store Historien</h1><p>Brødtekst.</p>"
            }
        }
        assertTrue("Front-seksjon (omslag) finnes", sections.any { it.kind == SectionKind.COVER && !it.inToc })
        // Kontinuerlig flyt: innhold etter front samles i ÉN seksjon med overskrift-tittel.
        val body = sections.filter { it.inToc }
        assertEquals(1, body.size)
        assertEquals("Den Store Historien", body[0].title)
        assertTrue(body[0].htmlContent.contains("Forord-teksst."))
        assertTrue(body[0].htmlContent.contains("Brødtekst."))
    }
}
