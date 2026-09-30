package com.bookrio.podcast.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.podcast.data.remote.NetworkStatus
import com.bookrio.podcast.data.repository.PodcastRepository
import com.bookrio.podcast.playback.PodcastNowPlaying
import com.bookrio.podcast.playback.PodcastPlaybackLauncher
import com.bookrio.podcast.playback.PodcastPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PodcastPlayerUiState(
    val episodeId: Long = 0L,
    val feedId: Long = 0L,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isLocal: Boolean = false,
    val serviceBound: Boolean = false,
    val notFound: Boolean = false,
    val offlineBlocked: Boolean = false,
    val sleepTimerMinutes: Int? = null,
    val sleepTimerRemainingMs: Long = 0L
)

class PodcastPlayerViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val initialEpisodeId: Long
) : AndroidViewModel(application) {

    private val repository = PodcastRepository(
        feedDao = db.podcastFeedDao(),
        episodeDao = db.podcastEpisodeDao(),
        playbackDao = db.podcastPlaybackDao(),
        downloadDao = db.podcastDownloadDao()
    )

    private val _state = MutableStateFlow(PodcastPlayerUiState(episodeId = initialEpisodeId))
    val state: StateFlow<PodcastPlayerUiState> = _state.asStateFlow()

    private var service: PodcastPlaybackService? = null
    private var nowPlayingJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? PodcastPlaybackService.LocalBinder
            service = local?.getService()
            _state.value = _state.value.copy(serviceBound = service != null)
            observeNowPlaying()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            nowPlayingJob?.cancel()
            nowPlayingJob = null
            service = null
            _state.value = _state.value.copy(serviceBound = false)
        }
    }

    init {
        viewModelScope.launch {
            val episode = repository.getEpisode(initialEpisodeId)
            if (episode == null) {
                _state.value = _state.value.copy(notFound = true)
                return@launch
            }
            val feed = repository.getFeed(episode.feedId)
            val source = repository.resolvePlaybackSource(initialEpisodeId)
            val local = source?.isLocal == true
            _state.value = _state.value.copy(
                feedId = episode.feedId,
                title = episode.title,
                podcastTitle = feed?.title.orEmpty(),
                artworkUrl = episode.artworkUrl ?: feed?.artworkUrl,
                isLocal = local
            )
            if (source == null) {
                _state.value = _state.value.copy(notFound = true)
                return@launch
            }
            // Offline and nothing downloaded: never attempt a stream that cannot work.
            if (!local && !NetworkStatus.isOnline(getApplication())) {
                _state.value = _state.value.copy(offlineBlocked = true)
                return@launch
            }
            startPlaybackAndBind()
        }
    }

    private fun startPlaybackAndBind() {
        // Reaching this screen is always an explicit user gesture (episode tap,
        // resume card, mini-player tap), so it may take ownership back from the
        // audiobook instead of silently doing nothing.
        PodcastPlaybackLauncher.play(getApplication(), initialEpisodeId, force = true)
        bind()
    }

    private fun bind() {
        runCatching {
            getApplication<Application>().bindService(
                Intent(getApplication(), PodcastPlaybackService::class.java),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    /**
     * The service owns the now-playing snapshot; the UI observes it instead of
     * polling the binder every 500 ms.
     */
    private fun observeNowPlaying() {
        val svc = service ?: return
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            svc.nowPlaying.collect { np -> applyNowPlaying(np) }
        }
    }

    private fun applyNowPlaying(np: PodcastNowPlaying) {
        if (np.episodeId <= 0L) return
        val current = _state.value
        // Ignore a stale snapshot from a different episode than this screen shows.
        if (current.episodeId > 0L && np.episodeId != current.episodeId) return
        val sleepMinutes = if (np.sleepTimerRemainingMs > 0L) {
            (np.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
        } else {
            null
        }
        _state.value = current.copy(
            feedId = if (np.feedId > 0L) np.feedId else current.feedId,
            title = np.title.ifBlank { current.title },
            podcastTitle = np.podcastTitle.ifBlank { current.podcastTitle },
            artworkUrl = np.artworkUrl ?: current.artworkUrl,
            isPlaying = np.isPlaying,
            positionMs = np.positionMs,
            durationMs = if (np.durationMs > 0L) np.durationMs else current.durationMs,
            playbackSpeed = np.playbackSpeed,
            serviceBound = true,
            sleepTimerMinutes = sleepMinutes,
            sleepTimerRemainingMs = np.sleepTimerRemainingMs
        )
    }

    fun playPause() {
        val svc = service
        if (svc == null) {
            // The service was stopped (e.g. a deferred load called stopSelf) and the
            // gesture must not be dropped: re-issue the load as an explicit play.
            PodcastPlaybackLauncher.play(getApplication(), initialEpisodeId, force = true)
            bind()
            return
        }
        // Pass the episode the UI is showing: the service may have lost its own
        // request when it was recreated for the binding.
        svc.playPause(initialEpisodeId)
    }

    fun seekTo(ms: Long) {
        service?.seekTo(ms)
    }

    fun skipBack() {
        service?.skipBack()
    }

    fun skipForward() {
        service?.skipForward()
    }

    fun setSpeed(speed: Float) {
        service?.setSpeed(speed)
    }

    fun setSleepTimer(minutes: Int?) {
        val svc = service ?: return
        if (minutes != null) {
            svc.startSleepTimer(minutes)
        } else {
            svc.cancelSleepTimer()
        }
        _state.value = _state.value.copy(
            sleepTimerMinutes = minutes,
            sleepTimerRemainingMs = if (minutes != null) minutes * 60_000L else 0L
        )
    }

    override fun onCleared() {
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        runCatching { getApplication<Application>().unbindService(connection) }
        service = null
        super.onCleared()
    }
}
