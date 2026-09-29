package com.bookrio.shared.podcast

import com.bookrio.core.time.nowMillis
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity

/**
 * Feed ingestion on top of the shared Room podcast tables.
 *
 * This is the multiplatform mirror of the Android `PodcastRepository` core
 * (subscribe/sync) without downloads or discovery: fetch a feed, parse it in
 * common Kotlin, then upsert `podcast_feeds` / `podcast_episodes` through the
 * EXISTING DAOs. Existing episode rows keep their id; episodes are never
 * deleted, so playback history survives a feed that omits old items.
 */
class PodcastRepository(private val db: ShelfDatabase) {

    companion object {
        /** New subscriptions only store the newest episodes, like Android. */
        const val INITIAL_EPISODE_LIMIT = 100

        private const val SYNC_UPDATED = "UPDATED"
        private const val SYNC_FAILED = "FAILED"
    }

    /**
     * Normalizes [rawUrl], fetches and parses the feed, then stores feed +
     * episodes (newest first, up to [INITIAL_EPISODE_LIMIT]) and returns the
     * feed id. An already-known feed is simply (re)followed.
     */
    suspend fun subscribe(rawUrl: String): Result<Long> {
        val normalized = PodcastUrls.normalize(rawUrl)
            ?: return Result.failure(IllegalArgumentException("invalid_url"))
        val feedDao = db.podcastFeedDao()
        val existing = runCatching { feedDao.getByUrl(normalized) }.getOrNull()
        if (existing != null) {
            if (!existing.isFollowed) runCatching { feedDao.setFollowed(existing.id, true) }
            return Result.success(existing.id)
        }
        return runCatching {
            subscribeXml(normalized, httpGetText(normalized))
        }.fold(onSuccess = { it }, onFailure = { Result.failure(it) })
    }

    /**
     * Network-free core of [subscribe]: parse [xml] and persist the feed + episodes
     * under [feedUrl]. Used by [subscribe] after the fetch and by the CI smoke test
     * (which reads a fixture from disk so it does not depend on runner networking).
     */
    suspend fun subscribeXml(feedUrl: String, xml: String): Result<Long> {
        val normalized = PodcastUrls.normalize(feedUrl) ?: feedUrl
        val feedDao = db.podcastFeedDao()
        val existing = runCatching { feedDao.getByUrl(normalized) }.getOrNull()
        if (existing != null) {
            if (!existing.isFollowed) runCatching { feedDao.setFollowed(existing.id, true) }
            return Result.success(existing.id)
        }
        return runCatching {
            val parsed = PodcastFeedParser.parse(xml, normalized)
            persistFeed(feed = null, parsed = parsed, initial = true).first
        }
    }

    /**
     * Re-fetches one feed, upserts its metadata and episodes and records the
     * sync outcome via `PodcastFeedDao.setSyncResult`. Returns the number of
     * episodes stored for the feed; failures are reported as [Result.failure]
     * (with the feed flagged `FAILED`).
     */
    suspend fun refresh(feedId: Long): Result<Int> {
        val feedDao = db.podcastFeedDao()
        val feed = runCatching { feedDao.getById(feedId) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("feed_not_found"))
        return try {
            val xml = httpGetText(feed.feedUrl)
            val parsed = PodcastFeedParser.parse(xml, feed.feedUrl)
            persistFeed(feed = feed, parsed = parsed, initial = false)
            val now = nowMillis()
            runCatching { feedDao.setSyncResult(feedId, now, SYNC_UPDATED, null, now) }
            Result.success(db.podcastEpisodeDao().listIdsByFeed(feedId).size)
        } catch (t: Throwable) {
            val now = nowMillis()
            runCatching { feedDao.setSyncResult(feedId, now, SYNC_FAILED, syncErrorCode(t), now) }
            Result.failure(t)
        }
    }

    /** Refreshes every followed feed, isolating per-feed failures. Returns the success count. */
    suspend fun refreshAll(): Int {
        val feeds = runCatching { db.podcastFeedDao().getFollowed() }.getOrNull().orEmpty()
        var succeeded = 0
        for (feed in feeds) {
            if (refresh(feed.id).isSuccess) succeeded++
        }
        return succeeded
    }

