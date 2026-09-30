package com.bookrio.reader.engine

/**
 * Resultatet av én navigasjonsbeslutning i leseren. Ren, Compose-fri og
 * JVM-testbar — UI-en eksekverer handlingen via de eksisterende
 * ViewModel-veiene (onPageTurned / jumpToChapterPage).
 */
sealed interface PageNavAction {
    /** Bli i samme kapittel og gå til side [page] (chapter-lokal indeks). */
    data class TurnTo(val page: Int) : PageNavAction

    /**
     * Kryss kapittelgrensen via den eksisterende chapter-jump-veien.
     * [chapterPct] er kapittel-lokal prosent (0 = første side, 1 = siste side).
     */
    data class JumpToChapter(
        val chapterIndex: Int,
        val page: Int,
        val chapterPct: Float,
    ) : PageNavAction

    /** Ingen gyldig navigasjon (bokens første/siste side, eller ukjent sidetall). */
    data object None : PageNavAction
}

/**
 * Enkel grenselogikk for leseren: currentIndex + boundary checks.
 *
 * Invarianter:
 *  - En sidevending innenfor kapittelet forlater ALDRI kapittelet.
 *  - Et kapittelkryss skjer kun når indeksen står på kanten og nabokapittelet finnes,
 *    og går alltid via [PageNavAction.JumpToChapter] — aldri et ekstra tap.
 *  - Er sidetallet ukjent (<= 0) gjetter vi ingen grense: [PageNavAction.None].
 *  - Bakover-kryss krever forrige kapittels sidetall; er det ukjent returneres
 *    [PageNavAction.None] slik at UI-en klargjør kapittelet og prøver på nytt.
 *    Aldri et hopp til side 0 av feil.
 */
object PageNavigator {

    fun turnForward(
        currentIndex: Int,
        pageCount: Int,
        chapterIndex: Int,
        chapterCount: Int,
    ): PageNavAction {
        if (pageCount <= 0) return PageNavAction.None
        val page = PageIndexMath.clampPage(currentIndex, pageCount)
        if (page + 1 < pageCount) return PageNavAction.TurnTo(page + 1)
        return if (chapterIndex + 1 < chapterCount) {
            PageNavAction.JumpToChapter(chapterIndex + 1, page = 0, chapterPct = 0f)
        } else {
            PageNavAction.None
        }
    }

    fun turnBackward(
        currentIndex: Int,
        pageCount: Int,
        chapterIndex: Int,
        chapterCount: Int,
        previousChapterPageCount: Int?,
    ): PageNavAction {
        if (pageCount <= 0) return PageNavAction.None
        val page = PageIndexMath.clampPage(currentIndex, pageCount)
        if (page > 0) return PageNavAction.TurnTo(page - 1)
        if (chapterIndex <= 0) return PageNavAction.None
        val prevPages = previousChapterPageCount?.takeIf { it > 0 } ?: return PageNavAction.None
        return PageNavAction.JumpToChapter(
            chapterIndex = chapterIndex - 1,
            page = prevPages - 1, // forrige kapitels SISTE side — aldri side 0
            chapterPct = 1f,
        )
    }

    /**
     * Revalidering for et utsatt bakover-kryss. Klargjøringen av forrige kapittel
     * kan ta tid, og brukeren kan ha navigert i mellomtiden. Commit bare når vi
     * fortsatt står nøyaktig på samme kapittel/side som da tappet skjedde.
     */
    fun shouldCommitBackwardCross(
        tapChapterIndex: Int,
        tapPage: Int,
        currentChapterIndex: Int,
        currentPage: Int,
    ): Boolean = currentChapterIndex == tapChapterIndex && currentPage == tapPage
}

/**
 * Ren indeks-/prosent-matematikk delt av leser-UI og ReaderViewModel.
 * Formlene er identiske med gjenopprettings- og klemmelogikken som allerede
 * lagres i DB, slik at gamle lagrede sideindekser forblir gyldige.
 */
object PageIndexMath {

    /**
     * Trygg clamp av en (lagret) sideindeks mot et kjent sidetall.
     * Ukjent/ugyldig sidetall (<= 0) gir 0 uten å kaste.
     * En indeks utenfor det nye sidetallet klemmes til siste side — aldri til start.
     */
    fun clampPage(page: Int, pageCount: Int): Int =
        if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

    /** Prosent → sideindeks. Identisk med ViewModel-ens gjenopprettingsformel. */
    fun pageForPercent(pct: Float, pageCount: Int): Int =
        if (pageCount <= 0) {
            0
        } else {
            (pct * pageCount.toFloat()).toInt().coerceIn(0, pageCount - 1)
        }

    /** Sideindeks → prosent (side / antall sider). Identisk med ViewModel-ens formel. */
    fun percentForPage(page: Int, pageCount: Int): Float =
        if (pageCount > 0) page.toFloat() / pageCount.toFloat() else 0f
}