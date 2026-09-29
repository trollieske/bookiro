package com.bookrio.podcast.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getInstance
import com.bookrio.podcast.data.download.PodcastDownloads
import com.bookrio.podcast.data.repository.PodcastRepository
import java.util.concurrent.TimeUnit

/**
 * Background RSS synchronization. Requires network, runs at most once per feed
 * at a time, and isolates per-feed failures so one broken feed cannot stop the
 * others. Never runs on playback ticks.
 */
class PodcastFeedSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = ShelfDatabase.getInstance(applicationContext)
        val repository = PodcastRepository(
            feedDao = db.podcastFeedDao(),
            episodeDao = db.podcastEpisodeDao(),
            playbackDao = db.podcastPlaybackDao(),
            downloadDao = db.podcastDownloadDao()
        )
        val feedId = inputData.getLong(KEY_FEED_ID, KEY_ALL)
        return try {
            if (feedId == KEY_ALL) {
                repository.syncAllFollowed()
            } else {
                repository.syncFeed(feedId)
            }
            runCatching {
                PodcastDownloads(
                    applicationContext,
                    db.podcastDownloadDao(),
                    db.podcastEpisodeDao()
                ).reconcile()
            }
            Result.success()
        } catch (t: Throwable) {
            // Transient failure: let WorkManager retry with backoff.
            Result.retry()
        }
    }

    companion object {
        const val KEY_FEED_ID = "feed_id"
        const val KEY_ALL = -1L

        private const val PERIODIC_WORK = "shelf_podcast_periodic_sync"
        private const val PREFIX_FEED = "shelf_podcast_sync_feed_"
        /** Unique-work name for the manual "refresh all" action (observed by the UI). */
        const val WORK_ALL = "shelf_podcast_sync_all"
        private const val ALL_WORK = WORK_ALL

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Schedules the ~daily background sync. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<PodcastFeedSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .addTag("podcast_sync")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Syncs one feed; repeated calls for the same feed never run concurrently. */
        fun enqueueFeed(context: Context, feedId: Long) {
            val request = OneTimeWorkRequestBuilder<PodcastFeedSyncWorker>()
                .setConstraints(networkConstraints)
                .setInputData(workDataOf(KEY_FEED_ID to feedId))
                .addTag("podcast_sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                PREFIX_FEED + feedId,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /** Syncs all followed feeds; repeated manual refreshes are serialized. */
        fun enqueueAll(context: Context) {
            val request = OneTimeWorkRequestBuilder<PodcastFeedSyncWorker>()
                .setConstraints(networkConstraints)
                .setInputData(workDataOf(KEY_FEED_ID to KEY_ALL))
                .addTag("podcast_sync")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ALL_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}