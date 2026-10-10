package com.bookrio

import android.content.ComponentName
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.PodcastEpisodeEntity
import com.bookrio.data.local.entity.PodcastFeedEntity
import com.bookrio.player.service.AudiobookPlaybackService
import com.bookrio.player.service.BookiroLibraryTree
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduces what Android Auto's MediaBrowser does when it opens the app: connect
 * to the audiobook MediaLibraryService, fetch the home root, then fetch each option
 * folder's children — including the podcast branch. If any future does not complete,
 * Auto is left on its loading screen.
 *
 * MediaController/MediaBrowser calls must run on the application's main thread, so
 * each request is dispatched there and awaited from the test thread.
 */
@RunWith(AndroidJUnit4::class)
class AutoBrowseInstrumentedTest {

    private inline fun <reified T> await(
        timeoutSeconds: Long = 30,
        crossinline call: () -> ListenableFuture<T>
    ): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        instrumentation.runOnMainSync {
            Futures.addCallback(
                call(),
                object : FutureCallback<T> {
                    override fun onSuccess(value: T?) {
                        result = value
                        latch.countDown()
                    }

                    override fun onFailure(t: Throwable) {
                        error = t
                        latch.countDown()
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }
        assertTrue("future did not complete in ${timeoutSeconds}s", latch.await(timeoutSeconds, TimeUnit.SECONDS))
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun childrenOf(browser: MediaBrowser, parentId: String): LibraryResult<ImmutableList<MediaItem>> =
        await { browser.getChildren(parentId, 0, 100, null) }

    @Test
    fun homeExposesAudiobooksAndPodcastsAndBothBrowse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = ShelfDatabase.getInstance(context)

        val bookId = runBlocking {
            db.bookDao().insert(
                BookEntity(
                    title = "Auto Test Audiobook",
                    type = BookTypeEntity.AUDIOBOOK,
                    format = FormatEntity.M4B,
                    filePath = "/data/local/tmp/auto.m4b",
                    importSource = ImportSourceEntity.SAMPLE,
                )
            )
        }
        val feedId = runBlocking {
            db.podcastFeedDao().insert(
                PodcastFeedEntity(
                    feedUrl = "https://auto.test/feed-${System.nanoTime()}.xml",
                    title = "Auto Test Podcast",
                    author = "Auto Tester",
                )
            )
        }
        val episodeId = runBlocking {
            db.podcastEpisodeDao().insert(
                PodcastEpisodeEntity(
                    feedId = feedId,
                    stableIdentity = "auto-test-ep-${System.nanoTime()}",
                    enclosureUrl = "https://auto.test/episode.mp3",
                    title = "Auto Test Episode",
                    publishedAt = System.currentTimeMillis(),
                )
            )
        }

        val token = SessionToken(
            context,
            ComponentName(context, AudiobookPlaybackService::class.java),
        )
        val browser = MediaBrowser.Builder(context, token).buildAsync().get(30, TimeUnit.SECONDS)

        try {
            val root = await { browser.getLibraryRoot(null) }
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            val homeId = root.value!!.mediaId
            assertEquals(BookiroLibraryTree.HOME_MEDIA_ID, homeId)

            val home = childrenOf(browser, homeId)
            assertEquals(LibraryResult.RESULT_SUCCESS, home.resultCode)
            val homeChildren = home.value!!
            Log.i("AUTO_BROWSE", "home children=${homeChildren.map { it.mediaId }}")
            assertTrue(
                "home must expose the Audiobooks option",
                homeChildren.any { it.mediaId == BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID },
            )
            assertTrue(
                "home must expose the Podcasts option",
                homeChildren.any { it.mediaId == BookiroLibraryTree.PODCASTS_MEDIA_ID },
            )

            val audiobooks = childrenOf(browser, BookiroLibraryTree.AUDIOBOOKS_MEDIA_ID)
            assertEquals(LibraryResult.RESULT_SUCCESS, audiobooks.resultCode)
            assertTrue(
                "the seeded audiobook must appear under Audiobooks",
                audiobooks.value!!.any { it.mediaId == "book_$bookId" },
            )

            val podcasts = childrenOf(browser, BookiroLibraryTree.PODCASTS_MEDIA_ID)
            assertEquals(LibraryResult.RESULT_SUCCESS, podcasts.resultCode)
            assertTrue(
                "the seeded feed must appear under Podcasts",
                podcasts.value!!.any { it.mediaId == "podfeed_$feedId" },
            )

            val episodes = childrenOf(browser, "podfeed_$feedId")
            assertEquals(LibraryResult.RESULT_SUCCESS, episodes.resultCode)
            assertTrue(
                "the seeded episode must appear under its feed",
                episodes.value!!.any { it.mediaId == "episode_$episodeId" },
            )
        } finally {
            instrumentation.runOnMainSync { browser.release() }
        }
    }
}
