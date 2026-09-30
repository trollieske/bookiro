package com.bookrio.podcast.domain

import com.bookrio.core.playback.LoadGate
import com.bookrio.core.playback.LoadedTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress writes must be keyed to the timeline the player actually holds.
 *
 * The regression: a load sets the *requested* book/episode id while the player
 * still holds the previous timeline; a tick in that window would persist the old
 * position under the new id (clamped to 100% = the new book falsely finished).
 */
class LoadedTimelineTest {

    @Test
    fun `a write captured for the loaded timeline targets it`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 5L, token = 1L)
        assertEquals(5L, timeline.progressTarget(5L, 1L))
    }

    @Test
    fun `a write captured before a switch never hits the new id`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 5L, token = 1L)
        val captured = timeline.snapshot() // tick captured while book 5 played

        timeline.committed(id = 6L, token = 2L) // user switched; player holds book 6 now

        assertNull(timeline.progressTarget(captured))
        assertEquals(6L, timeline.progressTarget(6L, 2L))
    }

    @Test
    fun `a merely requested id is never a write target`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 5L, token = 1L) // book 5 is loaded and playing
        val requestedByNewLoad = 6L // loadBook(6) requested, not committed yet
        assertNull(timeline.progressTarget(requestedByNewLoad, 1L))
        assertEquals(5L, timeline.progressTarget(5L, 1L))
    }

    @Test
    fun `a rapid a to b to a switch drops the stale same-id write`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 5L, token = 1L) // A1 loaded
        val staleCapture = timeline.snapshot() // tick captured for A1

        timeline.committed(id = 6L, token = 2L) // B
        timeline.committed(id = 5L, token = 3L) // A2: same id, newer generation

        assertNull(timeline.progressTarget(staleCapture)) // must not override A2's progress
        assertEquals(5L, timeline.progressTarget(5L, 3L))
    }

    @Test
    fun `retire keeps the last loaded id and token so the final persist survives`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 7L, token = 4L)
        val captured = timeline.snapshot()
        // retirePlayback() persists and then clears the player, but keeps id + token.
        assertEquals(7L, timeline.progressTarget(captured))
    }

    @Test
    fun `an external timeline clears the write target`() {
        val timeline = LoadedTimeline()
        timeline.committed(id = 9L, token = 1L)
        timeline.cleared() // e.g. a media-library item took over the player
        assertNull(timeline.progressTarget(9L, 1L))
        assertNull(timeline.progressTarget(0L, 0L))
        assertNull(timeline.progressTarget(-1L, 1L))
    }

    @Test
    fun `progress captured before a rapid switch is dropped, not stamped onto the new id`() {
        val gate = LoadGate()
        val timeline = LoadedTimeline()

        val tokenA = gate.begin()
        assertTrue(gate.shouldApply(tokenA, force = false, otherEngineIsPlaying = false))
        timeline.committed(id = 1L, token = tokenA) // book 1 accepted by the player
        val captured = timeline.snapshot() // progress tick captured for book 1

        val tokenB = gate.begin() // user switches to book 2
        assertTrue(gate.shouldApply(tokenB, force = false, otherEngineIsPlaying = false))
        timeline.committed(id = 2L, token = tokenB)

        assertNull(timeline.progressTarget(captured)) // must not write 1's position to 2
        assertEquals(2L, timeline.progressTarget(2L, tokenB))
    }
}
