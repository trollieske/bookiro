package com.bookrio.core.parse

/**
 * Detects and strips narrator credits that audio tags often carry in the ARTIST
 * field, e.g.:
 *
 *   "Knut Hamsun (innlest av Per Theodor Haugen)"
 *   "Terry Pratchett - read by Nigel Planer"
 *   "Read by Stephen Fry"
 *   "Narrator: Celia Imrie"
 *
 * Without this an audiobook is filed (and sorted) under the narrator's name.
 * Pure Kotlin, no Android dependencies, so it is unit-testable on the JVM.
 */
object NarratorTags {

    private const val CREDIT =
        "innlest|opplest|lest|inläst|læst|gelesen|read|narrated|performed|narrator"
    private const val AV_BY = "av|by|af|von"

    private val PATTERNS: List<Regex> = listOf(
        // Bracketed credit: "(innlest av X)", "[read by X]", "(narrator: X)"
        Regex(
            "(?i)[\\(\\[]\\s*(?:$CREDIT)\\b\\s*(?:$AV_BY)?\\s*:?\\s*([^\\)\\]]+)[\\)\\]]"
        ),
        // Whole string is a credit: "Read by X", "Innlest av X", "Narrator: X"
        Regex("(?i)^(?:$CREDIT)\\b\\s*(?:$AV_BY)?\\s*:?\\s+(.+)$"),
        // Trailing credit after a separator: " - read by X", ", narrated by X"
        Regex("(?i)\\s*(?:[-–—,;]|\\s)\\s*(?:$CREDIT)\\b\\s+(?:$AV_BY)\\s*:?\\s*(.+)$"),
        // "Narrator:X" with no space (anchored so a name is never eaten)
        Regex("(?i)^(?:$CREDIT)\\s*:\\s*(.+)$")
    )

    data class Split(val author: String, val narrator: String?)

    /**
     * Splits a raw author/artist tag into the cleaned author and an optional
     * narrator credit. When the whole value is only a credit, [Split.author] is
     * blank and callers should fall back to other metadata (filename, folder).
     */
    fun split(raw: String?): Split {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return Split("", null)
        for (pattern in PATTERNS) {
            val match = pattern.find(value) ?: continue
            val narrator = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                ?: continue
            val removed = value.substring(0, match.range.first) + " " + value.substring(match.range.last + 1)
            val author = removed
                .trim()
                .trim(' ', ',', ';', '-', '–', '—', ':', '(', ')', '[', ']')
                .replace(Regex("\\s{2,}"), " ")
                .trim()
            return Split(author, narrator)
        }
        return Split(value, null)
    }

    /** True when [raw] carries a narrator credit we can recognise. */
    fun hasNarratorCredit(raw: String?): Boolean = split(raw).narrator != null
}