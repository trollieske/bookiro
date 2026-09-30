package com.bookrio.data.repository

/**
 * Pure policy for the app's single "now playing" surface (mini-player + system
 * media controls).
 *
 * It encodes the two invariants the two audio engines must never break:
 *
 * 1. **Exactly one publisher.** Only the engine that currently owns
 *    [NowPlayingOwnership] may publish title/subtitle/artwork/transport state.
 *    A stale state object from the other engine is ignored, never merged.
 * 2. **Deterministic artwork identity.** Every snapshot carries an artwork key:
 *    `book:<bookId>` for audiobooks and `episode:<episodeId>:<artworkUrl>` for
 *    podcasts. The podcast key is never empty (the URL part may be empty when the
 *    episode has no artwork), so a podcast snapshot can never be mistaken for — or
 *    silently inherit — a book artwork.
 *
 * The implementation is free of Android/coroutine dependencies so ownership
 * transitions and metadata snapshots can be unit-tested on the JVM; the state
 * objects themselves expose their [ActiveAudioState.artworkKey] /
 * [PodcastActiveState.artworkKey] in production.
 */
object NowPlayingPolicy {

    const val BOOK_PREFIX = "book:"
    const val EPISODE_PREFIX = "episode:"

    /** The one snapshot the system/app is allowed to show. */
    data class Snapshot(
        val owner: NowPlayingOwnership.Engine,
        val artworkKey: String,
        val title: String,
        val subtitle: String,
        val isPlaying: Boolean,
        /** Only the owner publishes working transport controls. */
        val transportEnabled: Boolean = true
    )

    fun bookArtworkKey(bookId: Long): String = "$BOOK_PREFIX$bookId"

    fun episodeArtworkKey(episodeId: Long, artworkUrl: String?): String =
        "$EPISODE_PREFIX$episodeId:${artworkUrl.orEmpty()}"

    fun bookSnapshot(book: ActiveAudioState?): Snapshot? =
        book?.takeIf { it.bookId > 0L }?.let {
            Snapshot(
                owner = NowPlayingOwnership.Engine.AUDIOBOOK,
                artworkKey = it.artworkKey,
                title = it.title,
                subtitle = it.author,
                isPlaying = it.isPlaying
            )
        }

    fun podcastSnapshot(podcast: PodcastActiveState?): Snapshot? =
        podcast?.takeIf { it.episodeId > 0L }?.let {
            Snapshot(
                owner = NowPlayingOwnership.Engine.PODCAST,
                artworkKey = it.artworkKey,
                title = it.title,
                subtitle = it.podcastTitle,
                isPlaying = it.isPlaying
            )
        }

    /**
     * Resolves the single snapshot for the current [owner]. The owner always wins:
     * a non-null state object of the *other* engine (a stale tick racing a hand-off)
     * can never contribute its artwork key to the result.
     */
    fun resolve(
        owner: NowPlayingOwnership.Engine,
        book: ActiveAudioState?,
        podcast: PodcastActiveState?
    ): Snapshot? = when (owner) {
        NowPlayingOwnership.Engine.AUDIOBOOK -> bookSnapshot(book)
        NowPlayingOwnership.Engine.PODCAST -> podcastSnapshot(podcast)
        NowPlayingOwnership.Engine.NONE -> null
    }

    /**
     * The snapshots currently published by the two engines. This must contain at
     * most one element — more than one means both engines publish at once and the
     * ownership invariant is broken.
     */
    fun publishedSnapshots(book: ActiveAudioState?, podcast: PodcastActiveState?): List<Snapshot> =
        listOfNotNull(bookSnapshot(book), podcastSnapshot(podcast))
}