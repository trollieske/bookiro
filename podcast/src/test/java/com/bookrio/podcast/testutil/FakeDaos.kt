package com.bookrio.podcast.testutil

import com.bookrio.data.local.dao.PodcastDownloadDao
import com.bookrio.data.local.dao.PodcastEpisodeDao
import com.bookrio.data.local.dao.PodcastFeedDao
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.dao.PodcastPlaybackDao
import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.data.local.entity.PodcastDownloadEntity
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory feed DAO for JVM repository tests. */
class FakePodcastFeedDao : PodcastFeedDao {
    val feeds = LinkedHashMap<Long, PodcastFeedEntity>()
    private var nextId = 1L

    override suspend fun insert(feed: PodcastFeedEntity): Long {
        val id = if (feed.id != 0L) feed.id else nextId++
        feeds[id] = feed.copy(id = id)
        return id
    }

    override suspend fun update(feed: PodcastFeedEntity) {
        feeds[feed.id] = feed
    }

    override suspend fun getById(id: Long): PodcastFeedEntity? = feeds[id]

    override fun observeById(id: Long): Flow<PodcastFeedEntity?> = flowOf(feeds[id])

    override suspend fun getByUrl(url: String): PodcastFeedEntity? = feeds.values.firstOrNull { it.feedUrl == url }

    override suspend fun getFollowed(): List<PodcastFeedEntity> = feeds.values.filter { it.isFollowed }

    override fun observeFollowedCount(): Flow<Int> = flowOf(feeds.values.count { it.isFollowed })

    override fun observeAllFeedUrls(): Flow<List<String>> = flowOf(feeds.values.map { it.feedUrl })

    override suspend fun setFollowed(id: Long, followed: Boolean, now: Long) {
        feeds[id] = feeds[id]!!.copy(isFollowed = followed, updatedAt = now)
    }

    override suspend fun setSyncResult(id: Long, syncedAt: Long, status: String, error: String?, now: Long) {
        feeds[id] = feeds[id]!!.copy(
            lastSyncedAt = syncedAt,
            lastSyncStatus = status,
            lastSyncError = error,
            updatedAt = now
        )
    }

    override fun observeSummaries(): Flow<List<PodcastFeedSummary>> = flowOf(emptyList())
}

/** In-memory episode DAO. Enforces stable-identity uniqueness like the real unique index. */
class FakePodcastEpisodeDao : PodcastEpisodeDao {
    val episodes = LinkedHashMap<Long, PodcastEpisodeEntity>()
    private var nextId = 1L

    override suspend fun insert(episode: PodcastEpisodeEntity): Long {
        val existingByIdentity = episodes.values.firstOrNull { it.stableIdentity == episode.stableIdentity }
        val id = when {
            episode.id != 0L -> episode.id
            existingByIdentity != null -> existingByIdentity.id
            else -> nextId++
        }
        episodes[id] = episode.copy(id = id)
        return id
    }

    override suspend fun insertAll(episodes: List<PodcastEpisodeEntity>) {
        episodes.forEach { insert(it) }
    }

    override suspend fun update(episode: PodcastEpisodeEntity) {
        episodes[episode.id] = episode
    }

    override suspend fun getById(id: Long): PodcastEpisodeEntity? = episodes[id]

    override suspend fun getByIdentity(identity: String): PodcastEpisodeEntity? =
        episodes.values.firstOrNull { it.stableIdentity == identity }

    override fun observeByFeed(feedId: Long): Flow<List<PodcastEpisodeEntity>> =
        flowOf(episodes.values.filter { it.feedId == feedId })

    override suspend fun listByFeed(feedId: Long, limit: Int): List<PodcastEpisodeEntity> =
        episodes.values.filter { it.feedId == feedId }.take(limit)

    override suspend fun listIdsByFeed(feedId: Long): List<Long> =
        episodes.values.filter { it.feedId == feedId }.map { it.id }

    override suspend fun deleteByIds(ids: List<Long>) {
        ids.forEach { episodes.remove(it) }
    }

    override fun observeResumeItems(limit: Int): Flow<List<PodcastResumeItem>> = flowOf(emptyList())
}

class FakePodcastPlaybackDao : PodcastPlaybackDao {
    val rows = LinkedHashMap<Long, PodcastPlaybackEntity>()

    override suspend fun upsert(playback: PodcastPlaybackEntity) {
        rows[playback.episodeId] = playback
    }

    override suspend fun getByEpisode(episodeId: Long): PodcastPlaybackEntity? = rows[episodeId]

    override fun observeByEpisode(episodeId: Long): Flow<PodcastPlaybackEntity?> = flowOf(rows[episodeId])

    override fun observeAll(): Flow<List<PodcastPlaybackEntity>> = flowOf(rows.values.toList())

    override suspend fun markCompleted(episodeId: Long, at: Long) {
        rows[episodeId] = rows[episodeId]!!.copy(isCompleted = true, completedAt = at, positionMs = 0)
    }

    override suspend fun deleteByEpisode(episodeId: Long) {
        rows.remove(episodeId)
    }
}

class FakePodcastDownloadDao : PodcastDownloadDao {
    val rows = LinkedHashMap<Long, PodcastDownloadEntity>()

    override suspend fun upsert(download: PodcastDownloadEntity) {
        rows[download.episodeId] = download
    }

    override suspend fun getByEpisode(episodeId: Long): PodcastDownloadEntity? = rows[episodeId]

    override fun observeByEpisode(episodeId: Long): Flow<PodcastDownloadEntity?> = flowOf(rows[episodeId])

    override suspend fun getActive(): List<PodcastDownloadEntity> =
        rows.values.filter { it.status == PodcastDownloadStatus.QUEUED || it.status == PodcastDownloadStatus.DOWNLOADING }

    override fun observeAll(): Flow<List<PodcastDownloadEntity>> = flowOf(rows.values.toList())

    override suspend fun updateStatus(episodeId: Long, status: PodcastDownloadStatus, dmId: Long?, reason: String?) {
        rows[episodeId] = rows[episodeId]!!.copy(status = status, downloadManagerId = dmId, failureReason = reason)
    }

    override suspend fun deleteByEpisode(episodeId: Long) {
        rows.remove(episodeId)
    }
}