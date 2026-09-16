package com.shelf.reader.torrent.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerDiagnosticsTest {

    @Test
    fun `masks a passkey query parameter`() {
        val masked = TrackerDiagnostics.maskUrl("https://tracker.example/announce?passkey=abc123&other=1")
        assertEquals("https://tracker.example/announce?passkey=***&other=1", masked)
    }

    @Test
    fun `masks userinfo and common secret params`() {
        val masked = TrackerDiagnostics.maskUrl("https://user:s3cret@tracker.example/announce?authkey=deadbeef")
        assertFalse(masked.contains("s3cret"))
        assertFalse(masked.contains("deadbeef"))
        assertTrue(masked.contains("authkey=***"))
    }

    @Test
    fun `masking is safe for null and blank`() {
        assertEquals("", TrackerDiagnostics.maskUrl(null))
        assertEquals("", TrackerDiagnostics.maskUrl("  "))
    }

    @Test
    fun `classifies authentication rejection`() {
        assertEquals(
            TrackerState.AUTH_REJECTED,
            TrackerDiagnostics.classify("The tracker responded: invalid passkey")
        )
        assertEquals(TrackerState.AUTH_REJECTED, TrackerDiagnostics.classify("HTTP 401 Unauthorized"))
    }

    @Test
    fun `classifies client policy rejection`() {
        assertEquals(
            TrackerState.POLICY_REJECTED,
            TrackerDiagnostics.classify("Client not allowed by tracker policy")
        )
        assertEquals(TrackerState.POLICY_REJECTED, TrackerDiagnostics.classify("you are banned"))
    }

    @Test
    fun `classifies tls timeout and dns`() {
        assertEquals(TrackerState.TLS_ERROR, TrackerDiagnostics.classify("SSL certificate verify failed"))
        assertEquals(TrackerState.TIMEOUT, TrackerDiagnostics.classify("operation timed out"))
        assertEquals(TrackerState.DNS_ERROR, TrackerDiagnostics.classify("could not resolve host name"))
    }

    @Test
    fun `sanitizeReason removes tracker urls and secrets`() {
        val reason = TrackerDiagnostics.sanitizeReason(
            "Failed https://tracker.example/announce?passkey=abc123 nope"
        )
        assertFalse(reason!!.contains("passkey=abc123"))
        assertFalse(reason.contains("tracker.example"))
        assertTrue(reason.contains("<tracker>"))
    }

    @Test
    fun `empty reason becomes null`() {
        assertEquals(null, TrackerDiagnostics.sanitizeReason(""))
        assertEquals(null, TrackerDiagnostics.sanitizeReason(null))
    }
}