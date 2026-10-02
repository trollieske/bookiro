package com.bookrio.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure Home logic (no Android dependencies):
 * remaining-time formatting and audio remaining calculation.
 */
class HomeLogicTest {

    @Test
    fun `remaining parts round up to whole minutes`() {
        assertEquals(RemainingParts(0, 1), remainingParts(1L))
        assertEquals(RemainingParts(0, 1), remainingParts(60_000L))
        assertEquals(RemainingParts(1, 0), remainingParts(3_600_000L))
        assertEquals(RemainingParts(1, 1), remainingParts(3_660_000L))
    }

    @Test
    fun `remaining parts is null when nothing is left`() {
        assertNull(remainingParts(null))
        assertNull(remainingParts(0L))
        assertNull(remainingParts(-1L))
    }

    @Test
    fun `audio remaining needs a known duration and a started position`() {
        assertEquals(3_600_000L, audioRemainingMs(3_600_000L, 7_200_000L))
        // Not started -> no remaining label, even with a duration.
        assertNull(audioRemainingMs(0L, 7_200_000L))
        assertNull(audioRemainingMs(null, 7_200_000L))
        // Unknown duration -> cannot compute.
        assertNull(audioRemainingMs(1_000L, 0L))
        assertNull(audioRemainingMs(1_000L, null))
        // Past the end clamps to zero rather than going negative.
        assertEquals(0L, audioRemainingMs(5_000_000L, 3_600_000L))
    }
}