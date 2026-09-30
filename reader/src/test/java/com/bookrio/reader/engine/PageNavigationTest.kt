package com.bookrio.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tester den forenklede navigasjonen (currentIndex + boundary checks) og den
 * rene gjenopprettings-/klemme-matematikken som leser-UI og ReaderViewModel
 * deler. Erstatter PageWindowTest etter at curl-vinduet ble fjernet.
 */
class PageNavigationTest {

    // ── turnForward ────────────────────────────────────────────────────────────

    @Test
    fun `forward inneholder sidevending i samme kapittel`() {
        assertEquals(PageNavAction.TurnTo(3), PageNavigator.turnForward(2, 10, 0, 3))
        assertEquals(PageNavAction.TurnTo(1), PageNavigator.turnForward(0, 10, 1, 3))
    }

    @Test
    fun `forward på siste side krysser til neste kapittel side 0 uten ekstra tap`() {
        assertEquals(
            PageNavAction.JumpToChapter(chapterIndex = 1, page = 0, chapterPct = 0f),
            PageNavigator.turnForward(9, 10, 0, 3),
        )
        assertEquals(
            PageNavAction.JumpToChapter(chapterIndex = 2, page = 0, chapterPct = 0f),
            PageNavigator.turnForward(4, 5, 1, 3),
        )
    }

    @Test
    fun `forward på aller siste side gir None`() {
        assertEquals(PageNavAction.None, PageNavigator.turnForward(9, 10, 2, 3))
        assertEquals(PageNavAction.None, PageNavigator.turnForward(0, 1, 0, 1))
    }

    @Test
    fun `forward med ukjent sidetall gjetter ingen grense`() {
        assertEquals(PageNavAction.None, PageNavigator.turnForward(0, 0, 0, 3))
        assertEquals(PageNavAction.None, PageNavigator.turnForward(0, -4, 0, 3))
    }

    @Test
    fun `forward fra indeks utenfor sidetallet lander på grensen - aldri forbi`() {
        // Lagret indeks 500 mot 300 sider: klemmes til 299 → siste side → neste kapittel.
        assertEquals(
            PageNavAction.JumpToChapter(chapterIndex = 1, page = 0, chapterPct = 0f),
            PageNavigator.turnForward(500, 300, 0, 3),
        )
    }

    // ── turnBackward ───────────────────────────────────────────────────────────

    @Test
    fun `backward inneholder sidevending i samme kapittel`() {
        assertEquals(PageNavAction.TurnTo(2), PageNavigator.turnBackward(3, 10, 1, 3, 5))
        assertEquals(PageNavAction.TurnTo(0), PageNavigator.turnBackward(1, 10, 1, 3, 5))
    }

    @Test
    fun `backward på første side krysser til forrige kapitels SISTE side`() {
        assertTrue(
            PageNavigator.turnBackward(0, 10, 1, 3, 7) ==
                PageNavAction.JumpToChapter(chapterIndex = 0, page = 6, chapterPct = 1f),
        )
        assertEquals(
            PageNavAction.JumpToChapter(chapterIndex = 1, page = 0, chapterPct = 1f),
            PageNavigator.turnBackward(0, 3, 2, 3, 1),
        )
    }

