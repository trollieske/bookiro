package com.bookrio.shared.podcast

/**
 * Pure-Kotlin feed URL helpers: the multiplatform port of the Android
 * `com.bookrio.podcast.data.remote.PodcastUrls`.
 *
 * Normalization is conservative: lowercase the scheme/host, drop default ports
 * and fragments, and give an empty path a single "/". Path case and query
 * parameters are never touched because they can be meaningful to a feed host.
 */
internal object PodcastUrls {

    private val AUDIO_EXTENSIONS = listOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mpga", "mpeg"
    )

    /**
     * Normalizes a user-supplied feed URL. Missing scheme defaults to `https`;
     * anything that is not clearly http(s) with a non-empty host returns null.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isWhitespace() }) return null

        val scheme: String
        val remainder: String
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator > 0) {
            scheme = trimmed.substring(0, schemeSeparator).lowercase()
            remainder = trimmed.substring(schemeSeparator + 3)
        } else {
            val firstDelimiter = trimmed.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val head = if (firstDelimiter < 0) trimmed else trimmed.substring(0, firstDelimiter)
            if (head.contains(':')) return null
            scheme = "https"
            remainder = trimmed.removePrefix("//")
        }
        if (scheme != "http" && scheme != "https") return null

        val withoutFragment = remainder.substringBefore('#')
        if (withoutFragment.isEmpty()) return null

        val authorityEnd = withoutFragment.indexOfFirst { it == '/' || it == '?' }
        val authorityRaw =
            if (authorityEnd < 0) withoutFragment else withoutFragment.substring(0, authorityEnd)
        val pathAndQuery = if (authorityEnd < 0) "" else withoutFragment.substring(authorityEnd)

        val at = authorityRaw.lastIndexOf('@')
        val authority = if (at >= 0) authorityRaw.substring(at + 1) else authorityRaw
        if (authority.isEmpty()) return null

        val colon = authority.lastIndexOf(':')
        val host = (if (colon >= 0) authority.substring(0, colon) else authority).lowercase()
        val port = if (colon >= 0) authority.substring(colon + 1).toIntOrNull() else null
        if (host.isEmpty()) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null

        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val authorityOut = if (port == null || defaultPort) host else "$host:$port"
        val path = when {
            pathAndQuery.isEmpty() -> "/"
            pathAndQuery.startsWith("/") -> pathAndQuery
            else -> "/$pathAndQuery"
        }
        return "$scheme://$authorityOut$path"
    }

    /** True when [url] points at something that looks like an audio enclosure. */
    fun looksLikeAudio(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val query = if ('?' in url) url.substringAfter('?').substringBefore('#').lowercase() else ""
        return AUDIO_EXTENSIONS.any { ext -> path.endsWith(".$ext") || query.contains(".$ext") }
    }

    /**
     * Resolves a (possibly relative, possibly percent-encoded) candidate URL
     * against [base]. Absolute http(s) URLs pass through; the result is
     * percent-decoded.
     */
    fun resolve(base: String, candidate: String?): String? {
        val c = candidate?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = c.lowercase()
        val resolved = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> c
            lower.startsWith("//") ->
                (if (base.lowercase().startsWith("https://")) "https:" else "http:") + c
            c.startsWith("/") -> {
                val origin = originOf(base) ?: return percentDecode(c)
                origin + c
            }
            else -> {
                val origin = originOf(base) ?: return percentDecode(c)
                val afterScheme = base.substringAfter("://", "")
                val firstSlash = afterScheme.indexOf('/')
                val basePath = if (firstSlash < 0) {
                    "/"
                } else {
                    afterScheme.substring(firstSlash).substringBefore('?').substringBefore('#')
                }
                joinRelative(origin, basePath, c)
            }
        }
        return percentDecode(resolved)
    }

    /** Decodes `%XX` escapes (UTF-8 byte sequences) without touching other characters. */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        val buffer = ArrayList<Byte>()
        fun flush() {
            if (buffer.isEmpty()) return
            out.append(buffer.toByteArray().decodeToString())
            buffer.clear()
        }
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length && isHex(value[i + 1]) && isHex(value[i + 2])) {
                buffer.add(((hexValue(value[i + 1]) shl 4) or hexValue(value[i + 2])).toByte())
                i += 3
            } else {
                flush()
                out.append(ch)
                i++
            }
        }
        flush()
        return out.toString()
    }

    private fun joinRelative(origin: String, basePath: String, relative: String): String {
        val queryIndex = relative.indexOf('?')
        val withoutQuery = if (queryIndex >= 0) relative.substring(0, queryIndex) else relative
        val cleanRelative = withoutQuery.substringBefore('#')
        val segments = ArrayList<String>()
        val baseDir =
            if (basePath.endsWith("/")) basePath else basePath.substringBeforeLast('/', "/")
        for (segment in baseDir.split('/')) if (segment.isNotEmpty()) segments.add(segment)
        for (segment in cleanRelative.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        val path = "/" + segments.joinToString("/")
        if (queryIndex < 0) return origin + path
        val query = relative.substring(queryIndex + 1).substringBefore('#')
        return if (query.isEmpty()) origin + path else "$origin$path?$query"
    }

    private fun originOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx <= 0) return null
        val rest = url.substring(idx + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        if (authority.isEmpty()) return null
        return url.substring(0, idx + 3) + authority
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> c - 'A' + 10
    }
}