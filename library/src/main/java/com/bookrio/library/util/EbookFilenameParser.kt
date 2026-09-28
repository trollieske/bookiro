package com.bookrio.library.util

import java.util.Locale

/**
 * Smart filename parser for e-books.
 *
 * Decodes release-group naming conventions into title / author / series / seriesIndex
 * with a 180+ common-author lookup table so "Herbert" reliably becomes "Frank Herbert"
 * and short last-name-only tags inside [ brackets ] resolve properly.
 *
 * Typical patterns handled:
 *   [Herbert, Dune 005,] Frank Herbert - Dune Messiah (1969).epub
 *   Terry Pratchett - [Discworld 29] - Night Watch (US).epub
 *   [Author, SeriesName SeriesNo, BookTitle]
 *   Series Name Vol. 3 - Title - Author
 *   Title by Author Name
 *   {Author} Title (retail) [v3]
 *   Plus: strip release noise tags like [EPUB], [retail], (v5.0), Retail, Scan, etc.
 */
object EbookFilenameParser {

    data class ParsedFilename(
        val title: String,
        val author: String,
        val series: String? = null,
        val seriesIndex: Double? = null,
    )

    // === Last-name → full-name mapping for common, widely-known authors.
    // When an EPUB release tag contains only last name inside brackets (e.g. [Herbert, Dune 005])
    // we promote it to the full canonical name so sorting / covers / search all work.
    // Feel free to extend: the format is "lowercase last name" to "Canonical Full Name".
    private val KNOWN_AUTHORS: Map<String, String> = sortedMapOf(
        // === Science Fiction ===
        "herbert" to "Frank Herbert",
        "asimov" to "Isaac Asimov",
        "bradbury" to "Ray Bradbury",
        "clarke" to "Arthur C. Clarke",
        "heinlein" to "Robert A. Heinlein",
        "dick" to "Philip K. Dick",
        "gibson" to "William Gibson",
        "orwell" to "George Orwell",
        "huxley" to "Aldous Huxley",
        "le guin" to "Ursula K. Le Guin",
        "leguin" to "Ursula K. Le Guin",
        "vonnegut" to "Kurt Vonnegut",
        "wells" to "H. G. Wells",
        "verne" to "Jules Verne",
        "atwood" to "Margaret Atwood",
        "simmons" to "Dan Simmons",
        "card" to "Orson Scott Card",
        "herrick" to "Lois Lowry",
        "lowry" to "Lois Lowry",
        "adams" to "Douglas Adams",
        "banks" to "Iain M. Banks",
        "stephenson" to "Neal Stephenson",
        "larry niven" to "Larry Niven",
        "niven" to "Larry Niven",
        "pournelle" to "Jerry Pournelle",
        "aldiss" to "Brian W. Aldiss",
        "ballard" to "J. G. Ballard",
        "pohl" to "Frederik Pohl",
        "bova" to "Ben Bova",
        "chalker" to "Jack L. Chalker",
        "cherryh" to "C. J. Cherryh",
        "delany" to "Samuel R. Delany",
        "effinger" to "George Alec Effinger",
        "forward" to "Robert L. Forward",
        "haldeman" to "Joe Haldeman",
        "harrison" to "Harry Harrison",
        "korshak" to "Stanley Schmidt",
        "laumer" to "Keith Laumer",
        "mccaffrey" to "Anne McCaffrey",
        "norton" to "Andre Norton",
        "pratchett" to "Terry Pratchett",
        "gaiman" to "Neil Gaiman",
        "pullman" to "Philip Pullman",
        "miéville" to "China Miéville",
        "mieville" to "China Miéville",
        "baxter" to "Stephen Baxter",
        "hamilton" to "Peter F. Hamilton",
        "reynolds" to "Alastair Reynolds",
        "hutchins" to "Matthew Mather",
        "martin" to "George R. R. Martin",
        "king" to "Stephen King",
        "koontz" to "Dean Koontz",
        "barker" to "Clive Barker",
        "straub" to "Peter Straub",
        "rice" to "Anne Rice",
        "gaiman" to "Neil Gaiman",
        "sanderson" to "Brandon Sanderson",
        "jordan" to "Robert Jordan",
        "goodkind" to "Terry Goodkind",
        "salvatore" to "R. A. Salvatore",
        "weis" to "Margaret Weis",
        "hickman" to "Tracy Hickman",
        "brooks" to "Terry Brooks",
        "donaldson" to "Stephen R. Donaldson",
        "kushner" to "Ellen Kushner",
        "leiber" to "Fritz Leiber",
        "howard" to "Robert E. Howard",
        "moorcock" to "Michael Moorcock",
        "poul anderson" to "Poul Anderson",
        "anderson" to "Poul Anderson",
        "del rey" to "Lester del Rey",
        "bear" to "Greg Bear",
        "benford" to "Gregory Benford",
        "brin" to "David Brin",
        "crichton" to "Michael Crichton",
        "coney" to "Michael G. Coney",
        "cook" to "Glen Cook",
        "dan simmons" to "Dan Simmons",
        "disch" to "Thomas M. Disch",
        "dozois" to "Gardner Dozois",
        "ellis" to "Warren Ellis",
        "erikson" to "Steven Erikson",
        "esslemont" to "Ian C. Esslemont",
        "feist" to "Raymond E. Feist",
        "gemmell" to "David Gemmell",
        "gentle" to "Mary Gentle",
        "gleick" to "James Gleick",
        "golding" to "William Golding",
        "harris" to "Charlaine Harris",
        "herbert" to "Frank Herbert",
        "hobb" to "Robin Hobb",
        "holdstock" to "Robert Holdstock",
        "jones" to "Diana Wynne Jones",
        "kay" to "Guy Gavriel Kay",
        "keyes" to "Daniel Keyes",
        "kingston" to "Maxine Hong Kingston",
        "kurtz" to "Katherine Kurtz",
        "lackey" to "Mercedes Lackey",
        "l'engle" to "Madeleine L'Engle",
        "lengle" to "Madeleine L'Engle",
        "lewis" to "C. S. Lewis",
        "lindskold" to "Jane M. Lindskold",
        "lovelace" to "Ada Lovelace",
        "macauley" to "Colin Kapp",
        "macleod" to "Ken MacLeod",
        "mahy" to "Margaret Mahy",
        "mckillip" to "Patricia A. McKillip",
        "morrison" to "Toni Morrison",
        "naylor" to "Gloria Naylor",
        "o'brien" to "Tim O'Brien",
        "panshin" to "Alexei Panshin",
        "patterson" to "James Patterson",
        "peake" to "Mervyn Peake",
        "penrice" to "Dale Brown",
        "pierce" to "Tamora Pierce",
        "pilcher" to "Rosamunde Pilcher",
        "pratchett" to "Terry Pratchett",
        "power" to "Richard Powers",
        "pynchon" to "Thomas Pynchon",
        "robbins" to "Tom Robbins",
        "robinson" to "Kim Stanley Robinson",
        "roberts" to "Nora Roberts",
        "rushdie" to "Salman Rushdie",
        "sagan" to "Carl Sagan",
        "sansom" to "C. J. Sansom",
        "schmidt" to "Stanley Schmidt",
        "scott" to "Kristine Kathryn Rusch",
        "sheckley" to "Robert Sheckley",
        "silverberg" to "Robert Silverberg",
        "smith" to "Cordwainer Smith",
        "stapledon" to "Olaf Stapledon",
        "stross" to "Charles Stross",
        "svarney" to "Clifford Simak",
        "tepper" to "Sheri S. Tepper",
        "turtledove" to "Harry Turtledove",
        "van vogt" to "A. E. van Vogt",
        "vanvogt" to "A. E. van Vogt",
        "vance" to "Jack Vance",
        "vinge" to "Vernor Vinge",
        "walton" to "Jo Walton",
        "watts" to "Peter Watts",
        "wolfe" to "Gene Wolfe",
        "zahn" to "Timothy Zahn",
        "zelazny" to "Roger Zelazny",

        // === Fantasy (epic) ===
        "tolkien" to "J. R. R. Tolkien",
        "tolkien jrrt" to "J. R. R. Tolkien",
        "rowling" to "J. K. Rowling",
        "tolkien jr" to "J. R. R. Tolkien",
        "colfer" to "Eoin Colfer",
        "riordan" to "Rick Riordan",
        "paolini" to "Christopher Paolini",
        "meyer" to "Stephenie Meyer",
        "collins" to "Suzanne Collins",

        // === Mystery / Thriller ===
        "connelly" to "Michael Connelly",
        "child" to "Lee Child",
        "cornwell" to "Patricia Cornwell",
        "doyle" to "Arthur Conan Doyle",
        "christie" to "Agatha Christie",
        "hammett" to "Dashiell Hammett",
        "chandler" to "Raymond Chandler",
        "elroy" to "James Ellroy",
        "grisham" to "John Grisham",
        "lehane" to "Dennis Lehane",
        "patterson" to "James Patterson",
        "renda" to "Ruth Rendell",
        "rendell" to "Ruth Rendell",
        "rankin" to "Ian Rankin",
        "french" to "Tana French",

        // === Romance / General Fiction ===
        "austen" to "Jane Austen",
        "brontë" to "Charlotte Brontë",
        "bronte" to "Charlotte Brontë",
        "dickens" to "Charles Dickens",
        "eliot" to "George Eliot",
        "hardy" to "Thomas Hardy",
        "james" to "Henry James",
        "lawrence" to "D. H. Lawrence",
        "woolf" to "Virginia Woolf",
        "faulkner" to "William Faulkner",
        "hemingway" to "Ernest Hemingway",
        "fitzgerald" to "F. Scott Fitzgerald",
        "twain" to "Mark Twain",
        "wharton" to "Edith Wharton",
        "porter" to "Katherine Anne Porter",

        // === Norwegian / Nordic authors ===
        "kjaerstad" to "Jan Kjærstad",
        "kjærstad" to "Jan Kjærstad",
        "nesset" to "Arvid Hanssen",
        "hanssen" to "Arvid Hanssen",
        "sagen" to "Jostein Gaarder",
        "gaarder" to "Jostein Gaarder",
        "lindgren" to "Astrid Lindgren",
        "barnebo" to "Tor Åge Bringsværd",
        "bringsværd" to "Tor Åge Bringsværd",
        "bringsverd" to "Tor Åge Bringsværd",
        "holme" to "Ingvar Ambjørnsen",
        "ambjørnsen" to "Ingvar Ambjørnsen",
        "ambjornsen" to "Ingvar Ambjørnsen",
        "fløgstad" to "Kjartan Fløgstad",
        "flogstad" to "Kjartan Fløgstad",
        "søiland" to "Bjørn Sortland",
        "sortland" to "Bjørn Sortland",
        "mysen" to "Johan Harstad",
        "harstad" to "Johan Harstad",
        "vassbø" to "Lars Mytting",
        "mytting" to "Lars Mytting",
        "gyldendal" to "Jan-Erik Fjell",
        "fjell" to "Jan-Erik Fjell",
        "larsson" to "Stieg Larsson",
        "mankell" to "Henning Mankell",
        "tranströmer" to "Tomas Tranströmer",
        "transtromer" to "Tomas Tranströmer",
        "lindqvist" to "John Ajvide Lindqvist",
        "kehlmann" to "Daniel Kehlmann",
        "anke" to "Jens Bjørneboe",
        "bjørneboe" to "Jens Bjørneboe",
        "bjorneboe" to "Jens Bjørneboe",
        "vik" to "Bjørn Vik",
        "hoel" to "Sigurd Hoel",
        "krohn" to "Karin Fossum",
        "fossum" to "Karin Fossum",
        "holt" to "Anne Holt",
        "ungar" to "Tom Egeland",
        "egeland" to "Tom Egeland",
        "sæterbakken" to "Stig Sæterbakken",
        "saeterbakken" to "Stig Sæterbakken",
        "jørgensen" to "Inger Elisabeth Hansen",
        "hansen" to "Inger Elisabeth Hansen",
        "borgen" to "Jussi Adler-Olsen",
        "adler-olsen" to "Jussi Adler-Olsen",
        "adlerolsen" to "Jussi Adler-Olsen",
    )

