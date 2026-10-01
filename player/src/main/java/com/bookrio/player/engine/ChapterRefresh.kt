package com.bookrio.player.engine

import android.util.Log

/**
 * Kanonisk kapittel-oppdatering og globale tidslinje-hjelpere for lydbøker.
 *
 * Alt her er rent (Compose-fri, DB-fri) slik at det er JVM-enhetstestbart.
 * DB-/filtilgang skjer i [AudiobookEngine.ensureFreshChapters].
 */

/** Hvor en reell kapittelliste ble funnet. */
enum class ChapterDiscoverySource { NONE, FOLDER, MP4_CHPL, ID3_CHAP, CUE }

/** Hvorfor lagret kapittelmetadata er utdatert/utilstrekkelig. */
enum class ChapterStaleReason {
    BLANK_JSON,
    MALFORMED_JSON,
    EMPTY,
    SINGLE_STUB,
    UNVERIFIED_SINGLE,
    COUNT_MISMATCH,
}

/** Lette rader for staleness-vurdering (uavhengig av JSON-parsing). */
data class StoredChapterRow(val title: String, val startMs: Long)

object ChapterRefresh {

    const val DIAG_TAG = "ChapterRefresh"

    /**
     * Vurderer om lagret kapittelmetadata er utdatert/utilstrekkelig og må
     * friskes opp. Returnerer null når listen er gyldig og skal beholdes.
     *
     * Regler (i rekkefølge):
     *  - tom/blank JSON → BLANK_JSON
     *  - umulig å tolke / null kapitler → MALFORMED_JSON / EMPTY
     *  - nøyaktig én syntetisk stub-oppføring (tittel == boktittel og startMs == 0,
     *    eller blank tittel) → SINGLE_STUB
     *  - chapterCount-metadata uenig med antall oppføringer → COUNT_MISMATCH
     *
     * ÉN ekte kapittel-oppføring (annen tittel eller startMs > 0) er GYLDIG og
     * skal aldri overskrives eller friskes opp.
     */
    fun evaluateStoredChapters(
        jsonBlank: Boolean,
        parsedChapters: List<StoredChapterRow>,
        bookTitle: String,
        storedChapterCount: Int?,
        /**
         * True for containere som kan ha innebygde kapitler (M4B/M4A/MP4/MP3/AAC).
         * Da er én enkelt lagret oppføring UAVKLART og må verifiseres ved skanning:
         * den kan være en import-fallback, ikke et ekte enkeltkapittel.
         */
        mayEmbedChapters: Boolean = false,
    ): ChapterStaleReason? {
        if (jsonBlank) return ChapterStaleReason.BLANK_JSON
        if (parsedChapters.isEmpty()) return ChapterStaleReason.EMPTY
        if (parsedChapters.size == 1) {
            val only = parsedChapters[0]
            val syntheticStub = (titlesMatch(only.title, bookTitle) && only.startMs == 0L) ||
                only.title.isBlank()
            if (syntheticStub) return ChapterStaleReason.SINGLE_STUB
            if (mayEmbedChapters && only.startMs == 0L) return ChapterStaleReason.UNVERIFIED_SINGLE
        }
        val stored = storedChapterCount?.takeIf { it > 0 }
        if (stored != null && stored != parsedChapters.size) return ChapterStaleReason.COUNT_MISMATCH
        return null
    }

