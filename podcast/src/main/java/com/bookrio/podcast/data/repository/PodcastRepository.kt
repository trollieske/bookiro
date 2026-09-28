package com.bookrio.podcast.data.repository

import com.bookrio.data.local.dao.PodcastDownloadDao
import com.bookrio.data.local.dao.PodcastEpisodeDao
import com.bookrio.data.local.dao.PodcastFeedDao
import com.bookrio.data.local.dao.PodcastPlaybackDao
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.podcast.data.remote.PodcastFeedParseException
import com.bookrio.podcast.data.remote.PodcastFeedParser
import com.bookrio.podcast.data.remote.PodcastNetworkException
import com.bookrio.podcast.data.remote.PodcastUrls
import com.bookrio.podcast.domain.ParsedPodcastEpisode
import com.bookrio.podcast.domain.ParsedPodcastFeed
import com.bookrio.podcast.domain.PodcastIdentity
import com.bookrio.podcast.domain.PodcastDownloadPolicy
import com.bookrio.podcast.domain.PodcastSyncOutcome
import com.bookrio.podcast.domain.PodcastSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of following a feed. */
data class FollowOutcome(
    val feedId: Long,
    val alreadyFollowed: Boolean,
    val storedEpisodeCount: Int
)

/** Resolved media source for an episode: a completed local file wins over the remote URL. */
data class EpisodePlaybackSource(
    val uri: String,
    val isLocal: Boolean
)

/**
 * Single source of truth for podcast persistence and feed ingestion. Both manual
 * "Add RSS feed" and discovery "Follow" funnel through here, so there is exactly
 * one canonical follow/sync code path.
 */
