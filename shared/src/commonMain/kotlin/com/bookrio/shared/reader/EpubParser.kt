package com.bookrio.shared.reader

import kotlin.text.RegexOption.DOT_MATCHES_ALL
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Minimal, defensive EPUB (2/3) parser:
 *
 * 1. open the ZIP container,
 * 2. read `META-INF/container.xml` to locate the OPF,
 * 3. parse the OPF manifest/spine and read every spine document whose media type
 *    is XHTML/HTML relative to the OPF directory,
 * 4. reduce each XHTML document to readable plain text for pagination.
 *
 * Malformed input never throws: [parse] returns null instead.
 */
internal object EpubParser {

    fun parse(bytes: ByteArray): EpubBook? {
        val zip = EpubZip.open(bytes) ?: return null
        val container = zip.read(CONTAINER_PATH)?.decodeText() ?: return null
        val opfPath = rootfileRe.find(container)
            ?.groupValues?.get(1)
            ?.let { resolvePath("", percentDecode(it)) }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = zip.read(opfPath)?.decodeText() ?: return null

        val title = cleanInline(titleRe.find(opf)?.groupValues?.get(1)).orEmpty()
        val author = cleanInline(creatorRe.find(opf)?.groupValues?.get(1))?.takeIf { it.isNotBlank() }

        val manifest = LinkedHashMap<String, ManifestItem>()
        for (tag in itemRe.findAll(opf)) {
            val text = tag.value
            val id = attribute(text, "id") ?: continue
            val href = attribute(text, "href") ?: continue
            manifest[id] = ManifestItem(href, attribute(text, "media-type"))
        }
        if (manifest.isEmpty()) return null

        val chapters = ArrayList<EpubChapter>()
        for (tag in itemrefRe.findAll(opf)) {
            val idref = attribute(tag.value, "idref") ?: continue
            val item = manifest[idref] ?: continue
            if (!isReadableXhtml(item.mediaType)) continue
            val chapterPath = resolvePath(opfDir, item.href)
            val raw = zip.read(chapterPath)?.decodeText() ?: continue
            val text = xhtmlToPlainText(raw)
            if (text.isBlank()) continue
            chapters.add(EpubChapter(index = chapters.size, title = firstHeading(raw), text = text))
        }
        if (chapters.isEmpty()) return null
        return EpubBook(title = title, author = author, chapters = chapters)
    }
}

private data class ManifestItem(val href: String, val mediaType: String?)

/**
 * Turns one XHTML document into plain text: scripts/styles/head and every tag are
 * dropped (block tags become line breaks), common entities are decoded and
 * whitespace is collapsed. Nothing is truncated.
 */
internal fun xhtmlToPlainText(xhtml: String): String {
    var text = commentRe.replace(xhtml, " ")
    text = headRe.replace(text, "\n")
    text = scriptRe.replace(text, "\n")
    text = styleRe.replace(text, "\n")
    text = blockBreakRe.replace(text, "\n")
    text = tagRe.replace(text, "")
    text = decodeEntities(text)
    val lines = ArrayList<String>()
    for (rawLine in text.split('\n')) {
        val line = rawLine.replace(horizontalWhitespaceRe, " ").trim()
        if (line.isNotEmpty()) lines.add(line)
    }
    return lines.joinToString("\n")
}

private fun isReadableXhtml(mediaType: String?): Boolean =
    mediaType == null ||
        mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
        mediaType.equals("text/html", ignoreCase = true)

private fun firstHeading(xhtml: String): String? {
    val heading = cleanInline(headingRe.find(xhtml)?.groupValues?.get(1)) ?: return null
    return heading.takeIf { it.isNotBlank() }
}

private fun cleanInline(input: String?): String? {
    if (input == null) return null
    return decodeEntities(tagRe.replace(input, " "))
        .replace(horizontalWhitespaceRe, " ")
        .trim()
}

/** Reads an attribute from a single tag string, XML-unescaping its value. */
private fun attribute(tag: String, name: String): String? {
    val regex = Regex("(?:^|\\s)" + Regex.escape(name) + "\\s*=\\s*[\"']([^\"']*)[\"']", IGNORE_CASE)
    return regex.find(tag)?.groupValues?.get(1)?.let(::decodeEntities)?.takeIf { it.isNotBlank() }
}

