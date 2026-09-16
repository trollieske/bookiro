package com.shelf.reader.ftp

import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.ftp.domain.LocalStaging
import com.shelf.reader.ftp.domain.MediaFormats
import com.shelf.reader.ftp.domain.ProgressThrottler
import com.shelf.reader.ftp.domain.TransferPolicyResolver
import com.shelf.reader.ftp.domain.TransferStateMachine
import com.shelf.reader.ftp.domain.TransportType
import com.shelf.reader.ftp.transfer.FtpWorkNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FtpDomainTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ------------------------------------------------------ state machine

    @Test
    fun `state machine accepts documented transitions`() {
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.QUEUED, DownloadStatusEntity.RUNNING))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.VERIFYING))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.VERIFYING, DownloadStatusEntity.IMPORTING))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.IMPORTING, DownloadStatusEntity.COMPLETED))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.RETRYING))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.RETRYING, DownloadStatusEntity.QUEUED))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.PAUSED_BY_USER))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.PAUSED_BY_USER, DownloadStatusEntity.QUEUED))
        assertTrue(TransferStateMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.FAILED))
    }

    @Test
    fun `state machine rejects invalid transitions`() {
        assertFalse(TransferStateMachine.canTransition(DownloadStatusEntity.QUEUED, DownloadStatusEntity.COMPLETED))
        assertFalse(TransferStateMachine.canTransition(DownloadStatusEntity.QUEUED, DownloadStatusEntity.VERIFYING))
        assertFalse(TransferStateMachine.canTransition(DownloadStatusEntity.COMPLETED, DownloadStatusEntity.RUNNING))
        assertFalse(TransferStateMachine.canTransition(DownloadStatusEntity.COMPLETED, DownloadStatusEntity.QUEUED))
        assertFalse(TransferStateMachine.canTransition(DownloadStatusEntity.CANCELLED, DownloadStatusEntity.RUNNING))
    }

    @Test
    fun `legacy statuses map onto the explicit model`() {
        assertEquals(DownloadStatusEntity.QUEUED, TransferStateMachine.normalize(DownloadStatusEntity.PENDING))
        assertEquals(DownloadStatusEntity.PAUSED_BY_USER, TransferStateMachine.normalize(DownloadStatusEntity.PAUSED))
    }

    // ---------------------------------------------------------- policy

    @Test
    fun `auto concurrency is 2 on wifi and 1 on mobile`() {
        assertEquals(2, TransferPolicyResolver.autoConcurrency(TransportType.WIFI))
        assertEquals(1, TransferPolicyResolver.autoConcurrency(TransportType.MOBILE))
    }

    @Test
    fun `user override is clamped to safe maximum`() {
        assertEquals(4, TransferPolicyResolver.resolve(TransportType.WIFI, userOverride = 9).concurrency)
        assertEquals(2, TransferPolicyResolver.resolve(TransportType.MOBILE, userOverride = 9).concurrency)
        assertEquals(2, TransferPolicyResolver.resolve(TransportType.WIFI, userOverride = -3).concurrency) // <=0 means Auto
    }

    @Test
    fun `degraded policy never raises concurrency`() {
        val normal = TransferPolicyResolver.resolve(TransportType.WIFI, userOverride = 4)
        val degraded = TransferPolicyResolver.resolve(TransportType.WIFI, userOverride = 4, degraded = true)
        assertTrue(degraded.concurrency < normal.concurrency)
        assertTrue(degraded.concurrency >= 1)
    }

    // ------------------------------------------------- staging / resume

    @Test
    fun `resume offset uses validated part length`() {
        val part = temp.newFile("book.m4b.part")
        part.writeBytes(ByteArray(500))
        assertEquals(500L, LocalStaging.resumeOffset(part, expectedSize = 1000))
        assertEquals(0L, LocalStaging.resumeOffset(part, expectedSize = 400))
        assertEquals(0L, LocalStaging.resumeOffset(temp.newFile("missing.part"), 1000))
    }

    @Test
    fun `relative path never escapes the staging root`() {
        val relative = LocalStaging.sanitizeRelative("/books/../../etc/passwd", "/books")
        assertFalse(relative.contains(".."))
        val finalFile = LocalStaging.resolveFinal(temp.root, "/books", "/books/Author/Book.m4b")
        assertTrue(finalFile.absolutePath.startsWith(temp.root.absolutePath))
    }

    @Test
    fun `atomic move replaces the final file`() {
        val part = temp.newFile("staged.part").apply { writeBytes(ByteArray(42)) }
        val target = File(temp.root, "final.m4b").apply { writeBytes(ByteArray(3)) }
        assertTrue(LocalStaging.moveIntoPlace(part, target))
        assertEquals(42L, target.length())
        assertFalse(part.exists())
    }

    @Test
    fun `media formats classify book files`() {
        assertTrue(MediaFormats.isBook("The Name of the Wind.m4b"))
        assertTrue(MediaFormats.isBook("book.EPUB"))
        assertFalse(MediaFormats.isBook("cover.jpg"))
        assertTrue(MediaFormats.isCompressedAudio("chapter.mp3"))
    }

    // --------------------------------------------------- progress write

    @Test
    fun `progress persistence is rate limited`() {
        val throttler = ProgressThrottler(minIntervalMs = 500, minBytes = 1_000)
        assertTrue(throttler.shouldPersist(0, now = 0))
        assertFalse(throttler.shouldPersist(100, now = 100))
        assertTrue(throttler.shouldPersist(1_500, now = 200)) // enough bytes
        assertFalse(throttler.shouldPersist(1_600, now = 300))
        assertTrue(throttler.shouldPersist(1_700, now = 900)) // enough time
    }

    // -------------------------------------------------------- work spec

    @Test
    fun `work name is per server and input only carries the id`() {
        assertEquals("ftp-sync-server-42", FtpWorkNaming.uniqueName(42))
        assertEquals(setOf("serverId"), FtpWorkNaming.allowedInputKeys)
    }
}