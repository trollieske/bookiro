package com.bookrio.shared.player

/** One chapter of an audiobook, as persisted in `BookEntity.chaptersJson`. */
data class AudiobookChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long? = null,
    val mediaUri: String? = null,
)

/**
 * Parses the Android chapter JSON format (`[{index,title,startMs,endMs,mediaUri}]`)
 * with a tiny hand-rolled parser — no `org.json`, no kotlinx.serialization, so it
 * works in `commonMain` (iOS) without adding a dependency.
 *
 * Be defensive: blank, malformed or non-array input yields an empty list.
 */
object AudiobookChapters {

    fun parse(json: String?): List<AudiobookChapter> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            ChapterArrayParser(json).parseChapters()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The chapter covering [positionMs]: the last chapter whose [AudiobookChapter.startMs]
     * is at or before [positionMs]. Returns null when no chapter has started yet
     * (including an empty chapter list).
     */
    fun chapterAt(chapters: List<AudiobookChapter>, positionMs: Long): AudiobookChapter? {
        if (chapters.isEmpty()) return null
        var found: AudiobookChapter? = null
        for (chapter in chapters) {
            if (chapter.startMs <= positionMs && (found == null || chapter.startMs >= found.startMs)) {
                found = chapter
            }
        }
        return found
    }
}

private class ChapterParseException : Exception()

/**
 * Minimal recursive-descent JSON reader for a top-level array of flat objects.
 * Unknown keys/values are skipped generically so extra fields (e.g. `durationMs`)
 * never break parsing.
 */
private class ChapterArrayParser(private val src: String) {

    private var pos = 0

    fun parseChapters(): List<AudiobookChapter> {
        skipWhitespace()
        expect('[')
        val chapters = ArrayList<AudiobookChapter>()
        skipWhitespace()
        if (consume(']')) return chapters
        while (true) {
            skipWhitespace()
            val fields = parseObject()
            chapters += toChapter(fields, chapters.size)
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> return chapters
                else -> throw ChapterParseException()
            }
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val fields = HashMap<String, Any?>()
        skipWhitespace()
        if (consume('}')) return fields
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            fields[key] = parseValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume('}') -> return fields
                else -> throw ChapterParseException()
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val values = ArrayList<Any?>()
        skipWhitespace()
        if (consume(']')) return values
        while (true) {
            skipWhitespace()
            values += parseValue()
            skipWhitespace()
            when {
                consume(',') -> Unit
                consume(']') -> return values
                else -> throw ChapterParseException()
            }
        }
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (pos >= src.length) throw ChapterParseException()
        return when (val c = src[pos]) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> parseArray()
            't' -> {
                expectLiteral("true")
                true
            }
            'f' -> {
                expectLiteral("false")
                false
            }
            'n' -> {
                expectLiteral("null")
                null
            }
            else -> {
                if (c == '-' || c == '+' || c.isDigit()) parseNumber() else throw ChapterParseException()
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw ChapterParseException()
            when (val c = src[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) throw ChapterParseException()
                    when (val escaped = src[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = (0 until 4).map { _ ->
                                if (pos >= src.length) throw ChapterParseException()
                                val digit = src[pos++].digitToIntOrNull(16) ?: throw ChapterParseException()
                                digit
                            }
                            val code = hex.fold(0) { acc, digit -> acc * 16 + digit }
                            sb.append(code.toChar())
                        }
                        else -> throw ChapterParseException()
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Any {
        val start = pos
        if (src[pos] == '-' || src[pos] == '+') pos++
        var isFloating = false
        while (pos < src.length) {
            when (val c = src[pos]) {
                in '0'..'9' -> pos++
                '.', 'e', 'E' -> {
                    isFloating = true
                    pos++
                }
                else -> break
            }
        }
        val raw = src.substring(start, pos)
        if (raw.isEmpty() || raw == "-" || raw == "+") throw ChapterParseException()
        if (!isFloating) raw.toLongOrNull()?.let { return it }
        return raw.toDoubleOrNull() ?: throw ChapterParseException()
    }

    private fun expectLiteral(literal: String) {
        if (pos + literal.length > src.length) throw ChapterParseException()
        if (src.substring(pos, pos + literal.length) != literal) throw ChapterParseException()
        pos += literal.length
    }

    private fun expect(c: Char) {
        if (!consume(c)) throw ChapterParseException()
    }

    private fun consume(c: Char): Boolean {
        if (pos < src.length && src[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun toChapter(fields: Map<String, Any?>, fallbackIndex: Int): AudiobookChapter {
        val index = fields["index"].asLong()?.toInt() ?: fallbackIndex
        val title = fields["title"] as? String ?: ""
        val startMs = fields["startMs"].asLong()?.coerceAtLeast(0L) ?: 0L
        // The Android writer persists a missing end as JSON null (and 0 means "unknown"),
        // so both map to a null end here.
        val endMs = fields["endMs"].asLong()?.takeIf { it > 0L }
        val mediaUri = (fields["mediaUri"] as? String)?.takeIf { it.isNotBlank() }
        return AudiobookChapter(
            index = index,
            title = title,
            startMs = startMs,
            endMs = endMs,
            mediaUri = mediaUri,
        )
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        is Double -> if (isFinite()) toLong() else null
        is String -> toLongOrNull()
        else -> null
    }
}