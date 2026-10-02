package com.bookrio.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bookrio.data.local.entity.PodcastDownloadEntity
import com.bookrio.data.local.entity.PodcastDownloadStatus
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import kotlinx.coroutines.flow.Flow

/** Read model for one followed feed row in the podcast root list. */
data class PodcastFeedSummary(
    val feedId: Long,
    val title: String,
    val author: String?,
    val artworkUrl: String?,
    val unplayedCount: Int,
    val episodeCount: Int,
    val latestEpisodeTitle: String?,
    val latestPublishedAt: Long?,
    val language: String?,
    val lastSyncedAt: Long?,
    val lastSyncStatus: String?
)

/** Read model for the newest episodes across all followed feeds (Home tab). */
data class PodcastLatestEpisode(
    val episodeId: Long,
    val feedId: Long,
    val episodeTitle: String,
    val feedTitle: String,
    val artworkUrl: String?,
    val publishedAt: Long?,
    val durationMs: Long?,
    val positionMs: Long,
    val isCompleted: Boolean,
    val lastPlayedAt: Long?
)

/** Read model for a resumable podcast episode (multi-episode resume). */
data class PodcastResumeItem(
    val episodeId: Long,
    val feedId: Long,
    val episodeTitle: String,
    val feedTitle: String,
    val artworkUrl: String?,
    val positionMs: Long,
    val durationMs: Long?,
    val lastPlayedAt: Long?,
    val isCompleted: Boolean = false,
    val feedFollowed: Boolean = true
)

