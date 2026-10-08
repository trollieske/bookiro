package com.bookrio

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import androidx.work.WorkManagerInitializer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.bookrio.core.di.AppDependenciesProvider
import com.bookrio.core.db.DatabaseRecoveryPolicy
import com.bookrio.core.gamification.ReadingTrackerFacade
import com.bookrio.data.gamification.engine.ReadingTrackerEngine
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.app.workers.MediaScannerWorker
import com.bookrio.ftp.worker.FtpPeriodicSyncWorker
import com.bookrio.ftp.worker.FtpSyncCoordinator
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ShelfApplication : Application(), ImageLoaderFactory, AppDependenciesProvider {

    override val appContext: Context
        get() = this

    private var _database: ShelfDatabase? = null
    val database: ShelfDatabase
        get() = _database ?: synchronized(this) {
            // No deleteDatabase()/recreate fallback here: a transient open failure must
            // never wipe user data. The getter returns the (lazily built) instance and
            // open failures are surfaced via [databaseError].
            _database ?: ShelfDatabase.getInstance(this).also { _database = it }
        }

    private val _databaseError = MutableStateFlow<Throwable?>(null)

    /** Non-null when the database could not be opened. The database file is preserved. */
    val databaseError: StateFlow<Throwable?> = _databaseError.asStateFlow()

    /**
     * Opens (and migrates) the database eagerly on the warm-up thread. On failure it
     * records a non-destructive error state for the UI instead of deleting anything.
     */
    fun openDatabaseOrSurfaceError() {
        runCatching { database.openHelper.writableDatabase }
            .onSuccess { _databaseError.value = null }
            .onFailure {
                android.util.Log.e("Bookiro", "Database open failed; keeping the file for recovery", it)
                _databaseError.value = it
            }
    }

    private val databaseRetryInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * User-initiated retry after a database error. The blocking open runs on a
     * background thread so the UI thread is never blocked, and the error state is kept
     * until the retry succeeds so no Room-backed UI runs against a broken database.
     */
    fun retryDatabaseOpen() {
        if (!databaseRetryInProgress.compareAndSet(false, true)) return
        Thread {
            runCatching { database.openHelper.writableDatabase }
                .onSuccess { _databaseError.value = null }
                .onFailure {
                    android.util.Log.e("Bookiro", "Database retry failed; keeping the file", it)
                    _databaseError.value = it
                }
            databaseRetryInProgress.set(false)
        }.apply { name = "shelf-db-retry"; isDaemon = true }.start()
    }

    /**
     * Explicit, user-confirmed destructive reset. Runs off the UI thread, then restarts
     * the process so no ViewModel/repository can keep using the closed database
     * instance. Never called automatically.
     */
    fun resetDatabaseAfterUserConfirmation() {
        if (!DatabaseRecoveryPolicy.mayDeleteDatabase(userConfirmedReset = true)) return
        Thread {
            synchronized(this) {
                ShelfDatabase.resetInstance()
                _database = null
                deleteDatabase("shelf.db")
                _databaseError.value = null
            }
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
                startActivity(intent)
            }
            Runtime.getRuntime().exit(0)
        }.apply { name = "shelf-db-reset"; isDaemon = true }.start()
    }

    private var _readingTracker: ReadingTrackerEngine? = null
    override val readingTracker: ReadingTrackerFacade
        get() = _readingTracker ?: synchronized(this) {
            _readingTracker ?: ReadingTrackerEngine(database.readingRhythmDao())
                .also {
                    it.initialize()
                    _readingTracker = it
                }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // InitializationProvider er FJERNET FRA MANIFEST (tools:node="remove") for å unngå
        // ClassCastException mellom startup-runtime versjoner fra car-app vs lifecycle.
        // Kjører derfor alle kjente Startup-Initializers MANUELT (try/sikker).
        runCatching {
            val ai = AppInitializer.getInstance(this)
            runCatching { ai.initializeComponent(WorkManagerInitializer::class.java) }
            runCatching { ai.initializeComponent(ProcessLifecycleInitializer::class.java) }
        }

        val warmUpThread = Thread {
            runCatching {
                database
                readingTracker
            }
            // Open/migrate eagerly so an open failure is surfaced as a recoverable
            // error state (never an automatic delete).
            openDatabaseOrSurfaceError()
            // Self-heal duplicate rows and polluted metadata left by earlier builds.
            // Runs off the main thread, is idempotent, and is cheap once clean.
            runCatching {
                val repo = com.bookrio.library.data.BookImportRepository(this, database)
                kotlinx.coroutines.runBlocking {
                    // Repair audiobook trails first, then de-duplicate (the split can
                    // create books that a later scan also imported), then clean titles.
                    repo.repairDuplicateAudioTracks()
                    repo.splitMergedAudiobooks()
                    repo.deduplicateLibrary()
                    repo.repairTitlesAndAuthors()
                }
            }
        }
        warmUpThread.name = "shelf-db-warm"
        warmUpThread.isDaemon = true
        warmUpThread.start()

        MediaScannerWorker.schedule(this)
        FtpPeriodicSyncWorker.schedule(this)
        runCatching { FtpSyncCoordinator.start(this) }
        // Torrent is full-only; the playstore flavor provides a no-op implementation.
        runCatching { com.bookrio.app.torrent.TorrentFeatureProvider.feature.applyBackgroundSettings(this) }
        runCatching { com.bookrio.podcast.worker.PodcastFeedSyncWorker.schedulePeriodic(this) }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .okHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            )
            .components {
                add(SvgDecoder.Factory())
                add(GifDecoder.Factory())
            }
            .respectCacheHeaders(false)
            .build()
    }

    companion object {
        lateinit var instance: ShelfApplication
            private set
    }
}
