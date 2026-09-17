package com.shelf.reader.podcast.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shelf.reader.core.playback.PlaybackArbiter
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.data.prefs.UserPreferencesRepository
import com.shelf.reader.data.repository.PodcastPlaybackState
import com.shelf.reader.podcast.R
import com.shelf.reader.podcast.data.repository.PodcastRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated background playback service for podcast episodes.
 *
 * Deliberately separate from [com.shelf.reader.player.service.AudiobookPlaybackService]:
 * one episode equals one media item (never an audiobook chapter), it uses its own
 * MediaSession id, its own ExoPlayer and its own notification. The two engines are
 * coordinated only through [PlaybackArbiter].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PodcastPlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PodcastPlaybackService"
        const val CHANNEL_ID = "podcast_playback_channel"
        const val NOTIFICATION_ID = 8899
        const val ACTION_LOAD_EPISODE = "com.shelf.reader.podcast.LOAD_EPISODE"
        const val EXTRA_EPISODE_ID = "extra_episode_id"
        const val SEEK_BACK_MS = 30_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val CMD_SKIP_BACK = "CMD_PODCAST_SKIP_BACK"
        const val CMD_SKIP_FORWARD = "CMD_PODCAST_SKIP_FORWARD"
        const val CMD_SET_SLEEP = "CMD_PODCAST_SET_SLEEP"
        const val ACTION_SKIP_BACK = "com.shelf.reader.podcast.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "com.shelf.reader.podcast.SKIP_FORWARD"
        const val ACTION_STOP = "com.shelf.reader.podcast.STOP"

        /** Fraction of the episode after which it counts as completed. */
        const val COMPLETION_PERCENT = 0.98f
        /** Also treat the final 30 seconds as completed. */
        const val COMPLETION_TAIL_MS = 30_000L
    }

    // ExoPlayer may only be accessed from the main thread, so the service scope is
    // main-dispatched. Any blocking work (network, files) dispatches to IO itself.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "service coroutine failed", t)
        }
    )
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var db: ShelfDatabase? = null
    private var repository: PodcastRepository? = null
    private var prefs: UserPreferencesRepository? = null
    private var currentEpisodeId: Long = -1L
    private var currentFeedId: Long = -1L
    private var tickerJob: Job? = null
    private var loadWatchdogJob: Job? = null
    private var sleepTimer: android.os.CountDownTimer? = null
    private var sleepTimerEndTimeMs: Long = 0L
    private val binder = LocalBinder()

    /** Single writer of the engine's now-playing snapshot (UI + mini-player). */
    private val _nowPlaying = MutableStateFlow(PodcastNowPlaying())
    val nowPlaying: StateFlow<PodcastNowPlaying> = _nowPlaying.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): PodcastPlaybackService = this@PodcastPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val database = ShelfDatabase.getInstance(applicationContext)
        db = database
        prefs = UserPreferencesRepository(applicationContext)
        repository = PodcastRepository(
            feedDao = database.podcastFeedDao(),
            episodeDao = database.podcastEpisodeDao(),
            playbackDao = database.podcastPlaybackDao(),
            downloadDao = database.podcastDownloadDao()
        )

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    serviceScope.launch { finishEpisode() }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // A real playback start owns the single audio output: stop audiobooks.
                    // Loading an episode does NOT do this — only actually playing it does.
                    PlaybackArbiter.stopOthers(PlaybackArbiter.ID_PODCAST)
                    ensureTicker()
                } else {
                    persistProgress()
                }
                publishState()
            }
        })
        player = exo

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_SKIP_BACK, Bundle()))
                    .add(SessionCommand(CMD_SKIP_FORWARD, Bundle()))
                    .add(SessionCommand(CMD_SET_SLEEP, Bundle()))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val p = player ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                return when (customCommand.customAction) {
                    CMD_SKIP_BACK -> {
                        p.seekTo((p.currentPosition - SEEK_BACK_MS).coerceAtLeast(0L))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SKIP_FORWARD -> {
                        val max = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        p.seekTo((p.currentPosition + SEEK_FORWARD_MS).coerceAtMost(max))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SET_SLEEP -> {
                        val minutes = args.getInt("minutes", -1)
                        if (minutes >= 0) startSleepTimer(minutes) else cancelSleepTimer()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            }
        }

        val sessionIntent = Intent().apply {
            setClassName(this@PodcastPlaybackService, "com.shelf.reader.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, exo)
            .setId(PlaybackArbiter.ID_PODCAST)
            .setSessionActivity(pi)
            .setCallback(sessionCallback)
            .build()

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                session: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                val skipBackIntent = Intent(this@PodcastPlaybackService, PodcastPlaybackService::class.java)
                    .setAction(ACTION_SKIP_BACK)
                val skipBackPi = PendingIntent.getService(
                    this@PodcastPlaybackService, 2001, skipBackIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val skipForwardIntent = Intent(this@PodcastPlaybackService, PodcastPlaybackService::class.java)
                    .setAction(ACTION_SKIP_FORWARD)
                val skipForwardPi = PendingIntent.getService(
                    this@PodcastPlaybackService, 2002, skipForwardIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val meta = session.player.mediaMetadata
                val notification = androidx.core.app.NotificationCompat.Builder(
                    this@PodcastPlaybackService, CHANNEL_ID
                )
                    .setContentTitle(meta.title ?: getString(R.string.pod_notif_title))
                    .setContentText(meta.artist ?: "")
                    .setSubText(meta.albumTitle)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(session.player.isPlaying)
                    .setShowWhen(false)
                    .setContentIntent(session.sessionActivity)
                    .addAction(
                        android.R.drawable.ic_media_rew,
                        getString(R.string.pod_notif_skip_back),
                        skipBackPi
                    )
                    .addAction(
                        android.R.drawable.ic_media_play,
                        getString(R.string.pod_notif_play),
                        actionFactory.createMediaActionPendingIntent(session, Player.COMMAND_PLAY_PAUSE.toLong())
                    )
                    .addAction(
                        android.R.drawable.ic_media_ff,
                        getString(R.string.pod_notif_skip_forward),
                        skipForwardPi
                    )
                    .setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                    .build()
                return MediaNotification(NOTIFICATION_ID, notification)
            }

            override fun handleCustomCommand(session: MediaSession, action: String, args: Bundle): Boolean = false
        })

        PlaybackArbiter.register(PlaybackArbiter.ID_PODCAST) { stopForOtherMedia() }
        ensureTicker()
    }

    /** Called by [PlaybackArbiter] when audiobooks take over. */
    private fun stopForOtherMedia() {
        // Runs synchronously when already on the main thread so the audio output is
        // released before the new engine starts.
        onMain {
            runCatching {
                persistProgress()
                // Hide + suppress first, so the pause callback cannot re-publish the bar
                // and briefly steal it back from the audiobook that just started.
                PodcastPlaybackState.dismiss()
                player?.pause()
                player?.playWhenReady = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Always satisfy the startForegroundService() contract; the live media
        // notification replaces this placeholder as soon as the player reports the
        // seek / metadata change.
        startForegroundLoading()

        when (action) {
            ACTION_STOP -> {
                // Mini-player close: pause and hide, without tearing down the service.
                onMain {
                    player?.pause()
                    player?.playWhenReady = false
                    PodcastPlaybackState.dismiss()
                }
                if (player?.mediaItemCount == 0) stopForegroundAndSelf()
            }
            ACTION_SKIP_BACK -> player?.let { it.seekTo((it.currentPosition - SEEK_BACK_MS).coerceAtLeast(0L)) }
            ACTION_SKIP_FORWARD -> player?.let {
                val max = it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                it.seekTo((it.currentPosition + SEEK_FORWARD_MS).coerceAtMost(max))
            }
            ACTION_LOAD_EPISODE -> {
                val episodeId = intent.getLongExtra(EXTRA_EPISODE_ID, -1L)
                if (episodeId > 0L) {
                    loadEpisode(episodeId, autoPlay = true)
                    startLoadWatchdog()
                } else {
                    stopForegroundAndSelf()
                }
            }
            else -> {
                // Unknown or null restart intent: do not linger with a placeholder.
                if (player?.mediaItemCount == 0) stopForegroundAndSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Foreground placeholder for the brief window before an episode is prepared. */
    private fun startForegroundLoading() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        val initial = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.pod_notif_loading))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, initial, type)
            } else {
                startForeground(NOTIFICATION_ID, initial)
            }
        }
    }

    /** Removes the placeholder notification and stops the service. */
    private fun stopForegroundAndSelf() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        // Also clear a notification orphaned by a previous process kill.
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    /**
     * Safety net: if loading never produces a playable item (offline stream, missing
     * download, exception) the "Loading\u2026" notification must not become permanent.
     */
    private fun startLoadWatchdog() {
        loadWatchdogJob?.cancel()
        loadWatchdogJob = serviceScope.launch(Dispatchers.Main) {
            delay(20_000L)
            val p = player
            if (p == null || p.mediaItemCount == 0) {
                Log.w(TAG, "load watchdog: no media item after timeout; stopping")
                stopForegroundAndSelf()
            }
        }
    }

    fun loadEpisode(episodeId: Long, autoPlay: Boolean = true) {
        val p = player ?: return
        val repo = repository ?: return
        // NOTE: audio arbitration happens when playback actually starts
        // (onIsPlayingChanged), not on load — opening an episode must not kill an
        // audiobook that is currently playing.
        currentEpisodeId = episodeId
        serviceScope.launch {
            val episode = repo.getEpisode(episodeId)
            if (episode == null) {
                Log.e(TAG, "loadEpisode: episode $episodeId not found")
                stopForegroundAndSelf()
                return@launch
            }
            currentFeedId = episode.feedId
            val feed = repo.getFeed(episode.feedId)
            val source = repo.resolvePlaybackSource(episodeId)
            if (source == null) {
                Log.e(TAG, "loadEpisode: no playback source for $episodeId")
                stopForegroundAndSelf()
                return@launch
            }
            val playback = repo.getPlayback(episodeId)
            val artwork = episode.artworkUrl ?: feed?.artworkUrl

            val metadata = MediaMetadata.Builder()
                .setTitle(episode.title)
                .setDisplayTitle(episode.title)
                .setArtist(feed?.title ?: "")
                .setAlbumTitle(feed?.title)
                .setSubtitle(feed?.title)
                .setIsPlayable(true)
                .apply {
                    artwork?.takeIf { it.isNotBlank() }?.let {
                        runCatching { setArtworkUri(android.net.Uri.parse(it)) }
                    }
                }
                .build()
            val item = MediaItem.Builder()
                .setUri(source.uri)
                .setMediaId("episode_$episodeId")
                .setMediaMetadata(metadata)
                .build()

            val globalSpeed = runCatching { prefs?.podcastSpeed?.first() }.getOrNull() ?: 1f
            withContext(Dispatchers.Main) {
                p.setMediaItem(item)
                loadWatchdogJob?.cancel()
                p.setPlaybackSpeed(globalSpeed.coerceIn(0.5f, 3f))
                val start = if (playback?.isCompleted == true) 0L else playback?.positionMs ?: 0L
                if (start > 0L) p.seekTo(start)
                p.prepare()
                p.playWhenReady = autoPlay
            }
            ensureTicker()
            publishState()
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            var tick = 0
            while (isActive) {
                if (player?.isPlaying == true) {
                    publishState()
                    if (tick % 5 == 0) persistProgress()
                    maybeMarkCompleted()
                }
                tick++
                delay(1000L)
            }
        }
    }

    private fun maybeMarkCompleted() {
        val p = player ?: return
        if (currentEpisodeId <= 0L) return
        val duration = p.duration
        if (duration <= 0L) return
        val pos = p.currentPosition
        val nearEnd = pos >= duration - COMPLETION_TAIL_MS
        val pastThreshold = pos.toFloat() / duration.toFloat() >= COMPLETION_PERCENT
        if (nearEnd || pastThreshold) {
            serviceScope.launch { finishEpisode() }
        }
    }

    private suspend fun finishEpisode() {
        val episodeId = currentEpisodeId
        if (episodeId <= 0L) return
        val repo = repository ?: return
        repo.savePlayback(episodeId, 0L, player?.duration?.takeIf { it > 0 }, completed = true)
        PodcastPlaybackState.dismiss()
    }

    private fun persistProgress() {
        val episodeId = currentEpisodeId
        if (episodeId <= 0L) return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 }
        val repo = repository ?: return
        serviceScope.launch {
            if (pos > 0L) repo.savePlayback(episodeId, pos, dur, completed = false)
        }
    }

    private fun publishState() {
        val episodeId = currentEpisodeId
        if (episodeId <= 0L) return
        val p = player ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        val meta = p.mediaMetadata
        val remaining = sleepTimerRemainingMs()
        val snapshot = PodcastNowPlaying(
            episodeId = episodeId,
            feedId = currentFeedId,
            title = meta.title?.toString() ?: "",
            podcastTitle = meta.albumTitle?.toString() ?: "",
            artworkUrl = meta.artworkUri?.toString(),
            isPlaying = p.isPlaying,
            positionMs = pos,
            durationMs = dur,
            playbackSpeed = p.playbackParameters.speed,
            sleepTimerRemainingMs = remaining
        )
        _nowPlaying.value = snapshot
        PodcastPlaybackState.update(
            episodeId = episodeId,
            feedId = currentFeedId,
            title = snapshot.title,
            podcastTitle = snapshot.podcastTitle,
            artworkUrl = snapshot.artworkUrl,
            isPlaying = snapshot.isPlaying,
            progressPercent = if (dur > 0L) pos.toFloat() / dur.toFloat() else 0f,
            positionMs = pos,
            durationMs = dur,
            sleepTimerRemainingMs = remaining
        )
    }

    private fun ensureChannel() {
        val mgr = NotificationManagerCompat.from(this)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.pod_notif_channel))
                .setDescription(getString(R.string.pod_notif_channel_desc))
                .setShowBadge(false)
                .build()
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player ?: return
        if (!p.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        cancelSleepTimer()
        PlaybackArbiter.unregister(PlaybackArbiter.ID_PODCAST)
        // Synchronous so the bar never outlives the service, even if the coroutine
        // below is cancelled before it runs.
        PodcastPlaybackState.dismiss()
        _nowPlaying.value = PodcastNowPlaying()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        serviceScope.launch(Dispatchers.Main) {
            persistProgress()
            session?.release()
            player?.stop()
            player?.release()
            session = null
            player = null
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // ---- Local binder API used by the player screen ----

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    // ---- Sleep timer ----

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        val totalMs = minutes * 60L * 1000L
        sleepTimerEndTimeMs = System.currentTimeMillis() + totalMs
        sleepTimer = object : android.os.CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val p = player ?: return
                // Fade out over the final 30 seconds.
                if (millisUntilFinished in 1L until 30_000L) {
                    p.volume = (millisUntilFinished.toFloat() / 30_000f).coerceIn(0.05f, 1.0f)
                } else if (p.volume < 1.0f && millisUntilFinished >= 30_000L) {
                    p.volume = 1.0f
                }
                // Keep the mini-player countdown live even while paused.
                publishState()
            }

            override fun onFinish() {
                val p = player
                if (p != null) {
                    p.volume = 1.0f
                    if (p.isPlaying) p.playWhenReady = false
                }
                persistProgress()
                sleepTimer = null
                sleepTimerEndTimeMs = 0L
                publishState()
            }
        }.start()
        publishState()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerEndTimeMs = 0L
        player?.volume = 1.0f
        publishState()
    }

    fun sleepTimerRemainingMs(): Long {
        if (sleepTimer == null || sleepTimerEndTimeMs <= 0L) return 0L
        return (sleepTimerEndTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun playPause() {
        onMain {
            player?.let { it.playWhenReady = !it.playWhenReady }
        }
    }

    fun seekTo(ms: Long) {
        onMain {
            val p = player ?: return@onMain
            p.seekTo(com.shelf.reader.podcast.domain.PodcastSeek.clamp(ms, p.duration.takeIf { d -> d > 0 } ?: 0L))
            publishState()
        }
    }

    fun skipBack() = seekTo(currentPositionMs() - SEEK_BACK_MS)
    fun skipForward() = seekTo(currentPositionMs() + SEEK_FORWARD_MS)

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3f)
        onMain {
            player?.setPlaybackSpeed(clamped)
            publishState()
        }
        val id = currentEpisodeId
        serviceScope.launch {
            if (id > 0L) repository?.savePlaybackSpeed(id, clamped)
            runCatching { prefs?.setPodcastSpeed(clamped) }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            serviceScope.launch(Dispatchers.Main) { block() }
        }
    }
}