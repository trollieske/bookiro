package com.shelf.reader.data.transfer

import com.shelf.reader.data.local.entity.DownloadStatusEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteTransferTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sanitizeRelative strips the base and blocks path escape`() {
        assertEquals("book.epub", RemoteStaging.sanitizeRelative("/base/book.epub", "/base"))
        assertEquals("a/b.m4b", RemoteStaging.sanitizeRelative("/root/a/b.m4b", "/root"))
        // ".." segments are dropped so a remote path can never escape the root.
        assertEquals("etc/passwd", RemoteStaging.sanitizeRelative("/../../etc/passwd", "/"))
    }

    @Test
    fun `resumeOffset validates against the remote size`() {
        val staging = temp.newFile("x.part").apply { writeBytes(ByteArray(100)) }
        assertEquals(100L, RemoteStaging.resumeOffset(staging, 500L))
        // A part larger than the remote file is stale and must restart.
        assertEquals(0L, RemoteStaging.resumeOffset(staging, 50L))
    }

    @Test
    fun `moveIntoPlace promotes only an existing staging file`() {
        val dir = temp.newFolder("root")
        val staging = File(dir, "book.epub.part").apply { writeBytes(ByteArray(10)) }
        val finalFile = File(dir, "book.epub")
        assertTrue(RemoteStaging.moveIntoPlace(staging, finalFile))
        assertTrue(finalFile.exists())
        assertFalse(staging.exists())
        assertEquals(10L, finalFile.length())
    }

    @Test
    fun `media formats gate what may be imported`() {
        assertTrue(RemoteMediaFormats.isBook("The Book.epub"))
        assertTrue(RemoteMediaFormats.isBook("audio.M4B"))
        assertFalse(RemoteMediaFormats.isBook("notes.txt"))
        assertFalse(RemoteMediaFormats.isBook("archive.rar"))
    }

    @Test
    fun `transfer state machine accepts legal transitions`() {
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.QUEUED, DownloadStatusEntity.RUNNING))
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.VERIFYING))
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.VERIFYING, DownloadStatusEntity.IMPORTING))
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.IMPORTING, DownloadStatusEntity.COMPLETED))
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.RUNNING, DownloadStatusEntity.RETRYING))
        assertTrue(TransferStatusMachine.canTransition(DownloadStatusEntity.FAILED, DownloadStatusEntity.QUEUED))
    }

    @Test
    fun `transfer state machine rejects illegal transitions`() {
        assertFalse(TransferStatusMachine.canTransition(DownloadStatusEntity.COMPLETED, DownloadStatusEntity.RUNNING))
        assertFalse(TransferStatusMachine.canTransition(DownloadStatusEntity.QUEUED, DownloadStatusEntity.COMPLETED))
        assertFalse(TransferStatusMachine.canTransition(DownloadStatusEntity.IMPORTING, DownloadStatusEntity.QUEUED))
    }

    @Test
    fun `legacy PENDING and PAUSED map onto the explicit model`() {
        assertEquals(DownloadStatusEntity.QUEUED, TransferStatusMachine.normalize(DownloadStatusEntity.PENDING))
        assertEquals(DownloadStatusEntity.PAUSED_BY_USER, TransferStatusMachine.normalize(DownloadStatusEntity.PAUSED))
    }
}