    // Release noise that should be stripped from all tokens/segments.
    private val NOISE_TAGS_REGEX = Regex(
        "\\b(epub|retail|scan|digital|unabridged|abridged|v[\\d._]+|release|rip|repack|proof|corrected|revised|fixed|converted|amazon|kobo|barnes|nook|google play|overdrive|library|acsm|lcp|drmfree|drm free|mobi|azw3|azw|pdf|docx|txt|rtf|fb2|cbz|cbr|html|us|uk|de|fr|es|it|nl|no|sv|dk|fi|ru|en|cs|pl|pt|hu|tr|ro|bg|sr|hr|sk|sl)\\b",
        RegexOption.IGNORE_CASE
    )

    private val BRACKET_NOISE_REGEX = Regex(
        "\\[[^\\[\\]]{0,30}\\]",
        RegexOption.IGNORE_CASE
    )

    private val PAREN_NOISE_REGEX = Regex(
        "\\((v?\\d[\\d._]*|retail|scan|digital|release|repack|proof|corrected|epub|azw|mobi|converted|\\d{4}|unabridged|abridged|drmfree|drm-free|kobo|amazon|nook|overdrive|library|acsm)\\)",
        RegexOption.IGNORE_CASE
    )

    private val YEAR_IN_PARENS_REGEX = Regex("\\((1[5-9]\\d{2}|20\\d{2})\\)")
    private val SERIES_IN_BRACKETS_REGEX = Regex("\\[([^\\[\\]]+)\\]")
    private val DASH_SPLIT_REGEX = Regex("\\s+[-–—]\\s+")

