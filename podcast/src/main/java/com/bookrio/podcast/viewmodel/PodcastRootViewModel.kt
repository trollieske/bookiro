package com.bookrio.podcast.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.dao.PodcastFeedSummary
import com.bookrio.data.local.dao.PodcastResumeItem
import com.bookrio.data.repository.PodcastActiveState
import com.bookrio.data.repository.PodcastPlaybackState
import com.bookrio.podcast.data.download.PodcastDownloads
import com.bookrio.podcast.data.repository.PodcastRepository
import com.bookrio.podcast.domain.ParsedPodcastFeed
import com.bookrio.podcast.domain.PodcastResumeCandidate
import com.bookrio.podcast.domain.PodcastResumePolicy
import com.bookrio.podcast.worker.PodcastFeedSyncWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastRootUiState(
    val summaries: List<PodcastFeedSummary> = emptyList(),
    val resumeItems: List<PodcastResumeItem> = emptyList(),
    val playerState: PodcastActiveState? = null
) {
    val totalNew: Int get() = summaries.sumOf { it.unplayedCount }
    val hasFollows: Boolean get() = summaries.isNotEmpty()
}

/** Result of following a feed from the root "Add RSS feed" flow. */
sealed interface PodcastFollowEvent {
    data class Success(val feedId: Long, val alreadyFollowed: Boolean) : PodcastFollowEvent
    data class Failure(val code: String) : PodcastFollowEvent
}

/** Live preview state while the user pastes a feed URL. */
sealed interface PodcastPreviewState {
    data object Idle : PodcastPreviewState
    data object Loading : PodcastPreviewState
    data class Ready(val feed: ParsedPodcastFeed) : PodcastPreviewState
    data class Error(val code: String) : PodcastPreviewState
}

class PodcastRootViewModel(
    application: Application,
    private val db: ShelfDatabase
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

    val state: StateFlow<PodcastRootUiState> = combine(
        db.podcastFeedDao().observeSummaries(),
        db.podcastEpisodeDao().observeResumeItems(25),
        PodcastPlaybackState.state
    ) { summaries, resume, player ->
        PodcastRootUiState(
            summaries = summaries,
            resumeItems = applyResumePolicy(resume),
            playerState = player
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastRootUiState())

    /**
     * True while the manual "refresh all" WorkManager job is queued or running, so
     * the header can show a spinner instead of a dead button.
     */
    val isRefreshing: StateFlow<Boolean> = WorkManager.getInstance(getApplication())
        .getWorkInfosForUniqueWorkFlow(PodcastFeedSyncWorker.WORK_ALL)
        .map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), false)

    private fun applyResumePolicy(items: List<PodcastResumeItem>): List<PodcastResumeItem> {
        val byId = items.associateBy { it.episodeId }
        return PodcastResumePolicy.active(
            items.map {
                PodcastResumeCandidate(
                    episodeId = it.episodeId,
                    positionMs = it.positionMs,
                    isCompleted = it.isCompleted,
                    lastPlayedAt = it.lastPlayedAt,
                    feedFollowed = it.feedFollowed
                )
            }
        ).mapNotNull { byId[it.episodeId] }
    }

    private val _followEvent = MutableStateFlow<PodcastFollowEvent?>(null)
    val followEvent: StateFlow<PodcastFollowEvent?> = _followEvent.asStateFlow()

    private val _preview = MutableStateFlow<PodcastPreviewState>(PodcastPreviewState.Idle)
    val preview: StateFlow<PodcastPreviewState> = _preview.asStateFlow()

    fun previewFeed(rawUrl: String) {
        _preview.value = PodcastPreviewState.Loading
        viewModelScope.launch {
            repository.previewFeed(rawUrl)
                .onSuccess { _preview.value = PodcastPreviewState.Ready(it) }
                .onFailure { t ->
                    _preview.value = PodcastPreviewState.Error(
                        (t as? com.bookrio.podcast.data.remote.PodcastNetworkException)?.code
                            ?: (t as? com.bookrio.podcast.data.remote.PodcastFeedParseException)?.code
                            ?: "feed"
                    )
                }
        }
    }

    fun clearPreview() {
        _preview.value = PodcastPreviewState.Idle
    }

    fun consumeFollowEvent() {
        _followEvent.value = null
    }

    fun refreshAll() {
        PodcastFeedSyncWorker.enqueueAll(getApplication())
        reconcileDownloads()
    }

    fun reconcileDownloads() {
        viewModelScope.launch { runCatching { downloads.reconcile() } }
    }

    fun followFromUrl(rawUrl: String) {
        viewModelScope.launch {
            val result = repository.followFeed(rawUrl)
            result.onSuccess { outcome ->
                PodcastFeedSyncWorker.enqueueFeed(getApplication(), outcome.feedId)
                _followEvent.value = PodcastFollowEvent.Success(outcome.feedId, outcome.alreadyFollowed)
            }.onFailure { t ->
                _followEvent.value = PodcastFollowEvent.Failure(
                    (t as? com.bookrio.podcast.data.remote.PodcastNetworkException)?.code
                        ?: (t as? com.bookrio.podcast.data.remote.PodcastFeedParseException)?.code
                        ?: "feed"
                )
            }
        }
    }

    fun unfollow(feedId: Long) {
        viewModelScope.launch { repository.setFollowed(feedId, false) }
    }
}