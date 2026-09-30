package com.bookrio.podcast.domain

import com.bookrio.core.playback.PlaybackArbiter
import com.bookrio.data.repository.ActivePlaybackState
import com.bookrio.data.repository.NowPlayingOwnership
import com.bookrio.data.repository.NowPlayingPolicy
import com.bookrio.data.repository.PodcastPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two mini-player states stay separate and mutually exclusive through
 * [NowPlayingOwnership]. The regression these tests lock down is the flickering
 * bar: a background/paused engine refreshing its progress must never evict the
 * engine that is actually playing.
 *
 * The metadata-snapshot tests additionally lock down the BUG B fix: after a
 * hand-off exactly one snapshot is published and it always carries the artwork
 * identity of the engine that owns playback (`book:<id>` / `episode:<id>:<url>`),
 * never the loser's artwork.
 */
class PodcastPlaybackStateIsolationTest {

    @After
    fun tearDown() {
        NowPlayingOwnership.reset()
    }

    private fun publishAudiobook(isPlaying: Boolean) {
        ActivePlaybackState.update(
            bookId = 42L,
            title = "Audiobook",
            author = "Author",
            isPlaying = isPlaying,
            progressPercent = 0.5f,
            sleepTimerMinutes = null,
            sleepTimerRemainingMs = 0L
        )
    }

    private fun publishPodcast(isPlaying: Boolean, artworkUrl: String? = null) {
        PodcastPlaybackState.update(
            episodeId = 7L,
            feedId = 3L,
            title = "Episode",
            podcastTitle = "Podcast",
            artworkUrl = artworkUrl,
            isPlaying = isPlaying,
            progressPercent = 0.1f,
            positionMs = 1000L,
            durationMs = 10_000L
        )
    }

    /** Artwork keys of the snapshots currently published by the two engines. */
    private fun publishedArtworkKeys(): List<String> = listOfNotNull(
        ActivePlaybackState.state.value?.artworkKey,
        PodcastPlaybackState.state.value?.artworkKey
    )

    /** Which engine currently publishes the one allowed snapshot. */
    private fun publishedEngine(): NowPlayingOwnership.Engine {
        val book = ActivePlaybackState.state.value
        val podcast = PodcastPlaybackState.state.value
        check(!(book != null && podcast != null)) { "both engines published at once" }
        return when {
            book != null -> NowPlayingOwnership.Engine.AUDIOBOOK
            podcast != null -> NowPlayingOwnership.Engine.PODCAST
            else -> NowPlayingOwnership.Engine.NONE
        }
    }

    @Test
    fun `a podcast claims the slot once the audiobook is retired`() {
        publishAudiobook(isPlaying = true)
        assertNotNull(ActivePlaybackState.state.value)

        // The arbiter stops the audiobook before the podcast starts.
        ActivePlaybackState.clear()
        publishPodcast(isPlaying = true)

        assertNull(ActivePlaybackState.state.value)
        assertNotNull(PodcastPlaybackState.state.value)
    }

    @Test
    fun `an audiobook claims the slot once the podcast is retired`() {
        publishPodcast(isPlaying = true)
        assertNotNull(PodcastPlaybackState.state.value)

        // The arbiter stops the podcast before the audiobook starts.
        PodcastPlaybackState.clear()
        publishAudiobook(isPlaying = true)

        assertNull(PodcastPlaybackState.state.value)
        assertNotNull(ActivePlaybackState.state.value)
    }

    @Test
    fun `a paused audiobook refresh cannot evict a playing podcast`() {
        publishAudiobook(isPlaying = true)
        ActivePlaybackState.clear() // arbiter hand-off
        publishPodcast(isPlaying = true)

        // Audiobook player keeps ticking every 500 ms after being paused.
        repeat(5) { publishAudiobook(isPlaying = false) }

        assertNull(ActivePlaybackState.state.value)
        assertNotNull(PodcastPlaybackState.state.value)
        assertEquals(7L, PodcastPlaybackState.state.value!!.episodeId)
    }

