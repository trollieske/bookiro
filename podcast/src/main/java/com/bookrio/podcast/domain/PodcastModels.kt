package com.bookrio.podcast.domain

/** A validated, parsed public RSS/Atom podcast feed, ready to persist. */
data class ParsedPodcastFeed(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val language: String?,
    val categories: List<String>,
    val explicit: Boolean?,
    val episodes: List<ParsedPodcastEpisode>
) {
    fun validEpisodeCount(): Int = episodes.size
}

/** One playable episode extracted from a feed. Only items with audio enclosures survive. */
data class ParsedPodcastEpisode(
    val guid: String?,
    val enclosureUrl: String,
    val enclosureMimeType: String?,
    val enclosureLengthBytes: Long?,
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val publishedAt: Long?,
    val durationMs: Long?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val explicit: Boolean?,
    val episodeType: String?
)

/** One row from the public iTunes Search API (discovery only). */
data class PodcastSearchResult(
    val collectionId: Long?,
    val trackId: Long?,
    val title: String,
    val author: String?,
    val artworkUrl: String?,
    val feedUrl: String?,
    val genre: String?,
    val country: String?,
    val explicit: Boolean
)

/** Podcast discovery storefront choices (independent from app language). */
object PodcastCountries {
    const val NORWAY = "NO"
    const val SWEDEN = "SE"
    const val DENMARK = "DK"
    const val UNITED_STATES = "US"
    const val UNITED_KINGDOM = "GB"
    const val GERMANY = "DE"
    const val FRANCE = "FR"
    const val SPAIN = "ES"

    val SUPPORTED: List<String> = listOf(
        NORWAY, SWEDEN, DENMARK, UNITED_STATES, UNITED_KINGDOM, GERMANY, FRANCE, SPAIN
    )

    fun isSupported(code: String?): Boolean = code != null && SUPPORTED.contains(code.uppercase())

    /**
     * Country inference used only when the user has never chosen a storefront.
     * Never re-inferred once the user makes an explicit choice.
     */
    fun inferFromLanguage(languageTag: String?): String {
        val tag = languageTag?.lowercase()?.trim().orEmpty()
        val lang = tag.substringBefore('-').substringBefore('_')
        return when (lang) {
            "nb", "no", "nn" -> NORWAY
            "sv" -> SWEDEN
            "da" -> DENMARK
            "de" -> GERMANY
            "fr" -> FRANCE
            "es" -> SPAIN
            "en" -> UNITED_STATES
            else -> UNITED_STATES
        }
    }
}

/** Local "Start Here" shortcut: a localized label plus a separate search term. */
data class PodcastShortcut(
    val id: String,
    val labelRes: Int,
    val query: String
)

/** Result of syncing a single followed feed. */
enum class PodcastSyncOutcome { UPDATED, UNCHANGED, FAILED }

data class PodcastSyncResult(
    val feedId: Long,
    val outcome: PodcastSyncOutcome,
    val newEpisodeCount: Int = 0,
    val error: String? = null
)