    private fun String.cleanNoise(): String {
        var s = this
        s = PAREN_NOISE_REGEX.replace(s, "")
        s = s.replace("(", " ").replace(")", " ")
        s = s.replace("[", " ").replace("]", " ")
        s = s.replace("{", " ").replace("}", " ")
        s = s.replace(" - ", " ")
        s = s.replace(" – ", " ")
        s = s.replace(" — ", " ")
        s = NOISE_TAGS_REGEX.replace(s, " ")
        s = s.replace(Regex("[|/\\\\]"), " ")
        s = s.replace(Regex("\\s{2,}"), " ")
        return s.trim()
    }

    private fun String.cleanTitleOrAuthor(): String =
        this.trim()
            .removeSuffix(",")
            .removePrefix(",")
            .trim()
            .replace(Regex(",\\s*,+"), ",")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun titleCase(s: String): String =
        s.split(' ').joinToString(" ") { tok ->
            if (tok.isEmpty()) tok
            else if (tok.length <= 3 && tok.all { it.isLowerCase() } && tok in setOf("of","the","and","or","for","to","in","on","at","by","with","from","a","an","is","de","la","von","van","der","den","het","en","et","ei","en")) tok
            else tok[0].uppercaseChar() + tok.substring(1).lowercase(Locale.ROOT)
        }

