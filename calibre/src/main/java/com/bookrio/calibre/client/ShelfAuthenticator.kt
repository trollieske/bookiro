package com.bookrio.calibre.client

import java.security.MessageDigest
import java.security.SecureRandom
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * HTTP authenticator that supports both Basic and Digest (RFC 2617/7616).
 *
 * Calibre Content Server started with `--enable-auth` challenges OPDS with
 * `WWW-Authenticate: Digest`, which OkHttp does not implement out of the box.
 * The Authorization header is never logged.
 */
class ShelfAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (username.isBlank() && password.isBlank()) return null
        // Never loop forever: at most two attempts per URL.
        if (responseCount(response) >= 2) return null

        val challenge = response.headers("WWW-Authenticate")
            .firstOrNull { it.startsWith("Digest", ignoreCase = true) }
        if (challenge != null) {
            val header = buildDigestHeader(challenge, response.request) ?: return null
            return response.request.newBuilder()
                .header("Authorization", header)
                .build()
        }

        val basic = response.headers("WWW-Authenticate")
            .any { it.startsWith("Basic", ignoreCase = true) }
        if (basic) {
            return response.request.newBuilder()
                .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                .build()
        }
        return null
    }

    private fun buildDigestHeader(challenge: String, request: Request): String? {
        val params = parseChallenge(challenge)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        val algorithm = (params["algorithm"] ?: "MD5").uppercase()
        val opaque = params["opaque"]

        val algorithmName = algorithm.removeSuffix("-SESS")
        val digest = messageDigest(algorithmName) ?: return null

        val uri = request.url.encodedPath +
            (request.url.encodedQuery?.let { "?$it" } ?: "")

        val cnonce = randomCnonce()
        val nc = "00000001"

        var ha1 = hex(digest.digest("$username:$realm:$password".toByteArray(Charsets.UTF_8)))
        if (algorithm.endsWith("-SESS")) {
            ha1 = hex(digest.digest("$ha1:$nonce:$cnonce".toByteArray(Charsets.UTF_8)))
        }
        val ha2 = hex(digest.digest("${request.method}:$uri".toByteArray(Charsets.UTF_8)))
        val response = if (qop != null) {
            hex(
                digest.digest(
                    "$ha1:$nonce:$nc:$cnonce:$qop:$ha2".toByteArray(Charsets.UTF_8)
                )
            )
        } else {
            hex(digest.digest("$ha1:$nonce:$ha2".toByteArray(Charsets.UTF_8)))
        }

        return buildString {
            append("Digest ")
            append("username=\"").append(username).append("\", ")
            append("realm=\"").append(realm).append("\", ")
            append("nonce=\"").append(nonce).append("\", ")
            append("uri=\"").append(uri).append("\", ")
            append("response=\"").append(response).append("\", ")
            append("algorithm=").append(algorithm)
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
            if (!opaque.isNullOrBlank()) {
                append(", opaque=\"").append(opaque).append("\"")
            }
        }
    }

    private fun parseChallenge(challenge: String): Map<String, String> {
        val body = challenge.substringAfter("Digest", "").trim()
        val result = mutableMapOf<String, String>()
        val regex = Regex("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")
        regex.findAll(body).forEach { match ->
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[key] = value
        }
        return result
    }

    private fun messageDigest(name: String): MessageDigest? = runCatching {
        MessageDigest.getInstance(
            when (name) {
                "SHA-256" -> "SHA-256"
                "SHA-512-256" -> "SHA-512/256"
                else -> "MD5"
            }
        )
    }.getOrNull()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun randomCnonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return hex(bytes)
    }

    private fun responseCount(response: Response): Int {
        var count = 0
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}