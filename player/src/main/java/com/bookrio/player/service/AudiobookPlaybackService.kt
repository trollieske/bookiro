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
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.player.AudiobookNowPlaying
import com.bookrio.player.engine.AudiobookChapter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import java.io.ByteArrayOutputStream
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudiobookPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "AudiobookPlaybackService"
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 8888
        const val ACTION_LOAD_BOOK = "com.bookrio.player.LOAD_BOOK"
        const val EXTRA_BOOK_ID = "extra_book_id"

        /**
         * Explicit play intent: the load may start playback and take ownership back
         * from the other engine. Used when the UI re-issues a load on a play press
         * after the service was stopped; a plain screen load stays deferred.
         */
        const val EXTRA_PLAY_INTENT = "extra_play_intent"
        const val SEEK_BACK_MS = 30_000L
        const val SEEK_FORWARD_MS = 30_000L

        const val CMD_SPEED = "CMD_SET_SPEED"
        const val CMD_SKIP_BACK = "CMD_SKIP_BACK"
        const val CMD_SKIP_FORWARD = "CMD_SKIP_FORWARD"
        const val CMD_SET_SLEEP = "CMD_SET_SLEEP"

        const val ACTION_SKIP_BACK = "com.bookrio.player.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "com.bookrio.player.SKIP_FORWARD"
        const val ACTION_STOP = "com.bookrio.player.STOP"

        // Podcast engine contract. The player module must not depend on the
        // podcast module, so the service class name and extras are matched by
        // string exactly as PodcastPlaybackService declares them.
        private const val PODCAST_SERVICE_CLASS = "com.bookrio.podcast.playback.PodcastPlaybackService"
        private const val PODCAST_ACTION_LOAD_EPISODE = "com.bookrio.podcast.LOAD_EPISODE"
        private const val PODCAST_EXTRA_EPISODE_ID = "extra_episode_id"
        private const val PODCAST_EXTRA_FORCE_PLAY = "extra_force_play"

        /** Bounded browse-artwork cache size (entries are a few tens of KB). */
        private const val MAX_COVER_CACHE_ENTRIES = 256
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

    /**
     * Monotonic arbitration for loads: a token from [LoadGate.begin] may only
     * commit while it is current and (forced or the other engine is idle), and
     * retire/hand-off invalidates every in-flight token.
     */
    private val loadGate = com.bookrio.core.playback.LoadGate()

    /**
     * Id of the book whose MediaItems the player actually holds. Written only in
     * the main-thread commit of [loadBook], so progress/snapshots can never be
     * attributed to a book that was merely requested while the old one still plays.
     */
    private val loadedTimeline = com.bookrio.core.playback.LoadedTimeline()

    inner class LocalBinder : Binder() {
        fun getService(): AudiobookPlaybackService = this@AudiobookPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind: action=${intent?.action}")
        // Media3's MediaSessionService framework binder is what a MediaController /
        // MediaBrowser (Android Auto, Bluetooth, system media controls) must receive.
        // The in-app PlayerViewModel binds with an explicit component and no action
        // and wants the local binder. Returning the local binder for EVERY caller
        // (the previous behaviour) handed Auto a plain Binder that never answers the
        // session handshake, so its browse screen stayed on the loading spinner.
        return if (intent?.action == null) {
            binder
        } else {
            super.onBind(intent)
        }
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
                val libraryBookId = AudiobookLibraryTree.bookIdOf(id)
                if (libraryBookId != null) {
                    // A `book_<id>` item can only come from the media library
                    // (Android Auto / browser playFromMediaId): our own timeline items
                    // are `<bookId>_<chapterIndex>` and always carry a URI. The player
                    // therefore no longer holds our timeline, so (re)load it and treat
                    // the request as an explicit play gesture — selecting a book in
                    // the car must also take audio ownership back from a playing
                    // podcast, and must work after the engine was retired for the
                    // same book (currentBookId is intentionally kept across retire).
                    loadedTimeline.cleared()
                    Log.i(TAG, "library item $id selected: loading book $libraryBookId")
                    loadBook(libraryBookId, autoPlay = true, force = true)
                    return
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
                        val finished = loadedTimeline.snapshot()
                        if (finished.id > 0L) serviceScope.launch {
                            // Only mark finished if the player still holds that timeline.
                            if (loadedTimeline.progressTarget(finished) != null) {
                                withContext(NonCancellable) { saveProgress(finished.id, 1.0f) }
                            }
                        }
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

        // NOTE: this callback is passed to the MediaLibrarySession below, so the
        // library command grants MUST live here. A previous version kept them in a
        // separate MediaSession.Callback that was never attached, so a MediaBrowser /
        // Android Auto library controller connected but stayed PENDING forever
        // (perpetual loading screen) because the library command codes were absent.
        val libraryCallback = object : MediaLibraryService.MediaLibrarySession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                // DEFAULT_SESSION_AND_LIBRARY_COMMANDS, not DEFAULT_SESSION_COMMANDS:
                // Media3's MediaLibraryServiceLegacyStub refuses onGetChildren and
                // onGetItem for Android Auto unless the controller holds the library
                // command codes (50003/50004).
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
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
                val p = player ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
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
                    else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
            }

            override fun onGetLibraryRoot(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(buildLibraryRootItem(), params))

            /**
             * Resolves browsed media ids to library items. Android Auto calls this
             * (legacy onLoadItem) when the browse tree item is selected and when the
             * host needs details for the now-playing card; without it the card falls
             * back to the app icon and grey placeholders.
             */
            override fun onGetItem(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                mediaId: String
            ): ListenableFuture<LibraryResult<MediaItem>> = libraryFuture {
                when (mediaId) {
                    BookiroLibraryTree.HOME_MEDIA_ID ->
                        LibraryResult.ofItem(buildLibraryRootItem(), null)
                    BookiroLibraryTree.CONTINUE_MEDIA_ID -> LibraryResult.ofItem(
                        sectionItem(BookiroLibraryTree.CONTINUE_MEDIA_ID, getString(R.string.ply_aa_continue)),
                        null
                    )
                    BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID -> LibraryResult.ofItem(
                        sectionItem(BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID, getString(R.string.ply_aa_audiobooks)),
                        null
                    )
                    BookiroLibraryTree.PODCASTS_MEDIA_ID -> LibraryResult.ofItem(
                        sectionItem(BookiroLibraryTree.PODCASTS_MEDIA_ID, getString(R.string.ply_aa_podcasts)),
                        null
                    )
                    else -> {
                        val feedId = BookiroLibraryTree.feedIdOf(mediaId)
                        val episodeId = BookiroLibraryTree.episodeIdOf(mediaId)
                        val bookId = AudiobookLibraryTree.bookIdOf(mediaId)
                        when {
                            feedId != null -> {
                                val feed = db?.podcastFeedDao()?.getById(feedId)
                                    ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                LibraryResult.ofItem(feedItem(feed), null)
                            }
                            episodeId != null -> {
                                val episode = db?.podcastEpisodeDao()?.getById(episodeId)
                                    ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                val feed = db?.podcastFeedDao()?.getById(episode.feedId)
                                val playback = db?.podcastPlaybackDao()?.getByEpisode(episodeId)
                                LibraryResult.ofItem(episodeItem(episode, feed, playback), null)
                            }
                            bookId != null -> {
                                val book = db?.bookDao()?.getById(bookId)?.takeIf { !it.isDeleted }
                                    ?: return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                                val entry = BookiroLibraryTree.bookEntry(book)
                                LibraryResult.ofItem(
                                    entryToMediaItem(entry, coverBytesFor(book.id, book.coverPath)),
                                    null
                                )
                            }
                            else -> LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        }
                    }
                }
            }

            override fun onGetChildren(
                session: MediaLibraryService.MediaLibrarySession,
                caller: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = libraryFuture {
                // Home -> the three option folders; a section -> its children; a feed
                // -> its episodes; a book/episode leaf -> nothing (playable leaves
                // are not browsable — the engine owns their timeline). Anything else
                // is not a node in this tree.
                val entries: List<BookiroLibraryTree.Entry> = when (parentId) {
                    BookiroLibraryTree.HOME_MEDIA_ID -> homeChildren()
                    BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID -> {
                        val books = db?.bookDao()?.getAllOnce().orEmpty()
                        BookiroLibraryTree.audiobookEntries(books)
                    }
                    BookiroLibraryTree.CONTINUE_MEDIA_ID -> buildContinueEntries()
                    BookiroLibraryTree.PODCASTS_MEDIA_ID -> {
                        val feeds = db?.podcastFeedDao()?.getFollowed().orEmpty()
                        BookiroLibraryTree.sortedFeeds(feeds).map(BookiroLibraryTree::feedEntry)
                    }
                    else -> {
                        val feedId = BookiroLibraryTree.feedIdOf(parentId)
                        if (feedId != null) {
                            val feed = db?.podcastFeedDao()?.getById(feedId)
                            val episodes = db?.podcastEpisodeDao()
                                ?.listByFeed(feedId, BookiroLibraryTree.FEED_EPISODE_LIMIT)
                                .orEmpty()
                            BookiroLibraryTree.sortedEpisodes(episodes).map { episode ->
                                val playback = db?.podcastPlaybackDao()?.getByEpisode(episode.id)
                                BookiroLibraryTree.episodeEntry(episode, feed, playback)
                            }
                        } else if (
                            AudiobookLibraryTree.bookIdOf(parentId) != null ||
                            BookiroLibraryTree.episodeIdOf(parentId) != null
                        ) {
                            emptyList()
                        } else {
                            return@libraryFuture LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        }
                    }
                }
                val items = BookiroLibraryTree.page(entries, page, pageSize).map { entry ->
                    val artwork = if (entry.kind == BookiroLibraryTree.Kind.BOOK) {
                        coverBytesFor(entry.bookId ?: -1L, entry.coverPath)
                    } else {
                        null
                    }
                    entryToMediaItem(entry, artwork)
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }

            /**
             * Resolves a controller request (`playFromMediaId` from Android Auto, a
             * Media3 library controller's `setMediaItems`, …) into COMPLETE playable
             * items.
             *
             * Media3 1.4.1's default implementation returns a failed future
             * (`UnsupportedOperationException`) for every item that has no
             * `LocalConfiguration`. A browsed leaf is an ID-only `book_<id>` library
             * entry, and the legacy MediaBrowserCompat path used by Android Auto
             * silently swallows that failure (`MediaSessionLegacyStub.onFailure`),
             * which is exactly why selecting a book in the car started nothing. The
             * service must expand the id here — asynchronously, off the session
             * callback thread — into the same chapter timeline [loadBook] builds.
             */
            override fun onAddMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                val remaining = routePodcastSelections(mediaItems)
                if (remaining.isEmpty()) {
                    // Every requested item belonged to the podcast engine, which was
                    // just started and owns its own session/notification. Handing the
                    // audiobook player an empty timeline keeps a single active session.
                    return Futures.immediateFuture(emptyList())
                }
                return Futures.transformAsync<ResolvedSelection, List<MediaItem>>(
                    resolveSelection(remaining, C.INDEX_UNSET, C.TIME_END_OF_SOURCE),
                    { selection: ResolvedSelection -> Futures.immediateFuture(selection.items) },
                    MoreExecutors.directExecutor()
                )
            }

            /**
             * The actual entry point for `playFromMediaId` (Android Auto) and for
             * controller `setMediaItems`. Media3's default delegates to
             * [onAddMediaItems], but overriding here as well lets a resolved book
             * selection start at the stored position instead of always at chapter 1.
             */
            override fun onSetMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val remaining = routePodcastSelections(mediaItems)
                if (remaining.isEmpty()) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            ImmutableList.of(), C.INDEX_UNSET, 0L
                        )
                    )
                }
                // Episodes were removed from the request, so a caller startIndex can
                // no longer point at the same element; an opaque play request (Auto)
                // already passes INDEX_UNSET.
                val adjustedStart = if (remaining.size == mediaItems.size) startIndex else C.INDEX_UNSET
                return Futures.transformAsync<ResolvedSelection, MediaSession.MediaItemsWithStartPosition>(
                    resolveSelection(remaining, adjustedStart, startPositionMs),
                    { selection: ResolvedSelection ->
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(
                                ImmutableList.copyOf(selection.items),
                                selection.startIndex,
                                selection.startPositionMs
                            )
                        )
                    },
                    MoreExecutors.directExecutor()
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
                // Hide + suppress first, so the pause callback cannot re-publish the bar.
                com.bookrio.data.repository.ActivePlaybackState.dismiss()
                retirePlayback("arbiter hand-off")
            }
        }
    }

    /**
     * A load refused arbitration: nothing of ours may stay prepared/published while
     * the other engine owns playback (a second timeline would post a second paused
     * MediaStyle notification). Retiring also stops the just-started service.
     */
    private fun deferLoad(reason: String) = runOnMain {
        runCatching {
            com.bookrio.data.repository.ActivePlaybackState.dismiss()
            retirePlayback(reason)
        }
    }

    /**
     * Tears the engine down to a clean IDLE state after playback ownership moved to
     * the other engine (arbiter hand-off), the user closed the mini-player, or a
     * load was deferred.
     *
     * Pausing alone is not enough: Media3 keeps a MediaStyle notification (with the
     * old artwork) alive while the timeline is non-empty, even when paused, so a
     * paused audiobook cover would stay in the system controls/lock screen next to a
     * playing podcast. Stopping the player, clearing the timeline and cancelling the
     * notification, plus stopSelf() (safe while bound), guarantees exactly one
     * active media session and no idle service left running.
     */
    private fun retirePlayback(reason: String) = runOnMain {
        // Final persist keyed to the timeline the player actually holds — captured
        // here, before we clear it.
        maybePersistProgress()
        loadGate.invalidate() // invalidate any in-flight load
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        sleepTimer?.cancel()
        sleepTimer = null
        sleepTimerEndTimeMs = 0L
        player?.let { p ->
            p.pause()
            p.stop()
            p.clearMediaItems()
        }
        activeChapters = emptyList()
        activePlan = emptyList()
        // loadedTimeline keeps its id: it is the last timeline we held, which is
        // exactly what a late progress write (and a play press) must target.
        // currentBookId is intentionally kept: an explicit play press re-loads it.
        _nowPlaying.value = AudiobookNowPlaying()
        Log.i(TAG, "retirePlayback ($reason)")
        stopForegroundAndSelf()
    }

    /** True when the other audio engine currently owns playback. */
    private fun otherEngineIsPlaying(): Boolean =
        com.bookrio.data.repository.NowPlayingOwnership.otherEngineIsPlaying(
            com.bookrio.data.repository.NowPlayingOwnership.Engine.AUDIOBOOK
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        val action = intent?.action
        // Always satisfy the startForegroundService() contract; the live media
        // notification replaces this placeholder as soon as the player reports the
        // seek / metadata change.
        startForegroundLoading()

        when (action) {
            ACTION_STOP -> {
                // Mini-player close: persist + hide, then retire the engine so no
                // paused notification/timeline survives the close.
                runOnMain {
                    maybePersistProgress()
                    com.bookrio.data.repository.ActivePlaybackState.dismiss()
                }
                retirePlayback("stop action")
            }
            ACTION_SKIP_BACK -> player?.let { it.seekTo((it.currentPosition - skipBackSec * 1000L).coerceAtLeast(0L)) }
            ACTION_SKIP_FORWARD -> player?.let { it.seekTo((it.currentPosition + skipFwdSec * 1000L).coerceAtMost(it.duration.coerceAtLeast(0L))) }
            ACTION_LOAD_BOOK -> {
                // NOTE: audio arbitration happens when playback actually starts
                // (onIsPlayingChanged), not on load — opening the player screen must
                // not kill a podcast that is currently playing. While that podcast
                // plays, the load is deferred instead of posting a second paused
                // notification with this book's artwork.
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                if (bookId > 0L) {
                    // An explicit play intent (play press with a dead service) may
                    // autoplay and take ownership; a plain screen load defers.
                    val playIntent = intent.getBooleanExtra(EXTRA_PLAY_INTENT, false)
                    if (loadBook(bookId, autoPlay = playIntent, force = playIntent)) startLoadWatchdog()
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
        removeNotificationNow()
        stopSelf()
    }

    private fun removeNotificationNow() {
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

    /**
     * Planned item list mirroring the player's timeline 1:1: global chapter start plus
     * the clip window inside a shared file. Replaces the old `ActiveItemSpec` cache;
     * produced by [AudiobookPlaybackPlan] so the in-app load and the Auto/library
     * selection share one construction path.
     */
    private var activePlan: List<PlaybackItemPlan> = emptyList()

    /** One book resolved into the complete, playable Media3 timeline. */
    private data class BuiltTimeline(
        val book: BookEntity,
        val chapters: List<AudiobookChapter>,
        val plan: List<PlaybackItemPlan>,
        val items: List<MediaItem>,
        /** Resume item index, or -1 when there is no stored progress. */
        val resumeIndex: Int,
        val resumeOffsetMs: Long
    )

    /** Controller media request resolved into a complete, playable selection. */
    private data class ResolvedSelection(
        val items: List<MediaItem>,
        val startIndex: Int,
        val startPositionMs: Long
    )

    /**
     * Loads [bookId] into the player.
     *
     * @param autoPlay start playback as soon as the book is prepared (explicit user
     *   play and auto-play-next). Plain screen loads stay paused.
     * @param force explicit user action (play/resume or a play-intent screen load):
     *   bypass the "other engine owns playback" gate and take ownership back. A plain
     *   screen load never forces.
     * @return true when a load was scheduled, false when it was deferred because the
     *   other engine owns playback. A deferred load prepares nothing, so no second
     *   paused MediaStyle notification with this book's artwork is posted.
     */
    private fun loadBook(bookId: Long, autoPlay: Boolean = false, force: Boolean = false): Boolean {
        // Requested id (used by the UI / play press). The *loaded* id is only written
        // once the player actually accepts the new timeline, further down.
        currentBookId = bookId
        val p = player ?: return false
        val token = loadGate.begin()
        if (loadGate.shouldDefer(force, otherEngineIsPlaying())) {
            Log.i(TAG, "loadBook($bookId) deferred: other engine owns playback")
            deferLoad("load deferred: other engine owns playback")
            return false
        }
        serviceScope.launch {
            val built = buildBookTimeline(bookId) ?: run {
                Log.w(TAG, "loadBook($bookId): no playable timeline (missing book/source)")
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!loadGate.shouldApply(token, force, otherEngineIsPlaying())) {
                    // Stale token: either a newer same-engine load (UI or Auto select)
                    // owns the engine now — then this load must not touch the player at
                    // all — or the other engine owns playback, in which case a passive
                    // load retires instead of posting a second paused timeline.
                    if (otherEngineIsPlaying()) {
                        deferLoad("load superseded while in flight")
                    } else {
                        Log.i(TAG, "loadBook($bookId) superseded by a newer load; not applying")
                    }
                    return@withContext
                }
                // Swap the chapter/plan caches and the loaded-timeline id *before* the
                // player call: any ExoPlayer listener callback fired by setMediaItems
                // then sees a consistent (id, caches, player) triple.
                activeChapters = built.chapters
                activePlan = built.plan
                loadedTimeline.committed(built.book.id, token)
                p.setMediaItems(built.items)
                if (built.resumeIndex >= 0) p.seekTo(built.resumeIndex, built.resumeOffsetMs)
                p.prepare()
                p.setPlaybackSpeed(defaultSpeed.coerceIn(0.5f, 3f))
                loadWatchdogJob?.cancel()
                if (autoPlay) p.playWhenReady = true
                publishNowPlaying()
            }
        }
        return true
    }

    /**
     * Resolves a controller's requested items into complete, playable items.
     *
     * - Items that already carry a URI are passed through unchanged (a host echoing
     *   our own resolved timeline back).
     * - Every `book_<id>` library leaf is expanded, asynchronously, into that book's
     *   complete chapter timeline (the same builder [loadBook] uses).
     * - Anything else (unknown id, deleted book, missing/unreadable file) fails the
     *   returned future instead of handing the player an unplayable item, so Media3
     *   reports an error on the session/auto path instead of silently doing nothing.
     *
     * Service state (chapter caches + loaded timeline) is committed on the main thread
     * *before* the future completes, so Media3 applies the items with consistent
     * progress and now-playing bookkeeping.
     */
    private fun resolveSelection(
        requested: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<ResolvedSelection> {
        if (requested.isEmpty() || requested.all { it.localConfiguration != null }) {
            // Nothing to resolve; keep the caller's start request untouched.
            return Futures.immediateFuture(ResolvedSelection(requested, startIndex, startPositionMs))
        }
        val future = SettableFuture.create<ResolvedSelection>()
        serviceScope.launch {
            try {
                val resolved = ArrayList<MediaItem>(requested.size)
                val resolvedStartForInput = IntArray(requested.size) { -1 }
                val expandedBooks = LinkedHashSet<Long>()
                var firstResumeIndex = -1
                var firstResumeOffsetMs = 0L

                for ((inputIndex, item) in requested.withIndex()) {
                    if (item.localConfiguration != null) {
                        resolvedStartForInput[inputIndex] = resolved.size
                        resolved.add(item)
                        continue
                    }
                    val bookId = AudiobookLibraryTree.bookIdOf(item.mediaId)
                    if (bookId == null) {
                        Log.w(TAG, "selection rejected: unresolvable media id '${item.mediaId}'")
                        failSelection(future, IllegalArgumentException("unresolvable media id '${item.mediaId}'"))
                        return@launch
                    }
                    val built = buildBookTimeline(bookId)
                    if (built == null) {
                        Log.w(TAG, "selection rejected: book_$bookId has no playable timeline")
                        failSelection(future, IllegalStateException("book $bookId has no playable timeline"))
                        return@launch
                    }
                    val committed = withContext(Dispatchers.Main) { commitSelectedTimeline(built) }
                    if (!committed) {
                        Log.w(TAG, "selection superseded while resolving book_$bookId")
                        failSelection(future, IllegalStateException("selection superseded"))
                        return@launch
                    }
                    expandedBooks.add(bookId)
                    resolvedStartForInput[inputIndex] = resolved.size
                    if (firstResumeIndex < 0 && built.resumeIndex >= 0) {
                        firstResumeIndex = resolved.size + built.resumeIndex
                        firstResumeOffsetMs = built.resumeOffsetMs
                    }
                    resolved.addAll(built.items)
                }

                // Media3's legacy playFromMediaId (Android Auto) expresses the request
                // as startIndex = INDEX_UNSET + startPosition = TIME_END_OF_SOURCE. A
                // resolved book starts at its stored position instead of chapter 1.
                val opaquePlayRequest = requested.size == 1 &&
                    startIndex == C.INDEX_UNSET &&
                    startPositionMs == C.TIME_END_OF_SOURCE
                val resolvedStart: Int
                val resolvedPositionMs: Long
                when {
                    opaquePlayRequest && firstResumeIndex >= 0 -> {
                        resolvedStart = firstResumeIndex
                        resolvedPositionMs = firstResumeOffsetMs
                    }
                    opaquePlayRequest -> {
                        resolvedStart = C.INDEX_UNSET
                        resolvedPositionMs = 0L
                    }
                    startIndex in resolvedStartForInput.indices && resolvedStartForInput[startIndex] >= 0 -> {
                        // Map the caller's index (pre-expansion) onto the expanded list.
                        resolvedStart = resolvedStartForInput[startIndex]
                        resolvedPositionMs = if (startPositionMs > 0L) startPositionMs else 0L
                    }
                    else -> {
                        resolvedStart = C.INDEX_UNSET
                        resolvedPositionMs = 0L
                    }
                }

                if (expandedBooks.size > 1) {
                    // The chapter caches and progress target model exactly one book.
                    // Multiple expanded books still play (every item is complete), but
                    // nothing may claim the single-book progress slot: dropping the
                    // attribution is safer than writing this book's data under another.
                    Log.i(TAG, "selection spans ${expandedBooks.size} books; chapter/progress attribution disabled")
                    withContext(Dispatchers.Main) {
                        activeChapters = emptyList()
                        activePlan = emptyList()
                        loadedTimeline.cleared()
                    }
                }

                withContext(Dispatchers.Main) {
                    future.set(
                        ResolvedSelection(
                            items = resolved,
                            startIndex = resolvedStart,
                            startPositionMs = resolvedPositionMs
                        )
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "selection resolution failed", t)
                future.setException(t)
            }
        }
        return future
    }

    private fun failSelection(future: SettableFuture<ResolvedSelection>, error: Throwable) {
        if (!future.setException(error)) {
            Log.w(TAG, "selection failure was not delivered: ${error.message}")
        }
    }

    /**
     * Commits a controller-resolved book as the engine's active timeline, on the
     * player's main thread and *before* Media3 applies the items (the future only
     * completes after this returns). Returns false when a newer load won the race.
     *
     * Selection is an explicit user gesture (car select / host play), so it forces
     * ownership: it must take over from a playing podcast or a previously loaded
     * book. The arbiter stops the other engine as soon as playback starts.
     */
    private fun commitSelectedTimeline(built: BuiltTimeline): Boolean {
        val token = loadGate.begin()
        if (!loadGate.shouldApply(token, force = true, otherEngineIsPlaying())) return false
        currentBookId = built.book.id
        activeChapters = built.chapters
        activePlan = built.plan
        loadedTimeline.committed(built.book.id, token)
        // Playback speed is a player property, so applying it here survives the item
        // swap that follows this future.
        player?.setPlaybackSpeed(defaultSpeed.coerceIn(0.5f, 3f))
        Log.i(TAG, "committed library selection book=${built.book.id} items=${built.items.size}")
        return true
    }

    /**
     * Loads one book and builds its complete playable timeline: source URI, fresh
     * chapters, artwork and the MediaItem list. Shared by the in-app [loadBook] path
     * and the controller/Auto selection so both produce identical items.
     *
     * @return null when the book does not exist, is deleted, or has no playable
     *   source (missing/unreadable file).
     */
    private suspend fun buildBookTimeline(bookId: Long): BuiltTimeline? {
        val dao = db?.bookDao() ?: return null
        val book = dao.getById(bookId)?.takeIf { !it.isDeleted } ?: return null
        val progress = db?.progressDao()?.getByBook(bookId)?.progressPercent ?: 0f
        val source = resolvePlaybackSource(book)
        val cover = withContext(Dispatchers.IO) { coverArtworkFor(book.id, book.coverPath) }
        // KANONISK oppfriskning: oppdager/persisterer reelle kapitler for eksisterende
        // bøker med utdatert metadata (samme sti som ViewModel).
        val chapters = chapterEngine?.ensureFreshChapters(book)
            ?: parseChapters(book.chaptersJson ?: "")
        val planned = when (val result = AudiobookPlaybackPlan.plan(book.id, chapters, source)) {
            is AudiobookPlaybackPlan.Result.Planned -> result
            is AudiobookPlaybackPlan.Result.Unresolvable -> {
                Log.w(TAG, "book ${book.id} has no playable source (${result.reason})")
                return null
            }
        }
        val items = planned.items.map { planToMediaItem(book, it, cover?.bytes) }
        val totalDurationMs = book.durationMs ?: chapters.lastOrNull()?.endMs ?: 0L
        val resume = AudiobookPlaybackPlan.resumeTarget(planned, progress, totalDurationMs)
        return BuiltTimeline(
            book = book,
            chapters = chapters,
            plan = planned.items,
            items = items,
            resumeIndex = resume?.first ?: -1,
            resumeOffsetMs = resume?.second ?: 0L
        )
    }

    /** Same source preference as the engine: readable local file first, then URI. */
    private fun resolvePlaybackSource(book: BookEntity): String? {
        val path = book.filePath
        if (!path.isNullOrBlank() && File(path).canRead()) return Uri.fromFile(File(path)).toString()
        return book.fileUri?.takeIf { it.isNotBlank() }
    }

    /**
     * Maps one planned item to a COMPLETE Media3 MediaItem: URI, title, album,
     * subtitle, artwork and machine-readable extras. This is the single conversion
     * used by both the in-app load and the Auto/library selection.
     */
    private fun planToMediaItem(book: BookEntity, item: PlaybackItemPlan, cover: ByteArray?): MediaItem {
        val uri = Uri.parse(item.uri)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(item.mediaId)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(uri)
                    .setExtras(itemExtras(item))
                    .build()
            )
        if (item.clipStartMs != null) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(item.clipStartMs)
                    .setEndPositionMs(
                        if (item.clipEndMs != null && item.clipEndMs > item.clipStartMs) item.clipEndMs
                        else C.TIME_END_OF_SOURCE
                    )
                    .build()
            )
        }
        val title = item.title.ifBlank { book.title }
        val subtitle = if (item.isChapter) {
            getString(R.string.ply_chapter_of, item.chapterIndex + 1, item.chapterCount)
        } else {
            book.format.name + " – " + getString(R.string.ply_title)
        }
        builder.setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(book.author)
                .setAlbumArtist(book.author)
                .setAlbumTitle(book.title)
                .setDisplayTitle(title)
                .setSubtitle(subtitle)
                .setExtras(itemExtras(item))
                .apply { cover?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build()
        )
        return builder.build()
    }

    /** Machine-readable item context for hosts/automation (ids only, no paths). */
    private fun itemExtras(item: PlaybackItemPlan): Bundle = Bundle().apply {
        putLong("com.bookrio.extra.book_id", item.bookId)
        putInt("com.bookrio.extra.chapter_index", item.chapterIndex)
        putInt("com.bookrio.extra.chapter_count", item.chapterCount)
    }

    private fun maybePersistProgress() {
        val p = player ?: return

        // Ensure currentPosition is read on Main thread
        if (p.applicationLooper.thread != Thread.currentThread()) {
            serviceScope.launch(Dispatchers.Main) { maybePersistProgress() }
            return
        }

        // Persist for the timeline the player actually holds — never for a merely
        // requested book that has not been committed yet. Capture id + generation
        // together so a rapid A -> B -> A switch cannot let a stale write land.
        val timeline = loadedTimeline.snapshot()
        if (timeline.id <= 0L) return

        val duration = durationMs()
        if (duration <= 0L) return
        val pos = currentPositionMs()
        val pct = (pos.toFloat() / duration).coerceIn(0f, 1f)
        serviceScope.launch {
            // NonCancellable: retirePlayback() stops the service right after
            // persisting, and onDestroy cancels serviceScope.
            withContext(NonCancellable) {
                // Drop the write when the player has meanwhile committed a different
                // timeline or a newer generation of the same one (rapid switch). A
                // just-retired timeline keeps id + token, so the final hand-off
                // persist still lands.
                val target = loadedTimeline.progressTarget(timeline) ?: return@withContext
                saveProgress(target, pct, positionMs = pos)
            }
        }
    }

    private suspend fun saveProgress(bookId: Long, pct: Float, positionMs: Long? = null) {
        if (bookId <= 0L) return
        val dao = db?.progressDao() ?: return
        val now = System.currentTimeMillis()
        // Load-modify-upsert: `insertOrReplace` on the unique book_id would
        // otherwise null out position_ms / chapter fields and make
        // "continue listening" show the full duration as still remaining.
        dao.upsertForBook(
            bookId = bookId,
            updater = { existing -> audioProgressRow(existing, bookId, pct, positionMs, now) },
            creator = { audioProgressRow(null, bookId, pct, positionMs, now) }
        )
    }

    /**
     * When a book finishes and "Auto-play next in series" is enabled, continue with
     * the next book of the same series (by seriesIndex). Otherwise retire the bar.
     */
    private fun maybeAutoPlayNext() {
        fun retire() = com.bookrio.data.repository.ActivePlaybackState.dismiss()
        if (!autoPlayNextInSeries) { retire(); return }
        val finishedId = loadedTimeline.id
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
            // autoPlay only wins while this engine still owns playback; if the other
            // engine took over in the meantime the load defers instead of resurrecting.
            loadBook(next.id, autoPlay = true)
        }
    }

    /**
     * Single writer of both the engine snapshot and the app-wide mini-player state.
     * Must be called on the player's main thread.
     */
    private fun publishNowPlaying() {
        val p = player ?: return
        // Snapshot the timeline the player actually holds.
        val bookId = loadedTimeline.id
        if (bookId <= 0L) return
        // A retired engine has no timeline: never publish an empty snapshot.
        if (p.mediaItemCount == 0 && !p.isPlaying) return
        val pos = currentPositionMs().coerceAtLeast(0L)
        val dur = durationMs().takeIf { it > 0L } ?: 0L
        val remaining = sleepTimerRemainingMs()
        val meta = p.mediaMetadata
        val snapshot = AudiobookNowPlaying(
            bookId = bookId,
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

    /**
     * Runs a MediaLibrarySession callback body on [Dispatchers.IO] and completes the
     * returned future there. Media3 and Android Auto expect these callbacks to return
     * quickly; doing the DB read (and bitmap decoding) inline on the session callback
     * (main) thread — as the previous onGetChildren did — can stall/ANR the browse
     * until Auto gives up on a black screen.
     */
    private fun <T : Any> libraryFuture(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val future = SettableFuture.create<LibraryResult<T>>()
        serviceScope.launch(Dispatchers.IO) {
            try {
                future.set(block())
            } catch (t: Throwable) {
                Log.e(TAG, "media library callback failed", t)
                future.set(LibraryResult.ofError<T>(SessionError.ERROR_UNKNOWN))
            }
        }
        return future
    }

    /** Browsable, non-playable Android Auto home root. */
    private fun buildLibraryRootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(BookiroLibraryTree.HOME_MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(getString(R.string.ply_library_root))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    /**
     * The home's option folders, mirroring the app's Home menu. "Continue
     * listening" is only offered when there is something to continue.
     */
    private suspend fun homeChildren(): List<BookiroLibraryTree.Entry> {
        val children = ArrayList<BookiroLibraryTree.Entry>(3)
        if (buildContinueEntries().isNotEmpty()) {
            children += BookiroLibraryTree.section(
                BookiroLibraryTree.Kind.CONTINUE,
                BookiroLibraryTree.CONTINUE_MEDIA_ID,
                getString(R.string.ply_aa_continue)
            )
        }
        children += BookiroLibraryTree.section(
            BookiroLibraryTree.Kind.AUDIOBOOKS,
            BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID,
            getString(R.string.ply_aa_audiobooks)
        )
        children += BookiroLibraryTree.section(
            BookiroLibraryTree.Kind.PODCASTS,
            BookiroLibraryTree.PODCASTS_MEDIA_ID,
            getString(R.string.ply_aa_podcasts)
        )
        return children
    }

    /**
     * Mixed "Continue listening": in-progress audiobooks (newest progress first)
     * followed by resumable podcast episodes. Runs on the library IO dispatcher.
     */
    private suspend fun buildContinueEntries(): List<BookiroLibraryTree.Entry> {
        val books = runCatching { db?.bookDao()?.getAllOnce().orEmpty() }.getOrDefault(emptyList())
        val progressByBook = runCatching {
            db?.progressDao()?.observeAll()?.first().orEmpty()
        }.getOrDefault(emptyList()).associateBy { it.bookId }
        // Started audiobooks only (mirrors ResumeSelector): not finished, some
        // progress/position and some activity. Finished titles stay finished.
        val audiobooks = books.asSequence()
            .filter { !it.isDeleted && it.dateFinished == null && AudiobookLibraryTree.isAudiobook(it) }
            .map { book -> book to progressByBook[book.id] }
            .filter { (book, progress) ->
                val pct = progress?.progressPercent ?: 0f
                val positionMs = progress?.positionMs ?: 0L
                val lastActivity = progress?.updatedAt ?: book.lastOpenedAt ?: 0L
                pct < 1f && (pct > 0.0001f || positionMs > 0L) && lastActivity > 0L
            }
            .sortedByDescending { (book, progress) -> progress?.updatedAt ?: book.lastOpenedAt ?: 0L }
            .take(BookiroLibraryTree.CONTINUE_LIMIT)
            .map { (book, _) -> BookiroLibraryTree.bookEntry(book) }
            .toList()
        val podcasts = runCatching {
            db?.podcastEpisodeDao()?.observeResumeItems(BookiroLibraryTree.CONTINUE_LIMIT)?.first().orEmpty()
        }.getOrDefault(emptyList())
            .filterNot { it.isCompleted }
            .map(BookiroLibraryTree::resumeEntry)
        return (audiobooks + podcasts).take(BookiroLibraryTree.CONTINUE_LIMIT)
    }

    /**
     * Routes any `episode_<id>` items to the dedicated podcast engine and returns
     * the items that remain for the audiobook player. Android Auto hands us an
     * ID-only item on selection; the podcast engine resolves the stream, artwork
     * and progress itself, so nothing here may build a second timeline for it.
     */
    private fun routePodcastSelections(requested: List<MediaItem>): List<MediaItem> {
        val episodeIds = requested.mapNotNull { BookiroLibraryTree.episodeIdOf(it.mediaId) }
        if (episodeIds.isEmpty()) return requested
        // Explicit car selection: the podcast engine may take ownership back from a
        // currently playing audiobook (the arbiter stops the other engine).
        startPodcastEpisode(episodeIds.first())
        val routedMediaIds = requested.asSequence()
            .map { it.mediaId }
            .filter { BookiroLibraryTree.episodeIdOf(it) != null }
            .toHashSet()
        return requested.filterNot { it.mediaId in routedMediaIds }
    }

    /** Starts the podcast engine for one episode by component name (no module dep). */
    private fun startPodcastEpisode(episodeId: Long) {
        Log.i(TAG, "routing episode $episodeId to the podcast engine")
        val intent = Intent().apply {
            setClassName(packageName, PODCAST_SERVICE_CLASS)
            action = PODCAST_ACTION_LOAD_EPISODE
            putExtra(PODCAST_EXTRA_EPISODE_ID, episodeId)
            putExtra(PODCAST_EXTRA_FORCE_PLAY, true)
        }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        }.onFailure { t ->
            Log.e(TAG, "could not start the podcast engine", t)
            runCatching { startService(intent) }
        }
    }

    private fun sectionItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    private fun feedItem(feed: PodcastFeedEntity): MediaItem =
        entryToMediaItem(BookiroLibraryTree.feedEntry(feed), null)

    private fun episodeItem(
        episode: PodcastEpisodeEntity,
        feed: PodcastFeedEntity?,
        playback: PodcastPlaybackEntity?
    ): MediaItem =
        entryToMediaItem(BookiroLibraryTree.episodeEntry(episode, feed, playback), null)

    /** The single Media3 conversion of a pure [BookiroLibraryTree.Entry]. */
    private fun entryToMediaItem(entry: BookiroLibraryTree.Entry, artwork: ByteArray?): MediaItem {
        val mediaType = when (entry.kind) {
            BookiroLibraryTree.Kind.BOOK -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
            BookiroLibraryTree.Kind.EPISODE -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            BookiroLibraryTree.Kind.AUDIOBOOKS -> MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS
            BookiroLibraryTree.Kind.PODCASTS,
            BookiroLibraryTree.Kind.FEED -> MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setDisplayTitle(entry.title)
            .setArtist(entry.artist)
            .setAlbumTitle(entry.albumTitle)
            .setSubtitle(entry.subtitle)
            .setIsPlayable(entry.isPlayable)
            .setIsBrowsable(entry.isBrowsable)
            .setMediaType(mediaType)
            .apply {
                if (artwork != null) {
                    setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    entry.artworkUri?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(entry.mediaId)
            .setMediaMetadata(metadata)
            .build()
    }

    fun currentPositionMs(): Long {
        val p = player ?: return 0L
        val idx = p.currentMediaItemIndex
        val posInItem = p.currentPosition.coerceAtLeast(0L)
        // posInItem er relativt til klipp-start; globalStartMs er kapittelens start
        // på den globale boktidslinjen (filposisjon for enkeltfil, kumulativt for mapper).
        return (activePlan.getOrNull(idx)?.globalStartMs ?: 0L) + posInItem
    }

    fun durationMs(): Long {
        if (activeChapters.isNotEmpty()) {
            val last = activeChapters.last()
            return last.endMs ?: (last.startMs + 300_000L)
        }
        return player?.duration?.takeIf { it > 0L } ?: C.TIME_UNSET
    }

    /**
     * Play/pause control. [bookId] is the book the UI is showing, so the gesture
     * still works after a retire/stopSelf when this service instance no longer
     * remembers the request.
     */
    fun playPause(bookId: Long = currentBookId) {
        runOnMain {
            val p = player ?: return@runOnMain
            if (p.mediaItemCount == 0 || loadedTimeline.id != bookId) {
                // The engine was retired by a hand-off, or the last load was deferred
                // because the other engine owns playback. An explicit play press
                // re-loads the book and takes playback ownership with it.
                if (bookId > 0L) loadBook(bookId, autoPlay = true, force = true)
                return@runOnMain
            }
            p.playWhenReady = !p.playWhenReady
        }
    }

    fun seekTo(ms: Long) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val p = player ?: return
            if (activeChapters.isNotEmpty()) {
                val idx = activeChapters.indexOfLast { it.startMs <= ms }.coerceAtLeast(0)
                val globalStart = activePlan.getOrNull(idx)?.globalStartMs ?: activeChapters[idx].startMs
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

    /** One decoded cover per book file version, reused across browse callbacks. */
    private class CachedCover(val signature: String, val bytes: ByteArray)

    private val coverCache = java.util.concurrent.ConcurrentHashMap<Long, CachedCover>()

    /**
     * Cover bytes for a browse/library item, decoded once per file version. Always
     * called from [libraryFuture]'s IO dispatcher, never from the session callback
     * thread (the previous code decoded every cover inline on main).
     */
    private fun coverBytesFor(bookId: Long, coverPath: String?): ByteArray? {
        val file = resolveCoverFile(bookId, coverPath) ?: return null
        val signature = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        coverCache[bookId]?.let { cached -> if (cached.signature == signature) return cached.bytes }
        val artwork = coverArtworkFor(bookId, coverPath) ?: return null
        if (coverCache.size >= MAX_COVER_CACHE_ENTRIES) coverCache.clear()
        coverCache[bookId] = CachedCover(signature, artwork.bytes)
        return artwork.bytes
    }

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