    @Test
    fun `backward på første side uten kjent forrige-sidetall gir None - UI klargjør og prøver`() {
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 10, 1, 3, null))
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 10, 1, 3, 0))
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 10, 1, 3, -2))
    }

    @Test
    fun `backward på første side i første kapittel gir None`() {
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 10, 0, 3, 7))
    }

    @Test
    fun `backward med ukjent sidetall gjetter ingen grense`() {
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 0, 1, 3, 7))
    }

    @Test
    fun `backward med enkelt-side nabo lander på side 0 av forrige kapittel - gyldig side`() {
        assertEquals(
            PageNavAction.JumpToChapter(chapterIndex = 0, page = 0, chapterPct = 1f),
            PageNavigator.turnBackward(0, 10, 1, 3, 1),
        )
    }

    // ── Progress: gjenoppretting og trygg clamp ────────────────────────────────

    @Test
    fun `lagret indeks utenfor nytt sidetall klemmes til siste side - aldri start`() {
        assertEquals(299, PageIndexMath.clampPage(500, 300))
        assertEquals(299, PageIndexMath.clampPage(299, 300))
        assertEquals(1, PageIndexMath.clampPage(1, 300))
    }

    @Test
    fun `gyldig lagret indeks endres ikke ved gjenoppretting`() {
        assertEquals(0, PageIndexMath.clampPage(0, 300))
        assertEquals(42, PageIndexMath.clampPage(42, 300))
        assertEquals(17, PageIndexMath.clampPage(17, 40))
    }

    @Test
    fun `ukjent eller ugyldig sidetall gir 0 uten å kaste`() {
        assertEquals(0, PageIndexMath.clampPage(7, 0))
        assertEquals(0, PageIndexMath.clampPage(7, -5))
        assertEquals(0, PageIndexMath.clampPage(-3, 10))
    }

    @Test
    fun `clampPage er alltid innenfor gyldig intervall for alle lagrede indekser`() {
        for (count in 1..50) {
            for (page in -5..(count + 5)) {
                val clamped = PageIndexMath.clampPage(page, count)
                assertTrue("count=$count page=$page clamped=$clamped", clamped in 0 until count)
            }
        }
    }

    @Test
    fun `clampPage av en for høy lagret indeks gir ALLTID siste side - aldri start`() {
        for (count in 2..50) {
            assertEquals(count - 1, PageIndexMath.clampPage(count + 100, count))
        }
    }

    @Test
    fun `kapittel-slutt pct 1 gjenopprettes til siste side av nytt sidetall`() {
        // Brukes av bakover-kryss (chapterPct = 1f): siste side, ikke side 0.
        assertEquals(11, PageIndexMath.pageForPercent(1f, 12))
        assertEquals(0, PageIndexMath.pageForPercent(0f, 12))
        assertEquals(5, PageIndexMath.pageForPercent(0.5f, 10))
    }

    @Test
    fun `pageForPercent klemmer ugyldig pct trygt`() {
        assertEquals(0, PageIndexMath.pageForPercent(-0.5f, 10))
        assertEquals(9, PageIndexMath.pageForPercent(1.5f, 10))
        assertEquals(0, PageIndexMath.pageForPercent(0.5f, 0))
    }

    @Test
    fun `percentForPage er identisk med lagringsformelen`() {
        assertEquals(0f, PageIndexMath.percentForPage(0, 10), 0f)
        assertEquals(0.5f, PageIndexMath.percentForPage(5, 10), 1e-6f)
        assertEquals(0f, PageIndexMath.percentForPage(5, 0), 0f)
        // Endepunktet (siste side) rund-trip-er til samme side.
        assertEquals(11, PageIndexMath.pageForPercent(PageIndexMath.percentForPage(11, 12), 12))
    }

    // ── Revalidering av utsatt bakover-kryss ───────────────────────────────

    @Test
    fun `bakover-kryss committer bare når man fortsatt står på samme side`() {
        assertTrue(
            PageNavigator.shouldCommitBackwardCross(
                tapChapterIndex = 3, tapPage = 0, currentChapterIndex = 3, currentPage = 0,
            )
        )
        // Brukeren gikk videre / valgte et annet kapittel mens vi klargjorde.
        assertTrue(
            !PageNavigator.shouldCommitBackwardCross(
                tapChapterIndex = 3, tapPage = 0, currentChapterIndex = 5, currentPage = 2,
            )
        )
        assertTrue(
            !PageNavigator.shouldCommitBackwardCross(
                tapChapterIndex = 3, tapPage = 0, currentChapterIndex = 3, currentPage = 1,
            )
        )
    }

    @Test
    fun `turnBackward klemmer ugyldig indeks og gir None for første kapittel`() {
        // Intern clamp-sti: currentIndex utenfor sidetallet skal aldri kaste.
        assertEquals(PageNavAction.TurnTo(1), PageNavigator.turnBackward(500, 3, 1, 4, 5))
        assertEquals(PageNavAction.None, PageNavigator.turnBackward(0, 10, 0, 4, 5))
    }
}