package com.bookrio.player.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.core.dispatchers.DispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.player.AudiobookNowPlaying
import com.bookrio.player.engine.AudiobookEngine
import com.bookrio.player.engine.AudiobookState
import com.bookrio.player.engine.ChapterRefresh
import com.bookrio.player.service.AudiobookPlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class PlayerViewModel(
    application: Application,
    private val db: ShelfDatabase,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider
) : AndroidViewModel(application) {

    private val engine = AudiobookEngine(getApplication(), db)
    private val tracker by lazy {
        (getApplication<Application>().applicationContext as com.bookrio.core.di.AppDependenciesProvider).readingTracker
    }

    private var currentBookId: Long = 0L
    private var lastIsPlaying: Boolean = false
    private var nowPlayingJob: Job? = null

    /** True once the service has delivered a real now-playing snapshot. */
    private var gotNowPlaying: Boolean = false

    private val _serviceBound = MutableStateFlow(false)
    val serviceBound: StateFlow<Boolean> = _serviceBound.asStateFlow()

    private var service: AudiobookPlaybackService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // #region debug-point D:service-connected
            dbg("D", "service-connected", "component=${name?.className ?: ""} binder=${binder?.javaClass?.name ?: "null"}")
            // #endregion
            val localBinder = binder as? AudiobookPlaybackService.LocalBinder
            service = localBinder?.getService()
            _serviceBound.value = true
            observeNowPlaying()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // #region debug-point D:service-disconnected
            dbg("D", "service-disconnected", "component=${name?.className ?: ""}")
            // #endregion
            nowPlayingJob?.cancel()
            nowPlayingJob = null
            service = null
            _serviceBound.value = false
        }
    }

    private val _state = MutableStateFlow(
        AudiobookState(
            title = "",
            author = "",
            format = FormatEntity.UNKNOWN,
            type = BookTypeEntity.AUDIOBOOK,
            coverPath = null,
            mediaUri = null,
            durationMs = 0L,
            currentMs = 0L,
            isPlaying = false,
            playbackSpeed = 1.0f,
            chapters = emptyList(),
            currentChapterIndex = 0,
            percent = 0f,
            sleepTimerMinutes = null,
            error = null
        )
    )
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    fun load(bookId: Long) {
        currentBookId = bookId
        lastIsPlaying = false
        gotNowPlaying = false
        tracker.startSession(bookId.toString(), com.bookrio.core.gamification.model.SessionSource.TTS)

        viewModelScope.launch(dispatchers.io) {
            val initialState = engine.loadBook(bookId)
            // #region debug-point E:player-load-state
            dbg(
                "E",
                "player-load-state",
                "bookId=$bookId mediaUri=${initialState.mediaUri ?: ""} format=${initialState.format} error=${initialState.error ?: ""}"
            )
            // #endregion
            // If the service already pushed live playback state, only fill in the
            // static fields (cover/uri/format) — never clobber the live position.
            if (gotNowPlaying) {
                val live = _state.value
                _state.value = live.copy(
                    title = live.title.ifBlank { initialState.title },
                    author = live.author.ifBlank { initialState.author },
                    format = initialState.format,
                    type = initialState.type,
                    coverPath = initialState.coverPath,
                    mediaUri = initialState.mediaUri,
                    durationMs = if (live.durationMs > 0L) live.durationMs else initialState.durationMs,
                    chapters = live.chapters.ifEmpty { initialState.chapters },
                    error = initialState.error
                )
            } else {
                _state.value = initialState
            }
        }

        val intent = Intent(getApplication(), AudiobookPlaybackService::class.java).apply {
            action = AudiobookPlaybackService.ACTION_LOAD_BOOK
            putExtra(AudiobookPlaybackService.EXTRA_BOOK_ID, bookId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // #region debug-point D:start-fgs
                dbg("D", "start-foreground-service-attempt", "bookId=$bookId sdk=${Build.VERSION.SDK_INT}")
                // #endregion
                startForegroundServiceCompat(intent)
                // #region debug-point D:start-fgs-ok
                dbg("D", "start-foreground-service-ok", "bookId=$bookId")
                // #endregion
            } catch (t: Throwable) {
                // #region debug-point D:start-fgs-fail
                dbg("D", "start-foreground-service-failed", "bookId=$bookId error=${t::class.java.simpleName}:${t.message}")
                // #endregion
                throw t
            }
        } else {
            getApplication<Application>().startService(intent)
        }

        val bindIntent = Intent(getApplication(), AudiobookPlaybackService::class.java)
        try {
            val bound = getApplication<Application>().bindService(
                bindIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            // #region debug-point D:bind-service
            dbg("D", "bind-service-result", "bookId=$bookId bound=$bound")
            // #endregion
        } catch (t: Throwable) {
            // #region debug-point D:bind-service-fail
            dbg("D", "bind-service-failed", "bookId=$bookId error=${t::class.java.simpleName}:${t.message}")
            // #endregion
            throw t
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startForegroundServiceCompat(intent: Intent) {
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    /**
     * The service owns the now-playing snapshot; the UI just observes it. This
     * replaces the old 500 ms poll of the binder (which also kept running while
     * paused and could republish a stale mini-player state).
     */
    private fun observeNowPlaying() {
        val svc = service ?: return
        nowPlayingJob?.cancel()
        nowPlayingJob = viewModelScope.launch {
            svc.nowPlaying.collect { np -> applyNowPlaying(np) }
        }
    }

    private fun applyNowPlaying(np: AudiobookNowPlaying) {
        if (np.bookId <= 0L) return
        val current = _state.value
        if (currentBookId > 0L && np.bookId != currentBookId) return
        gotNowPlaying = true

        val durationMs = if (np.durationMs > 0L) np.durationMs else current.durationMs
        val positionMs = if (durationMs > 0L) np.positionMs else current.currentMs
        val percent = if (durationMs > 0L) positionMs.toFloat() / durationMs.toFloat() else current.percent
        val sleepMinutes = if (np.sleepTimerRemainingMs > 0L) {
            (np.sleepTimerRemainingMs / 60_000L).toInt().coerceAtLeast(1)
        } else {
            null
        }

        _state.value = current.copy(
            title = np.title.ifBlank { current.title },
            author = np.author.ifBlank { current.author },
            durationMs = durationMs,
            currentMs = positionMs,
            isPlaying = np.isPlaying,
            playbackSpeed = np.playbackSpeed,
            chapters = np.chapters.ifEmpty { current.chapters },
            currentChapterIndex = np.chapterIndex,
            percent = percent.coerceIn(0f, 1f),
            sleepTimerMinutes = sleepMinutes,
            sleepTimerRemainingMs = np.sleepTimerRemainingMs,
            error = null
        )

        if (np.isPlaying != lastIsPlaying) {
            lastIsPlaying = np.isPlaying
            tracker.updateTtsPlaybackState(np.isPlaying)
        }
    }

    // #region debug-point shared:player-http
    private fun dbg(hypothesisId: String, msg: String, data: String) {
        Thread {
            try {
                val safeMsg = msg.replace("\\", "/").replace("\"", "'").replace("\n", " ")
                val safeData = data.replace("\\", "/").replace("\"", "'").replace("\n", " ")
                val body = """{"sessionId":"ebook-audio-crash","runId":"pre-fix","hypothesisId":"$hypothesisId","location":"PlayerViewModel","msg":"[DEBUG] $safeMsg","data":{"info":"$safeData"},"ts":${System.currentTimeMillis()}}"""
                val conn = (URL("http://192.168.1.10:7777/event").openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                runCatching { conn.inputStream.close() }
                conn.disconnect()
            } catch (_: Throwable) {
            }
        }.start()
    }
    // #endregion

    fun playPause() {
        service?.playPause()
    }

    /**
     * GLOBAL søk: mål på hele boktidslinjen (0..global varighet). Løses i
     * service: global tid → kapittel/MediaItem → klipp-relativ posisjon. All
     * spoling (±30 s, ±5 min, kapittelliste, slider) går hit — ÉN metode.
     */
    fun seekToGlobal(ms: Long) {
        val duration = _state.value.durationMs
        val clamped = ChapterRefresh.quickSeekTarget(ms, 0L, duration)
        service?.seekTo(clamped)
    }

    fun seekTo(ms: Long) = seekToGlobal(ms)

    /** Hurtigsøk på GLOBAL boktid: +30 s / +5 min (stort hopp i lange bøker). */
    fun skipForward(ms: Long = 30_000L) {
        val s = _state.value
        seekToGlobal(ChapterRefresh.quickSeekTarget(s.currentMs, ms, s.durationMs))
    }

    /** Hurtigsøk på GLOBAL boktid: −30 s / −5 min, clampet til 0. */
    fun skipBack(ms: Long = 30_000L) {
        val s = _state.value
        seekToGlobal(ChapterRefresh.quickSeekTarget(s.currentMs, -ms, s.durationMs))
    }

    /**
     * Forrige kapittel: litt inne i kapittelet → kapittelstart; ved start →
     * forrige kapittels start. Kun meningsfullt med > 1 reelle kapitler.
     */
    fun prevChapter() {
        val current = _state.value
        if (current.chapters.size <= 1) return
        val idx = current.currentChapterIndex.coerceIn(0, current.chapters.size - 1)
        val ch = current.chapters[idx]
        if (current.currentMs - ch.startMs > 3_000L) {
            seekToGlobal(ch.startMs)
        } else {
            seekToGlobal(current.chapters.getOrNull(idx - 1)?.startMs ?: ch.startMs)
        }
    }

    /** Neste kapittel; deaktivert (no-op) i siste kapittel. */
    fun nextChapter() {
        val current = _state.value
        if (current.chapters.size <= 1) return
        if (current.currentChapterIndex >= current.chapters.size - 1) return
        seekToGlobal(current.chapters.getOrNull(current.currentChapterIndex + 1)?.startMs ?: return)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        service?.setSpeed(clamped)
    }

    fun setSleepTimer(minutes: Int?) {
        _state.value = _state.value.copy(sleepTimerMinutes = minutes)

        viewModelScope.launch(dispatchers.main) {
            val svc = service ?: return@launch
            if (minutes != null) {
                svc.startSleepTimer(minutes)
            } else {
                svc.cancelSleepTimer()
            }
        }
    }

    override fun onCleared() {
        nowPlayingJob?.cancel()
        nowPlayingJob = null
        tracker.updateTtsPlaybackState(false)
        tracker.endSession()
        if (_serviceBound.value) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (_: Exception) {
            }
        }
        service = null
        _serviceBound.value = false
        super.onCleared()
    }
}
