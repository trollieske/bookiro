package com.bookrio.ftp

import com.bookrio.ftp.transfer.ActiveTransfer
import com.bookrio.ftp.transfer.FtpTransferRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM audit of the overnight FTP "scanning remote folder" phase:
 * begin/progress/end, and that clearing a server can never leave a stuck
 * "preparing" spinner.
 */
class FtpPreparingRuntimeTest {

    private val runtime = FtpTransferRuntime()

    @Test
    fun `preparing state starts with zero files and tracks the live count`() {
        runtime.beginPreparing(serverId = 7L)
        assertTrue(runtime.preparing.value.containsKey(7L))
        assertEquals(0, runtime.preparing.value.getValue(7L).scannedFiles)

        runtime.progressPreparing(7L, 120)
        assertEquals(120, runtime.preparing.value.getValue(7L).scannedFiles)

        runtime.progressPreparing(7L, 431)
        assertEquals(431, runtime.preparing.value.getValue(7L).scannedFiles)

        runtime.endPreparing(7L)
        assertFalse(runtime.preparing.value.containsKey(7L))
    }

    @Test
    fun `progress for a server that is not preparing is ignored`() {
        runtime.progressPreparing(99L, 5)
        assertFalse(runtime.preparing.value.containsKey(99L))
    }

    @Test
    fun `clearing a server drops its preparing state`() {
        runtime.beginPreparing(7L)
        runtime.update(ActiveTransfer(1L, 7L, "a.m4b", 10, 100, 1))
        runtime.clearServer(7L)
        assertFalse(runtime.preparing.value.containsKey(7L))
        assertTrue(runtime.snapshot().isEmpty())
    }

    @Test
    fun `active transfers are published sorted and the fraction is clamped`() {
        runtime.update(ActiveTransfer(2L, 1L, "zeta.m4b", 50, 100, 5))
        runtime.update(ActiveTransfer(1L, 1L, "alpha.m4b", 300, 100, 5))
        assertEquals(listOf("alpha.m4b", "zeta.m4b"), runtime.snapshot().map { it.name })
        assertEquals(1f, runtime.snapshot().first { it.name == "alpha.m4b" }.fraction, 0.0001f)
        assertEquals(0.5f, runtime.snapshot().first { it.name == "zeta.m4b" }.fraction, 0.0001f)

        runtime.remove(2L)
        assertNull(runtime.snapshot().firstOrNull { it.name == "zeta.m4b" })
    }
}