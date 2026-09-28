package com.bookrio.podcast.data.remote

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Recoverable network failure. Holds a stable code, never a raw exception. */
class PodcastNetworkException(val code: String, cause: Throwable? = null) : Exception(code, cause) {
    companion object {
        const val OFFLINE = "offline"
        const val TIMEOUT = "timeout"
        const val HTTP = "http"
        const val INVALID_URL = "invalid_url"
        const val TOO_LARGE = "too_large"
    }
}

/**
 * Minimal public-feed HTTP client built on [HttpURLConnection] (the same
 * primitive already used by Shelf's metadata fetchers). No API keys, no SDKs,
 * no tracking. HTTPS is preferred; http is tolerated only because some legacy
 * public feeds are http-only and Shelf does not globally enable cleartext.
 */
object PodcastHttp {

    private const val UA = "Shelf/1.0 (Android; podcast RSS reader)"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_FEED_BYTES = 8 * 1024 * 1024

    fun connection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
        conn.setRequestProperty("Accept-Encoding", "gzip")
        return conn
    }

    /** Fetches a feed body. Enforces a hard byte cap so a malicious feed cannot exhaust memory. */
    fun fetchFeedBody(url: String): String {
        val conn = try {
            connection(url)
        } catch (t: Throwable) {
            throw PodcastNetworkException(PodcastNetworkException.INVALID_URL, t)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw PodcastNetworkException(PodcastNetworkException.HTTP)
            }
            val stream = conn.inputStream
            return readLimited(stream, conn.contentEncoding, MAX_FEED_BYTES)
        } catch (e: PodcastNetworkException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("PodcastHttp", "feed fetch failed: ${t::class.java.simpleName}: ${t.message}")
            throw PodcastNetworkException(PodcastNetworkException.OFFLINE, t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Fetches a small JSON body (iTunes search/lookup). */
    fun fetchPlain(url: String): String {
        val conn = try {
            connection(url)
        } catch (t: Throwable) {
            throw PodcastNetworkException(PodcastNetworkException.INVALID_URL, t)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw PodcastNetworkException(PodcastNetworkException.HTTP)
            return readLimited(conn.inputStream, conn.contentEncoding, 4 * 1024 * 1024)
        } catch (e: PodcastNetworkException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w("PodcastHttp", "json fetch failed: ${t::class.java.simpleName}: ${t.message}")
            throw PodcastNetworkException(PodcastNetworkException.OFFLINE, t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun readLimited(stream: InputStream, encoding: String?, maxBytes: Int): String {
        val decoded = if (encoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(stream)
        } else {
            stream
        }
        BufferedInputStream(decoded).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) throw PodcastNetworkException(PodcastNetworkException.TOO_LARGE)
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}