package com.bookrio.podcast.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.prefs.UserPreferencesRepository
import com.bookrio.podcast.data.remote.ItunesSearchClient
import com.bookrio.podcast.data.remote.PodcastNetworkException
import com.bookrio.podcast.data.remote.PodcastUrls
import com.bookrio.podcast.data.repository.PodcastRepository
import com.bookrio.podcast.domain.PodcastCountries
import com.bookrio.podcast.domain.PodcastSearchResult
import com.bookrio.podcast.worker.PodcastFeedSyncWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DiscoverError { OFFLINE, GENERIC }

data class DiscoverUiState(
    val query: String = "",
    val country: String = PodcastCountries.NORWAY,
    val results: List<PodcastSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: DiscoverError? = null,
    val followedUrls: Set<String> = emptySet(),
    val countryChosenByUser: Boolean = false
)

/** Outcome of following a discovery result. */
sealed interface DiscoverFollowEvent {
    data class Success(val feedId: Long, val alreadyFollowed: Boolean) : DiscoverFollowEvent
    data object NoOpenFeed : DiscoverFollowEvent
    data class Failure(val code: String) : DiscoverFollowEvent
}

class PodcastDiscoverViewModel(
    application: Application,
    private val db: ShelfDatabase
) : AndroidViewModel(application) {

    private val prefs = UserPreferencesRepository(application)
    private val repository = PodcastRepository(
        feedDao = db.podcastFeedDao(),
        episodeDao = db.podcastEpisodeDao(),
        playbackDao = db.podcastPlaybackDao(),
        downloadDao = db.podcastDownloadDao()
    )

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val _followEvent = MutableStateFlow<DiscoverFollowEvent?>(null)
    val followEvent: StateFlow<DiscoverFollowEvent?> = _followEvent.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = prefs.podcastCountry.first()
            val country = if (PodcastCountries.isSupported(saved)) {
                saved
            } else {
                PodcastCountries.inferFromLanguage(java.util.Locale.getDefault().language)
            }
            _state.value = _state.value.copy(
                country = country,
                countryChosenByUser = PodcastCountries.isSupported(saved)
            )
            db.podcastFeedDao().observeAllFeedUrls()
                .onEach { urls ->
                    val normalized = urls.mapNotNull { PodcastUrls.normalize(it) }.toSet()
                    _state.value = _state.value.copy(followedUrls = normalized)
                }
                .launchIn(viewModelScope)
        }
    }

    fun consumeFollowEvent() {
        _followEvent.value = null
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _state.value = _state.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(trimmed)
        }
    }

    fun retry() {
        val trimmed = _state.value.query.trim()
        if (trimmed.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(trimmed) }
    }

    fun setCountry(code: String) {
        val normalized = code.uppercase()
        if (!PodcastCountries.isSupported(normalized)) return
        _state.value = _state.value.copy(country = normalized, countryChosenByUser = true)
        viewModelScope.launch { prefs.setPodcastCountry(normalized) }
        val trimmed = _state.value.query.trim()
        if (trimmed.length >= 2) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(trimmed) }
        }
    }

    /** Applies a Start Here shortcut by running its search term through the normal flow. */
    fun applyShortcut(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(term: String) {
        _state.value = _state.value.copy(isSearching = true, error = null)
        try {
            val results = ItunesSearchClient.search(term, _state.value.country)
            // Only apply if the query is still current.
            if (_state.value.query.trim() == term.trim()) {
                _state.value = _state.value.copy(results = results, isSearching = false, error = null)
            }
        } catch (e: PodcastNetworkException) {
            val err = if (e.code == PodcastNetworkException.OFFLINE || e.code == PodcastNetworkException.TIMEOUT) {
                DiscoverError.OFFLINE
            } else {
                DiscoverError.GENERIC
            }
            _state.value = _state.value.copy(isSearching = false, error = err)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(isSearching = false, error = DiscoverError.GENERIC)
        }
    }

    fun follow(result: PodcastSearchResult) {
        viewModelScope.launch {
            var feedUrl = result.feedUrl
            if (feedUrl.isNullOrBlank() && result.collectionId != null) {
                feedUrl = runCatching { ItunesSearchClient.lookupFeedUrl(result.collectionId) }.getOrNull()
            }
            val normalized = PodcastUrls.normalize(feedUrl)
            if (normalized == null) {
                _followEvent.value = DiscoverFollowEvent.NoOpenFeed
                return@launch
            }
            repository.followFeed(normalized).onSuccess { outcome ->
                PodcastFeedSyncWorker.enqueueFeed(getApplication(), outcome.feedId)
                _followEvent.value = DiscoverFollowEvent.Success(outcome.feedId, outcome.alreadyFollowed)
            }.onFailure { t ->
                _followEvent.value = DiscoverFollowEvent.Failure(
                    (t as? PodcastNetworkException)?.code ?: "feed"
                )
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 400L
    }
}