class PodcastRepository(
    private val feedDao: PodcastFeedDao,
    private val episodeDao: PodcastEpisodeDao,
    private val playbackDao: PodcastPlaybackDao,
    private val downloadDao: PodcastDownloadDao,
    /** Parses a feed body. Injectable so JVM tests can avoid the Android Xml factory. */
    private val parseFeed: (body: String, feedUrl: String) -> ParsedPodcastFeed = { body, url ->
        java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)).use { stream ->
            PodcastFeedParser.parse(stream, url)
        }
    }
) {

    companion object {
        const val INITIAL_EPISODE_LIMIT = 100
    }

    /** Fetch + validate + parse a public RSS/Atom feed without persisting it. */
    suspend fun previewFeed(rawUrl: String): Result<ParsedPodcastFeed> {
        val normalized = PodcastUrls.normalize(rawUrl)
            ?: return Result.failure(PodcastNetworkException(PodcastNetworkException.INVALID_URL))
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = PodcastHttpFetch.body(normalized)
                parse(body, normalized)
            }
        }
    }
    suspend fun followFeed(rawUrl: String): Result<FollowOutcome> {
        val normalized = PodcastUrls.normalize(rawUrl)
            ?: return Result.failure(PodcastNetworkException(PodcastNetworkException.INVALID_URL))
        val existing = feedDao.getByUrl(normalized)
        if (existing != null) {
            if (!existing.isFollowed) {
                feedDao.setFollowed(existing.id, true)
            }
            return Result.success(FollowOutcome(existing.id, alreadyFollowed = true, storedEpisodeCount = 0))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = PodcastHttpFetch.body(normalized)
                val parsed = parse(body, normalized)
                val stored = persistFeed(feed = null, parsed = parsed, initial = true)
                FollowOutcome(stored.first, alreadyFollowed = false, storedEpisodeCount = stored.second)
            }
        }
    }

    /** Re-fetch and upsert one followed feed, preserving historical episodes. */
    suspend fun syncFeed(feedId: Long): PodcastSyncResult {
        val feed = feedDao.getById(feedId)
            ?: return PodcastSyncResult(feedId, PodcastSyncOutcome.FAILED, error = PodcastNetworkException.INVALID_URL)
        return try {
            val parsed = withContext(Dispatchers.IO) {
                val body = PodcastHttpFetch.body(feed.feedUrl)
                parse(body, feed.feedUrl)
            }
            val newCount = persistFeed(feed = feed, parsed = parsed, initial = false).second
            val now = System.currentTimeMillis()
            feedDao.setSyncResult(feedId, now, PodcastSyncOutcome.UPDATED.name, null, now)
            PodcastSyncResult(feedId, PodcastSyncOutcome.UPDATED, newCount)
        } catch (t: Throwable) {
            val now = System.currentTimeMillis()
            val code = (t as? PodcastNetworkException)?.code
                ?: (t as? PodcastFeedParseException)?.code
                ?: PodcastNetworkException.OFFLINE
            feedDao.setSyncResult(feedId, now, PodcastSyncOutcome.FAILED.name, code, now)
            PodcastSyncResult(feedId, PodcastSyncOutcome.FAILED, error = code)
        }
    }

    /** Syncs every followed feed, isolating per-feed failures. */
    suspend fun syncAllFollowed(): List<PodcastSyncResult> {
        return feedDao.getFollowed().map { syncFeed(it.id) }
    }

    suspend fun setFollowed(feedId: Long, followed: Boolean) {
        // Unfollowing hides the feed but keeps episodes, playback history and downloads.
        feedDao.setFollowed(feedId, followed)
    }

    /**
     * Upserts feed metadata and episodes. Existing rows keep their id; new rows are
     * inserted. Episodes are never deleted when a feed temporarily omits them.
     */
    private suspend fun persistFeed(
        feed: PodcastFeedEntity?,
        parsed: ParsedPodcastFeed,
        initial: Boolean
    ): Pair<Long, Int> {
        val now = System.currentTimeMillis()
        val normalized = PodcastUrls.normalize(parsed.feedUrl) ?: parsed.feedUrl
        val feedId = if (feed == null) {
            feedDao.insert(
                PodcastFeedEntity(
                    feedUrl = normalized,
                    title = parsed.title,
                    author = parsed.author,
                    description = parsed.description,
                    artworkUrl = parsed.artworkUrl,
                    websiteUrl = parsed.websiteUrl,
                    language = parsed.language,
                    categoriesJson = encodeCategories(parsed.categories),
                    explicit = parsed.explicit,
                    isFollowed = true,
                    addedAt = now,
                    updatedAt = now
                )
            )
        } else {
            feedDao.update(
                feed.copy(
                    title = parsed.title,
                    author = parsed.author ?: feed.author,
                    description = parsed.description ?: feed.description,
                    artworkUrl = parsed.artworkUrl ?: feed.artworkUrl,
                    websiteUrl = parsed.websiteUrl ?: feed.websiteUrl,
                    language = parsed.language ?: feed.language,
                    categoriesJson = encodeCategories(parsed.categories) ?: feed.categoriesJson,
                    explicit = parsed.explicit ?: feed.explicit,
                    updatedAt = now
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
        for (ep in ordered) {
            if (inserted >= limit) break
            val identity = identityFor(normalized, ep, usedGuids)
            val existing = episodeDao.getByIdentity(identity)
            if (existing == null) {
                episodeDao.insert(
                    PodcastEpisodeEntity(
                        feedId = feedId,
                        stableIdentity = identity,
                        guid = ep.guid,
                        enclosureUrl = ep.enclosureUrl,
                        enclosureMimeType = ep.enclosureMimeType,
                        enclosureLengthBytes = ep.enclosureLengthBytes,
                        title = ep.title,
                        description = ep.description,
                        artworkUrl = ep.artworkUrl,
                        publishedAt = ep.publishedAt,
                        durationMs = ep.durationMs,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        explicit = ep.explicit,
                        episodeType = ep.episodeType,
                        addedAt = now,
                        updatedAt = now
                    )
                )
                inserted++
            } else {
                episodeDao.insert(
                    existing.copy(
                        guid = ep.guid ?: existing.guid,
                        enclosureUrl = ep.enclosureUrl,
                        enclosureMimeType = ep.enclosureMimeType ?: existing.enclosureMimeType,
                        enclosureLengthBytes = ep.enclosureLengthBytes ?: existing.enclosureLengthBytes,
                        title = ep.title,
                        description = ep.description ?: existing.description,
                        artworkUrl = ep.artworkUrl ?: existing.artworkUrl,
                        publishedAt = ep.publishedAt ?: existing.publishedAt,
                        durationMs = ep.durationMs ?: existing.durationMs,
                        seasonNumber = ep.seasonNumber ?: existing.seasonNumber,
                        episodeNumber = ep.episodeNumber ?: existing.episodeNumber,
                        explicit = ep.explicit ?: existing.explicit,
                        episodeType = ep.episodeType ?: existing.episodeType,
                        updatedAt = now
                    )
                )
            }
        }
        return feedId to inserted
    }

    private fun identityFor(feedUrl: String, ep: ParsedPodcastEpisode, usedGuids: MutableSet<String>): String {
        val guid = ep.guid?.trim()
        return if (!guid.isNullOrEmpty() && usedGuids.add(guid)) {
            PodcastIdentity.stableIdentity(feedUrl, guid, ep.enclosureUrl)
        } else {
            PodcastIdentity.fallbackIdentity(feedUrl, ep.enclosureUrl)
        }
    }

    private fun parse(body: String, feedUrl: String): ParsedPodcastFeed = parseFeed(body, feedUrl)

    private fun encodeCategories(categories: List<String>): String? {
        if (categories.isEmpty()) return null
        return runCatching {
            val arr = org.json.JSONArray()
            categories.forEach { arr.put(it) }
            arr.toString()
        }.getOrNull()
    }

    // ---- Playback state ----

    suspend fun getEpisode(episodeId: Long): PodcastEpisodeEntity? = episodeDao.getById(episodeId)
    suspend fun getFeed(feedId: Long): PodcastFeedEntity? = feedDao.getById(feedId)
    suspend fun getPlayback(episodeId: Long): PodcastPlaybackEntity? = playbackDao.getByEpisode(episodeId)

    suspend fun savePlayback(episodeId: Long, positionMs: Long, durationMs: Long?, completed: Boolean) {
        val existing = playbackDao.getByEpisode(episodeId)
        val now = System.currentTimeMillis()
        playbackDao.upsert(
            PodcastPlaybackEntity(
                episodeId = episodeId,
                positionMs = if (completed) 0L else positionMs.coerceAtLeast(0L),
                durationMs = durationMs ?: existing?.durationMs,
                lastPlayedAt = now,
                isCompleted = completed,
                completedAt = if (completed) now else existing?.completedAt,
                playbackSpeed = existing?.playbackSpeed ?: 1f
            )
        )
    }

    suspend fun savePlaybackSpeed(episodeId: Long, speed: Float) {
        val existing = playbackDao.getByEpisode(episodeId)
        playbackDao.upsert(
            (existing ?: PodcastPlaybackEntity(episodeId = episodeId)).copy(
                playbackSpeed = speed.coerceIn(0.5f, 3f)
            )
        )
    }

    /**
     * Resolves the media source for playback. A completed, non-empty local file
     * always wins over the remote enclosure URL. Never deletes a download just
     * because the network is unavailable.
     */
    suspend fun resolvePlaybackSource(episodeId: Long): EpisodePlaybackSource? {
        val episode = episodeDao.getById(episodeId) ?: return null
        val download = downloadDao.getByEpisode(episodeId)
        if (download != null) {
            val file = uriToFile(download.localUri)
            val length = file?.length() ?: 0L
            if (PodcastDownloadPolicy.preferLocal(download.status, download.localUri, length) && file != null) {
                // A completed, non-empty local file always wins over the remote URL.
                return EpisodePlaybackSource(download.localUri!!, isLocal = true)
            }
        }
        return episode.enclosureUrl.takeIf { it.isNotBlank() }?.let { EpisodePlaybackSource(it, isLocal = false) }
    }

    private fun uriToFile(uri: String?): File? {
        val raw = uri?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { File(java.net.URI(raw)) }.getOrNull()
            ?: runCatching { File(android.net.Uri.parse(raw).path ?: return null) }.getOrNull()
    }

    suspend fun clearPlaybackForMissingEpisode(episodeId: Long) {
        val episode = episodeDao.getById(episodeId) ?: return
        if (episode.enclosureUrl.isBlank()) playbackDao.deleteByEpisode(episodeId)
    }
}

/**
 * Indirection so tests can stub feed bodies without touching the network.
 * Production uses [com.bookrio.podcast.data.remote.PodcastHttp].
 */
object PodcastHttpFetch {
    var override: ((String) -> String)? = null

    fun body(url: String): String = override?.invoke(url)
        ?: com.bookrio.podcast.data.remote.PodcastHttp.fetchFeedBody(url)
}