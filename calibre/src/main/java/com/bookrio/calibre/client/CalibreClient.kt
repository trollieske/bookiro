package com.bookrio.calibre.client

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CalibreDownloadResult(
    val bytesOnDisk: Long,
    val totalBytes: Long?,
    val resumed: Boolean
)

/**
 * A real Calibre Content Server client.
 *
 * It talks to the stable OPDS interface (`/opds`, `/opds/search/...`,
 * acquisition links) rather than screen-scraping HTML. Authentication is
 * Basic or Digest (Calibre's `--enable-auth`). TLS verification is never
 * disabled. Redirects are followed manually so credentials are never forwarded
 * to a different origin.
 */
class CalibreClient(
    baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    userAgent: String = DEFAULT_USER_AGENT,
    connectTimeoutMs: Long = 15_000,
    readTimeoutMs: Long = 60_000
) {

    val rootUrl: String
    val opdsUrl: String

    private val http: OkHttpClient

    init {
        val trimmed = baseUrl.trim().trimEnd('/')
        rootUrl = if (trimmed.endsWith("/opds", ignoreCase = true)) {
            trimmed.substring(0, trimmed.length - "/opds".length)
        } else {
            trimmed
        }
        opdsUrl = "$rootUrl/opds"

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.1")
                        .build()
                )
            }
        if (username.isNotBlank() || password.isNotBlank()) {
            builder.authenticator(ShelfAuthenticator(username, password))
        }
        http = builder.build()
    }

    /** Loads and parses an OPDS feed. */
    fun loadFeed(url: String = opdsUrl): OpdsFeed {
        val body = getString(url)
        return try {
            OpdsFeedParser.parse(body, url)
        } catch (e: OpdsParseException) {
            throw CalibreException(
                CalibreErrorKind.PARSE,
                "The server did not return a valid OPDS feed",
                e
            )
        }
    }

    /** Verifies that the server is reachable and speaks OPDS. */
    fun probe(): OpdsFeed = loadFeed(opdsUrl)

    /** Runs an OpenSearch query. `template` comes from the feed's search link. */
    fun search(query: String, template: String = "$rootUrl/opds/search/{searchTerms}"): OpdsFeed {
        val encoded = java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = when {
            template.contains("{searchTerms}") -> template.replace("{searchTerms}", encoded)
            template.contains("?") -> "$template$encoded"
            else -> "$template/$encoded"
        }
        return loadFeed(url)
    }

    /**
     * Downloads [href] into [target]. When [offset] > 0 and the server supports
     * Range a `206 Partial Content` is appended; if the server responds with a
     * full `200` the file is restarted and [CalibreDownloadResult.resumed] is
     * false so the caller can tell the user honestly.
     */
    suspend fun download(
        href: String,
        target: File,
        offset: Long = 0L,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): CalibreDownloadResult = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()

        val useRange = offset > 0 && target.exists() && target.length() == offset
        val requestBuilder = Request.Builder().url(href).get()
        if (useRange) requestBuilder.header("Range", "bytes=$offset-")

        execute(requestBuilder.build()).use { response ->
            when (response.code) {
                416 -> {
                    // Range not satisfiable: if the part is already the full size
                    // the caller can verify and finish; otherwise fall back.
                    val total = contentRangeTotal(response) ?: -1L
                    if (useRange && total == offset) {
                        CalibreDownloadResult(offset, total, resumed = true)
                    } else {
                        if (target.exists()) target.delete()
                        download(href, target, 0L, onProgress)
                    }
                }
                200, 206 -> {
                    val resumed = useRange && response.code == 206
                    val body = response.body
                        ?: throw CalibreException(CalibreErrorKind.SERVER, "Empty response body")
                    val total = when {
                        response.code == 206 -> contentRangeTotal(response)
                        else -> body.contentLength().takeIf { it >= 0 }
                    }
                    val append = resumed
                    FileOutputStream(target, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var downloaded = if (append) offset else 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, total ?: -1L)
                            }
                            out.flush()
                            CalibreDownloadResult(downloaded, total, resumed)
                        }
                    }
                }
                else -> throw classify(response)
            }
        }
    }

    private fun getString(url: String): String {
        execute(Request.Builder().url(url).get().build()).use { response ->
            if (!response.isSuccessful) throw classify(response)
            return response.body?.string()
                ?: throw CalibreException(CalibreErrorKind.SERVER, "Empty response body")
        }
    }

    /** Follows redirects manually, dropping Authorization on cross-origin hops. */
    private fun execute(request: Request): Response {
        var current = request
        repeat(MAX_REDIRECTS) {
            val response = http.newCall(current).execute()
            val code = response.code
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    throw CalibreException(CalibreErrorKind.SERVER, "Redirect without Location")
                }
                val resolved = OpdsFeedParser.resolveUrl(current.url.toString(), location)
                val nextUrl = resolved.toHttpUrlOrNull()
                    ?: throw CalibreException(CalibreErrorKind.SERVER, "Invalid redirect target")
                val sameOrigin = nextUrl.host == current.url.host &&
                    nextUrl.scheme == current.url.scheme &&
                    nextUrl.port == current.url.port
                val builder = current.newBuilder().url(nextUrl)
                if (!sameOrigin) builder.removeHeader("Authorization")
                current = builder.build()
                return@repeat
            }
            return response
        }
        throw CalibreException(CalibreErrorKind.SERVER, "Too many redirects")
    }

    private fun classify(response: Response): CalibreException {
        val message = "HTTP ${response.code}"
        val kind = when (response.code) {
            401 -> CalibreErrorKind.AUTH
            403 -> CalibreErrorKind.FORBIDDEN
            404 -> CalibreErrorKind.NOT_FOUND
            429 -> CalibreErrorKind.RATE_LIMITED
            in 500..599 -> CalibreErrorKind.SERVER
            else -> CalibreErrorKind.UNKNOWN
        }
        return CalibreException(kind, message)
    }

    private fun contentRangeTotal(response: Response): Long? {
        val header = response.header("Content-Range") ?: return null
        return header.substringAfterLast('/', "").trim().toLongOrNull()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        const val DEFAULT_USER_AGENT = "Vierel/1.0 (Android; Calibre OPDS client)"

        /** Classifies a thrown transport error into a user-meaningful kind. */
        fun classifyThrowable(t: Throwable): CalibreErrorKind = when (t) {
            is CalibreException -> t.kind
            is SocketTimeoutException -> CalibreErrorKind.TIMEOUT
            is IOException -> CalibreErrorKind.NETWORK
            else -> CalibreErrorKind.UNKNOWN
        }
    }
}

private fun String.toHttpUrlOrNull(): okhttp3.HttpUrl? = runCatching { this.toHttpUrlOrNull() }.getOrNull()