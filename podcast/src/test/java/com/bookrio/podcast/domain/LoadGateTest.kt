package com.bookrio.podcast.domain

import com.bookrio.core.playback.LoadGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load/deferral decision the two playback services share.
 *
 * These tests exercise the real helper the services call, so removing the
 * generation guard or the deferral rule makes them fail (not just compile-error).
 */
class LoadGateTest {

    @Test
    fun `a passive load does not apply while the other engine plays`() {
        val gate = LoadGate()
        val token = gate.begin()
        assertFalse(gate.shouldApply(token, force = false, otherEngineIsPlaying = true))
    }

    @Test
    fun `a passive load applies once the other engine is not playing`() {
        val gate = LoadGate()
        val token = gate.begin()
        assertTrue(gate.shouldApply(token, force = false, otherEngineIsPlaying = false))
    }

    @Test
    fun `retire invalidates an in-flight load`() {
        val gate = LoadGate()
        val token = gate.begin()
        assertTrue(gate.shouldApply(token, force = true, otherEngineIsPlaying = false))

        gate.invalidate() // arbiter hand-off / mini-player close

        assertFalse(gate.shouldApply(token, force = true, otherEngineIsPlaying = false))
        assertFalse(gate.shouldApply(token, force = false, otherEngineIsPlaying = false))
    }

    @Test
    fun `an explicit play takes ownership while the other engine plays`() {
        val gate = LoadGate()
        val token = gate.begin()
        assertTrue(gate.shouldApply(token, force = true, otherEngineIsPlaying = true))
    }

    @Test
    fun `rapid a to b to a stays ordered`() {
        val gate = LoadGate()
        val a1 = gate.begin() // load A
        assertTrue(gate.shouldApply(a1, force = false, otherEngineIsPlaying = false))

        val b = gate.begin() // switch to B while A's load is still finishing
        assertFalse(gate.shouldApply(a1, force = true, otherEngineIsPlaying = false))
        assertTrue(gate.shouldApply(b, force = false, otherEngineIsPlaying = false))

        val a2 = gate.begin() // back to A before B's completion lands
        assertFalse(gate.shouldApply(b, force = false, otherEngineIsPlaying = false))
        assertTrue(gate.shouldApply(a2, force = false, otherEngineIsPlaying = false))
    }

    @Test
    fun `entry deferral only blocks passive loads`() {
        val gate = LoadGate()
        assertTrue(gate.shouldDefer(force = false, otherEngineIsPlaying = true))
        assertFalse(gate.shouldDefer(force = true, otherEngineIsPlaying = true))
        assertFalse(gate.shouldDefer(force = false, otherEngineIsPlaying = false))
    }
}