@Dao
interface PodcastFeedDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feed: PodcastFeedEntity): Long

    @Update
    suspend fun update(feed: PodcastFeedEntity)

    @Query("SELECT * FROM podcast_feeds WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PodcastFeedEntity?

    @Query("SELECT * FROM podcast_feeds WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<PodcastFeedEntity?>

    @Query("SELECT * FROM podcast_feeds WHERE feed_url = :url LIMIT 1")
    suspend fun getByUrl(url: String): PodcastFeedEntity?

    @Query("SELECT * FROM podcast_feeds WHERE is_followed = 1 ORDER BY added_at DESC")
    suspend fun getFollowed(): List<PodcastFeedEntity>

    @Query("SELECT feed_url FROM podcast_feeds")
    fun observeAllFeedUrls(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM podcast_feeds WHERE is_followed = 1")
    fun observeFollowedCount(): Flow<Int>

    @Query("UPDATE podcast_feeds SET is_followed = :followed, updated_at = :now WHERE id = :id")
    suspend fun setFollowed(id: Long, followed: Boolean, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE podcast_feeds SET last_synced_at = :syncedAt, last_sync_status = :status, " +
            "last_sync_error = :error, updated_at = :now WHERE id = :id"
    )
    suspend fun setSyncResult(
        id: Long,
        syncedAt: Long,
        status: String,
        error: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        """
        SELECT f.id AS feedId, f.title AS title, f.author AS author, f.artwork_url AS artworkUrl,
          (SELECT COUNT(*) FROM podcast_episodes e WHERE e.feed_id = f.id
             AND NOT EXISTS (SELECT 1 FROM podcast_playback p WHERE p.episode_id = e.id AND p.is_completed = 1)) AS unplayedCount,
          (SELECT COUNT(*) FROM podcast_episodes e WHERE e.feed_id = f.id) AS episodeCount,
          (SELECT e.title FROM podcast_episodes e WHERE e.feed_id = f.id
             ORDER BY COALESCE(e.published_at, 0) DESC, e.id DESC LIMIT 1) AS latestEpisodeTitle,
          (SELECT MAX(e.published_at) FROM podcast_episodes e WHERE e.feed_id = f.id) AS latestPublishedAt,
          f.language AS language, f.last_synced_at AS lastSyncedAt, f.last_sync_status AS lastSyncStatus
        FROM podcast_feeds f
        WHERE f.is_followed = 1
        ORDER BY COALESCE(latestPublishedAt, 0) DESC, f.title ASC
        """
    )
    fun observeSummaries(): Flow<List<PodcastFeedSummary>>
}

@Dao
interface PodcastEpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: PodcastEpisodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<PodcastEpisodeEntity>)

    @Update
    suspend fun update(episode: PodcastEpisodeEntity)

    @Query("SELECT * FROM podcast_episodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PodcastEpisodeEntity?

    @Query("SELECT * FROM podcast_episodes WHERE stable_identity = :identity LIMIT 1")
    suspend fun getByIdentity(identity: String): PodcastEpisodeEntity?

    @Query("SELECT * FROM podcast_episodes WHERE feed_id = :feedId ORDER BY COALESCE(published_at, 0) DESC, id DESC")
    fun observeByFeed(feedId: Long): Flow<List<PodcastEpisodeEntity>>

    @Query("SELECT * FROM podcast_episodes WHERE feed_id = :feedId ORDER BY COALESCE(published_at, 0) DESC, id DESC LIMIT :limit")
    suspend fun listByFeed(feedId: Long, limit: Int): List<PodcastEpisodeEntity>

    @Query("SELECT id FROM podcast_episodes WHERE feed_id = :feedId")
    suspend fun listIdsByFeed(feedId: Long): List<Long>

    @Query("DELETE FROM podcast_episodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query(
        """
        SELECT p.episode_id AS episodeId, e.feed_id AS feedId, e.title AS episodeTitle,
               f.title AS feedTitle, COALESCE(e.artwork_url, f.artwork_url) AS artworkUrl,
               p.position_ms AS positionMs, COALESCE(p.duration_ms, e.duration_ms) AS durationMs,
               p.last_played_at AS lastPlayedAt, p.is_completed AS isCompleted,
               f.is_followed AS feedFollowed
        FROM podcast_playback p
        JOIN podcast_episodes e ON e.id = p.episode_id
        JOIN podcast_feeds f ON f.id = e.feed_id
        ORDER BY p.last_played_at DESC, e.id ASC
        LIMIT :limit
        """
    )
    fun observeResumeItems(limit: Int = 25): Flow<List<PodcastResumeItem>>

    /**
     * Newest episodes across every followed feed, read-only and cross-feed.
     * Used by the Home tab's "Latest episodes" section; playback progress is
     * exposed so the caller can hide episodes already shown in the resume list.
     */
    @Query(
        """
        SELECT e.id AS episodeId, e.feed_id AS feedId, e.title AS episodeTitle,
               f.title AS feedTitle, COALESCE(e.artwork_url, f.artwork_url) AS artworkUrl,
               e.published_at AS publishedAt, e.duration_ms AS durationMs,
               COALESCE(p.position_ms, 0) AS positionMs,
               COALESCE(p.is_completed, 0) AS isCompleted,
               p.last_played_at AS lastPlayedAt
        FROM podcast_episodes e
        JOIN podcast_feeds f ON f.id = e.feed_id
        LEFT JOIN podcast_playback p ON p.episode_id = e.id
        WHERE f.is_followed = 1
        ORDER BY COALESCE(e.published_at, 0) DESC, e.id DESC
        LIMIT :limit
        """
    )
    fun observeLatestEpisodes(limit: Int = 25): Flow<List<PodcastLatestEpisode>>
}

@Dao
interface PodcastPlaybackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playback: PodcastPlaybackEntity)

    @Query("SELECT * FROM podcast_playback WHERE episode_id = :episodeId LIMIT 1")
    suspend fun getByEpisode(episodeId: Long): PodcastPlaybackEntity?

    @Query("SELECT * FROM podcast_playback WHERE episode_id = :episodeId LIMIT 1")
    fun observeByEpisode(episodeId: Long): Flow<PodcastPlaybackEntity?>

    @Query("SELECT * FROM podcast_playback")
    fun observeAll(): Flow<List<PodcastPlaybackEntity>>

    @Query("UPDATE podcast_playback SET is_completed = 1, completed_at = :at, position_ms = 0 WHERE episode_id = :episodeId")
    suspend fun markCompleted(episodeId: Long, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM podcast_playback WHERE episode_id = :episodeId")
    suspend fun deleteByEpisode(episodeId: Long)
}

@Dao
interface PodcastDownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: PodcastDownloadEntity)

    @Query("SELECT * FROM podcast_downloads WHERE episode_id = :episodeId LIMIT 1")
    suspend fun getByEpisode(episodeId: Long): PodcastDownloadEntity?

    @Query("SELECT * FROM podcast_downloads WHERE episode_id = :episodeId LIMIT 1")
    fun observeByEpisode(episodeId: Long): Flow<PodcastDownloadEntity?>

    @Query("SELECT * FROM podcast_downloads WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun getActive(): List<PodcastDownloadEntity>

    @Query("SELECT * FROM podcast_downloads")
    fun observeAll(): Flow<List<PodcastDownloadEntity>>

    @Query("UPDATE podcast_downloads SET status = :status, download_manager_id = :dmId, failure_reason = :reason WHERE episode_id = :episodeId")
    suspend fun updateStatus(episodeId: Long, status: PodcastDownloadStatus, dmId: Long?, reason: String?)

    @Query("DELETE FROM podcast_downloads WHERE episode_id = :episodeId")
    suspend fun deleteByEpisode(episodeId: Long)
}