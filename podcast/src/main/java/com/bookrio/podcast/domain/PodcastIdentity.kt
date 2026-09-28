package com.bookrio.podcast.domain

/**
 * Episode identity rules.
 *
 * - Feed URL is the subscription identity and is normalized before storage.
 * - RSS/Atom GUID is preferred as episode identity (scoped by feed so two feeds
 *   with the same GUID cannot collide).
 * - If the GUID is blank, or duplicated inside the same feed, fall back to
 *   normalized feed URL + enclosure URL.
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