package com.shelf.reader.ftp

import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.ftp.client.FtpProtocol
import com.shelf.reader.ftp.data.FtpSourceInput
import com.shelf.reader.ftp.data.FtpSourceRepository
import com.shelf.reader.ftp.data.FtpTransferRepository
import com.shelf.reader.ftp.domain.LocalStaging
import com.shelf.reader.ftp.domain.TransportType
import com.shelf.reader.ftp.fakes.FakeCredentialCipher
import com.shelf.reader.ftp.fakes.FakeDownloadTaskDao
import com.shelf.reader.ftp.fakes.FakeFtpImporter
import com.shelf.reader.ftp.fakes.FakeFtpServerDao
import com.shelf.reader.ftp.fakes.FakeRemoteFileClient
import com.shelf.reader.ftp.transfer.FtpTransferEngine
import com.shelf.reader.ftp.transfer.FtpTransferRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class FtpTransferEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val serverDao = FakeFtpServerDao()
    private val taskDao = FakeDownloadTaskDao()
    private val cipher = FakeCredentialCipher()
    private val client = FakeRemoteFileClient()
    private val sourceRepository = FtpSourceRepository(serverDao, taskDao, cipher) { client }
    private val transferRepository = FtpTransferRepository(taskDao) { serverId ->
        File(temp.root, "server_$serverId")
    }
    private val importer = FakeFtpImporter()
    private val runtime = FtpTransferRuntime()

    private fun engine() = FtpTransferEngine(
        sourceRepository = sourceRepository,
        transferRepository = transferRepository,
        importer = importer,
        runtime = runtime,
        transportProvider = { TransportType.WIFI },
        onUpdate = {}
    )

    private suspend fun createSource(): Long = sourceRepository.upsert(
        FtpSourceInput(
            displayName = "EVO",
            host = "seedbox.example",
            port = 22,
            username = "user",
            password = "pw",
            protocol = FtpProtocol.SFTP,
            basePath = "/books",
            concurrencyOverride = 1 // deterministic lanes for the fake DAO
        )
    )

    private fun finalFileFor(serverId: Long, remotePath: String): File {
        val root = File(temp.root, "server_$serverId")
        return LocalStaging.resolveFinal(root, "/books", remotePath)
    }

    @Test
    fun `complete download is verified, moved and imported exactly once`() = runTest {
        val serverId = createSource()
        client.addFile("/books/The Name of the Wind.m4b", size = 256)
        transferRepository.enqueue(
            serverId, "/books",
            listOf(FakeRemoteFileClient.fileEntry("The Name of the Wind.m4b", "/books/The Name of the Wind.m4b", 256))
        )

        val result = engine().run(serverId) { false }

        assertEquals(1, result.completed)
        assertEquals(1, importer.imported.size)
        val finalFile = finalFileFor(serverId, "/books/The Name of the Wind.m4b")
        assertTrue(finalFile.exists())
        assertEquals(256L, finalFile.length())
        assertFalse(LocalStaging.stagingFor(finalFile).exists())
        val task = taskDao.rows.values.single()
        assertEquals(DownloadStatusEntity.COMPLETED, task.status)
    }

    @Test
    fun `an incomplete download is never imported and keeps its part file`() = runTest {
        val serverId = createSource()
        client.addFile("/books/Broken.m4b", size = 256)
        client.truncateTo = 100L // server drops after 100 of 256 bytes
        transferRepository.enqueue(
            serverId, "/books",
            listOf(FakeRemoteFileClient.fileEntry("Broken.m4b", "/books/Broken.m4b", 256))
        )

        val result = engine().run(serverId) { false }

        assertEquals(0, result.completed)
        assertTrue(importer.imported.isEmpty())
        val finalFile = finalFileFor(serverId, "/books/Broken.m4b")
        assertFalse(finalFile.exists())
        assertTrue(LocalStaging.stagingFor(finalFile).exists())
        val task = taskDao.rows.values.single()
        assertTrue(task.status == DownloadStatusEntity.RETRYING || task.status == DownloadStatusEntity.FAILED)
    }

    @Test
    fun `a compatible part file is resumed and completed`() = runTest {
        val serverId = createSource()
        val bytes = ByteArray(256) { (it % 251).toByte() }
        client.files["/books/Resume.m4b"] = bytes

        val finalFile = finalFileFor(serverId, "/books/Resume.m4b")
        val staging = LocalStaging.stagingFor(finalFile)
        staging.parentFile?.mkdirs()
        FileOutputStream(staging).use { it.write(bytes.copyOfRange(0, 100)) }

        transferRepository.enqueue(
            serverId, "/books",
            listOf(FakeRemoteFileClient.fileEntry("Resume.m4b", "/books/Resume.m4b", 256))
        )

        val result = engine().run(serverId) { false }

        assertEquals(1, result.completed)
        assertTrue(finalFile.exists())
        assertEquals(256L, finalFile.length())
        assertTrue(importer.imported.size == 1)
    }

    @Test
    fun `queue and state survive repository recreation`() = runTest {
        val serverId = createSource()
        client.addFile("/books/A.m4b", size = 64)
        transferRepository.enqueue(
            serverId, "/books",
            listOf(FakeRemoteFileClient.fileEntry("A.m4b", "/books/A.m4b", 64))
        )
        // New repository instances backed by the same durable DAO.
        val recreatedTasks = FakeDownloadTaskDao().also { it.rows.putAll(taskDao.rows) }
        val recreatedRepo = FtpTransferRepository(recreatedTasks) { File(temp.root, "server_$it") }
        assertEquals(1, recreatedTasks.rows.size)
        assertTrue(recreatedRepo.queuedCount(serverId) == 1)
    }
}