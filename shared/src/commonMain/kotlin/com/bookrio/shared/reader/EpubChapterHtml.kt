package com.bookrio.shared.reader

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Builds the HTML the reader displays for one EPUB spine document.
 *
 * This mirrors the Android content pipeline exactly (`BookFormatParsers.kt`):
 * every `<img>` / `<image>` whose source is a relative archive path is inlined
 * as a `data:` URI (base64), then only the `<body>` inner markup is kept and
 * wrapped in `<section>`. The Android reader feeds that same shape into
 * `HtmlPageRenderer`, so both platforms paginate/typography the identical markup
 * with the identical reader CSS ([buildEpubReaderHtml]).
 *
 * Plain text is never rendered.
 */
internal fun xhtmlToChapterHtml(raw: String, zip: EpubZip?, chapterPath: String): String {
    val withImages = if (zip != null && IMAGE_TAG_RE.containsMatchIn(raw)) {
        embedImagesAsDataUris(raw, zip, chapterPath)
    } else {
        raw
    }
    return "<section>${xhtmlBodyInner(withImages)}</section>"
}

/**
 * Fallback used only when a chapter carries no HTML (defensive; [EpubParser]
 * always produces HTML): escapes [text] into paragraph markup so the reader can
 * still render formatted content instead of raw text.
 */
internal fun epubFallbackHtml(text: String): String {
    if (text.isBlank()) return ""
    return text.split('\n')
        .filter { it.isNotBlank() }
        .joinToString(separator = "") { "<p>${escapeHtml(it.trim())}</p>" }
}

/** Keeps only the inner markup of `<body>…</body>`; anything else is returned as-is. */
private fun xhtmlBodyInner(raw: String): String {
    return try {
        val lower = raw.lowercase()
        val bodyStart = lower.indexOf("<body")
        val bodyEnd = lower.lastIndexOf("</body>")
        if (bodyStart >= 0 && bodyEnd > bodyStart) {
            val tagEnd = raw.indexOf('>', bodyStart)
            if (tagEnd > 0 && tagEnd < bodyEnd) raw.substring(tagEnd + 1, bodyEnd) else raw
        } else {
            raw
        }
    } catch (t: Throwable) {
        raw
    }
}

/** Inlines every relative image source as a base64 `data:` URI, like the Android parser. */
private fun embedImagesAsDataUris(rawHtml: String, zip: EpubZip, chapterPath: String): String {
    fun replace(match: MatchResult): String {
        val prefix = match.groupValues[1]
        val relative = match.groupValues[2]
        val suffix = match.groupValues[3]
        if (relative.startsWith("data:") || relative.startsWith("http://") || relative.startsWith("https://")) {
            return match.value
        }
        val baseDir = chapterPath.substringBeforeLast('/', "")
        val resolved = resolvePath(baseDir, relative)
        val bytes = zip.read(resolved)?.takeIf { it.isNotEmpty() } ?: return match.value
        return prefix + "data:${mimeTypeFor(resolved)};base64,${base64Encode(bytes)}$suffix"
    }
    var result = IMG_SRC_RE.replace(rawHtml, ::replace)
    result = XLINK_HREF_RE.replace(result, ::replace)
    return result
}

private fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "png").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "webp" -> "image/webp"
    else -> "image/png"
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard base64 with padding (`Base64.NO_WRAP` semantics), no platform calls. */
internal fun base64Encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val triple = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
        out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
        out.append(BASE64_ALPHABET[(triple ushr 6) and 0x3F])
        out.append(BASE64_ALPHABET[triple and 0x3F])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val single = (bytes[i].toInt() and 0xFF) shl 16
            out.append(BASE64_ALPHABET[(single ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(single ushr 12) and 0x3F])
            out.append("==")
        }
        2 -> {
            val pair = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out.append(BASE64_ALPHABET[(pair ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(pair ushr 12) and 0x3F])
            out.append(BASE64_ALPHABET[(pair ushr 6) and 0x3F])
            out.append("=")
        }
    }
    return out.toString()
}

/** Minimal HTML text escaping for the plain-text fallback path. */
internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

private val IMAGE_TAG_RE = Regex("<(?:img|image)\\b", IGNORE_CASE)
private val IMG_SRC_RE = Regex("""(<img[^>]+src=["'])([^"']+)(["'])""", IGNORE_CASE)
private val XLINK_HREF_RE = Regex("""(<image[^>]+(?:xlink:href|href)=["'])([^"']+)(["'])""", IGNORE_CASE)