package com.bookrio.shared.podcast

/**
 * Pure-Kotlin RSS 2.0 / Atom podcast parser (no XML pull parser, no java.*,
 * no org.json — it compiles for every KMP target).
 *
 * Handles:
 * - RSS: `<channel><title>`, `<item>` with `<title>`, `<guid>`, `<enclosure>`,
 *   `<pubDate>`, `<itunes:duration>` (`HH:MM:SS` or seconds), `<itunes:image href>`.
 * - Atom: `<feed><entry>` with `<id>`, `<title>`, `<link rel="enclosure" href type>`,
 *   `<published>`, `<summary>`.
 * - Relative and percent-encoded enclosure URLs.
 * - RFC-822 (`Tue, 01 Oct 2024 10:00:00 GMT`) and ISO-8601 dates, converted to
 *   epoch millis by hand.
 *
 * Malformed items are skipped; the parser only throws [IllegalArgumentException]
 * when the document is not a feed or contains zero usable audio episodes.
 */
object PodcastFeedParser {

    private const val AUDIO_MIME_PREFIX = "audio/"
    private const val VIDEO_MIME_PREFIX = "video/"

    fun parse(xml: String, feedUrl: String): ParsedPodcastFeed {
        if (xml.isBlank()) throw IllegalArgumentException("malformed")
        return try {
            parseDocument(xml, feedUrl)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalArgumentException("malformed", t)
        }
    }

    /** True when the enclosure type/URL identifies playable audio. */
    fun isPlayableAudio(type: String?, url: String): Boolean {
        val mime = type?.substringBefore(';')?.trim()?.lowercase() ?: ""
        if (mime.isNotEmpty()) {
            if (mime.startsWith(VIDEO_MIME_PREFIX)) return false
            if (mime.startsWith(AUDIO_MIME_PREFIX)) return true
            if (mime == "application/ogg" || mime == "application/octet-stream") {
                return PodcastUrls.looksLikeAudio(url)
            }
            return false
        }
        return PodcastUrls.looksLikeAudio(url)
    }

    /** Parses `HH:MM:SS`, `MM:SS`, or plain seconds into millis. */
    fun parseDurationMs(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = value.split(':')
        val millis = when (parts.size) {
            1 -> value.toLongOrNull()?.times(1000L)
            2 -> {
                val minutes = parts[0].trim().toLongOrNull() ?: return null
                val seconds = parts[1].trim().toLongOrNull() ?: return null
                (minutes * 60L + seconds) * 1000L
            }
            3 -> {
                val hours = parts[0].trim().toLongOrNull() ?: return null
                val minutes = parts[1].trim().toLongOrNull() ?: return null
                val seconds = parts[2].trim().toLongOrNull() ?: return null
                (hours * 3600L + minutes * 60L + seconds) * 1000L
            }
            else -> null
        }
        return millis?.takeIf { it > 0L }
    }

    /** Best-effort RFC-822 / ISO-8601 date parsing to epoch millis. Never throws. */
    fun parseDate(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseIso8601(value)?.let { return it }
        return parseRfc822(value)
    }

    // ---- document ------------------------------------------------------------------

