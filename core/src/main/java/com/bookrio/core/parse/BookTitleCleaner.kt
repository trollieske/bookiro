package com.bookrio.core.parse

/**
 * Strips leading series/track indexes that leak from file names into titles, e.g.
 * `01 The Sword of Shannara` -> `The Sword of Shannara`,
 * `03.25 Indomitable` -> `Indomitable`.
 *
 * Only zero-padded or decimal indexes are stripped, so real numeric titles such
 * as `1984`, `2001: A Space Odyssey`, `20,000 Leagues Under the Sea`,
 * `11/22/63` and `7 Habits of Highly Effective People` are left intact.
 */
object BookTitleCleaner {

    private val LEADING_INDEX = Regex("^\\s*0\\d{1,2}(?:[.,]\\d{1,2})?\\s+")
    private val LEADING_DECIMAL = Regex("^\\s*\\d{1,2}[.,]\\d{1,2}\\s+")

    fun clean(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return value
        var result = value
        // Repeat so a rare "01 02 Title" is fully cleaned.
        while (true) {
            val next = result
                .replaceFirst(LEADING_INDEX, "")
                .replaceFirst(LEADING_DECIMAL, "")
            if (next == result) break
            result = next
        }
        return result.trim().ifBlank { value }
    }
}