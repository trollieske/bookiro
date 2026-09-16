package com.shelf.reader.webdav.client

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG_WEBDAV_ENGINE = "WebdavClientEngine"

/** Classified WebDAV failure so the UI can translate a precise state. */
enum class WebdavErrorKind { AUTH, FORBIDDEN, NOT_FOUND, SERVER, NETWORK, TIMEOUT, UNKNOWN }

data class WebdavConnectResult(val success: Boolean, val kind: WebdavErrorKind? = null)

enum class WebdavEntryType { FILE, FOLDER, UNKNOWN }

data class WebdavEntry(
    val name: String,
    val path: String,
    val href: String,
    val type: WebdavEntryType,
    val sizeBytes: Long,
    val modifiedEpochSec: Long,
    val etag: String? = null,
    val contentType: String? = null
)

class WebdavClientEngine {

    private var client: OkHttpClient? = null
    private var baseUrl: String? = null
    private var authHeader: String? = null
    private var connected = false

    val isConnected: Boolean
        get() = connected

    suspend fun connect(
        baseUrl: String,
        username: String,
        password: String? = null,
        bearerToken: String? = null,
        authType: String = "BASIC",
        trustAllCertificates: Boolean = false,
        userAgent: String? = null
    ): Boolean = connectResult(
        baseUrl = baseUrl,
        username = username,
        password = password,
        bearerToken = bearerToken,
        authType = authType,
        trustAllCertificates = trustAllCertificates,
        userAgent = userAgent
    ).success