    private fun parseDocument(xml: String, feedUrl: String): ParsedPodcastFeed {
        val tokens = tokenize(xml)
        if (tokens.isEmpty()) throw IllegalArgumentException("malformed")
        val depths = computeDepths(tokens)

        var rootSeen = false
        var channelTitle: String? = null
        var itunesTitle: String? = null
        var author: String? = null
        var description: String? = null
        var summary: String? = null
        var artwork: String? = null
        var language: String? = null
        val episodes = ArrayList<ParsedPodcastEpisode>()

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.kind == TokenKind.START || token.kind == TokenKind.SELF_CLOSING) {
                val local = token.localName
                if (local == "rss" || local == "feed" || local == "channel") rootSeen = true

                if (token.kind == TokenKind.START && (local == "item" || local == "entry")) {
                    val end = findMatchingEnd(tokens, depths, i)
                    if (end > i) {
                        // A single bad episode must never fail the whole feed.
                        runCatching { parseEpisode(tokens, depths, i, end, feedUrl) }
                            .getOrNull()
                            ?.let { episodes.add(it) }
                        i = end + 1
                        continue
                    }
                } else if (depths[i] <= 2) {
                    when (token.fullName) {
                        "title" ->
                            if (channelTitle.isNullOrBlank()) channelTitle = elementText(tokens, depths, i)
                        "itunes:title" ->
                            if (itunesTitle.isNullOrBlank()) itunesTitle = elementText(tokens, depths, i)
                        "itunes:author" ->
                            if (author.isNullOrBlank()) author = elementText(tokens, depths, i)
                        "author" -> if (author.isNullOrBlank()) {
                            author = descendantText(tokens, depths, i, setOf("name"))
                                .ifBlank { elementText(tokens, depths, i) }
                        }
                        "description" ->
                            if (description.isNullOrBlank()) description = elementText(tokens, depths, i)
                        "itunes:summary", "itunes:subtitle" ->
                            if (summary.isNullOrBlank()) summary = elementText(tokens, depths, i)
                        "language" ->
                            if (language.isNullOrBlank()) language = elementText(tokens, depths, i)
                        "itunes:image" -> {
                            val href = token.attributes["href"]
                            if (artwork.isNullOrBlank() && !href.isNullOrBlank()) artwork = href.trim()
                        }
                        "image" -> if (artwork.isNullOrBlank()) {
                            val url = descendantText(tokens, depths, i, setOf("url"))
                                .ifBlank { elementText(tokens, depths, i) }
                            if (url.isNotBlank()) artwork = url
                        }
                        else -> Unit
                    }
                }
            }
            i++
        }

        if (!rootSeen || episodes.isEmpty()) throw IllegalArgumentException("no_audio")

        val title = (channelTitle ?: itunesTitle ?: feedUrl).trim().ifBlank { feedUrl }
        return ParsedPodcastFeed(
            feedUrl = feedUrl,
            title = title,
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            description = (description ?: summary)?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = PodcastUrls.resolve(feedUrl, artwork),
            language = language?.trim()?.takeIf { it.isNotEmpty() },
            episodes = episodes,
        )
    }

    private fun parseEpisode(
        tokens: List<Token>,
        depths: IntArray,
        start: Int,
        end: Int,
        feedUrl: String,
    ): ParsedPodcastEpisode? {
        var guid: String? = null
        var title: String? = null
        var description: String? = null
        var published: String? = null
        var duration: String? = null
        var artwork: String? = null
        var enclosureUrl: String? = null
        var enclosureType: String? = null

        val childDepth = depths[start] + 1
        var i = start + 1
        while (i < end) {
            val token = tokens[i]
            if (depths[i] == childDepth &&
                (token.kind == TokenKind.START || token.kind == TokenKind.SELF_CLOSING)
            ) {
                when (token.fullName) {
                    "guid", "id" ->
                        if (guid.isNullOrBlank()) guid = elementText(tokens, depths, i)
                    "title", "itunes:title" ->
                        if (title.isNullOrBlank()) title = elementText(tokens, depths, i)
                    "description", "itunes:summary", "itunes:subtitle", "summary", "content" ->
                        if (description.isNullOrBlank()) description = elementText(tokens, depths, i)
                    "pubdate", "published", "updated", "itunes:pubdate" ->
                        if (published.isNullOrBlank()) published = elementText(tokens, depths, i)
                    "itunes:duration" ->
                        if (duration.isNullOrBlank()) duration = elementText(tokens, depths, i)
                    "itunes:image" -> {
                        val href = token.attributes["href"]
                        if (artwork.isNullOrBlank() && !href.isNullOrBlank()) artwork = href.trim()
                    }
                    "image" -> if (artwork.isNullOrBlank()) {
                        val url = descendantText(tokens, depths, i, setOf("url", "href"))
                            .ifBlank { elementText(tokens, depths, i) }
                        if (url.isNotBlank()) artwork = url
                    }
                    "enclosure" -> {
                        if (enclosureUrl == null) {
                            val url = token.attributes["url"]
                            val type = token.attributes["type"]
                            if (!url.isNullOrBlank() && isPlayableAudio(type, url)) {
                                enclosureUrl = url.trim()
                                enclosureType = type?.trim()?.takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                    "link" -> {
                        if (enclosureUrl == null) {
                            val rel = token.attributes["rel"]
                            val href = token.attributes["href"]
                            val type = token.attributes["type"]
                            if (!href.isNullOrBlank() &&
                                (rel == null || rel == "enclosure") &&
                                isPlayableAudio(type, href)
                            ) {
                                enclosureUrl = href.trim()
                                enclosureType = type?.trim()?.takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            i++
        }

        val rawUrl = enclosureUrl ?: return null
        val audioUrl = PodcastUrls.resolve(feedUrl, rawUrl) ?: return null
        val fallbackTitle = audioUrl.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: audioUrl
        return ParsedPodcastEpisode(
            guid = guid?.trim()?.takeIf { it.isNotEmpty() },
            enclosureUrl = audioUrl,
            enclosureMimeType = enclosureType,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
            description = description?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = PodcastUrls.resolve(audioUrl, artwork),
            publishedAt = parseDate(published),
            durationMs = parseDurationMs(duration),
        )
    }

    // ---- token tree helpers ----------------------------------------------------------

    private enum class TokenKind { START, END, SELF_CLOSING }

    private class Token(
        val kind: TokenKind,
        val fullName: String,
        val attributes: Map<String, String>,
        /** Decoded text between the previous token and this one (element text for END). */
        val text: String,
    ) {
        val localName: String get() = fullName.substringAfterLast(':')
    }

    private fun tokenize(xml: String): List<Token> {
        val tokens = ArrayList<Token>()
        val openElements = ArrayList<StringBuilder>()
        var pending = StringBuilder()

        fun flushText() {
            if (pending.isEmpty()) return
            openElements.lastOrNull()?.append(pending)
            pending = StringBuilder()
        }

        fun emit(kind: TokenKind, name: String, attributes: Map<String, String>) {
            flushText()
            val text = if (kind == TokenKind.END) {
                val content = openElements.removeLastOrNull()?.toString().orEmpty()
                openElements.lastOrNull()?.append(content)
                content
            } else {
                ""
            }
            tokens.add(Token(kind, name, attributes, text))
            if (kind == TokenKind.START) openElements.add(StringBuilder())
        }

        var i = 0
        val length = xml.length
        while (i < length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                pending.append(decodeEntities(xml.substring(i)))
                break
            }
            if (lt > i) pending.append(decodeEntities(xml.substring(i, lt)))

            when {
                xml.startsWith("<!--", lt) -> {
                    val end = xml.indexOf("-->", lt + 4)
                    i = if (end < 0) length else end + 3
                }
                xml.startsWith("<![CDATA[", lt) -> {
                    val end = xml.indexOf("]]>", lt + 9)
                    val content = if (end < 0) xml.substring(lt + 9) else xml.substring(lt + 9, end)
                    pending.append(content)
                    i = if (end < 0) length else end + 3
                }
                xml.startsWith("<?", lt) -> {
                    val end = xml.indexOf("?>", lt + 2)
                    i = if (end < 0) length else end + 2
                }
                xml.startsWith("<!", lt) -> {
                    val end = xml.indexOf('>', lt + 2)
                    i = if (end < 0) length else end + 1
                }
                else -> {
                    val gt = findTagEnd(xml, lt + 1)
                    if (gt < 0) {
                        pending.append(decodeEntities(xml.substring(lt)))
                        break
                    }
                    val raw = xml.substring(lt + 1, gt).trim()
                    if (raw.startsWith("/")) {
                        val name = raw.substring(1).trim().lowercase()
                        if (name.isNotEmpty()) emit(TokenKind.END, name, emptyMap())
                    } else {
                        val selfClosing = raw.endsWith("/")
                        val body = if (selfClosing) raw.dropLast(1) else raw
                        val name = body.takeWhile { !it.isWhitespace() }.lowercase()
                        if (name.isNotEmpty() && !name.startsWith("!")) {
                            emit(
                                if (selfClosing) TokenKind.SELF_CLOSING else TokenKind.START,
                                name,
                                parseAttributes(body),
                            )
                        }
                    }
                    i = gt + 1
                }
            }
        }
        return tokens
    }

    private fun findTagEnd(xml: String, start: Int): Int {
        var i = start
        var quote = '\u0000'
        while (i < xml.length) {
            val c = xml[i]
            if (quote == '\u0000') {
                if (c == '"' || c == '\'') quote = c
                else if (c == '>') return i
            } else if (c == quote) {
                quote = '\u0000'
            }
            i++
        }
        return -1
    }

    private fun parseAttributes(body: String): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        var i = 0
        val length = body.length
        while (i < length && !body[i].isWhitespace()) i++
        while (i < length) {
            while (i < length && body[i].isWhitespace()) i++
            val nameStart = i
            while (i < length && !body[i].isWhitespace() && body[i] != '=') i++
            val name = body.substring(nameStart, i).lowercase()
            while (i < length && body[i].isWhitespace()) i++
            if (i < length && body[i] == '=') {
                i++
                while (i < length && body[i].isWhitespace()) i++
                if (i < length && (body[i] == '"' || body[i] == '\'')) {
                    val quote = body[i]
                    i++
                    val valueStart = i
                    while (i < length && body[i] != quote) i++
                    attributes[name] = decodeEntities(body.substring(valueStart, i))
                    if (i < length) i++
                } else {
                    val valueStart = i
                    while (i < length && !body[i].isWhitespace()) i++
                    attributes[name] = decodeEntities(body.substring(valueStart, i))
                }
            } else if (name.isNotEmpty()) {
                attributes[name] = ""
            }
        }
        return attributes
    }

    private fun computeDepths(tokens: List<Token>): IntArray {
        val depths = IntArray(tokens.size)
        var depth = 0
        for (i in tokens.indices) {
            when (tokens[i].kind) {
                TokenKind.START -> {
                    depths[i] = depth
                    depth++
                }
                TokenKind.END -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    depths[i] = depth
                }
                TokenKind.SELF_CLOSING -> depths[i] = depth
            }
        }
        return depths
    }

    private fun findMatchingEnd(tokens: List<Token>, depths: IntArray, start: Int): Int {
        val target = depths[start]
        for (j in start + 1 until tokens.size) {
            if (tokens[j].kind == TokenKind.END && depths[j] == target) return j
        }
        return -1
    }

    private fun elementText(tokens: List<Token>, depths: IntArray, index: Int): String =
        when (tokens[index].kind) {
            TokenKind.SELF_CLOSING -> ""
            TokenKind.END -> tokens[index].text
            TokenKind.START -> {
                val end = findMatchingEnd(tokens, depths, index)
                if (end > index) tokens[end].text else ""
            }
        }

    private fun descendantText(
        tokens: List<Token>,
        depths: IntArray,
        index: Int,
        localNames: Set<String>,
    ): String {
        if (tokens[index].kind != TokenKind.START) return ""
        val end = findMatchingEnd(tokens, depths, index)
        if (end < 0) return ""
        for (j in index + 1 until end) {
            val token = tokens[j]
            if ((token.kind == TokenKind.START || token.kind == TokenKind.SELF_CLOSING) &&
                token.localName in localNames
            ) {
                val text = elementText(tokens, depths, j)
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    // ---- entities --------------------------------------------------------------------

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '&') {
                val semicolon = value.indexOf(';', i + 1)
                if (semicolon in (i + 1)..(i + 12)) {
                    val entity = value.substring(i + 1, semicolon)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        out.append(decoded)
                        i = semicolon + 1
                        continue
                    }
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? {
        when (entity.lowercase()) {
            "amp" -> return "&"
            "lt" -> return "<"
            "gt" -> return ">"
            "quot" -> return "\""
            "apos" -> return "'"
            "nbsp" -> return "\u00A0"
        }
        if (!entity.startsWith("#")) return null
        val codePoint = if (entity.startsWith("#x", ignoreCase = true)) {
            entity.substring(2).toIntOrNull(16)
        } else {
            entity.substring(1).toIntOrNull()
        } ?: return null
        if (codePoint !in 0..0x10FFFF) return null
        if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        val high = (0xD800 + (offset shr 10)).toChar()
        val low = (0xDC00 + (offset and 0x3FF)).toChar()
        return "$high$low"
    }

    // ---- dates -----------------------------------------------------------------------

    private val ISO_8601 = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|z|[+-]\d{2}:?\d{2})?$"""
    )

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    private val ZONE_NAMES = mapOf(
        "ut" to 0, "utc" to 0, "gmt" to 0, "z" to 0,
        "est" to -300, "edt" to -240, "cst" to -360, "cdt" to -300,
        "mst" to -420, "mdt" to -360, "pst" to -480, "pdt" to -420,
        "bst" to 60, "cet" to 60, "cest" to 120, "eet" to 120, "eest" to 180,
        "jst" to 540, "ist" to 330, "aest" to 600, "aedt" to 660,
        "nzst" to 720, "nzdt" to 780
    )

    private fun parseIso8601(value: String): Long? {
        val match = ISO_8601.matchEntire(value) ?: return null
        val groups = match.groupValues
        val year = groups[1].toIntOrNull() ?: return null
        val month = groups[2].toIntOrNull() ?: return null
        val day = groups[3].toIntOrNull() ?: return null
        val hour = groups[4].toIntOrNull() ?: return null
        val minute = groups[5].toIntOrNull() ?: return null
        val second = groups[6].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        val zone = groups[7]
        val offsetMinutes = when {
            zone.isEmpty() -> 0
            zone == "Z" || zone == "z" -> 0
            else -> parseOffset(zone) ?: return null
        }
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 ||
            second !in 0..60
        ) {
            return null
        }
        val days = daysFromCivil(year, month, day)
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L -
            offsetMinutes * 60_000L
    }

    private fun parseRfc822(value: String): Long? {
        val cleaned = value.replace(',', ' ').replace(';', ' ')
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        var index = 0
        if (parts[0].toIntOrNull() == null) index = 1
        if (index + 2 >= parts.size) return null

        val day = parts[index].toIntOrNull() ?: return null
        val month = MONTHS[parts[index + 1].lowercase()] ?: return null
        var year = parts[index + 2].toIntOrNull() ?: return null
        if (year < 100) year += if (year >= 50) 1900 else 2000
        index += 3

        var hour = 0
        var minute = 0
        var second = 0
        if (index < parts.size && ':' in parts[index]) {
            val time = parts[index].split(':')
            hour = time.getOrNull(0)?.toIntOrNull() ?: return null
            minute = time.getOrNull(1)?.toIntOrNull() ?: return null
            second = time.getOrNull(2)?.toIntOrNull() ?: 0
            index++
        }
        val offsetMinutes = if (index < parts.size) parseZone(parts[index]) ?: 0 else 0

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 ||
            second !in 0..60
        ) {
            return null
        }
        val days = daysFromCivil(year, month, day)
        return days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1000L -
            offsetMinutes * 60_000L
    }

    private fun parseZone(raw: String): Int? {
        if (raw.isEmpty()) return null
        return when (raw[0]) {
            '+', '-' -> parseOffset(raw)
            else -> ZONE_NAMES[raw.lowercase()]
        }
    }

    private fun parseOffset(raw: String): Int? {
        val sign = when (raw.getOrNull(0)) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val digits = raw.substring(1).replace(":", "")
        if (digits.length != 4 || digits.any { !it.isDigit() }) return null
        val hours = digits.substring(0, 2).toInt()
        val minutes = digits.substring(2, 4).toInt()
        if (hours > 23 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }

    /** Days since 1970-01-01 (Howard Hinnant's civil-from-days algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year
        if (month <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}