    @Test
    fun `a stale audiobook playing tick cannot evict a playing podcast`() {
        publishAudiobook(isPlaying = true)
        ActivePlaybackState.clear() // arbiter hand-off
        publishPodcast(isPlaying = true)

        // A read racing the pause can still report isPlaying = true for one tick.
        publishAudiobook(isPlaying = true)

        assertNull(ActivePlaybackState.state.value)
        assertNotNull(PodcastPlaybackState.state.value)
    }

    @Test
    fun `a paused podcast cannot steal the slot back from a playing audiobook`() {
        publishPodcast(isPlaying = true)
        PodcastPlaybackState.clear() // arbiter hand-off
        publishAudiobook(isPlaying = true)

        // Pausing fires onIsPlayingChanged(false) -> publishState().
        publishPodcast(isPlaying = false)

        assertNull(PodcastPlaybackState.state.value)
        assertNotNull(ActivePlaybackState.state.value)
    }

    @Test
    fun `clearing podcast state leaves audiobook state untouched`() {
        publishAudiobook(isPlaying = false)
        PodcastPlaybackState.clear()
        assertNotNull(ActivePlaybackState.state.value)
        assertEquals(42L, ActivePlaybackState.state.value!!.bookId)
    }

    @Test
    fun `dismissing the audiobook hides it until playback restarts`() {
        publishAudiobook(isPlaying = true)
        ActivePlaybackState.dismiss()

        publishAudiobook(isPlaying = false)
        assertNull(ActivePlaybackState.state.value)

        publishAudiobook(isPlaying = true)
        assertNotNull(ActivePlaybackState.state.value)
    }

    // ---- BUG B: one publisher, deterministic artwork identity ----

    @Test
    fun `after podcast play exactly one snapshot is published and never carries a book artwork key`() {
        publishAudiobook(isPlaying = true)
        assertEquals("book:42", ActivePlaybackState.state.value!!.artworkKey)

        // Arbiter hand-off: the audiobook retires before the podcast starts.
        ActivePlaybackState.clear()
        publishPodcast(isPlaying = true, artworkUrl = "https://cdn.example/cover.jpg")

        assertNull(ActivePlaybackState.state.value)
        assertEquals(NowPlayingOwnership.Engine.PODCAST, publishedEngine())
        assertEquals(listOf("episode:7:https://cdn.example/cover.jpg"), publishedArtworkKeys())
        assertFalse(publishedArtworkKeys().any { it.startsWith(NowPlayingPolicy.BOOK_PREFIX) })
    }

    @Test
    fun `after audiobook play exactly one snapshot is published and never carries an episode artwork key`() {
        publishPodcast(isPlaying = true, artworkUrl = "https://cdn.example/cover.jpg")
        assertEquals("episode:7:https://cdn.example/cover.jpg", PodcastPlaybackState.state.value!!.artworkKey)

        PodcastPlaybackState.clear()
        publishAudiobook(isPlaying = true)

        assertNull(PodcastPlaybackState.state.value)
        assertEquals(NowPlayingOwnership.Engine.AUDIOBOOK, publishedEngine())
        assertEquals(listOf("book:42"), publishedArtworkKeys())
        assertFalse(publishedArtworkKeys().any { it.startsWith(NowPlayingPolicy.EPISODE_PREFIX) })
    }

    @Test
    fun `missing podcast artwork still yields a deterministic episode-scoped key`() {
        publishPodcast(isPlaying = true, artworkUrl = null)
        assertEquals(listOf("episode:7:"), publishedArtworkKeys())
        assertFalse(publishedArtworkKeys().any { it.startsWith(NowPlayingPolicy.BOOK_PREFIX) })
    }

