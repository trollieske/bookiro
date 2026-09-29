package com.bookrio.player.service

import com.bookrio.player.R
import com.bookrio.player.engine.localizedChapterTitle

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.getInstance
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import com.bookrio.player.AudiobookNowPlaying
import com.bookrio.player.engine.AudiobookChapter
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/** Intern spesifikasjon for ett MediaItem: kapittel + global start + evt. klipp innenfor samme fil. */
private data class ActiveItemSpec(
    val chapter: AudiobookChapter,
    val globalStartMs: Long,
    val clipStartMs: Long?,
    val clipEndMs: Long?
)

class AudiobookPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "AudiobookPlaybackService"
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 8888
        const val ACTION_LOAD_BOOK = "com.bookrio.player.LOAD_BOOK"
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val SEEK_BACK_MS = 30_000L
        const val SEEK_FORWARD_MS = 30_000L

        const val CMD_SPEED = "CMD_SET_SPEED"
        const val CMD_SKIP_BACK = "CMD_SKIP_BACK"
        const val CMD_SKIP_FORWARD = "CMD_SKIP_FORWARD"
        const val CMD_SET_SLEEP = "CMD_SET_SLEEP"

        const val ACTION_SKIP_BACK = "com.bookrio.player.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "com.bookrio.player.SKIP_FORWARD"
        const val ACTION_STOP = "com.bookrio.player.STOP"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "service coroutine failed", t)
        }
    )
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var librarySession: MediaLibraryService.MediaLibrarySession? = null
    private var currentBookId: Long = -1L
    private var db: ShelfDatabase? = null
    private var chapterEngine: com.bookrio.player.engine.AudiobookEngine? = null
    private var prefs: com.bookrio.data.prefs.UserPreferencesRepository? = null

    /** Live-updated user preferences for skip amounts / default speed (notification + UI). */
    @Volatile private var skipBackSec: Int = 10
    @Volatile private var skipFwdSec: Int = 30
    @Volatile private var defaultSpeed: Float = 1f
    @Volatile private var sleepFadeOut: Boolean = true
    @Volatile private var autoPlayNextInSeries: Boolean = false
    private val binder = LocalBinder()
    private var sleepTimer: android.os.CountDownTimer? = null

    /** Single writer of the engine's now-playing snapshot (UI + mini-player). */
    private val _nowPlaying = MutableStateFlow(AudiobookNowPlaying())
    val nowPlaying: StateFlow<AudiobookNowPlaying> = _nowPlaying.asStateFlow()
    private var progressTickerJob: Job? = null
    private var loadWatchdogJob: Job? = null
    private var lastProgressSaveMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookPlaybackService = this@AudiobookPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: action=${intent?.action}")
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        db = ShelfDatabase.getInstance(applicationContext)
        chapterEngine = com.bookrio.player.engine.AudiobookEngine(applicationContext, db!!)
        prefs = com.bookrio.data.prefs.UserPreferencesRepository(applicationContext).also { p ->
            serviceScope.launch { p.audioSkipBackSec.collect { skipBackSec = it } }
            serviceScope.launch { p.audioSkipFwdSec.collect { skipFwdSec = it } }
            serviceScope.launch { p.audioSpeed.collect { defaultSpeed = it } }
            serviceScope.launch { p.autoSleepFadeOut.collect { sleepFadeOut = it } }
            serviceScope.launch { p.autoPlayNextInSeries.collect { autoPlayNextInSeries = it } }
        }
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK or C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId ?: return
                if (id.startsWith("book_")) {
                    val bookId = runCatching { id.removePrefix("book_").toLong() }.getOrNull() ?: return
                    if (currentBookId != bookId) {
                        val startPlaying = exo.playWhenReady
                        loadBook(bookId)
                        if (startPlaying) serviceScope.launch(Dispatchers.Main) { player?.playWhenReady = true }
                        return
                    }
                }
                // Chapter transition inside the same book: refresh immediately instead
                // of waiting for the next ticker tick.
                publishNowPlaying()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged: $playbackState")
                maybePersistProgress()
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        serviceScope.launch { saveProgress(1.0f) }
                        maybeAutoPlayNext()
                    }
                }
                publishNowPlaying()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged: $isPlaying")
                if (isPlaying) {
                    // A real playback start (play button, notification, Bluetooth, car)
                    // owns the single audio output: stop podcasts through the arbiter.
                    // Loading a book does NOT do this — only actually playing it does.
                    com.bookrio.core.playback.PlaybackArbiter.stopOthers(
                        com.bookrio.core.playback.PlaybackArbiter.ID_AUDIOBOOK
                    )
                }
                maybePersistProgress()
                publishNowPlaying()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "onPlayerError: ${error.message}", error)
            }
        })
        player = exo

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(CMD_SPEED, Bundle()))
                    .add(SessionCommand(CMD_SKIP_BACK, Bundle()))
                    .add(SessionCommand(CMD_SKIP_FORWARD, Bundle()))
                    .add(SessionCommand(CMD_SET_SLEEP, Bundle()))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val p = player ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return when (customCommand.customAction) {
                    CMD_SPEED -> {
                        val speed = args.getFloat("speed", 1.0f).coerceIn(0.5f, 2f)
                        p.setPlaybackSpeed(speed)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SKIP_BACK -> {
                        p.seekTo((p.currentPosition - skipBackSec * 1000L).coerceAtLeast(0L))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SKIP_FORWARD -> {
                        p.seekTo((p.currentPosition + skipFwdSec * 1000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    CMD_SET_SLEEP -> {
                        val minutes = args.getInt("minutes", -1)
                        if (minutes >= 0) {
                            startSleepTimer(minutes)
                        } else {
                            cancelSleepTimer()
                        }
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                }
            }
        }

        val libraryCallback = object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> {
                val root = MediaItem.Builder()
                    .setMediaId("__ROOT__")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(this@AudiobookPlaybackService.getString(R.string.ply_library_root))
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                    )
                    .build()
                return Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val db = db ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                return Futures.immediateFuture(
                    runCatching {
                        val audioFormats = setOf(
                            FormatEntity.M4B, FormatEntity.M4A, FormatEntity.MP3,
                            FormatEntity.AAC, FormatEntity.FLAC, FormatEntity.OGG,
                            FormatEntity.OPUS, FormatEntity.OGG_OPUS, FormatEntity.WAV
                        )
                        val allBooks = runBlocking(Dispatchers.IO) {
                            db.bookDao().observeAll().first().filter { it.format in audioFormats }
                        }
                        val sorted = allBooks.sortedWith(
                            compareByDescending<com.bookrio.data.local.entity.BookEntity> { it.lastOpenedAt ?: 0L }
                                .thenBy { it.title }
                        )
                        val items = sorted.map { book ->
                            val cover = coverArtworkFor(book.id)
                            MediaItem.Builder()
                                .setMediaId("book_${book.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(book.title)
                                        .setDisplayTitle(book.title)
                                        .setArtist(book.author)
                                        .setAlbumTitle(book.title)
                                        .setSubtitle(book.author)
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .apply { cover?.bytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                                        .build()
                                )
                                .build()
                        }
                        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
                    }.getOrElse { t ->
                        Log.e(TAG, "onGetChildren failed", t)
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN)
                    }
                )
            }
        }

        val sessionIntent = Intent().apply {
            setClassName(this@AudiobookPlaybackService, "com.bookrio.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, sessionIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val layoutButtons = listOf(
            CommandButton.Builder()
                .setDisplayName(getString(R.string.ply_notif_skip_back)).setIconResId(android.R.drawable.ic_media_rew)
                .setSessionCommand(SessionCommand(CMD_SKIP_BACK, Bundle()))
                .build(),
            CommandButton.Builder().setDisplayName(getString(R.string.ply_notif_prev)).setIconResId(android.R.drawable.ic_media_previous)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build(),
            CommandButton.Builder().setDisplayName(getString(R.string.ply_notif_play)).setIconResId(android.R.drawable.ic_media_play)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE).build(),
            CommandButton.Builder().setDisplayName(getString(R.string.ply_notif_next)).setIconResId(android.R.drawable.ic_media_next)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build(),
            CommandButton.Builder()
                .setDisplayName(getString(R.string.ply_notif_skip_forward)).setIconResId(android.R.drawable.ic_media_ff)
                .setSessionCommand(SessionCommand(CMD_SKIP_FORWARD, Bundle()))
                .build()
        )

        // ─── ENESTE SESSION! MediaLibrarySession ER en MediaSession (alt fungerer: notif, BT, Auto) ───
        librarySession = MediaLibraryService.MediaLibrarySession.Builder(this, exo, libraryCallback)
            .setSessionActivity(pi)
            .setId("shelf_audio")
            .setCustomLayout(layoutButtons)
            .build()
        session = librarySession

        // Use Media3's well-tested notification provider: correct content intent
        // (opens the app), correct ongoing/dismiss behaviour, working transport
        // actions and Android Auto / Wear integration. The custom layout above
        // supplies the prev/back/play/forward/next buttons.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        // Playback arbitration: podcasts must stop when an audiobook starts.
        com.bookrio.core.playback.PlaybackArbiter.register(
            com.bookrio.core.playback.PlaybackArbiter.ID_AUDIOBOOK
        ) { stopForOtherMedia() }
        ensureProgressTicker()
    }

    /** Called by [com.bookrio.core.playback.PlaybackArbiter] when podcasts take over. */
    private fun stopForOtherMedia() {
        // Runs synchronously when already on the main thread so the audio output is
        // released before the new engine starts.
        runOnMain {
            runCatching {
                maybePersistProgress()
                // Hide + suppress first, so the pause callback cannot re-publish the bar.
                com.bookrio.data.repository.ActivePlaybackState.dismiss()
                player?.pause()
                player?.playWhenReady = false
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        val action = intent?.action
        // Always satisfy the startForegroundService() contract; the live media
        // notification replaces this placeholder as soon as the player reports the
        // seek / metadata change.
        startForegroundLoading()

        when (action) {
            ACTION_STOP -> {
                // Mini-player close: pause and hide, without tearing down the service.
                runOnMain {
                    maybePersistProgress()
                    com.bookrio.data.repository.ActivePlaybackState.dismiss()
                    player?.pause()
                    player?.playWhenReady = false
                }
                if (player?.mediaItemCount == 0) stopForegroundAndSelf()
            }
            ACTION_SKIP_BACK -> player?.let { it.seekTo((it.currentPosition - skipBackSec * 1000L).coerceAtLeast(0L)) }
            ACTION_SKIP_FORWARD -> player?.let { it.seekTo((it.currentPosition + skipFwdSec * 1000L).coerceAtMost(it.duration.coerceAtLeast(0L))) }
            ACTION_LOAD_BOOK -> {
                // NOTE: audio arbitration happens when playback actually starts
                // (onIsPlayingChanged), not on load — opening the player screen must
                // not kill a podcast that is currently playing.
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                if (bookId > 0L) {
                    loadBook(bookId)
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

    /** Foreground placeholder for the brief window before a book is prepared. */
    private fun startForegroundLoading() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        val notif = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ply_notif_loading))
            .setSmallIcon(com.bookrio.designsystem.R.drawable.ic_stat_bookrio)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notif, type)
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
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
     * Safety net: if loading never produces a playable item (missing file, bad
     * source, exception) the "Loading\u2026" notification must not become permanent.
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

    private var activeChapters: List<AudiobookChapter> = emptyList()
    private var activeItemSpecs: List<ActiveItemSpec> = emptyList()

    private fun loadBook(bookId: Long) {
        currentBookId = bookId
        val p = player ?: return
        serviceScope.launch {
            val db = db ?: return@launch
            val book = db.bookDao().getById(bookId) ?: return@launch
            val prog = db.progressDao().getByBook(bookId)?.progressPercent ?: 0f
            val source = run {
                val fp = book.filePath
                val fu = book.fileUri
                when {
                    fp != null && fp.isNotBlank() && java.io.File(fp).canRead() ->
                        Uri.fromFile(java.io.File(fp)).toString()
                    fu != null && fu.isNotBlank() -> fu
                    else -> null
                }
            }

            val cover = withContext(Dispatchers.IO) { coverArtworkFor(book.id, book.coverPath) }

            // KANONISK oppfriskning: oppdager/persisterer reelle kapitler for
            // eksisterende bøker med utdatert metadata (samme sti som ViewModel).
            activeChapters = chapterEngine?.ensureFreshChapters(book)
                ?: parseChapters(book.chaptersJson ?: "")

            if (activeChapters.isNotEmpty()) {
                // Flere kapitler i SAMME fil (M4B/MP3 med innebygde kapitler) får
                // ClippingConfiguration slik at hvert MediaItem spiller KUN sitt intervall.
                // Én fil per kapittel (mappe-import) klippes ikke — startMs er kumulativ.
                val uriCounts = HashMap<String, Int>()
                activeChapters.forEach { ch ->
                    val u = ch.mediaUri ?: source ?: ""
                    uriCounts[u] = (uriCounts[u] ?: 0) + 1
                }
                val uriBases = HashMap<String, Long>()
                val itemSpecs = activeChapters.map { ch ->
                    val u = ch.mediaUri ?: source ?: ""
                    if ((uriCounts[u] ?: 0) > 1) {
                        val base = uriBases.getOrPut(u) { ch.startMs }
                        val clipStart = (ch.startMs - base).coerceAtLeast(0L)
                        val clipEnd = ch.endMs?.takeIf { it > ch.startMs }?.let { it - base }
                        ActiveItemSpec(ch, clipStart, clipStart, clipEnd)
                    } else {
                        ActiveItemSpec(ch, ch.startMs, null, null)
                    }
                }
                activeItemSpecs = itemSpecs

                val mediaItems = itemSpecs.map { spec ->
                    val ch = spec.chapter
                    val uriStr = ch.mediaUri ?: source ?: ""
                    val builder = MediaItem.Builder()
                        .setUri(Uri.parse(uriStr))
                        .setMediaId("${book.id}_${ch.index}")
                    if (spec.clipStartMs != null) {
                        builder.setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(spec.clipStartMs)
                                .setEndPositionMs(
                                    if (spec.clipEndMs != null && spec.clipEndMs > spec.clipStartMs) spec.clipEndMs
                                    else C.TIME_END_OF_SOURCE
                                )
                                .build()
                        )
                    }
                    builder.setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(ch.title)
                            .setArtist(book.author)
                            .setAlbumArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setDisplayTitle(ch.title)
                            .setSubtitle(getString(R.string.ply_chapter_of, ch.index + 1, activeChapters.size))
                            .apply { cover?.bytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                            .build()
                    )
                    builder.build()
                }

                withContext(Dispatchers.Main) {
                    p.setMediaItems(mediaItems)
                    val totalDur = book.durationMs ?: activeChapters.lastOrNull()?.endMs ?: 0L
                    if (prog > 0f && totalDur > 0L) {
                        val targetMs = (prog * totalDur).toLong()
                        val targetIdx = activeChapters.indexOfLast { it.startMs <= targetMs }.coerceAtLeast(0)
                        val offsetMs = (targetMs - (itemSpecs.getOrNull(targetIdx)?.globalStartMs ?: 0L)).coerceAtLeast(0L)
                        p.seekTo(targetIdx, offsetMs)
                    }
                    p.prepare()
                    p.setPlaybackSpeed(defaultSpeed.coerceIn(0.5f, 3f))
                    loadWatchdogJob?.cancel()
                    publishNowPlaying()
                }
            } else if (source != null) {
                val dur = book.durationMs ?: C.TIME_UNSET
                val item = MediaItem.Builder()
                    .setUri(Uri.parse(source))
                    .setMediaId(book.id.toString())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(book.title)
                            .setArtist(book.author)
                            .setAlbumArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setDisplayTitle(book.title)
                            .setSubtitle(book.format.name + " – " + getString(R.string.ply_title))
                            .apply { cover?.bytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                            .build()
                    )
                    .build()
                
                withContext(Dispatchers.Main) {
                    p.setMediaItem(item, (prog * (if (dur == C.TIME_UNSET) 0L else dur).toDouble()).toLong().coerceAtLeast(0L))
                    p.prepare()
                    p.setPlaybackSpeed(defaultSpeed.coerceIn(0.5f, 3f))
                    loadWatchdogJob?.cancel()
                    publishNowPlaying()
                }
            }
        }
    }

    private fun maybePersistProgress() {
        if (currentBookId < 0) return
        val p = player ?: return
        
        // Ensure currentPosition is read on Main thread
        if (p.applicationLooper.thread != Thread.currentThread()) {
            serviceScope.launch(Dispatchers.Main) { maybePersistProgress() }
            return
        }

        val duration = durationMs()
        if (duration <= 0L) return
        val pos = currentPositionMs()
        val pct = pos.toFloat() / duration
        serviceScope.launch { saveProgress(pct.coerceIn(0f, 1f)) }
    }

    private suspend fun saveProgress(pct: Float) {
        if (currentBookId < 0) return
        db?.progressDao()?.insertOrReplace(
            ReadingProgressEntity(bookId = currentBookId, progressPercent = pct)
        )
    }

    /**
     * When a book finishes and "Auto-play next in series" is enabled, continue with
     * the next book of the same series (by seriesIndex). Otherwise retire the bar.
     */
    private fun maybeAutoPlayNext() {
        fun retire() = com.bookrio.data.repository.ActivePlaybackState.dismiss()
        if (!autoPlayNextInSeries) { retire(); return }
        val finishedId = currentBookId
        if (finishedId <= 0L) { retire(); return }
        serviceScope.launch {
            val d = db
            if (d == null) { retire(); return@launch }
            val books = runCatching { d.bookDao().getAllOnce() }.getOrDefault(emptyList())
            val current = books.firstOrNull { it.id == finishedId }
            if (current == null) { retire(); return@launch }
            val series = current.series?.takeIf { it.isNotBlank() }
            val idx = current.seriesIndex
            if (series == null || idx == null) { retire(); return@launch }
            val next = books.asSequence()
                .filter {
                    it.id != current.id && !it.isDeleted && it.type == current.type &&
                        it.series?.equals(series, ignoreCase = true) == true &&
                        (it.seriesIndex ?: Float.MAX_VALUE) > idx
                }
                .minByOrNull { it.seriesIndex ?: Float.MAX_VALUE }
            if (next == null) { retire(); return@launch }
            Log.i(TAG, "Auto-playing next in series id=${next.id} (after $finishedId)")
            loadBook(next.id)
            // Wait until the new book's media items are prepared, then start.
            var waited = 0
            while ((player?.mediaItemCount ?: 0) == 0 && waited < 5_000) {
                delay(100)
                waited += 100
            }
            runOnMain { player?.playWhenReady = true }
        }
    }

    /**
     * Single writer of both the engine snapshot and the app-wide mini-player state.
     * Must be called on the player's main thread.
     */
    private fun publishNowPlaying() {
        val p = player ?: return
        if (currentBookId <= 0L) return
        val pos = currentPositionMs().coerceAtLeast(0L)
        val dur = durationMs().takeIf { it > 0L } ?: 0L
        val remaining = sleepTimerRemainingMs()
        val meta = p.mediaMetadata
        val snapshot = AudiobookNowPlaying(
            bookId = currentBookId,
            title = meta.title?.toString() ?: "",
            author = meta.artist?.toString() ?: "",
            isPlaying = p.isPlaying,
            positionMs = pos,
            durationMs = dur,
            playbackSpeed = p.playbackParameters.speed,
            chapterIndex = p.currentMediaItemIndex,
            chapters = activeChapters,
            sleepTimerRemainingMs = remaining
        )
        _nowPlaying.value = snapshot
        com.bookrio.data.repository.ActivePlaybackState.update(
            bookId = snapshot.bookId,
            title = snapshot.title,
            author = snapshot.author,
            isPlaying = snapshot.isPlaying,
            progressPercent = if (dur > 0L) pos.toFloat() / dur.toFloat() else 0f,
            sleepTimerMinutes = if (remaining > 0L) (remaining / 60_000L).toInt().coerceAtLeast(1) else null,
            sleepTimerRemainingMs = remaining
        )
    }

    /** Publishes position while playing; nothing changes while paused, so it idles. */
    private fun ensureProgressTicker() {
        if (progressTickerJob?.isActive == true) return
        progressTickerJob = serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    publishNowPlaying()
                    maybePersistProgressThrottled()
                }
                delay(500L)
            }
        }
    }

    /** Periodic safety net so progress survives a process kill mid-playback. */
    private fun maybePersistProgressThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastProgressSaveMs >= 10_000L) {
            lastProgressSaveMs = now
            maybePersistProgress()
        }
    }

    private fun ensureChannel() {
        val mgr = NotificationManagerCompat.from(this)
        val existing = mgr.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.ply_notif_channel))
                .setDescription(getString(R.string.ply_notif_channel_desc))
                .setShowBadge(false)
                .build()
            mgr.createNotificationChannel(channel)
        }
    }

    private var sleepTimerEndTimeMs: Long = 0L

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        if (minutes <= 0) return
        val totalMs = minutes * 60L * 1000L
        sleepTimerEndTimeMs = System.currentTimeMillis() + totalMs

        sleepTimer = object : android.os.CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val p = player ?: return
                if (sleepFadeOut) {
                    // Fade out volume during the last 30 seconds.
                    if (millisUntilFinished < 30_000L && millisUntilFinished > 0L) {
                        val fadeVol = (millisUntilFinished.toFloat() / 30_000f).coerceIn(0.05f, 1.0f)
                        p.volume = fadeVol
                    } else if (p.volume < 1.0f && millisUntilFinished >= 30_000L) {
                        p.volume = 1.0f
                    }
                }
                // Keep the mini-player countdown live even while paused.
                publishNowPlaying()
            }

            override fun onFinish() {
                val p = player
                if (p != null) {
                    p.volume = 1.0f
                    if (p.isPlaying) p.playWhenReady = false
                }
                
                maybePersistProgress()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                sleepTimer = null
                sleepTimerEndTimeMs = 0L
                publishNowPlaying()
                Log.i(TAG, "Sleep timer expired, playback stopped.")
            }
        }.start()
        publishNowPlaying()
    }

    fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerEndTimeMs = 0L
        player?.volume = 1.0f
        publishNowPlaying()
    }

    fun sleepTimerRemainingMs(): Long {
        if (sleepTimer == null || sleepTimerEndTimeMs <= 0L) return 0L
        return (sleepTimerEndTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val p = player ?: return
        if (!p.isPlaying) stopSelf()
    }

    override fun onDestroy() {
        cancelSleepTimer()
        progressTickerJob?.cancel()
        progressTickerJob = null
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        _nowPlaying.value = AudiobookNowPlaying()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
        com.bookrio.data.repository.ActivePlaybackState.dismiss()
        com.bookrio.core.playback.PlaybackArbiter.unregister(
            com.bookrio.core.playback.PlaybackArbiter.ID_AUDIOBOOK
        )
        serviceScope.launch(Dispatchers.Main) {
            maybePersistProgress()
            session?.run {
                release()
            }
            player?.run {
                stop()
                release()
            }
            session = null
            player = null
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? = librarySession

    fun currentPositionMs(): Long {
        val p = player ?: return 0L
        val idx = p.currentMediaItemIndex
        val posInItem = p.currentPosition.coerceAtLeast(0L)
        // posInItem er relativt til klipp-start; globalStartMs er kapittelens start
        // på den globale boktidslinjen (filposisjon for enkeltfil, kumulativt for mapper).
        return (activeItemSpecs.getOrNull(idx)?.globalStartMs ?: 0L) + posInItem
    }

    fun durationMs(): Long {
        if (activeChapters.isNotEmpty()) {
            val last = activeChapters.last()
            return last.endMs ?: (last.startMs + 300_000L)
        }
        return player?.duration?.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    fun playPause() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            player?.let { it.playWhenReady = !it.playWhenReady }
        } else {
            serviceScope.launch(Dispatchers.Main) {
                player?.let { it.playWhenReady = !it.playWhenReady }
            }
        }
    }

    fun seekTo(ms: Long) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val p = player ?: return
            if (activeChapters.isNotEmpty()) {
                val idx = activeChapters.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
                val globalStart = activeItemSpecs.getOrNull(idx)?.globalStartMs ?: activeChapters[idx].startMs
                val trackMs = (ms - globalStart).coerceAtLeast(0L)
                p.seekTo(idx, trackMs)
            } else {
                p.seekTo(ms.coerceAtLeast(0L).let { if (durationMs() != C.TIME_UNSET) it.coerceAtMost(durationMs()) else it })
            }
            publishNowPlaying()
        } else {
            serviceScope.launch(Dispatchers.Main) { seekTo(ms) }
        }
    }

    fun setSpeed(speed: Float) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            player?.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
            publishNowPlaying()
        } else {
            serviceScope.launch(Dispatchers.Main) { setSpeed(speed) }
        }
    }
    /** Runs [block] on the player's main thread, immediately when already there. */
    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            serviceScope.launch(Dispatchers.Main) { block() }
        }
    }

    private fun parseChapters(json: String): List<AudiobookChapter> {
        if (json.isBlank()) return emptyList()
        val list = runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AudiobookChapter(
                    index = obj.optInt("index", i),
                    title = localizedChapterTitle(this, obj.optString("title"), i),
                    startMs = obj.optLong("startMs", 0L),
                    endMs = obj.optLong("endMs", 0L).takeIf { it > 0L },
                    mediaUri = if (obj.has("mediaUri") && !obj.isNull("mediaUri")) obj.getString("mediaUri") else null
                )
            }
        }.getOrElse { emptyList() }
        return list.mapIndexed { i, ch ->
            val end = ch.endMs?.takeIf { it > ch.startMs }
                ?: list.getOrNull(i + 1)?.startMs?.takeIf { it > ch.startMs }
            ch.copy(endMs = end)
        }
    }

    private data class CoverArtwork(val bytes: ByteArray, val bitmap: Bitmap)

    private fun resolveCoverFile(bookId: Long, coverPathFromDb: String?): File? {
        val fromDb = coverPathFromDb?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (fromDb != null && fromDb.exists() && fromDb.length() > 0L) return fromDb
        val fallback = File(filesDir, "covers/book_${bookId}.webp")
        return if (fallback.exists() && fallback.length() > 0L) fallback else null
    }

    private fun coverArtworkFor(bookId: Long): CoverArtwork? {
        return coverArtworkFor(bookId, coverPathFromDb = null)
    }

    private fun coverArtworkFor(bookId: Long, coverPathFromDb: String?): CoverArtwork? {
        val file = resolveCoverFile(bookId, coverPathFromDb) ?: return null
        val path = file.absolutePath
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val targetPx = 512
            var scale = 1
            while (bounds.outWidth / scale / 2 >= targetPx && bounds.outHeight / scale / 2 >= targetPx) scale *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = scale }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val scaled = if (decoded.width <= targetPx && decoded.height <= targetPx) decoded else {
                val ratio = minOf(targetPx.toFloat() / decoded.width, targetPx.toFloat() / decoded.height)
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                val s = Bitmap.createScaledBitmap(decoded, w, h, true)
                if (s !== decoded) decoded.recycle()
                s
            }
            val bao = ByteArrayOutputStream().apply {
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, this)
            }
            CoverArtwork(bao.toByteArray(), scaled)
        } catch (t: Throwable) {
            Log.w(TAG, "coverArtworkFor failed for book=$bookId", t)
            null
        }
    }
}
