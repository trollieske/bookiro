package com.bookrio.podcast.domain

import com.bookrio.data.repository.ActivePlaybackState
import com.bookrio.data.repository.NowPlayingOwnership
import com.bookrio.data.repository.PodcastPlaybackState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two mini-player states stay separate and mutually exclusive through
 * [NowPlayingOwnership]. The regression these tests lock down is the flickering
 * bar: a background/paused engine refreshing its progress must never evict the
 * engine that is actually playing.
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

    private fun publishPodcast(isPlaying: Boolean) {
        PodcastPlaybackState.update(
            episodeId = 7L,
            feedId = 3L,
            title = "Episode",
            podcastTitle = "Podcast",
            artworkUrl = null,
            isPlaying = isPlaying,
            progressPercent = 0.1f,
            positionMs = 1000L,
            durationMs = 10_000L
        )
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
}