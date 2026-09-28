package com.bookrio.core.parse

data class ParsedBookMetadata(
    val title: String,
    val author: String,
    val publisher: String? = null,
    val year: String? = null
)

object MetadataCleaner {
    private val JUNK_PATTERNS = listOf(
        Regex("""_libgen\.[a-z]+""", RegexOption.IGNORE_CASE),
        Regex("""_z-lib\.[a-z]+""", RegexOption.IGNORE_CASE),
        Regex("""\([^)]*z-library[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""\[[^\]]*libgen[^\]]*\]""", RegexOption.IGNORE_CASE),
        Regex("""\.epub$""", RegexOption.IGNORE_CASE),
        Regex("""\.pdf$""", RegexOption.IGNORE_CASE),
        Regex("""\.mobi$""", RegexOption.IGNORE_CASE),
        Regex("""\.azw3?$""", RegexOption.IGNORE_CASE),
        Regex("""\.cbz$""", RegexOption.IGNORE_CASE)
    )

    fun clean(rawTitle: String?, rawAuthor: String? = null, filename: String? = null): ParsedBookMetadata {
        val input = rawTitle?.takeIf { it.isNotBlank() } ?: filename ?: ""
        var clean = input

        JUNK_PATTERNS.forEach { regex -> clean = regex.replace(clean, "") }
        clean = clean.replace("_", " ").replace("  ", " ").trim()

        var author = rawAuthor?.replace("_", " ")?.trim() ?: ""
        var title = clean

        if (author.isBlank() || author.contains("Ukjent", ignoreCase = true)) {
            if (clean.contains(" - ")) {
                val parts = clean.split(" - ", limit = 2)
                val p0 = parts[0].trim()
                val p1 = parts[1].trim()
                if (p0.contains(",") || (p0.split(" ").size in 1..3 && !p1.contains(","))) {
                    author = formatAuthorName(p0)
                    title = p1.ifBlank { p0 }
                } else {
                    title = p0
                    author = formatAuthorName(p1)
                }
            } else if (clean.contains("._")) {
                val parts = clean.split("._", limit = 2)
                title = parts[0].trim()
                author = formatAuthorName(parts[1].trim())
            }
        }

        if (author.isBlank()) author = "Ukjent forfatter"
        if (title.isBlank()) title = "Uten tittel"

        return ParsedBookMetadata(
            title = title,
            author = author
        )
    }

    private fun formatAuthorName(name: String): String {
        var a = name.replace("_", " ").trim()
        if (a.contains(",")) {
            val parts = a.split(",", limit = 2)
            a = "${parts[1].trim()} ${parts[0].trim()}".trim()
        }
        return a
    }
}
