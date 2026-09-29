package com.bookrio.shared.podcast

/**
 * Episode identity rules — a Kotlin Multiplatform mirror of the Android
 * `com.bookrio.podcast.domain.PodcastIdentity`.
 *
 * - The normalized feed URL is the subscription identity.
 * - RSS/Atom GUID is preferred as episode identity, scoped by feed so two feeds
 *   with the same GUID cannot collide.
 * - If the GUID is blank (or duplicated inside one feed, which the caller
 *   detects) the identity falls back to feed URL + enclosure URL.
 * - Episode title is NEVER used as identity.
 */
object PodcastIdentity {

    const val GUID_PREFIX = "guid:"
    const val FALLBACK_PREFIX = "url:"

    fun stableIdentity(feedUrl: String, guid: String?, enclosureUrl: String): String {
        val g = guid?.trim()
        return if (!g.isNullOrEmpty()) {
            "$GUID_PREFIX$feedUrl|$g"
        } else {
            fallbackIdentity(feedUrl, enclosureUrl)
        }
    }

    fun fallbackIdentity(feedUrl: String, enclosureUrl: String): String =
        "$FALLBACK_PREFIX$feedUrl|${enclosureUrl.trim()}"
}