package com.shelf.reader

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
import com.shelf.reader.core.di.AppDependenciesProvider
import com.shelf.reader.core.gamification.ReadingTrackerFacade
import com.shelf.reader.data.gamification.engine.ReadingTrackerEngine
import com.shelf.reader.data.local.ShelfDatabase
import com.shelf.reader.app.workers.MediaScannerWorker
import com.shelf.reader.ftp.worker.FtpPeriodicSyncWorker
import com.shelf.reader.ftp.worker.FtpSyncCoordinator
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ShelfApplication : Application(), ImageLoaderFactory, AppDependenciesProvider {

    override val appContext: Context
        get() = this

    private var _database: ShelfDatabase? = null
    val database: ShelfDatabase
        get() = _database ?: synchronized(this) {
            _database ?: runCatching { ShelfDatabase.getInstance(this) }
                .getOrElse {
                    _database = null
                    deleteDatabase("shelf.db")
                    ShelfDatabase.getInstance(this)
                }
                .also { _database = it }
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
        }
        warmUpThread.name = "shelf-db-warm"
        warmUpThread.isDaemon = true
        warmUpThread.start()

        MediaScannerWorker.schedule(this)
        FtpPeriodicSyncWorker.schedule(this)
        runCatching { FtpSyncCoordinator.start(this) }
        runCatching { com.shelf.reader.torrent.worker.TorrentDownloadWorker.schedule(this) }
        runCatching { com.shelf.reader.torrent.worker.TorrentDownloadWorker.runNow(this) }
        runCatching { com.shelf.reader.podcast.worker.PodcastFeedSyncWorker.schedulePeriodic(this) }
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
