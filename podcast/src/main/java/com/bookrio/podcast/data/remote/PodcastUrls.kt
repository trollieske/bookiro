package com.bookrio.podcast.data.remote

import java.net.URI
import java.util.Locale

/**
 * Feed URL validation and normalization.
 *
 * A normalized feed URL is the unique subscription identity. Normalization is
 * conservative: it lowercases scheme/host, drops default ports and fragments,
 * and gives an empty path a single "/". It never touches path case or query
 * parameters, because those can be meaningful to a feed host.
 */
object PodcastUrls {

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        var host = uri.host?.lowercase(Locale.US) ?: return null
        if (host.isEmpty()) return null
        val port = uri.port
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val authority = when {
            port == -1 || defaultPort -> host
            else -> "$host:$port"
        }
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        host = authority
        return "$scheme://$host$path$query"
    }

    fun isAcceptable(raw: String?): Boolean = normalize(raw) != null

    /** True when a URL points at something that looks like an audio enclosure. */
    fun looksLikeAudio(url: String): Boolean {
        val path = runCatching { URI(url).path?.lowercase(Locale.US) }.getOrNull().orEmpty()
        val query = runCatching { URI(url).query?.lowercase(Locale.US) }.getOrNull().orEmpty()
        return AUDIO_EXTENSIONS.any { ext ->
            path.endsWith(".$ext") || query.contains(".$ext")
        }
    }

    private val AUDIO_EXTENSIONS = listOf(
        "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mpga", "mpeg"
    )
}