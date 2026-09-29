package com.bookrio.shared.podcast

import com.bookrio.core.dispatchers.platformIoDispatcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL

/**
 * iOS implementation of [httpGetText].
 *
 * Runs the (blocking) synchronous download off the main thread on
 * `platformIoDispatcher`, throws when the URL is invalid or the body is missing
 * or empty, and decodes UTF-8 with an ISO-8859-1 fallback. Podcasts are
 * streaming-only here, so no caching/download machinery is involved.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun httpGetText(url: String): String = withContext(platformIoDispatcher) {
    val nsUrl = NSURL.URLWithString(url) ?: throw IllegalStateException("invalid_url")
    val data = NSData.dataWithContentsOfURL(nsUrl) ?: throw IllegalStateException("http_failed")
    val length = data.length.toInt()
    if (length <= 0) throw IllegalStateException("empty_body")
    val bytes = data.bytes?.readBytes(length) ?: throw IllegalStateException("empty_body")

    val utf8 = bytes.decodeToString()
    if ('\uFFFD' in utf8) latin1(bytes) else utf8
}

/** ISO-8859-1 fallback: every byte maps directly to a code point. */
private fun latin1(bytes: ByteArray): String = buildString(bytes.size) {
    for (byte in bytes) append((byte.toInt() and 0xFF).toChar())
}

/** env `BOOKRIO_AUTO_SUBSCRIBE_RSS`, used by the CI simulator smoke test. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun autoSubscribeRssUrl(): String? =
    NSProcessInfo.processInfo.environment["BOOKRIO_AUTO_SUBSCRIBE_RSS"] as? String