    @Test
    fun `rapid switches stay ordered and only the latest engine publishes`() {
        // book -> podcast -> book -> podcast, each hand-off through the arbiter.
        publishAudiobook(isPlaying = true)
        assertEquals(listOf("book:42"), publishedArtworkKeys())

        ActivePlaybackState.clear()
        publishPodcast(isPlaying = true, artworkUrl = "https://cdn.example/ep1.jpg")
        assertEquals(listOf("episode:7:https://cdn.example/ep1.jpg"), publishedArtworkKeys())

        PodcastPlaybackState.clear()
        publishAudiobook(isPlaying = true)
        assertEquals(listOf("book:42"), publishedArtworkKeys())

        ActivePlaybackState.clear()
        publishPodcast(isPlaying = true, artworkUrl = "https://cdn.example/ep2.jpg")
        assertEquals(NowPlayingOwnership.Engine.PODCAST, NowPlayingOwnership.current())
        assertEquals(listOf("episode:7:https://cdn.example/ep2.jpg"), publishedArtworkKeys())
        assertNull(ActivePlaybackState.state.value)
    }

    @Test
    fun `no engine may steal the slot from a playing owner`() {
        publishPodcast(isPlaying = true, artworkUrl = "https://cdn.example/cover.jpg")
        assertEquals(NowPlayingOwnership.Engine.PODCAST, NowPlayingOwnership.current())
        assertTrue(NowPlayingOwnership.otherEngineIsPlaying(NowPlayingOwnership.Engine.AUDIOBOOK))
        assertFalse(NowPlayingOwnership.otherEngineIsPlaying(NowPlayingOwnership.Engine.PODCAST))

        // A racing audiobook play is refused until the arbiter retires the podcast.
        publishAudiobook(isPlaying = true)
        assertNull(ActivePlaybackState.state.value)
        assertEquals(listOf("episode:7:https://cdn.example/cover.jpg"), publishedArtworkKeys())

        PodcastPlaybackState.clear()
        publishAudiobook(isPlaying = true)
        assertEquals(listOf("book:42"), publishedArtworkKeys())
        assertFalse(NowPlayingOwnership.otherEngineIsPlaying(NowPlayingOwnership.Engine.AUDIOBOOK))
        assertTrue(NowPlayingOwnership.otherEngineIsPlaying(NowPlayingOwnership.Engine.PODCAST))
    }

    @Test
    fun `a paused owner no longer counts as playing so the other engine may claim`() {
        publishAudiobook(isPlaying = true)
        publishAudiobook(isPlaying = false) // pause keeps ownership
        assertTrue(NowPlayingOwnership.isOwner(NowPlayingOwnership.Engine.AUDIOBOOK))
        // Services use this to decide whether a deferred load may proceed: a paused
        // owner must not block the podcast from loading/claiming.
        assertFalse(NowPlayingOwnership.otherEngineIsPlaying(NowPlayingOwnership.Engine.PODCAST))

        publishPodcast(isPlaying = true)
        assertEquals(NowPlayingOwnership.Engine.PODCAST, NowPlayingOwnership.current())
        assertNull(ActivePlaybackState.state.value)

        // Paused refresh of the retired audiobook still cannot steal it back.
        publishAudiobook(isPlaying = false)
        assertNull(ActivePlaybackState.state.value)
        assertEquals(listOf("episode:7:"), publishedArtworkKeys())
    }

    @Test
    fun `arbiter stopOthers invokes only the losing engine`() {
        val stopped = mutableListOf<String>()
        PlaybackArbiter.register("test_winner") { stopped += "winner" }
        PlaybackArbiter.register("test_loser") { stopped += "loser" }
        try {
            val invoked = PlaybackArbiter.stopOthers("test_winner")
            assertEquals(listOf("test_loser"), invoked)
            assertEquals(listOf("loser"), stopped)
        } finally {
            PlaybackArbiter.unregister("test_winner")
            PlaybackArbiter.unregister("test_loser")
        }
    }
}
