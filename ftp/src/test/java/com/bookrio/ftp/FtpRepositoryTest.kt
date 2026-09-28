package com.bookrio.ftp

import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.FtpSourceStateEntity
import com.bookrio.ftp.client.FtpProtocol
import com.bookrio.ftp.data.FtpSourceInput
import com.bookrio.ftp.data.FtpSourceRepository
import com.bookrio.ftp.data.FtpTransferRepository
import com.bookrio.ftp.data.LegacyServerInput
import com.bookrio.ftp.fakes.FakeCredentialCipher
import com.bookrio.ftp.fakes.FakeDownloadTaskDao
import com.bookrio.ftp.fakes.FakeFtpServerDao
import com.bookrio.ftp.fakes.FakeRemoteFileClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FtpRepositoryTest {

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

    private fun input(host: String = "seedbox.example", password: String = "s3cret") = FtpSourceInput(
        displayName = "EVO",
        host = host,
        port = 22,
        username = "user",
        password = password,
        protocol = FtpProtocol.SFTP,
        basePath = "/books"
    )

    @Test
    fun `credentials are encrypted before persistence`() = runTest {
        val id = sourceRepository.upsert(input(password = "mypassword"))
        val stored = serverDao.getById(id)!!
        assertTrue(stored.passwordEncrypted!!.startsWith("enc:"))
        assertTrue(!stored.passwordEncrypted!!.contains("mypassword"))
        assertEquals("mypassword", cipher.decrypt(stored.passwordEncrypted))
    }

    @Test
    fun `blank password on edit keeps the stored ciphertext`() = runTest {
        val id = sourceRepository.upsert(input(password = "keepme"))
        val original = serverDao.getById(id)!!.passwordEncrypted
        sourceRepository.upsert(input(password = "").copy(id = id))
        assertEquals(original, serverDao.getById(id)!!.passwordEncrypted)
    }

    @Test
    fun `the same server is never stored twice`() = runTest {
        sourceRepository.upsert(input())
        sourceRepository.upsert(input())
        assertEquals(1, serverDao.rows.size)
    }

    @Test
    fun `legacy migration is idempotent`() = runTest {
        val legacy = listOf(
            LegacyServerInput("EVO", "seedbox.example", 22, "user", "pw", FtpProtocol.SFTP, true, "/books")
        )
        val first = sourceRepository.migrateLegacy(legacy)
        val second = sourceRepository.migrateLegacy(legacy)
        assertEquals(1, first.inserted)
        assertEquals(0, first.reused)
        assertEquals(0, second.inserted)
        assertEquals(1, second.reused)
        assertEquals(1, serverDao.rows.size)
    }

    @Test
    fun `decrypt failure marks the source as needing auth`() = runTest {
        val id = sourceRepository.upsert(input())
        serverDao.rows[id] = serverDao.rows[id]!!.copy(passwordEncrypted = "BROKEN")
        val credentials = sourceRepository.credentialsFor(id)
        assertEquals(null, credentials)
        assertEquals(FtpSourceStateEntity.NEEDS_AUTH, serverDao.getById(id)!!.state)
    }

    @Test
    fun `same remote file cannot be enqueued twice`() = runTest {
        val id = sourceRepository.upsert(input())
        val entries = listOf(FakeRemoteFileClient.fileEntry("a.m4b", "/books/a.m4b", 1024))
        val first = transferRepository.enqueue(id, "/books", entries)
        val second = transferRepository.enqueue(id, "/books", entries)
        assertEquals(1, first.added)
        assertEquals(0, second.added)
        assertEquals(1, second.alreadyQueued)
        assertEquals(1, taskDao.rows.size)
    }

    @Test
    fun `illegal transitions are rejected`() = runTest {
        val id = sourceRepository.upsert(input())
        val entries = listOf(FakeRemoteFileClient.fileEntry("a.m4b", "/books/a.m4b", 1024))
        transferRepository.enqueue(id, "/books", entries)
        val task = taskDao.rows.values.first()
        assertNotNull(task)
        val thrown = runCatching {
            transferRepository.transition(task.id, DownloadStatusEntity.COMPLETED)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `queued-only work is reported as runnable but paused work is not`() = runTest {
        val id = sourceRepository.upsert(input())
        transferRepository.enqueue(id, "/books", listOf(FakeRemoteFileClient.fileEntry("a.m4b", "/books/a.m4b", 1024)))
        assertEquals(listOf(id), transferRepository.runnableServerIds())

        transferRepository.pause(id)
        assertTrue(transferRepository.runnableServerIds().isEmpty())
    }

    @Test
    fun `deleting a source removes or cancels its tasks`() = runTest {
        val id = sourceRepository.upsert(input())
        transferRepository.enqueue(id, "/books", listOf(FakeRemoteFileClient.fileEntry("a.m4b", "/books/a.m4b", 10)))
        sourceRepository.deleteSource(id, deleteQueuedTasks = true)
        assertEquals(0, serverDao.rows.size)
        assertEquals(0, taskDao.rows.size)
    }
}