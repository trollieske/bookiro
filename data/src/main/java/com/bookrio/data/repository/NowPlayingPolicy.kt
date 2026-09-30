package com.bookrio.data.repository

/**
 * Canonical artwork identity for the app's single "now playing" surface.
 *
 * Audiobooks identify their artwork as `book:<bookId>`; podcasts as
 * `episode:<episodeId>:<artworkUrl>`. The podcast key is never empty (the URL part
 * may be empty when the episode has no artwork), so a podcast snapshot can never be
 * mistaken for — or silently inherit — a book artwork.
 *
 * The two published snapshots ([ActiveAudioState], [PodcastActiveState]) expose
 * their key, so the ownership/metadata tests can assert the invariant without
 * reaching into UI code. The runtime separation itself is enforced by
 * [NowPlayingOwnership] plus the services' retire/load arbitration.
 */
object NowPlayingPolicy {

    const val BOOK_PREFIX = "book:"
    const val EPISODE_PREFIX = "episode:"

    fun bookArtworkKey(bookId: Long): String = "$BOOK_PREFIX$bookId"

    fun episodeArtworkKey(episodeId: Long, artworkUrl: String?): String =
        "$EPISODE_PREFIX$episodeId:${artworkUrl.orEmpty()}"
}
