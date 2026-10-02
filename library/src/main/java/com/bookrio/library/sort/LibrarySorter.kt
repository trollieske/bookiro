package com.bookrio.library.sort

import java.text.Collator
import java.util.Locale
import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.SortDirection

/**
 * Single source of truth for library sorting.
 *
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 * The ViewModel maps BookEntity + ReadingProgressEntity rows into [SortBook]
 * and feeds them through [LibrarySorter.sort]; UI state is derived from the
 * result — no fake state.
 *
 * Sort modes (visible sort sheet): NYLIG · LAGT_TIL · TITTEL · FORFATTER · SERIE
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

    /** Norwegian A–Å collation, reused (Collator is not thread-safe → ThreadLocal). */
    private val collator: ThreadLocal<Collator> = object : ThreadLocal<Collator>() {
        override fun initialValue(): Collator =
            Collator.getInstance(Locale.forLanguageTag("nb-NO")).apply { strength = Collator.PRIMARY }
    }

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

    // ── Comparators ──────────────────────────────────────────────────────────

    private fun cmpText(a: String, b: String): Int = collator.get().compare(a, b)

    private fun displayTitle(b: SortBook): String =
        normalizeText(b.sortTitle.ifBlank { b.title }).lowercase()

    private fun displayAuthor(b: SortBook): String =
        normalizeText(b.sortAuthor.ifBlank { b.author })

    private fun displaySeries(b: SortBook): String = normalizeText(b.series)

    /**
     * Ascending base comparator for each mode. DESC is the exact reversal,
     * so toggling ⇅ always produces a deterministic, visibly different order.
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

    /** The one and only entry point the ViewModel uses. */
    fun sort(books: List<SortBook>, mode: LibrarySortMode, direction: SortDirection): List<SortBook> {
        val base = books.sortedWith(ascendingComparator(mode))
        return if (direction == SortDirection.DESC) base.asReversed() else base
    }

    // ── Section labels (Series / Author grouping) ────────────────────────────

    /**
     * Full-width section labels for the two grouping modes:
     *  - [LibrarySortMode.SERIE]: one label per series group ("SERIES · AUTHOR");
     *    series-less books fall back to author groups, and single-book author
     *    groups stay unlabeled to avoid noise.
     *  - [LibrarySortMode.FORFATTER]: one label per author group (always labeled —
     *    grouping by author is the point of the mode).
     * Returns a list parallel to [sorted]; null = no label before that book.
     */
    fun sectionLabels(sorted: List<SortBook>, mode: LibrarySortMode): List<String?> {
        if (sorted.isEmpty()) return emptyList()
        if (mode != LibrarySortMode.SERIE && mode != LibrarySortMode.FORFATTER) {
            return List(sorted.size) { null }
        }
        val labels = arrayOfNulls<String>(sorted.size)

        if (mode == LibrarySortMode.FORFATTER) {
            var prev: String? = null
            sorted.forEachIndexed { i, b ->
                if (!isValidAuthor(b.author)) {
                    labels[i] = null
                    prev = null
                    return@forEachIndexed
                }
                val key = displayAuthor(b).lowercase(Locale.ROOT)
                if (key != prev) {
                    labels[i] = displayAuthor(b).uppercase(Locale.ROOT)
                    prev = key
                }
            }
            return labels.toList()
        }

        // SERIE: series groups first, author fallback for series-less books.
        var prevKey: String? = null
        val groupSizes = HashMap<String, Int>()
        sorted.forEach { b ->
            val key = seriesGroupKey(b) ?: return@forEach
            groupSizes[key] = (groupSizes[key] ?: 0) + 1
        }
        sorted.forEachIndexed { i, b ->
            val key = seriesGroupKey(b)
            val label = when {
                key == null -> null
                key != prevKey -> {
                    val size = groupSizes[key] ?: 1
                    if (isValidSeries(b.series)) {
                        val author = displayAuthor(b)
                        val seriesName = displaySeries(b)
                        if (isValidAuthor(b.author)) "$seriesName · $author".uppercase(Locale.ROOT)
                        else seriesName.uppercase(Locale.ROOT)
                    } else if (size >= 2) {
                        displayAuthor(b).uppercase(Locale.ROOT)
                    } else null
                }
                else -> null
            }
            labels[i] = label
            if (key != prevKey) prevKey = key
        }
        return labels.toList()
    }

    private fun seriesGroupKey(b: SortBook): String? {
        if (isValidSeries(b.series)) return "s:" + displaySeries(b).lowercase(Locale.ROOT)
        if (isValidAuthor(b.author)) return "a:" + displayAuthor(b).lowercase(Locale.ROOT)
        return null
    }
}
