package com.bookrio.podcast.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.PodcastDownloadEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.podcast.data.download.PodcastDownloads
import com.bookrio.podcast.data.repository.PodcastRepository
import com.bookrio.podcast.worker.PodcastFeedSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastEpisodeRow(
    val episode: PodcastEpisodeEntity,
    val download: PodcastDownloadEntity?,
    val playback: PodcastPlaybackEntity?
)

data class PodcastDetailUiState(
    val feed: PodcastFeedEntity? = null,
    val episodes: List<PodcastEpisodeRow> = emptyList(),
    val isRefreshing: Boolean = false
)

class PodcastDetailViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val feedId: Long
) : AndroidViewModel(application) {

    private val repository = PodcastRepository(
        feedDao = db.podcastFeedDao(),
        episodeDao = db.podcastEpisodeDao(),
        playbackDao = db.podcastPlaybackDao(),
        downloadDao = db.podcastDownloadDao()
    )
    private val downloads = PodcastDownloads(
        application, db.podcastDownloadDao(), db.podcastEpisodeDao()
    )

    private val _refreshing = MutableStateFlow(false)
    private var reconcileJob: Job? = null

    val state: StateFlow<PodcastDetailUiState> = combine(
        db.podcastFeedDao().observeById(feedId),
        db.podcastEpisodeDao().observeByFeed(feedId),
        db.podcastDownloadDao().observeAll(),
        db.podcastPlaybackDao().observeAll(),
        _refreshing
    ) { feed, episodes, downloadRows, playbackRows, refreshing ->
        val downloadMap = downloadRows.associateBy { it.episodeId }
        val playbackMap = playbackRows.associateBy { it.episodeId }
        PodcastDetailUiState(
            feed = feed,
            episodes = episodes.map { PodcastEpisodeRow(it, downloadMap[it.id], playbackMap[it.id]) },
            isRefreshing = refreshing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastDetailUiState())

    init {
        startReconcileLoop()
    }

    /**
     * Reconciles DownloadManager state while any download is active, then stops.
     * No polling when nothing is downloading.
     */
    private fun startReconcileLoop() {
        reconcileJob?.cancel()
        reconcileJob = viewModelScope.launch {
            runCatching { downloads.reconcile() }
            while (true) {
                val active = runCatching { db.podcastDownloadDao().getActive() }.getOrDefault(emptyList())
                if (active.isEmpty()) break
                delay(2_000L)
                runCatching { downloads.reconcile() }
            }
        }
    }

    fun refresh() {
        _refreshing.value = true
        PodcastFeedSyncWorker.enqueueFeed(getApplication(), feedId)
        viewModelScope.launch {
            runCatching { repository.syncFeed(feedId) }
            runCatching { downloads.reconcile() }
            _refreshing.value = false
        }
    }

    fun download(episode: PodcastEpisodeEntity) {
        viewModelScope.launch {
            runCatching {
                if (downloads.enqueue(episode)) startReconcileLoop()
            }
        }
    }

    fun removeDownload(episodeId: Long) {
        viewModelScope.launch { runCatching { downloads.remove(episodeId) } }
    }

    fun retryDownload(episodeId: Long) {
        viewModelScope.launch { runCatching { downloads.retry(episodeId) } }
    }

    fun unfollow() {
        viewModelScope.launch { repository.setFollowed(feedId, false) }
    }

    fun refollow() {
        viewModelScope.launch { repository.setFollowed(feedId, true) }
    }
}