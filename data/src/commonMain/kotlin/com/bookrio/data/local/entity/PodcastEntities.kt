package com.bookrio.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Podcast domain persistence.
 *
 * These tables are intentionally separate from ebooks/audiobooks:
 * - A podcast feed is identified by its normalized public RSS/Atom URL.
 * - A podcast episode is identified by a stable identity (GUID, or
 *   normalized feed URL + enclosure URL when GUID is unusable).
 * - Podcast playback progress never shares a table with book/audiobook progress.
 */

/** Constrained offline-download states for a podcast episode. */
enum class PodcastDownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    REMOVING
}

@Entity(
    tableName = "podcast_feeds",
    indices = [Index(value = ["feed_url"], unique = true)]
)
data class PodcastFeedEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "feed_url") val feedUrl: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "author") val author: String? = null,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String? = null,
    @ColumnInfo(name = "website_url") val websiteUrl: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "categories_json") val categoriesJson: String? = null,
    @ColumnInfo(name = "explicit") val explicit: Boolean? = null,
    @ColumnInfo(name = "is_followed") val isFollowed: Boolean = true,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "last_sync_status") val lastSyncStatus: String? = null,
    @ColumnInfo(name = "last_sync_error") val lastSyncError: String? = null
)

@Entity(
    tableName = "podcast_episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastFeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feed_id"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index("feed_id"),
        Index("published_at"),
        Index(value = ["stable_identity"], unique = true),
        Index(value = ["feed_id", "published_at"])
    ]
)
data class PodcastEpisodeEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "feed_id") val feedId: Long,
    @ColumnInfo(name = "stable_identity") val stableIdentity: String,
    @ColumnInfo(name = "guid") val guid: String? = null,
    @ColumnInfo(name = "enclosure_url") val enclosureUrl: String,
    @ColumnInfo(name = "enclosure_mime_type") val enclosureMimeType: String? = null,
    @ColumnInfo(name = "enclosure_length_bytes") val enclosureLengthBytes: Long? = null,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String? = null,
    @ColumnInfo(name = "published_at") val publishedAt: Long? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "season_number") val seasonNumber: Int? = null,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int? = null,
    @ColumnInfo(name = "explicit") val explicit: Boolean? = null,
    @ColumnInfo(name = "episode_type") val episodeType: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "podcast_playback",
    indices = [Index(value = ["last_played_at"])]
)
data class PodcastPlaybackEntity(
    @PrimaryKey @ColumnInfo(name = "episode_id") val episodeId: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0L,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long? = null,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "playback_speed") val playbackSpeed: Float = 1f
)

@Entity(
    tableName = "podcast_downloads",
    indices = [
        Index(value = ["status"]),
        Index("episode_id")
    ]
)
data class PodcastDownloadEntity(
    @PrimaryKey @ColumnInfo(name = "episode_id") val episodeId: Long,
    @ColumnInfo(name = "download_manager_id") val downloadManagerId: Long? = null,
    @ColumnInfo(name = "local_uri") val localUri: String? = null,
    @ColumnInfo(name = "status") val status: PodcastDownloadStatus = PodcastDownloadStatus.NOT_DOWNLOADED,
    @ColumnInfo(name = "requested_at") val requestedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long? = null,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long? = null,
    @ColumnInfo(name = "failure_reason") val failureReason: String? = null
)