    /**
     * Titler matcher når de er like, eller når den ene er en normalisert del av den
     * andre. Håndterer fallback-titler som er filnavn-avledede og mangler/avviker i
     * tegnsetting, f.eks. «Dungeon Crawler Carl» mot
     * «Dungeon Crawler Carl: A LitRPG/Gamelit Adventure».
     */
    fun titlesMatch(chapterTitle: String, bookTitle: String): Boolean {
        val a = normalizeForTitleMatch(chapterTitle)
        val b = normalizeForTitleMatch(bookTitle)
        if (a.isBlank() || b.isBlank()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun normalizeForTitleMatch(raw: String): String =
        raw.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Filnavn (med ending) å gi kapittel-parseren ved gjenoppdaging. Kallere som
     * sender boktittelen mister filendingen, og da hopper AudioMetadataParser over
     * innebygd kapittel-parsing. Faller tilbake til en syntetisk ending fra formatet.
     */
    fun chapterParserFileName(filePath: String?, fileUri: String?, formatName: String): String {
        val fromPath = filePath?.substringBefore('?')?.substringAfterLast('/')
        if (!fromPath.isNullOrBlank() && fromPath.contains('.')) return fromPath
        val fromUri = fileUri?.substringBefore('?')?.substringAfterLast('/')
        if (!fromUri.isNullOrBlank() && fromUri.contains('.')) return fromUri
        return "audio.${formatName.lowercase()}"
    }

    /** Naturlig sortering: «1, 2, 10» — aldri «1, 10, 2». */
    fun <T> sortedNaturally(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith { a, b -> naturalCompare(nameOf(a), nameOf(b)) }

    /** Sammenligner tekststrenger med tallblokker som tall (naturlig rekkefølge). */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        val asLower = a.lowercase()
        val bsLower = b.lowercase()
        while (i < asLower.length && j < bsLower.length) {
            val ca = asLower[i]
            val cb = bsLower[j]
            val digitA = ca.isDigit()
            val digitB = cb.isDigit()
            if (digitA && digitB) {
                var i2 = i
                while (i2 < asLower.length && asLower[i2].isDigit()) i2++
                var j2 = j
                while (j2 < bsLower.length && bsLower[j2].isDigit()) j2++
                val numA = asLower.substring(i, i2).trimStart('0')
                val numB = bsLower.substring(j, j2).trimStart('0')
                val cmp = when {
                    numA.length != numB.length -> numA.length - numB.length
                    else -> numA.compareTo(numB)
                }
                if (cmp != 0) return cmp
                i = i2
                j = j2
            } else {
                val cmp = ca.compareTo(cb)
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return asLower.length - bsLower.length
    }

    /** Hurtigsøk: globalt mål med clamp til 0..global varighet. */
    fun quickSeekTarget(currentMs: Long, deltaMs: Long, globalDurationMs: Long): Long =
        (currentMs + deltaMs).coerceIn(0L, globalDurationMs.coerceAtLeast(0L))
}

/**
 * Ren tidslinje-matematikk for global boktid ↔ kapittel/klipp-relativ posisjon.
 * Brukes av både [AudiobookPlaybackService] (Media3-mapping) og PlayerScreen.
 */
object AudiobookTimeline {

    /** Global varighet: siste kapittels slutt, ellers fallback (f.eks. medievarighet). */
    fun globalDurationMs(chapters: List<AudiobookChapter>, fallbackMs: Long): Long {
        val last = chapters.lastOrNull() ?: return fallbackMs
        return last.endMs?.takeIf { it > last.startMs } ?: fallbackMs
    }

    /** Global posisjon som brøkdel av hele boken (0..1, clampet). */
    fun globalFraction(positionMs: Long, globalDurationMs: Long): Float =
        if (globalDurationMs > 0L) (positionMs.toFloat() / globalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f

    /**
     * Global søk-oppløsning: (kapittelindeks, offset inni kapittelet, faktisk ms
     * etter clamp). Kapittelindeksen løses via siste startMs <= mål, aldri ved
     * egen aritmetikk som kan desynke fra Media3-clipping.
     */
    fun resolveGlobalSeek(
        chapters: List<AudiobookChapter>,
        targetMs: Long,
        globalDurationMs: Long,
    ): Triple<Int, Long, Long> {
        val clamped = targetMs.coerceIn(0L, globalDurationMs.coerceAtLeast(0L))
        if (chapters.isEmpty()) return Triple(0, clamped, clamped)
        val idx = chapters.indexOfLast { it.startMs <= clamped }.coerceAtLeast(0)
        val offset = (clamped - chapters[idx].startMs).coerceAtLeast(0L)
        return Triple(idx, offset, clamped)
    }

    /** Start og slutt for kapittel [index] på den globale tidslinjen. */
    fun chapterBoundsMs(
        chapters: List<AudiobookChapter>,
        index: Int,
        globalDurationMs: Long,
    ): Pair<Long, Long> {
        val ch = chapters.getOrNull(index) ?: return 0L to globalDurationMs.coerceAtLeast(0L)
        val end = ch.endMs?.takeIf { it > ch.startMs } ?: globalDurationMs.coerceAtLeast(ch.startMs)
        return ch.startMs to end
    }

    /** Aktivt kapittel for en global posisjon. */
    fun currentChapterIndex(chapters: List<AudiobookChapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
}

/** Gated diagnostikk (AV som standard) — aldri filstier, rå metadata eller innhold. */
// MIDERTIDIG PÅ for live feilsøking på telefon — skru AV før release!
internal const val CHAPTER_REFRESH_DIAG = true
internal fun chapterDiag(msg: String) {
    if (CHAPTER_REFRESH_DIAG) Log.d(ChapterRefresh.DIAG_TAG, msg)
}
