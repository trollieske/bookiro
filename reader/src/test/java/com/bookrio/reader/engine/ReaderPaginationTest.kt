package com.bookrio.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ren JVM-test av side-matematikken den direkte WebView-leseren bruker:
 * sidetall fra `scrollWidth`, gjeldende side fra `scrollX`, og scroll-mål per
 * side. Formlene speiler JS-en i [buildReaderHtml] (samme matematikk begge
 * steder), så en regresjon i kontrakten fanges her.
 */
class ReaderPaginationTest {

    @Test
    fun `eksakt kolonnebredde gir ikke en tom ekstra side`() {
        // 1000 px viewport: 1, 2 og 3 fulle kolonner.
        assertEquals(1, ReaderPagination.pageCount(scrollWidthPx = 1000, viewportWidthPx = 1000))
        assertEquals(2, ReaderPagination.pageCount(scrollWidthPx = 2000, viewportWidthPx = 1000))
        assertEquals(3, ReaderPagination.pageCount(scrollWidthPx = 3000, viewportWidthPx = 1000))
    }

    @Test
    fun `delvis ny kolonne teller som en side - 1 px slakk for subpiksel-avrunding`() {
        // Formelen er ceil((scrollWidth - 1) / stride): inntil 1 px overflow over
        // et eksakt multiplum regnes som samme side (WebView-ens scrollWidth kan
        // avrunde oppover). Faktisk innhold i en ny kolonne teller alltid.
        assertEquals(2, ReaderPagination.pageCount(scrollWidthPx = 1500, viewportWidthPx = 1000))
        assertEquals(3, ReaderPagination.pageCount(scrollWidthPx = 3001, viewportWidthPx = 1000))
        assertEquals(4, ReaderPagination.pageCount(scrollWidthPx = 3002, viewportWidthPx = 1000))
        assertEquals(4, ReaderPagination.pageCount(scrollWidthPx = 3999, viewportWidthPx = 1000))
        assertEquals(4, ReaderPagination.pageCount(scrollWidthPx = 4001, viewportWidthPx = 1000))
        assertEquals(5, ReaderPagination.pageCount(scrollWidthPx = 4002, viewportWidthPx = 1000))
    }

    @Test
    fun `ukjent eller degenerert viewport gir alltid minst en side`() {
        assertEquals(1, ReaderPagination.pageCount(scrollWidthPx = 0, viewportWidthPx = 1000))
        assertEquals(1, ReaderPagination.pageCount(scrollWidthPx = 5000, viewportWidthPx = 0))
        assertEquals(1, ReaderPagination.pageCount(scrollWidthPx = 5000, viewportWidthPx = -10))
    }

    @Test
    fun `side fra scrollX runder til nærmeste kolonne og klemmes`() {
        assertEquals(0, ReaderPagination.pageFromScrollX(scrollX = 0, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(1, ReaderPagination.pageFromScrollX(scrollX = 1000, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(1, ReaderPagination.pageFromScrollX(scrollX = 1499, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(2, ReaderPagination.pageFromScrollX(scrollX = 1500, viewportWidthPx = 1000, pageCount = 5))
        // Klem alltid innenfor kjent sidetall — aldri forbi siste side.
        assertEquals(4, ReaderPagination.pageFromScrollX(scrollX = 9999, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(0, ReaderPagination.pageFromScrollX(scrollX = -300, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(0, ReaderPagination.pageFromScrollX(scrollX = 100, viewportWidthPx = 1000, pageCount = 0))
    }

    @Test
    fun `scroll-mål for side er side ganger viewportbredde`() {
        assertEquals(0, ReaderPagination.scrollXForPage(page = 0, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(3000, ReaderPagination.scrollXForPage(page = 3, viewportWidthPx = 1000, pageCount = 5))
        // Klem: side utenfor kjent sidetall lander på siste side — aldri forbi.
        assertEquals(4000, ReaderPagination.scrollXForPage(page = 99, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(0, ReaderPagination.scrollXForPage(page = -4, viewportWidthPx = 1000, pageCount = 5))
        assertEquals(0, ReaderPagination.scrollXForPage(page = 2, viewportWidthPx = 0, pageCount = 5))
    }

    @Test
    fun `sidetall og scrollX er konsistente for hver side`() {
        val stride = 1080
        val pageCount = ReaderPagination.pageCount(scrollWidthPx = stride * 7, viewportWidthPx = stride)
        assertEquals(7, pageCount)
        for (page in 0 until pageCount) {
            val scrollX = ReaderPagination.scrollXForPage(page, stride, pageCount)
            assertEquals("round-trip for side $page", page, ReaderPagination.pageFromScrollX(scrollX, stride, pageCount))
        }
        assertTrue(ReaderPagination.pageCount(1, stride) >= 1)
    }
}