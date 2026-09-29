package com.bookrio.shared.podcast

/**
 * One podcast feed parsed from RSS 2.0 / Atom.
 *
 * [feedUrl] is the normalized feed URL, i.e. the subscription identity. None of
 * these types touch a platform API: parsing is pure Kotlin so the same code runs
 * on Android and iOS.
 */
data class ParsedPodcastFeed(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    val language: String?,
    val episodes: List<ParsedPodcastEpisode>,
)

/**
 * One playable podcast episode. Only items with a playable audio enclosure are
 * ever produced by [PodcastFeedParser]; [guid] may be null (identity then falls
 * back to the enclosure URL via [PodcastIdentity]).
 */
data class ParsedPodcastEpisode(
    val guid: String?,
    val enclosureUrl: String,
    val enclosureMimeType: String?,
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val publishedAt: Long?,
    val durationMs: Long?,
)