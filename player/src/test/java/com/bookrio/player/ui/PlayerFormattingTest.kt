package com.bookrio.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFormattingTest {

    // ── formatPlaybackSpeed: påkrevde eksakte formater ──
    @Test
    fun `playback speed formats exactly as specified`() {
        assertEquals("0.5×", formatPlaybackSpeed(0.5f))
        assertEquals("0.75×", formatPlaybackSpeed(0.75f))
        assertEquals("1×", formatPlaybackSpeed(1.0f))
        assertEquals("1.25×", formatPlaybackSpeed(1.25f))
        assertEquals("1.5×", formatPlaybackSpeed(1.5f))
        assertEquals("2×", formatPlaybackSpeed(2.0f))
        assertEquals("3×", formatPlaybackSpeed(3.0f))
    }

    @Test
    fun `playback speed output never contains template syntax or trailing dot zero`() {
        val speeds = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        speeds.forEach { s ->
            val out = formatPlaybackSpeed(s)
            assertFalse("literal \${ in output for $s: $out", out.contains("\${"))
            assertFalse("literal } in output for $s: $out", out.contains("}"))
            assertFalse("trailing .0× in output for $s: $out", out.contains(".0×"))
            assertFalse("newline in output for $s", out.contains("\n"))
            assertFalse("space in output for $s: $out", out.contains(" "))
            assertTrue("must end with × for $s: $out", out.endsWith("×"))
        }
    }

    @Test
    fun `playback speed handles degenerate input safely`() {
        assertEquals("1×", formatPlaybackSpeed(Float.NaN))
        assertEquals("1×", formatPlaybackSpeed(Float.POSITIVE_INFINITY))
    }

    // ── formatSleepCountdown ──
    @Test
    fun `sleep countdown formats mm colon ss`() {
        assertEquals("45:00", formatSleepCountdown(2700L))
        assertEquals("09:05", formatSleepCountdown(545L))
        assertEquals("00:00", formatSleepCountdown(0L))
        assertEquals("90:00", formatSleepCountdown(5400L))
        assertEquals("00:59", formatSleepCountdown(59L))
    }

    @Test
    fun `sleep countdown never negative and never zero colon zero`() {
        assertEquals("00:00", formatSleepCountdown(-1L))
        assertEquals("00:00", formatSleepCountdown(Long.MIN_VALUE))
        val out = formatSleepCountdown(-120L)
        assertFalse(out.contains("-"))
        assertFalse(out.contains("0:0") && out != "00:00")
    }
}