/** Resolves an OPF-relative href against the OPF directory, dropping a fragment. */
private fun resolvePath(baseDir: String, href: String): String {
    val cleaned = href.substringBefore('#').substringBefore('?')
    if (cleaned.isEmpty()) return baseDir
    val segments = ArrayList<String>()
    if (!cleaned.startsWith('/') && baseDir.isNotEmpty()) {
        segments.addAll(baseDir.split('/').filter { it.isNotEmpty() })
    }
    for (segment in cleaned.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(percentDecode(segment))
        }
    }
    return segments.joinToString("/")
}

/** Decodes %XX escapes, keeping multi-byte UTF-8 sequences intact. */
private fun percentDecode(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                bytes.add(code.toByte())
                i += 3
                continue
            }
        }
        for (b in c.toString().encodeToByteArray()) bytes.add(b)
        i++
    }
    return bytes.toByteArray().decodeToString()
}

private val namedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "mdash" to "\u2014",
    "ndash" to "\u2013",
    "hellip" to "\u2026",
    "lsquo" to "\u2018",
    "rsquo" to "\u2019",
    "ldquo" to "\u201C",
    "rdquo" to "\u201D",
    "copy" to "\u00A9",
    "deg" to "\u00B0",
    "eacute" to "\u00E9",
    "egrave" to "\u00E8",
    "aring" to "\u00E5",
    "oslash" to "\u00F8",
    "aelig" to "\u00E6",
)

private fun decodeEntities(input: String): String {
    if ('&' !in input) return input
    return entityRe.replace(input) { match ->
        val body = match.groupValues[1]
        val decoded = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)?.let(::codePointToString)
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let(::codePointToString)
            else -> namedEntities[body.lowercase()]
        }
        decoded ?: match.value
    }
}

private fun codePointToString(codePoint: Int): String = when {
    codePoint in 0..0xFFFF -> codePoint.toChar().toString()
    codePoint in 0x10000..0x10FFFF -> {
        val value = codePoint - 0x10000
        val high = (0xD800 + (value shr 10)).toChar()
        val low = (0xDC00 + (value and 0x3FF)).toChar()
        "$high$low"
    }
    else -> "\uFFFD"
}

private fun ByteArray.decodeText(): String {
    val text = decodeToString()
    return if (text.startsWith('\uFEFF')) text.substring(1) else text
}

private const val CONTAINER_PATH = "META-INF/container.xml"

private val rootfileRe = Regex(
    "<rootfile\\b[^>]*full-path\\s*=\\s*[\"']([^\"']+)[\"']",
    IGNORE_CASE,
)
private val titleRe = Regex(
    "<(?:[A-Za-z0-9_]+:)?title\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?title\\s*>",
    setOf(DOT_MATCHES_ALL, IGNORE_CASE),
)
private val creatorRe = Regex(
    "<(?:[A-Za-z0-9_]+:)?creator\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?creator\\s*>",
    setOf(DOT_MATCHES_ALL, IGNORE_CASE),
)
private val itemRe = Regex("<item\\b[^>]*>", IGNORE_CASE)
private val itemrefRe = Regex("<itemref\\b[^>]*>", IGNORE_CASE)
private val headingRe = Regex("<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>", setOf(DOT_MATCHES_ALL, IGNORE_CASE))
private val commentRe = Regex("<!--.*?-->", DOT_MATCHES_ALL)
private val headRe = Regex("<head\\b[^>]*>.*?</head\\s*>", setOf(DOT_MATCHES_ALL, IGNORE_CASE))
private val scriptRe = Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(DOT_MATCHES_ALL, IGNORE_CASE))
private val styleRe = Regex("<style\\b[^>]*>.*?</style\\s*>", setOf(DOT_MATCHES_ALL, IGNORE_CASE))
private val blockBreakRe = Regex(
    "</?(?:p|div|br|h[1-6]|li|tr|blockquote|section|article|pre|table|ul|ol)\\b[^>]*>",
    IGNORE_CASE,
)
private val tagRe = Regex("<[^>]*>")
private val entityRe = Regex("&(#?[A-Za-z0-9]{1,8});")
private val horizontalWhitespaceRe = Regex("[ \\t\\u000B\\f\\r]+")