    private fun resolveAuthorName(raw: String): String = resolveAuthor(raw)

    /**
     * Public helper: resolve a possibly-short / all-caps / last-name-only author token
     * to its canonical full name if known, or nicely title-cased if unknown but looks like
     * a real name.
     */
    fun resolveAuthor(raw: String): String {
        val r = raw.trim().trim(',', ';', '-', '_', '.').trim()
        if (r.isBlank()) return ""
        val lower = r.lowercase(Locale.ROOT)
        KNOWN_AUTHORS[lower]?.let { return it }
        // Try "last, first" form (e.g. "Herbert, Frank")
        if (r.contains(',')) {
            val parts = r.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size == 2) {
                val maybeLastLower = parts[0].lowercase()
                KNOWN_AUTHORS[maybeLastLower]?.let { return it }
                val swapped = "${parts[1]} ${parts[0]}"
                return titleCase(swapped)
            }
        }
        // Try just case fixing
        if (r.all { !it.isLetter() || it.isUpperCase() } && r.length > 3) {
            return titleCase(r.lowercase(Locale.ROOT))
        }
        // Return as is, but try to titlecase if all lower or all upper
        return if (r.lowercase(Locale.ROOT) == r || r.uppercase(Locale.ROOT) == r) titleCase(r.lowercase(Locale.ROOT)) else r
    }

    private fun parseBracketContent(bracket: String): ParsedFilename? {
        // Typical: [AuthorLastName, Series 005, BookTitle]
        // Also: [Series 05]    [Discworld 29]  [Author]
        val content = bracket.trim()
        if (content.isBlank()) return null
        val hasComma = content.contains(',')
        if (hasComma) {
            val parts = content.split(',').map { it.cleanTitleOrAuthor() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val head = parts.first()
            // If head is a name (doesn't start with digit, is short-ish, maybe matches author list)
            val headLower = head.lowercase()
            val headLooksLikeAuthor =
                (KNOWN_AUTHORS.containsKey(headLower)) ||
                    (!head.matches(Regex(".*\\d.*")) && head.length in 2..40 && !head.contains("book", ignoreCase = true) && !head.contains("vol", ignoreCase = true))

            val (seriesName: String?, seriesIndex: Double?, titleGuess: String?, authorGuess: String?) =
                if (headLooksLikeAuthor && parts.size >= 2) {
                    val author = resolveAuthorName(head)
                    val rest = parts.drop(1)
                    val second = rest.firstOrNull() ?: ""
                    val third = rest.getOrNull(1)
                    val (sname, sidx) = parseSeriesToken(second)
                    val (snameAlt, sidxAlt) = if (third != null) parseSeriesToken(third) else null to null
                    val finalSeries = sname ?: snameAlt
                    val finalIndex = sidx ?: sidxAlt
                    val finalTitle = if (sname != null) third?.takeIf { !it.isNullOrBlank() } else second.takeIf { it.isNotBlank() }
                    Four(finalSeries, finalIndex, finalTitle, author)
                } else {
                    // Head is a series, author maybe last or not present
                    val (sname, sidx) = parseSeriesToken(head)
                    val tail = parts.drop(1)
                    val tailAuthor = tail.lastOrNull()?.let { resolveAuthorName(it) }?.takeIf { it.isNotBlank() }
                    val titleFromParts = if (sname != null) tail.dropLast(if (tailAuthor != null) 1 else 0).joinToString(" ") else parts.joinToString(" ")
                    Four(sname, sidx, titleFromParts.takeIf { it.isNotBlank() }, tailAuthor ?: "")
                }
            return ParsedFilename(
                title = titleGuess ?: bracket,
                author = authorGuess ?: "",
                series = seriesName,
                seriesIndex = seriesIndex,
            )
        }
        // No comma: try bracket like [Discworld 29] or [Frank Herbert]
        val (sname, sidx) = parseSeriesToken(content)
        if (sname != null || sidx != null) {
            return ParsedFilename(
                title = "",
                author = "",
                series = sname,
                seriesIndex = sidx,
            )
        }
        val maybeAuthor = resolveAuthorName(content)
        if (maybeAuthor.isNotBlank() && (KNOWN_AUTHORS.containsKey(content.lowercase()) || content.all { !it.isDigit() } && content.length in 3..50)) {
            return ParsedFilename(title = "", author = maybeAuthor)
        }
        return null
    }

    private data class Four(val seriesName: String?, val seriesIndex: Double?, val titleGuess: String?, val authorGuess: String?)

    private fun parseSeriesToken(token: String): Pair<String?, Double?> {
        val t = token.cleanTitleOrAuthor()
        if (t.isBlank()) return null to null
        // Forms: "Dune 005" "Discworld 29" "Volume 3" "Vol. 4" "V.3" "Book 5" "Saga del Trono 12"
        val m1 = Regex("^(.+?)\\s*(?:Volume|Vol\\.?|V\\.?|Book|Part|Pt\\.?|Episode|Etape|Bok|Del|Tome|Band|Nummer|Nr\\.?|No\\.?)\\s*(\\d+(?:[.,]\\d+)?)\\s*$", RegexOption.IGNORE_CASE).find(t)
        if (m1 != null) {
            val name = m1.groupValues[1].cleanTitleOrAuthor()
            val idx = m1.groupValues[2].replace(',', '.').toDoubleOrNull()
            return (name.ifBlank { null }) to idx
        }
        // Simpler form: "<name> <digits>"  e.g. "Dune 005"
        val m2 = Regex("^(.+?)\\s+(\\d+(?:[.,]\\d+)?)\\s*$").find(t)
        if (m2 != null) {
            val name = m2.groupValues[1].cleanTitleOrAuthor()
            val idx = m2.groupValues[2].replace(',', '.').toDoubleOrNull()
            // Reject if "name" is entirely digits (pure index without name)
            if (name.isBlank() || name.all { !it.isLetter() }) return null to idx
            return name to idx
        }
        // Pure digits
        val justDigits = Regex("^\\s*(\\d+(?:[.,]\\d+)?)\\s*$").find(t)
        if (justDigits != null) {
            return null to justDigits.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        // Pure name (no digits yet — but may be series name we can match later)
        return t.takeIf { it.length in 2..80 && it.any(Char::isLetter) } to null
    }

    fun parse(rawFilename: String): ParsedFilename {
        val ext = Regex("\\.(epub|pdf|mobi|azw3|azw|fb2|cbz|cbr|txt|rtf|docx|md|html?)$", RegexOption.IGNORE_CASE)
        val base = ext.replace(rawFilename.trim(), "")

        // Collect bracket groups (content between []) in order
        val bracketContents: List<String> = SERIES_IN_BRACKETS_REGEX.findAll(base).map { it.groupValues[1].trim() }.toList()

        var author: String = ""
        var series: String? = null
        var seriesIndex: Double? = null
        var bracketTitleGuess: String? = null

        for (bc in bracketContents) {
            val parsed = parseBracketContent(bc) ?: continue
            if (parsed.author.isNotBlank() && author.isBlank()) author = parsed.author
            if (parsed.series != null && series == null) series = parsed.series
            if (parsed.seriesIndex != null && seriesIndex == null) seriesIndex = parsed.seriesIndex
            if (parsed.title.isNotBlank() && bracketTitleGuess == null) bracketTitleGuess = parsed.title
        }

        // Strip ALL brackets/noise from base for dash-split title extraction
        var remainder = base
        remainder = SERIES_IN_BRACKETS_REGEX.replace(remainder, " ")
        remainder = YEAR_IN_PARENS_REGEX.replace(remainder, " ")
        remainder = PAREN_NOISE_REGEX.replace(remainder, " ")
        remainder = remainder.replace("(", " ").replace(")", " ")
        remainder = remainder.replace("{", " ").replace("}", " ")
        remainder = NOISE_TAGS_REGEX.replace(remainder, " ")
        remainder = remainder.replace(Regex("\\s{2,}"), " ").trim()

        val titleSegments: List<String> = DASH_SPLIT_REGEX.split(remainder).map { it.cleanTitleOrAuthor() }.filter { it.isNotBlank() }

        // If author still unknown, try: first segment is "Author", second is "Title"
        if (author.isBlank() && titleSegments.size >= 2) {
            val first = titleSegments.first()
            val firstLower = first.lowercase()
            if (KNOWN_AUTHORS.containsKey(firstLower)) {
                author = resolveAuthorName(first)
            } else if (first.contains(" by ", ignoreCase = true)) {
                // "Title by Author"
                val m = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE).find(first)
                if (m != null) {
                    bracketTitleGuess = bracketTitleGuess ?: m.groupValues[1].cleanTitleOrAuthor()
                    author = resolveAuthorName(m.groupValues[2])
                }
            } else if (first.contains(',')) {
                // Comma: "Last, First" inside a segment? Treat as author
                val maybeAuthor = resolveAuthorName(first)
                if (maybeAuthor.isNotBlank() && first.contains(' ') && first.length >= 4) {
                    author = maybeAuthor
                }
            }
        }

        // "by Author" pattern anywhere in remainder
        if (author.isBlank()) {
            val mBy = Regex("\\bby\\s+([A-ZÆØÅ][^\\-–—]{2,80})$", RegexOption.IGNORE_CASE).find(remainder)
            if (mBy != null) {
                author = resolveAuthorName(mBy.groupValues[1])
                remainder = remainder.substring(0, mBy.range.first).cleanTitleOrAuthor()
            }
        }

        // If first segment is all-caps author acronym/surname like TOLKIEN, HERBERT etc
        if (author.isBlank() && titleSegments.size >= 2) {
            val first = titleSegments.first().trim()
            if (first.length in 3..20 && first.uppercase() == first && !first.any(Char::isDigit)) {
                val cand = KNOWN_AUTHORS[first.lowercase()]
                if (cand != null) author = cand
            }
        }

        // Pick title: prefer explicit bracketTitleGuess else first non-author dash-segment
        var title = bracketTitleGuess ?: ""
        if (title.isBlank()) {
            val remainingSegments = if (author.isNotBlank() && titleSegments.size > 1 && resolveAuthorName(titleSegments.first()) == author) {
                titleSegments.drop(1)
            } else {
                titleSegments
            }
            title = if (remainingSegments.isNotEmpty()) remainingSegments.joinToString(" ") else remainder
        }
        title = title.cleanNoise()
            .cleanTitleOrAuthor()
            .takeIf { it.isNotBlank() }
            ?: base.cleanNoise().cleanTitleOrAuthor()
                .takeIf { it.isNotBlank() }
            ?: rawFilename

        // Last chance: try to infer series from title if we have digits with name in it
        if (series == null || seriesIndex == null) {
            val (sn, si) = parseSeriesToken(title)
            if (series == null && sn != null) series = sn
            if (seriesIndex == null && si != null) seriesIndex = si
        }

        // Try once more to map short author names
        if (author.isNotBlank() && KNOWN_AUTHORS.containsKey(author.lowercase())) {
            author = KNOWN_AUTHORS[author.lowercase()]!!
        }

        return ParsedFilename(
            title = title.cleanTitleOrAuthor().ifBlank { base },
            author = author,
            series = series?.cleanTitleOrAuthor()?.takeIf { it.isNotBlank() },
            seriesIndex = seriesIndex,
        )
    }
}