    suspend fun connectResult(
        baseUrl: String,
        username: String,
        password: String? = null,
        bearerToken: String? = null,
        authType: String = "BASIC",
        trustAllCertificates: Boolean = false,
        userAgent: String? = null
    ): WebdavConnectResult = withContext(Dispatchers.IO) {
        try {
            val trustAll = trustAllCertificates
            val builder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                // Redirects are followed manually so Authorization never crosses origins.
                .followRedirects(false)
                .followSslRedirects(false)

            if (userAgent != null) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
            }

            if (trustAll) {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        Log.w(TAG_WEBDAV_ENGINE, "Trust-all enabled by the user (client certificate, authType=$authType)")
                    }
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                        Log.w(TAG_WEBDAV_ENGINE, "Trust-all enabled by the user (server certificate, authType=$authType)")
                    }
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier(HostnameVerifier { _, _ -> true })
            }

            client = builder.build()

            val normalizedBase = baseUrl.trimEnd('/')
            this@WebdavClientEngine.baseUrl = normalizedBase

            authHeader = when (authType.uppercase()) {
                "BEARER", "OAUTH2" -> "Bearer ${bearerToken ?: password ?: ""}"
                "NONE" -> null
                else -> Credentials.basic(username, password ?: "")
            }

            val probeUrl = normalizedBase + "/"
            val request = Request.Builder().url(probeUrl).method("PROPFIND", null)
                .header("Depth", "0")
                .header("Content-Type", "application/xml")
                .apply { if (authHeader != null) header("Authorization", authHeader!!) }
                .build()

            val response = execute(client!!, request)
            val code = response.code
            response.close()
            val result = if (response.isSuccessful) {
                WebdavConnectResult(success = true)
            } else {
                WebdavConnectResult(success = false, kind = classify(code))
            }
            connected = result.success
            result
        } catch (e: Throwable) {
            connected = false
            WebdavConnectResult(success = false, kind = classifyThrowable(e))
        }
    }

    private fun classify(code: Int): WebdavErrorKind = when (code) {
        401 -> WebdavErrorKind.AUTH
        403 -> WebdavErrorKind.FORBIDDEN
        404 -> WebdavErrorKind.NOT_FOUND
        in 500..599 -> WebdavErrorKind.SERVER
        else -> WebdavErrorKind.UNKNOWN
    }

    private fun classifyThrowable(e: Throwable): WebdavErrorKind = when (e) {
        is java.net.SocketTimeoutException -> WebdavErrorKind.TIMEOUT
        is java.net.UnknownHostException, is java.net.ConnectException -> WebdavErrorKind.NETWORK
        is java.io.IOException -> WebdavErrorKind.NETWORK
        else -> WebdavErrorKind.UNKNOWN
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { closeNow() }

    /** Synchronous cleanup for callers that cannot suspend (e.g. ViewModel.onCleared). */
    fun closeNow() {
        connected = false
        authHeader = null
        baseUrl = null
        client = null
    }

    private fun buildHref(path: String): String {
        val base = baseUrl?.trimEnd('/') ?: return ""
        val clean = if (path.startsWith("/")) path else "/$path"
        return "$base$clean"
    }

    /**
     * Follows redirects manually so the Authorization header is never forwarded
     * to a different origin (OkHttp's automatic redirect handling keeps it).
     */
    private fun execute(httpClient: OkHttpClient, request: Request): Response {
        var current = request
        repeat(MAX_REDIRECTS) {
            val response = httpClient.newCall(current).execute()
            val code = response.code
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) throw IllegalStateException("Redirect without Location")
                val nextUrl = runCatching {
                    current.url.resolve(location)
                }.getOrNull() ?: throw IllegalStateException("Invalid redirect target")
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
        throw IllegalStateException("Too many redirects")
    }

    suspend fun listDirectory(path: String): List<WebdavEntry> = withContext(Dispatchers.IO) {
        val httpClient = client ?: return@withContext emptyList()
        try {
            val href = buildHref(path) + (if (!path.endsWith("/")) "/" else "")
            val bodyXml = """<?xml version="1.0"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:displayname/>
                    <d:getcontentlength/>
                    <d:getlastmodified/>
                    <d:getcontenttype/>
                    <d:resourcetype/>
                    <d:getetag/>
                  </d:prop>
                </d:propfind>""".trimIndent().toRequestBody("application/xml".toMediaTypeOrNull())

            val request = Request.Builder().url(href).method("PROPFIND", bodyXml)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .apply { if (authHeader != null) header("Authorization", authHeader!!) }
                .build()

            val response = execute(httpClient, request)
            val bodyText = response.body?.string().orEmpty()
            response.close()
            if (!response.isSuccessful) return@withContext emptyList()

            WebdavMultiStatusParser.parse(bodyText, baseUrl.orEmpty(), path)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Downloads [remotePath] into [localFile] via a `<localFile>.part` staging
     * file. When the server supports HTTP Range the partial file is resumed
     * (`206`); otherwise it is restarted. Only a fully received file is moved
     * onto its final name, so a crash never leaves a truncated "complete" file.
     */
    suspend fun downloadFile(
        remotePath: String,
        localFile: File,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> }
    ): Long = withContext(Dispatchers.IO) {
        val httpClient = client ?: return@withContext -1L
        try {
            localFile.parentFile?.mkdirs()
            val staging = File(localFile.parentFile, localFile.name + ".part")
            val offset = if (staging.exists() && staging.length() > 0) staging.length() else 0L
            val url = buildHref(remotePath)
            val builder = Request.Builder().url(url).get()
            if (offset > 0) builder.header("Range", "bytes=$offset-")
            if (authHeader != null) builder.header("Authorization", authHeader!!)

            val response = execute(httpClient, builder.build())
            if (!response.isSuccessful) { response.close(); return@withContext -1L }
            val resumed = offset > 0 && response.code == 206
            val body = response.body ?: run { response.close(); return@withContext -1L }
            val total = body.contentLength()

            FileOutputStream(staging, resumed).use { out ->
                body.byteStream().use { stream ->
                    val buf = ByteArray(256 * 1024)
                    var written = if (resumed) offset else 0L
                    while (true) {
                        val read = stream.read(buf)
                        if (read == -1) break
                        out.write(buf, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    out.flush()
                }
            }

            // A partial file stays as `.part` for the next attempt; only a
            // verified, fully received file is promoted to its final name.
            if (total > 0 && staging.length() != total) {
                return@withContext -1L
            }
            if (localFile.exists() && !localFile.delete()) return@withContext -1L
            if (!staging.renameTo(localFile)) {
                runCatching {
                    staging.copyTo(localFile, overwrite = true)
                    staging.delete()
                }.getOrElse { return@withContext -1L }
            }
            localFile.length()
        } catch (_: Exception) {
            -1L
        }
    }

    fun matchesFormat(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "cbz", "cbr", "txt", "html", "rtf", "md",
            "m4b", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav", "zip")
    }

    private companion object {
        private const val MAX_REDIRECTS = 5
    }
}
