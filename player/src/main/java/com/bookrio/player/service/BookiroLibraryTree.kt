package com.bookrio.player.service

import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity

/**
 * Android Auto / Media3 **home** browse tree for Bookiro.
 *
 * This is the "home screen with options" the car shows first: a small set of
 * navigational nodes (Continue listening / Audiobooks / Podcasts) instead of the
 * previous flat list of every audiobook. Both media types share one
 * `MediaLibraryService` (the audiobook engine) so Android Auto lists a single
 * Bookiro app; podcast *playback* is delegated to the dedicated podcast engine by
 * media id (see `AudiobookPlaybackService.routePodcastSelections`).
 *
 * Pure Kotlin (no Android types) so the tree shape, media-id round-trip and
 * metadata mapping can be unit-tested on the JVM.
 *
 * Tree shape:
 * ```
 * __aa_home__
 *   ├─ __aa_continue__        (mixed: audiobooks + podcast episodes in progress)
 *   ├─ __aa_audiobooks__      (playable book_<id> leaves)
 *   └─ __aa_podcasts__        (browsable podfeed_<id> feeds)
 *         └─ episode_<id>     (playable leaves, delegated to the podcast engine)
 * ```
 */
object BookiroLibraryTree {

    const val HOME_MEDIA_ID = "__aa_home__"
    const val CONTINUE_MEDIA_ID = "__aa_continue__"
    const val AUDIOBOOKS_MEDIA_ID = "__aa_audiobooks__"
    const val PODCASTS_MEDIA_ID = "__aa_podcasts__"

    const val FEED_MEDIA_ID_PREFIX = "podfeed_"
    const val EPISODE_MEDIA_ID_PREFIX = "episode_"

    /** How many mixed "Continue listening" rows the home folder offers. */
    const val CONTINUE_LIMIT = 15

    /** Upper bound of episodes browsable inside one feed. */
    const val FEED_EPISODE_LIMIT = 200

    /** One node/leaf in the car browse tree. */
    enum class Kind { HOME, CONTINUE, AUDIOBOOKS, PODCASTS, FEED, BOOK, EPISODE }

    /**
     * Android-free description of one library item. The service maps it 1:1 to a
     * Media3 `MediaItem`.
     */
    data class Entry(
        val mediaId: String,
        val kind: Kind,
        val title: String,
        val artist: String = "",
        val albumTitle: String = "",
        val subtitle: String = "",
        val coverPath: String? = null,
        val artworkUri: String? = null,
        val bookId: Long? = null,
        val feedId: Long? = null,
        val episodeId: Long? = null,
        val durationMs: Long? = null,
        val progressPercent: Float = 0f
    ) {
        val isBrowsable: Boolean
            get() = kind == Kind.HOME || kind == Kind.CONTINUE ||
                kind == Kind.AUDIOBOOKS || kind == Kind.PODCASTS || kind == Kind.FEED

        val isPlayable: Boolean
            get() = kind == Kind.BOOK || kind == Kind.EPISODE
    }

    fun feedMediaId(feedId: Long): String = "$FEED_MEDIA_ID_PREFIX$feedId"

    fun episodeMediaId(episodeId: Long): String = "$EPISODE_MEDIA_ID_PREFIX$episodeId"

    /** Feed id encoded in a `podfeed_<id>` media id, or null for anything else. */
    fun feedIdOf(mediaId: String): Long? = positiveIdOf(mediaId, FEED_MEDIA_ID_PREFIX)

    /** Episode id encoded in an `episode_<id>` media id, or null for anything else. */
    fun episodeIdOf(mediaId: String): Long? = positiveIdOf(mediaId, EPISODE_MEDIA_ID_PREFIX)

    private fun positiveIdOf(mediaId: String, prefix: String): Long? =
        mediaId.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    /** A non-playable navigational node (section folder). */
    fun section(kind: Kind, mediaId: String, title: String): Entry = Entry(
        mediaId = mediaId,
        kind = kind,
        title = title
    )