    // ---- persistence -----------------------------------------------------------------

    /**
     * Upserts feed metadata and episodes exactly like the Android repository:
     * insert new rows, update existing ones in place (id preserved), keep the
     * newest [INITIAL_EPISODE_LIMIT] on first subscribe and never delete.
     */
    private suspend fun persistFeed(
        feed: PodcastFeedEntity?,
        parsed: ParsedPodcastFeed,
        initial: Boolean,
    ): Pair<Long, Int> {
        val feedDao = db.podcastFeedDao()
        val episodeDao = db.podcastEpisodeDao()
        val now = nowMillis()
        val normalized = PodcastUrls.normalize(parsed.feedUrl) ?: parsed.feedUrl

        val feedId = if (feed == null) {
            feedDao.insert(
                PodcastFeedEntity(
                    feedUrl = normalized,
                    title = parsed.title,
                    author = parsed.author,
                    description = parsed.description,
                    artworkUrl = parsed.artworkUrl,
                    language = parsed.language,
                    isFollowed = true,
                    addedAt = now,
                    updatedAt = now,
                )
            )
        } else {
            feedDao.update(
                feed.copy(
                    title = parsed.title,
                    author = parsed.author ?: feed.author,
                    description = parsed.description ?: feed.description,
                    artworkUrl = parsed.artworkUrl ?: feed.artworkUrl,
                    language = parsed.language ?: feed.language,
                    updatedAt = now,
                )
            )
            feed.id
        }

        val ordered = parsed.episodes
            .filter { it.enclosureUrl.isNotBlank() }
            .sortedWith(compareByDescending<ParsedPodcastEpisode> { it.publishedAt ?: 0L })
        val limit = if (initial) INITIAL_EPISODE_LIMIT else Int.MAX_VALUE

        val usedGuids = HashSet<String>()
        var inserted = 0
        for (episode in ordered) {
            if (inserted >= limit) break
            val identity = identityFor(normalized, episode, usedGuids)
            val existing = episodeDao.getByIdentity(identity)
            if (existing == null) {
                episodeDao.insert(
                    PodcastEpisodeEntity(
                        feedId = feedId,
                        stableIdentity = identity,
                        guid = episode.guid,
                        enclosureUrl = episode.enclosureUrl,
                        enclosureMimeType = episode.enclosureMimeType,
                        title = episode.title,
                        description = episode.description,
                        artworkUrl = episode.artworkUrl,
                        publishedAt = episode.publishedAt,
                        durationMs = episode.durationMs,
                        addedAt = now,
                        updatedAt = now,
                    )
                )
                inserted++
            } else {
                episodeDao.insert(
                    existing.copy(
                        guid = episode.guid ?: existing.guid,
                        enclosureUrl = episode.enclosureUrl,
                        enclosureMimeType = episode.enclosureMimeType ?: existing.enclosureMimeType,
                        title = episode.title,
                        description = episode.description ?: existing.description,
                        artworkUrl = episode.artworkUrl ?: existing.artworkUrl,
                        publishedAt = episode.publishedAt ?: existing.publishedAt,
                        durationMs = episode.durationMs ?: existing.durationMs,
                        updatedAt = now,
                    )
                )
            }
        }
        return feedId to inserted
    }

    /**
     * GUID is preferred; a blank or duplicated-in-feed GUID falls back to
     * feed URL + enclosure URL. Mirrors the Android rule set.
     */
    private fun identityFor(
        feedUrl: String,
        episode: ParsedPodcastEpisode,
        usedGuids: MutableSet<String>,
    ): String {
        val guid = episode.guid?.trim()
        return if (!guid.isNullOrEmpty() && usedGuids.add(guid)) {
            PodcastIdentity.stableIdentity(feedUrl, guid, episode.enclosureUrl)
        } else {
            PodcastIdentity.fallbackIdentity(feedUrl, episode.enclosureUrl)
        }
    }

    private fun syncErrorCode(t: Throwable): String = when (t) {
        is IllegalArgumentException -> t.message?.takeIf { it.isNotBlank() } ?: "malformed"
        else -> "offline"
    }
}