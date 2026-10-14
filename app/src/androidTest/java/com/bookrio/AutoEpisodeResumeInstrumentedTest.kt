package com.bookrio

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.data.local.entity.PodcastPlaybackEntity
import com.bookrio.player.service.AudiobookPlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Android Auto selects an `episode_<id>` browse leaf as an ID-only MediaItem
 * with `startIndex = C.INDEX_UNSET` and `startPositionMs = C.TIME_UNSET`.
 * This reproduces that exact request against the real MediaLibrarySession and
 * asserts it resumes at the stored position instead of 0.
 */
@RunWith(AndroidJUnit4::class)
class AutoEpisodeResumeInstrumentedTest {

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

    @Test
    fun opaqueLibrarySelectionResumesTheStoredEpisodePosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = ShelfDatabase.getInstance(context)
        val wav = File(context.filesDir, "auto_resume/episode.wav").apply {
            parentFile?.mkdirs()
            silentWav(this, seconds = 60)
        }
        val feedId = runBlocking {
            db.podcastFeedDao().insert(
                PodcastFeedEntity(feedUrl = "file://auto-resume-${System.nanoTime()}", title = "Auto Feed")
            )
        }
        val episodeId = runBlocking {
            db.podcastEpisodeDao().insert(
                PodcastEpisodeEntity(
                    feedId = feedId,
                    stableIdentity = "auto-resume-${System.nanoTime()}",
                    enclosureUrl = wav.toURI().toString(),
                    title = "Auto Episode",
                    durationMs = 60_000L,
                )
            )
        }
        runBlocking {
            db.podcastPlaybackDao().upsert(
                PodcastPlaybackEntity(episodeId = episodeId, positionMs = 25_000L, durationMs = 60_000L)
            )
        }

        val token = SessionToken(context, ComponentName(context, AudiobookPlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().get(30, TimeUnit.SECONDS)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                // Exactly what MediaSessionLegacyStub.onPlayFromMediaId builds.
                controller.setMediaItems(
                    listOf(MediaItem.Builder().setMediaId("episode_$episodeId").build()),
                    C.INDEX_UNSET,
                    C.TIME_UNSET,
                )
                controller.prepare()
            }
            Thread.sleep(5_000L)
            var position = 0L
            instrumentation.runOnMainSync { position = controller.currentPosition }
            assertTrue(
                "Android Auto selection must resume at 25000, was $position",
                position >= 20_000L,
            )
        } finally {
            instrumentation.runOnMainSync { controller.release() }
        }
    }
}
