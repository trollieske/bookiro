package com.bookrio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.podcast.playback.PodcastPlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduces the reported bug: a podcast episode with a stored position must
 * resume there, and a paused episode must write its position back.
 */
@RunWith(AndroidJUnit4::class)
class PodcastResumeInstrumentedTest {

    private fun silentWav(file: File, seconds: Int, sampleRate: Int = 8000) {
        val dataSize = seconds * sampleRate * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeBytes("RIFF")
            out.writeInt(Integer.reverseBytes(36 + dataSize))
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeInt(Integer.reverseBytes(16))
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            out.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            out.writeInt(Integer.reverseBytes(sampleRate))
            out.writeInt(Integer.reverseBytes(sampleRate * 2))
            out.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt())
            out.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            out.writeBytes("data")
            out.writeInt(Integer.reverseBytes(dataSize))
            out.write(ByteArray(dataSize))
        }
    }

    private fun bindService(context: Context): Pair<PodcastPlaybackService, () -> Unit> {
        val latch = CountDownLatch(1)
        var service: PodcastPlaybackService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? PodcastPlaybackService.LocalBinder)?.getService()
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        context.bindService(
            Intent(context, PodcastPlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("service must bind", latch.await(10, TimeUnit.SECONDS))
        return service!! to { runCatching { context.unbindService(connection) } }
    }

    @Test
    fun storedPositionIsResumedAndPausedPositionIsPersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = ShelfDatabase.getInstance(context)
        val wav = File(context.filesDir, "podcast_resume/episode.wav").apply {
            parentFile?.mkdirs()
            silentWav(this, seconds = 30)
        }
        val feedId = runBlocking {
            db.podcastFeedDao().insert(
                PodcastFeedEntity(feedUrl = "file://resume-test-${System.nanoTime()}", title = "Resume Feed")
            )
        }
        val episodeId = runBlocking {
            db.podcastEpisodeDao().insert(
                PodcastEpisodeEntity(
                    feedId = feedId,
                    stableIdentity = "resume-test-${System.nanoTime()}",
                    enclosureUrl = wav.toURI().toString(),
                    title = "Resume Episode",
                    durationMs = 30_000L,
                )
            )
        }
        runBlocking {
            db.podcastPlaybackDao().upsert(
                PodcastPlaybackEntity(episodeId = episodeId, positionMs = 12_000L, durationMs = 30_000L)
            )
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val (service, unbind) = bindService(context)
        try {
            service.loadEpisode(episodeId, autoPlay = true, force = true)
            // Give ExoPlayer time to prepare/start and apply the pending seek.
            Thread.sleep(4_000L)
            var resumed = 0L
            instrumentation.runOnMainSync { resumed = service.currentPositionMs() }
            assertTrue(
                "stored position 12000 must be resumed, was $resumed",
                resumed >= 10_000L,
            )

            println("RESUME_TEST afterLoad=${service.nowPlaying.value}")
            service.seekTo(24_000L)
            Thread.sleep(500L)
            println("RESUME_TEST beforeStop=${service.nowPlaying.value}")
            // A seek now persists immediately, even while paused.
            Thread.sleep(600L)
            val afterSeek = runBlocking { db.podcastPlaybackDao().getByEpisode(episodeId) }
            assertTrue(
                "seek position must persist, was ${afterSeek?.positionMs}",
                (afterSeek?.positionMs ?: 0L) >= 20_000L,
            )
            // Same path the mini-player close / task removal uses: retire, which
            // persists before clearing the timeline.
            context.startService(
                Intent(context, PodcastPlaybackService::class.java)
                    .setAction(PodcastPlaybackService.ACTION_STOP)
            )
            Thread.sleep(2_000L)
            println("RESUME_TEST afterStop=${service.nowPlaying.value}")
            val saved = runBlocking { db.podcastPlaybackDao().getByEpisode(episodeId) }
            assertTrue(
                "retired position must persist, was ${saved?.positionMs}",
                (saved?.positionMs ?: 0L) >= 20_000L,
            )
        } finally {
            unbind()
        }
    }
}
