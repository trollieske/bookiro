package com.bookrio.player.service

import com.bookrio.data.repository.ActivePlaybackState
import com.bookrio.data.repository.NowPlayingOwnership
import com.bookrio.data.repository.PodcastPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM audit of the audiobook/podcast now-playing hand-off (lockscreen /
 * notification / Android Auto metadata are built from exactly these snapshots).
 *
 * What is testable here is the single-owner arbitration: on rapid A -> B -> A
 * switching there must never be two published snapshots (two MediaStyle
 * notifications) and the surviving snapshot must always carry the artwork key of
 * the engine that owns playback (no stale cover).
 *
 * The Media3 session / Bluetooth / Android Auto wiring itself is device-level and
 * cannot be proven by these tests.
 */
class NowPlayingHandoffTest {

    @After
    fun tearDown() {
        NowPlayingOwnership.reset()
    }

    private fun book(isPlaying: Boolean, id: Long = 42L) = ActivePlaybackState.update(
        bookId = id,
        title = "Book $id",
        author = "Author",
        isPlaying = isPlaying,
        progressPercent = 0.4f,
        sleepTimerMinutes = null,
        sleepTimerRemainingMs = 0L
    )

    private fun podcast(isPlaying: Boolean, episodeId: Long = 7L, artwork: String? = "https://cdn/a.jpg") =
        PodcastPlaybackState.update(
            episodeId = episodeId,
            feedId = 3L,
            title = "Episode $episodeId",
            podcastTitle = "Podcast",
            artworkUrl = artwork,
            isPlaying = isPlaying,
            progressPercent = 0.1f,
            positionMs = 1_000L,
            durationMs = 10_000L
        )

    private fun publishedKeys(): List<String> = buildList {
        ActivePlaybackState.state.value?.artworkKey?.let { add(it) }
        PodcastPlaybackState.state.value?.artworkKey?.let { add(it) }
    }

    @Test
    fun `rapid A to B to A keeps exactly one snapshot and the right artwork key`() {
        // A: audiobook playing
        book(isPlaying = true)
        assertEquals(listOf("book:42"), publishedKeys())

        // B: podcast tries to start while the audiobook is still playing -> rejected.
        podcast(isPlaying = true)
        assertEquals(listOf("book:42"), publishedKeys())
        assertTrue(NowPlayingOwnership.isOwner(NowPlayingOwnership.Engine.AUDIOBOOK))

        // Arbiter stops the audiobook (releases the slot), then the podcast starts.
        ActivePlaybackState.clear()
        podcast(isPlaying = true)
        assertEquals(listOf("episode:7:https://cdn/a.jpg"), publishedKeys())
        assertTrue(NowPlayingOwnership.isOwner(NowPlayingOwnership.Engine.PODCAST))

        // Podcast paused: a progress-only audiobook tick cannot repaint over it.
        podcast(isPlaying = false)
        book(isPlaying = false)
        assertEquals(listOf("episode:7:https://cdn/a.jpg"), publishedKeys())
        assertTrue(NowPlayingOwnership.isOwner(NowPlayingOwnership.Engine.PODCAST))

        // A again: an explicit audiobook play takes over from the *paused* podcast
        // (the single-owner rule allows it; only a playing owner may not be stolen).
        book(isPlaying = true)
        assertEquals(listOf("book:42"), publishedKeys())
        assertTrue(NowPlayingOwnership.isOwner(NowPlayingOwnership.Engine.AUDIOBOOK))
        assertEquals(null, PodcastPlaybackState.state.value)

        // B again: podcast cannot take over while the audiobook is playing.
        podcast(isPlaying = true)
        assertEquals(listOf("book:42"), publishedKeys())

        // A different audiobook after a proper stop must publish its own id, not the old cover.
        ActivePlaybackState.clear()
        podcast(isPlaying = true)
        assertEquals(listOf("episode:7:https://cdn/a.jpg"), publishedKeys())
        PodcastPlaybackState.clear()
        book(isPlaying = true, id = 43L)
        assertEquals(listOf("book:43"), publishedKeys())
        assertNotNull(ActivePlaybackState.state.value)
    }

    @Test
    fun `paused audiobook progress ticks cannot repaint over a playing podcast`() {
        podcast(isPlaying = true)
        assertEquals(listOf("episode:7:https://cdn/a.jpg"), publishedKeys())

        // 500 ms progress tick from the paused audiobook service.
        book(isPlaying = false)

        assertEquals(listOf("episode:7:https://cdn/a.jpg"), publishedKeys())
        assertEquals(null, ActivePlaybackState.state.value)
    }

    @Test
    fun `podcast artwork key is always episode scoped even without artwork`() {
        podcast(isPlaying = true, artwork = null)
        assertEquals(listOf("episode:7:"), publishedKeys())
    }
}