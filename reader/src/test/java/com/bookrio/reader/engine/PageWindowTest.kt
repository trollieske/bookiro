package com.bookrio.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tester PageWindow/PageFlowState — den rene mappingen curl-indeks ↔ ReaderPageRef.
 */
class PageWindowTest {

    private fun ref(section: Int, page: Int) = ReaderPageRef(section, page)

    // 1) Normal seksjon: indekser 0..P-1 mapper til korrekte lokale sider
    @Test
    fun `normal seksjon mapper indekser til lokale sider`() {
        val w = PageFlowState.initial(sectionIndex = 4, sectionPageCount = 5)
        assertEquals(5, w.count)
        assertEquals(ref(4, 0), PageFlowState.resolve(w, 0))
        assertEquals(ref(4, 2), PageFlowState.resolve(w, 2))
        assertEquals(ref(4, 4), PageFlowState.resolve(w, 4))
        assertNull(PageFlowState.resolve(w, 5))
        for (p in 0..4) assertEquals(p, PageFlowState.realLocal(w, p))
    }

    // 2) Klar fremover-grense: siste side etterfulgt av neste seksjons side 1
    @Test
    fun `klar grense - siste side etterfulgt av neste seksjons side 0`() {
        val w = PageFlowState.initial(1, 3)
        val stabilized = PageFlowState.stabilize(
            window = w,
            current = 2, // står på siste reelle side
            nextRef = ref(2, 0),
            prevRef = null,
        )
        assertEquals(4, stabilized.count)
        assertEquals(ref(2, 0), PageFlowState.resolve(stabilized, 3))
        assertNull(PageFlowState.resolve(stabilized, 4))
        assertEquals(2, PageFlowState.realLocal(stabilized, 2))
        assertNull(PageFlowState.realLocal(stabilized, 3))
    }

    // 3) Ikke-klar grense: ingen ekstra side, ingen fantom
    @Test
    fun `ikke-klar grense gir ingen ekstra side`() {
        val w = PageFlowState.initial(1, 3)
        val stabilized = PageFlowState.stabilize(
            window = w,
            current = 2,
            nextRef = null, // neste side er ikke cachet
            prevRef = null,
        )
        assertEquals(3, stabilized.count)
        assertNull(stabilized.trailing)
        assertNull(PageFlowState.resolve(stabilized, 3))
    }

    // 4) Klar bakover-grense: forrige seksjons siste side foran indeks 0
    @Test
    fun `klar bakover-grense mapper indeks 0 til forrige siste side`() {
        val w = PageFlowState.initial(5, 4)
        val stabilized = PageFlowState.stabilize(
            window = w,
            current = 0, // står på første reelle side
            nextRef = null,
            prevRef = ref(4, 9),
        )
        assertEquals(5, stabilized.count)
        assertEquals(ref(4, 9), PageFlowState.resolve(stabilized, 0))
        // side 1 i seksjon 5 ligger nå på indeks 1
        assertEquals(ref(5, 0), PageFlowState.resolve(stabilized, 1))
        assertEquals(0, PageFlowState.realLocal(stabilized, 1))
    }

    // 5) Commit/landing: ref tilhører ny seksjon nøyaktig én gang
    @Test
    fun `landing på grense gir ref i ny seksjon - ett hopp`() {
        // Fremover: landet på trailing-indeksen
        val fwd = PageFlowState.initial(1, 3)
        val fwdStab = PageFlowState.stabilize(fwd, 2, ref(2, 0), null)
        val landed = PageFlowState.resolve(fwdStab, 3)
        assertEquals(ref(2, 0), landed)
        assertTrue(landed!!.sectionIndex != fwd.sectionIndex)

        // Bakover: landet på leading-indeksen
        val back = PageFlowState.initial(5, 4)
        val backStab = PageFlowState.stabilize(back, 0, null, ref(4, 9))
        val landedBack = PageFlowState.resolve(backStab, 0)
        assertEquals(ref(4, 9), landedBack)
        assertTrue(landedBack!!.sectionIndex != back.sectionIndex)
    }

