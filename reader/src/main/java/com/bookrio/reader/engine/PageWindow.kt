package com.bookrio.reader.engine

/**
 * Referanse til én konkret side i hele bokens sideflyt.
 * [sectionIndex] er seksjonen (kapitlet) og [localPageIndex] sidens lokale indeks
 * inni den seksjonen.
 */
data class ReaderPageRef(
    val sectionIndex: Int,
    val localPageIndex: Int,
)

/**
 * ÉN eier av mappingen fra PageCurl-indeks til [ReaderPageRef].
 *
 * Vinduet består av:
 *  - [sectionPageCount] reelle sider i den GITTE seksjonen (indeks 0 = første side,
 *    indeks P-1 = siste side)
 *  - valgfri [leading]-side (forrige seksjons SISTE side) foran indeks 0
 *  - valgfri [trailing]-side (neste seksjons FØRSTE side) etter indeks P-1
 *
 * Invarianter:
 *  - Hvert gyldig curl-indeks løses til nøyaktig én [ReaderPageRef] — ingen fantom-
 *    indekser (utenfor count er resolve null).
 *  - En kant-side legges til vinduet KUN når bitmapmen dens allerede er cachet
 *    (kalleren garanterer dette via ready-sjekk).
 *  - Den synlige [ReaderPageRef] endres aldri av en window-oppdatering alene —
 *    stabilize() flytter indeksen kun når den reelle posisjonen krever det, og
 *    snap skjer da som eksplisitt navigasjon (utenfor gest/animasjon).
 */
data class PageWindow(
    val sectionIndex: Int,
    val sectionPageCount: Int,
    val leading: ReaderPageRef? = null,
    val trailing: ReaderPageRef? = null,
) {
    val count: Int
        get() = sectionPageCount + (if (leading != null) 1 else 0) + (if (trailing != null) 1 else 0)

    private val leadingOffset: Int get() = if (leading != null) 1 else 0

    /** Indeksen til den avsluttende kant-siden (finnes kun når trailing er satt). */
    val trailingIndex: Int get() = leadingOffset + sectionPageCount

    /**
     * Løser et curl-indeks til en [ReaderPageRef]; returnerer null for
     * fantom-indekser (utenfor vinduet).
     */
    fun resolve(index: Int): ReaderPageRef? {
        var i = index
        if (leading != null) {
            if (i == 0) return leading
            i -= 1
        }
        if (i in 0 until sectionPageCount) return ReaderPageRef(sectionIndex, i)
        if (trailing != null && i == sectionPageCount) return trailing
        return null
    }

    /**
     * Lokal sideindeks i den gitte seksjonen for et curl-indeks; null når indeksen
     * peker på en kant-side eller er utenfor.
     */
    fun realLocal(index: Int): Int? {
        var i = index
        if (leading != null) {
            if (i == 0) return null
            i -= 1
        }
        return i.takeIf { it in 0 until sectionPageCount }
    }

    /** Curl-indeksen til en lokal sideindeks i den gitte seksjonen. */
    fun curlIndexOfLocal(local: Int): Int = local + leadingOffset

    /** Omvendt oppslag: curl-indeksen til en [ReaderPageRef], null hvis den ikke er i vinduet. */
    fun indexOfRef(ref: ReaderPageRef): Int? {
        if (leading != null && ref == leading) return 0
        if (ref.sectionIndex == sectionIndex &&
            ref.localPageIndex in 0 until sectionPageCount
        ) {
            return ref.localPageIndex + leadingOffset
        }
        if (trailing != null && ref == trailing) return leadingOffset + sectionPageCount
        return null
    }
}

/**
 * Ren (Compose-fri) tilstandsmaskin for sidevinduet — ÉN overgangshandler for
 * seksjonsgrenser, eier all mapping curl-indeks ↔ [ReaderPageRef].
 */
object PageFlowState {

    /** Startvindu for en seksjon: kun de reelle sidene, ingen fantomer. */
    fun initial(sectionIndex: Int, sectionPageCount: Int): PageWindow =
        PageWindow(sectionIndex = sectionIndex, sectionPageCount = sectionPageCount)

    /**
     * Vindu for en seksjon med allerede KLARE (cachete) kant-referanser.
     *
     * [previousReady] er forrige seksjons SISTE side og [nextReady] er neste
     * seksjons side 0 — begge KUN når bitmapmen allerede finnes i cachen
     * (kalleren sjekker PageBitmapCache med den kanoniske renderKey). Ingen
     * boolean hasNext, ingen fallback forrige sidetall: er bitmapmen ikke klar,
     * sendes null og vinduet får da ALDRI en fantom-kant-side.
     */
    fun windowFor(
        sectionIndex: Int,
        sectionPageCount: Int,
        previousReady: ReaderPageRef?,
        nextReady: ReaderPageRef?,
    ): PageWindow = PageWindow(
        sectionIndex = sectionIndex,
        sectionPageCount = sectionPageCount,
        leading = previousReady,
        trailing = nextReady,
    )

    /**
     * Justerer kant-sidene etter curl-posisjonen:
     *  - trailing (neste seksjons side 1) beholdes/legges til kun når vi står på
     *    siste reelle side (eller allerede har landet på den) og referansen er klar
     *  - leading (forrige seksjons siste side) beholdes/legges til kun når vi står
     *    på første reelle side og referansen er klar
     *  - landet på en kant-side → behold uendret (midt i overgang)
     *
     * @param current curl-indeksen
     * @param nextRef ReaderPageRef til neste seksjons side 1, eller null hvis ikke klar
     * @param prevRef ReaderPageRef til forrige seksjons siste side, eller null hvis ikke klar
     */
    fun stabilize(
        window: PageWindow,
        current: Int,
        nextRef: ReaderPageRef?,
        prevRef: ReaderPageRef?,
    ): PageWindow {
        val onTrailing = window.trailing != null && current == window.trailingIndex
        if (onTrailing) return window // midt i/etter overgang: behold kant-siden

        val local = window.realLocal(current)
        return when {
            local == null -> window // utenfor reelle sider: behold vinduet som er
            else -> {
                val atFirst = local == 0
                val atLast = local == window.sectionPageCount - 1
                window.copy(
                    leading = if (atFirst) prevRef else null,
                    trailing = if (atLast) nextRef else null,
                )
            }
        }
    }

    /**
     * Løser curl-indeksen og avgjør om en seksjonsgrense er krysset
     * (returnert ref tilhører en annen seksjon). Hovedløkken rapporterer kun
     * reelle lokale sider; grense-kryssing håndteres av overgangshandleren.
     */
    fun resolve(window: PageWindow, curlIndex: Int): ReaderPageRef? = window.resolve(curlIndex)

    /** Lokal sideindeks (null hvis curl-indeksen er en kant-side/utenfor). */
    fun realLocal(window: PageWindow, curlIndex: Int): Int? = window.realLocal(curlIndex)

    /** Curl-indeks for en lokal side (tar høyde for ev. leading-kant-side). */
    fun curlIndexOfLocal(window: PageWindow, local: Int): Int = window.curlIndexOfLocal(local)

    /** Indeksen til trailing-kant-siden, eller null hvis vinduet ikke har trailing. */
    fun trailingIndex(window: PageWindow): Int? = if (window.trailing != null) window.trailingIndex else null

    /** Indeksen til leading-kant-siden, eller null hvis vinduet ikke har leading. */
    fun leadingIndex(window: PageWindow): Int? = if (window.leading != null) 0 else null
}
