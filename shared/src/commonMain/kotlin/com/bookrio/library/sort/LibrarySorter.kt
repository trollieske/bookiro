package com.bookrio.library.sort

import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.SortDirection

/**
 * KMP port of the Android `:library` `LibrarySorter`
 * (`library/src/main/java/com/bookrio/library/sort/LibrarySorter.kt`).
 *
 * Single source of truth for library sorting, pure Kotlin so it runs on iOS
 * exactly like on Android. The only platform difference is collation: Android
 * used `java.text.Collator` for `nb-NO`; here the Norwegian alphabet is
 * approximated with a collation key that keeps the standard order
 * `a..z` < `æ` < `ø` < `å` (the original file is untouched).
 *
 * Sort modes (visible Sort Rail, in this order):
 *   HYLLE (default) · SERIE · FORFATTER · NYLIG · TITTEL · LAGT_TIL
 */

/**
 * Flat, media-type-agnostic snapshot of one book used purely for sorting.
 * [seriesIndexResolved] is the explicit Room value if present, otherwise a
 * conservative parse of the existing series string ("2", "2.5", "Book 2", "#2").
 * Nothing guessed is ever written back to Room.
 */
data class SortBook(
    val id: Long,
    val title: String,
    val sortTitle: String,
    val author: String,
    val sortAuthor: String,
    val series: String?,
    val seriesIndex: Float?,
    val seriesIndexResolved: Float?,
    val dateAdded: Long,
    val lastActivity: Long,
    val updatedAt: Long,
    val isAudio: Boolean
)

object LibrarySorter {

    // ── Normalization ────────────────────────────────────────────────────────

    /** Collapse whitespace; never return raw "null"-style garbage to the UI. */
    fun normalizeText(raw: String?): String =
        raw?.trim()?.replace(Regex("\\s+"), " ").orEmpty()

    private val garbageValues =
        setOf("", "null", "unknown", "ukjent", "n/a", "na", "-", "—")

    /** True when the series string is a real series (never "null"/"Series 0"/file paths). */
    fun isValidSeries(series: String?): Boolean {
        val s = normalizeText(series)
        if (s.isEmpty() || s.lowercase() in garbageValues) return false
        if (Regex("(?i)^series\\s*0*$").matches(s)) return false
        // Reject chapter-filename-like parser artifacts.
        if (s.contains('/') || s.contains('\\')) return false
        val lower = s.lowercase()
        if (listOf(".xhtml", ".html", ".htm", ".opf", ".ncx", ".xht").any { lower.endsWith(it) }) return false
        return true
    }

    /** Known author = non-empty and not an "unknown" placeholder. */
    fun isValidAuthor(author: String?): Boolean {
        val a = normalizeText(author)
        if (a.isEmpty() || a.lowercase() in garbageValues) return false
        if (Regex("(?i)^(ukjent|unknown)\\s*(forfatter|author)?$").matches(a)) return false
        return true
    }

    /**
     * Conservative series-index parsing from existing series metadata ONLY:
     * "2", "2.5", "Book 2", "#2". Never infers from title patterns.
     */
    fun parseSeriesIndex(series: String?): Float? {
        if (!isValidSeries(series)) return null
        val s = normalizeText(series)
        Regex("(?i)^#?(\\d+(?:[.,]\\d+)?)$").find(s)?.let {
            return it.groupValues[1].replace(',', '.').toFloatOrNull()
        }
        Regex("(?i)^\\s*(?:book|bok)\\s*#?(\\d+(?:[.,]\\d+)?)\\s*$").find(s)?.let {
            return it.groupValues[1].replace(',', '.').toFloatOrNull()
        }
        return null
    }

    // ── Collation ────────────────────────────────────────────────────────────

    /**
     * Norwegian collation key: lowercase, then map the letters that sort after
     * `z` to code points above `z` so a plain string compare yields `a..z < æ < ø < å`.
     * An approximation of `Collator.getInstance(Locale.forLanguageTag("nb-NO"))`.
     */
    private fun collationKey(value: String): String = buildString(value.length) {
        value.lowercase().forEach { c ->
            append(
                when (c) {
                    'æ' -> '{' // 0x7B, right after 'z'
                    'ø' -> '|' // 0x7C
                    'å' -> '}' // 0x7D
                    else -> c
                }
            )
        }
    }

    private fun cmpText(a: String, b: String): Int = collationKey(a).compareTo(collationKey(b))

    private fun displayTitle(b: SortBook): String =
        normalizeText(b.sortTitle.ifBlank { b.title }).lowercase()

    private fun displayAuthor(b: SortBook): String =
        normalizeText(b.sortAuthor.ifBlank { b.author })

    private fun displaySeries(b: SortBook): String = normalizeText(b.series)

