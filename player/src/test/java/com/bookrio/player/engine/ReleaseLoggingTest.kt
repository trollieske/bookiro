package com.bookrio.player.engine

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Audit of the overnight release-logging fixes in :player.
 *
 * `chapterDiag` writes book ids/titles and rescan details; the flag must stay
 * off in the released build. The LAN debug telemetry that used to POST from
 * PlayerScreen/PlayerViewModel was removed; see REGRESSION_AUDIT.md for the
 * source-level evidence (it is not a runtime behaviour that can be unit-tested).
 */
class ReleaseLoggingTest {

    @Test
    fun `verbose chapter diagnostics are disabled`() {
        assertFalse(CHAPTER_REFRESH_DIAG)
    }
}