    // 6) Frontmatter gir ikke falske BODY-kapitler (kind/inToc er parserens ansvar,
    //    men vinduet må ikke skjule eller duplisere fronmatter-sider)
    @Test
    fun `vindu med single-page seksjon har begge kanter når klare`() {
        val w = PageFlowState.initial(7, 1)
        val stabilized = PageFlowState.stabilize(
            window = w,
            current = 0,
            nextRef = ref(8, 0),
            prevRef = ref(6, 5),
        )
        assertEquals(3, stabilized.count)
        assertEquals(ref(6, 5), PageFlowState.resolve(stabilized, 0))
        assertEquals(ref(7, 0), PageFlowState.resolve(stabilized, 1))
        assertEquals(ref(8, 0), PageFlowState.resolve(stabilized, 2))
        assertNull(PageFlowState.resolve(stabilized, 3))
    }

    // 7) Gjenopprettet posisjon mapper til korrekt curl-indeks
    @Test
    fun `gjenopprettet posisjon mapper til korrekt curl-indeks`() {
        val w = PageFlowState.initial(3, 40)
        assertEquals(17, PageFlowState.curlIndexOfLocal(w, 17))
        // med leading (kun relevant ved side 1): side 1 → indeks 1
        val withLeading = w.copy(leading = ref(2, 39))
        assertEquals(1, PageFlowState.curlIndexOfLocal(withLeading, 0))
        assertEquals(ref(3, 0), PageFlowState.resolve(withLeading, 1))
    }

    @Test
    fun `stabilize fjerner kanter når brukeren forlater grensen`() {
        val base = PageFlowState.initial(1, 3)
        val withEdges = PageFlowState.stabilize(base, 2, ref(2, 0), null)
        assertTrue(withEdges.trailing != null)
        // brukeren snur og står fortsatt på siste side → behold
        val stillLast = PageFlowState.stabilize(withEdges, 2, ref(2, 0), null)
        assertTrue(stillLast.trailing != null)
        // brukeren blar tilbake til side 2 (lokal 1) → trailing fjernes
        val movedBack = PageFlowState.stabilize(withEdges, 1, ref(2, 0), null)
        assertNull(movedBack.trailing)
        assertEquals(3, movedBack.count)
        assertEquals(ref(1, 1), PageFlowState.resolve(movedBack, 1))
    }

    // 8) windowFor med null-ready-referanser: count == antall reelle sider, ingen fantom
    @Test
    fun `windowFor med null-ready-refs gir kun reelle sider`() {
        val w = PageFlowState.windowFor(
            sectionIndex = 3,
            sectionPageCount = 5,
            previousReady = null,
            nextReady = null,
        )
        assertEquals(5, w.count)
        assertNull(w.leading)
        assertNull(w.trailing)
        assertNull(PageFlowState.resolve(w, 5))
        assertNull(PageFlowState.resolve(w, -1))
        for (p in 0..4) assertEquals(ref(3, p), PageFlowState.resolve(w, p))
    }

    // 9) Innledende kapittel (seksjon 0): aldri fantom-sider i første vindu
    @Test
    fun `innledende kapittel-vindu har null fantom-sider`() {
        val w = PageFlowState.windowFor(
            sectionIndex = 0,
            sectionPageCount = 4,
            previousReady = null,
            nextReady = null,
        )
        assertEquals(4, w.count)
        assertEquals(4, PageFlowState.initial(0, 4).count)
        // side 0 på indeks 0 (ingen leading som forskyver), ingen trailing
        assertEquals(ref(0, 0), PageFlowState.resolve(w, 0))
        assertNull(PageFlowState.resolve(w, 4))
    }

    // 10) windowFor legger KANT-sider KUN via eksplisitte, allerede-klare referanser —
    //     og kant-sidene løses til nøyaktig den referansen som ble sendt inn
    @Test
    fun `windowFor bruker kun eksplisitte ready-referanser som kanter`() {
        val w = PageFlowState.windowFor(
            sectionIndex = 2,
            sectionPageCount = 3,
            previousReady = ref(1, 9),
            nextReady = ref(3, 0),
        )
        assertEquals(5, w.count)
        assertEquals(ref(1, 9), PageFlowState.resolve(w, 0))
        assertEquals(ref(3, 0), PageFlowState.resolve(w, 4))
        assertNull(PageFlowState.resolve(w, 5))
        // ready-refs er always eksplisitte: aldri en generert (section±1, sidetall)-gjett
        assertNull(PageFlowState.windowFor(2, 3, null, ref(3, 0)).leading)
        assertNull(PageFlowState.windowFor(2, 3, ref(1, 9), null).trailing)
    }
}