    /**
     * Ascending base comparator for each mode. DESC is the exact reversal,
     * so toggling the direction always produces a deterministic, visibly
     * different order.
     */
    fun ascendingComparator(mode: LibrarySortMode): Comparator<SortBook> = when (mode) {
        LibrarySortMode.HYLLE -> Comparator { a, b ->
            val aSeries = isValidSeries(a.series)
            val bSeries = isValidSeries(b.series)
            if (aSeries != bSeries) return@Comparator if (aSeries) -1 else 1
            if (aSeries) {
                cmpText(displaySeries(a), displaySeries(b))
                    .takeIf { it != 0 }
                    ?.let { return@Comparator it }
                // Natural numeric series order: 1, 2, 2.5, 3, 10. Books without
                // a resolvable index come after indexed ones in the same series.
                val ai = a.seriesIndexResolved
                val bi = b.seriesIndexResolved
                if (ai == null && bi != null) return@Comparator 1
                if (ai != null && bi == null) return@Comparator -1
                if (ai != null && bi != null && ai != bi) return@Comparator ai.compareTo(bi)
            } else {
                val aKnown = isValidAuthor(a.author)
                val bKnown = isValidAuthor(b.author)
                if (aKnown != bKnown) return@Comparator if (aKnown) -1 else 1
                // Unknown/missing authors: tie-break on title, not the raw author string.
                if (aKnown) {
                    cmpText(displayAuthor(a), displayAuthor(b))
                        .takeIf { it != 0 }
                        ?.let { return@Comparator it }
                }
            }
            cmpText(displayTitle(a), displayTitle(b)).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }

        LibrarySortMode.SERIE -> Comparator { a, b ->
            val aSeries = isValidSeries(a.series)
            val bSeries = isValidSeries(b.series)
            if (aSeries != bSeries) return@Comparator if (aSeries) -1 else 1
            if (aSeries) {
                cmpText(displaySeries(a), displaySeries(b))
                    .takeIf { it != 0 }
                    ?.let { return@Comparator it }
                val ai = a.seriesIndexResolved
                val bi = b.seriesIndexResolved
                if (ai == null && bi != null) return@Comparator 1
                if (ai != null && bi == null) return@Comparator -1
                if (ai != null && bi != null && ai != bi) return@Comparator ai.compareTo(bi)
            } else {
                val aKnown = isValidAuthor(a.author)
                val bKnown = isValidAuthor(b.author)
                if (aKnown != bKnown) return@Comparator if (aKnown) -1 else 1
                cmpText(displayAuthor(a), displayAuthor(b))
                    .takeIf { it != 0 }
                    ?.let { return@Comparator it }
            }
            cmpText(displayTitle(a), displayTitle(b)).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }

        LibrarySortMode.FORFATTER -> Comparator { a, b ->
            val aKnown = isValidAuthor(a.author)
            val bKnown = isValidAuthor(b.author)
            if (aKnown != bKnown) return@Comparator if (aKnown) -1 else 1
            if (aKnown) {
                cmpText(displayAuthor(a), displayAuthor(b))
                    .takeIf { it != 0 }
                    ?.let { return@Comparator it }
            }
            val aSeries = isValidSeries(a.series)
            val bSeries = isValidSeries(b.series)
            if (aSeries != bSeries) return@Comparator if (aSeries) -1 else 1
            if (aSeries) {
                cmpText(displaySeries(a), displaySeries(b))
                    .takeIf { it != 0 }
                    ?.let { return@Comparator it }
                val ai = a.seriesIndexResolved
                val bi = b.seriesIndexResolved
                if (ai == null && bi != null) return@Comparator 1
                if (ai != null && bi == null) return@Comparator -1
                if (ai != null && bi != null && ai != bi) return@Comparator ai.compareTo(bi)
            }
            cmpText(displayTitle(a), displayTitle(b)).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }

        LibrarySortMode.NYLIG -> Comparator { a, b ->
            val byActivity = a.lastActivity.compareTo(b.lastActivity)
            if (byActivity != 0) return@Comparator byActivity
            val byUpdated = a.updatedAt.compareTo(b.updatedAt)
            if (byUpdated != 0) return@Comparator byUpdated
            a.id.compareTo(b.id)
        }

        LibrarySortMode.TITTEL -> Comparator { a, b ->
            cmpText(displayTitle(a), displayTitle(b)).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }

        LibrarySortMode.LAGT_TIL -> Comparator { a, b ->
            val byDate = a.dateAdded.compareTo(b.dateAdded)
            if (byDate != 0) return@Comparator byDate
            a.id.compareTo(b.id)
        }
    }

    /** The one and only entry point the UI state builder uses. */
    fun sort(books: List<SortBook>, mode: LibrarySortMode, direction: SortDirection): List<SortBook> {
        val base = books.sortedWith(ascendingComparator(mode))
        return if (direction == SortDirection.DESC) base.asReversed() else base
    }

    // ── HYLLE section labels ─────────────────────────────────────────────────

    /**
     * Minimal full-width section labels for HYLLE mode only.
     * Series group  → "SERIE · FORFATTER"
     * Author group  → "FORFATTER" (skipped for single-book groups to avoid noise)
     * Returns a list parallel to [sorted]; null = no label before that book.
     */
    fun sectionLabels(sorted: List<SortBook>): List<String?> {
        if (sorted.isEmpty()) return emptyList()
        val labels = arrayOfNulls<String>(sorted.size)
        var prevKey: String? = null

        // Pre-compute group sizes per key so one-book author groups stay unlabeled.
        val groupSizes = HashMap<String, Int>()
        sorted.forEach { b ->
            val key = groupKey(b) ?: return@forEach
            groupSizes[key] = (groupSizes[key] ?: 0) + 1
        }

        sorted.forEachIndexed { i, b ->
            val key = groupKey(b)
            val label = when {
                key == null -> null
                key != prevKey -> {
                    val size = groupSizes[key] ?: 1
                    val isSeries = isValidSeries(b.series)
                    // Series groups always labeled; author labels skipped for 1-book groups.
                    if (!isSeries && size < 2) null
                    else if (isSeries) {
                        val author = displayAuthor(b)
                        val seriesName = displaySeries(b)
                        if (isValidAuthor(b.author)) "$seriesName · $author".uppercase()
                        else seriesName.uppercase()
                    } else displayAuthor(b).uppercase()
                }
                else -> null
            }
            labels[i] = label
            if (key != prevKey) prevKey = key
        }
        return labels.toList()
    }

    private fun groupKey(b: SortBook): String? {
        val series = displaySeries(b)
        if (isValidSeries(b.series)) return "s:" + series.lowercase()
        if (isValidAuthor(b.author)) return "a:" + displayAuthor(b).lowercase()
        return null // unknown series/author → no label, appears last
    }
}