    // ── Audiobooks ────────────────────────────────────────────────────────────

    /** Audiobooks only, most recently opened first, mapped to playable leaves. */
    fun audiobookEntries(books: List<BookEntity>): List<Entry> =
        AudiobookLibraryTree.sortedAudiobooks(books).map(::bookEntry)

    fun bookEntry(book: BookEntity): Entry = Entry(
        mediaId = AudiobookLibraryTree.bookMediaId(book.id),
        kind = Kind.BOOK,
        title = book.title,
        artist = book.author,
        albumTitle = book.title,
        subtitle = book.author,
        coverPath = book.coverPath,
        bookId = book.id,
        durationMs = book.durationMs
    )

    // ── Podcasts ──────────────────────────────────────────────────────────────

    /** Followed feeds, alphabetically by title (mirrors the Podcasts tab). */
    fun sortedFeeds(feeds: List<PodcastFeedEntity>): List<PodcastFeedEntity> =
        feeds.filter { it.isFollowed }.sortedBy { it.title.lowercase() }

    fun feedEntry(feed: PodcastFeedEntity): Entry = Entry(
        mediaId = feedMediaId(feed.id),
        kind = Kind.FEED,
        title = feed.title,
        artist = feed.author ?: "",
        albumTitle = feed.title,
        subtitle = feed.author ?: "",
        feedId = feed.id,
        artworkUri = feed.artworkUrl
    )

    /** Newest episode first, matching the feed's in-app ordering. */
    fun sortedEpisodes(episodes: List<PodcastEpisodeEntity>): List<PodcastEpisodeEntity> =
        episodes.sortedWith(
            compareByDescending<PodcastEpisodeEntity> { it.publishedAt ?: 0L }
                .thenByDescending { it.id }
        )

    fun episodeEntry(
        episode: PodcastEpisodeEntity,
        feed: PodcastFeedEntity?,
        playback: PodcastPlaybackEntity? = null
    ): Entry {
        val feedTitle = feed?.title ?: ""
        return Entry(
            mediaId = episodeMediaId(episode.id),
            kind = Kind.EPISODE,
            title = episode.title,
            artist = feedTitle,
            albumTitle = feedTitle,
            subtitle = feedTitle,
            artworkUri = episode.artworkUrl ?: feed?.artworkUrl,
            feedId = episode.feedId,
            episodeId = episode.id,
            durationMs = episode.durationMs,
            progressPercent = episodeProgress(playback)
        )
    }

    /** Resume row for the Continue-listening folder (mixed with audiobooks). */
    fun resumeEntry(item: PodcastResumeItem): Entry = Entry(
        mediaId = episodeMediaId(item.episodeId),
        kind = Kind.EPISODE,
        title = item.episodeTitle,
        artist = item.feedTitle,
        albumTitle = item.feedTitle,
        subtitle = item.feedTitle,
        artworkUri = item.artworkUrl,
        feedId = item.feedId,
        episodeId = item.episodeId,
        durationMs = item.durationMs,
        progressPercent = percent(item.positionMs, item.durationMs, item.isCompleted)
    )

    private fun episodeProgress(playback: PodcastPlaybackEntity?): Float {
        if (playback == null) return 0f
        return percent(playback.positionMs, playback.durationMs, playback.isCompleted)
    }

    private fun percent(positionMs: Long, durationMs: Long?, completed: Boolean): Float {
        if (completed) return 1f
        val duration = durationMs?.takeIf { it > 0L } ?: return 0f
        return (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Media3 paging convention: `pageSize == Int.MAX_VALUE` (or non-positive) means
     * "all items", otherwise a single page.
     */
    fun page(entries: List<Entry>, page: Int, pageSize: Int): List<Entry> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return entries
        val from = page.coerceAtLeast(0).toLong() * pageSize
        if (from >= entries.size) return emptyList()
        return entries.subList(from.toInt(), minOf(from.toInt() + pageSize, entries.size))
    }

    /** Locale-neutral `H:MM:SS` / `M:SS` used for podcast episode subtitles. */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
