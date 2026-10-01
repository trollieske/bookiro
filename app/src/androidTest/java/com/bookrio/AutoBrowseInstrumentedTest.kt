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
import com.bookrio.player.service.AudiobookPlaybackService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduces exactly what Android Auto's MediaBrowser does when it opens the app:
 * connect to the audiobook MediaLibraryService, fetch the library root, then fetch
 * the root's children. If any of these futures do not complete, Auto is left on its
 * loading screen — which is the reported symptom.
 *
 * MediaController calls must run on the application's main thread, so the browse
 * callbacks are dispatched there and a latch is awaited from the test thread.
 */
@RunWith(AndroidJUnit4::class)
class AutoBrowseInstrumentedTest {

    @Test
    fun browseRootAndChildrenComplete() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = ShelfDatabase.getInstance(context)
        runBlocking {
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

        val token = SessionToken(
            context,
            ComponentName(context, AudiobookPlaybackService::class.java),
        )
        val browser = MediaBrowser.Builder(context, token).buildAsync().get(30, TimeUnit.SECONDS)

        val latch = CountDownLatch(1)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var rootResult = -1
        var rootMediaId: String? = null
        var childrenResult = -1
        var childrenCount = -1

        fun onChildren(result: LibraryResult<ImmutableList<MediaItem>>?) {
            childrenResult = result?.resultCode ?: -1
            childrenCount = result?.value?.size ?: -1
            latch.countDown()
        }

        instrumentation.runOnMainSync {
            Futures.addCallback(
                browser.getLibraryRoot(null),
                object : FutureCallback<LibraryResult<MediaItem>> {
                    override fun onSuccess(root: LibraryResult<MediaItem>?) {
                        rootResult = root?.resultCode ?: -1
                        rootMediaId = root?.value?.mediaId
                        val id = rootMediaId
                        if (id == null) {
                            latch.countDown()
                        } else {
                            Futures.addCallback(
                                browser.getChildren(id, 0, 100, null),
                                object : FutureCallback<LibraryResult<ImmutableList<MediaItem>>> {
                                    override fun onSuccess(result: LibraryResult<ImmutableList<MediaItem>>?) = onChildren(result)
                                    override fun onFailure(t: Throwable) = onChildren(null)
                                },
                                mainExecutor,
                            )
                        }
                    }

                    override fun onFailure(t: Throwable) {
                        latch.countDown()
                    }
                },
                mainExecutor,
            )
        }

        val completed = latch.await(30, TimeUnit.SECONDS)
        Log.i(
            "AUTO_BROWSE",
            "completed=$completed root=$rootResult/$rootMediaId children=$childrenResult/$childrenCount",
        )

        assertTrue("browser handshake must complete (was PENDING before the onBind fix)", browser.isConnected)
        assertTrue("root must resolve", completed && rootResult == 0 && rootMediaId != null)
        assertTrue("children must resolve without error", childrenResult == 0)
        assertTrue("there must be at least one audiobook child", childrenCount >= 1)

        instrumentation.runOnMainSync { browser.release() }
    }
}