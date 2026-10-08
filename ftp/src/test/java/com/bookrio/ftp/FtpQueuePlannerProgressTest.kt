package com.bookrio.ftp

import com.bookrio.ftp.client.DownloadOutcome
import com.bookrio.ftp.client.FtpEntry
import com.bookrio.ftp.client.FtpEntryType
import com.bookrio.ftp.client.FtpProtocol
import com.bookrio.ftp.client.RemoteCredentials
import com.bookrio.ftp.client.RemoteFileClient
import com.bookrio.ftp.client.RemoteFileInfo
import com.bookrio.ftp.data.FtpSource
import com.bookrio.ftp.data.FtpSourceInput
import com.bookrio.ftp.data.FtpSourceRepository
import com.bookrio.ftp.data.FtpTransferRepository
import com.bookrio.ftp.fakes.FakeCredentialCipher
import com.bookrio.ftp.fakes.FakeDownloadTaskDao
import com.bookrio.ftp.fakes.FakeFtpServerDao
import com.bookrio.ftp.fakes.FakeRemoteFileClient
import com.bookrio.ftp.transfer.FtpQueuePlanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM audit of the overnight FTP scanning phase on a large folder:
 * live progress while the remote tree is listed, idempotent replanning
 * (no duplicate import after restart) and cancellation cleanup.
 */
class FtpQueuePlannerProgressTest {

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

    private suspend fun createSource(): Long = sourceRepository.upsert(
        FtpSourceInput(
            displayName = "EVO",
            host = "seedbox.example",
            port = 22,
            username = "user",
            password = "pw",
            protocol = FtpProtocol.SFTP,
            basePath = "/books",
            concurrencyOverride = 1
        )
    )

    private fun seedTree() {
        client.addFolder(
            "/books",
            listOf(
                FakeRemoteFileClient.fileEntry("A.m4b", "/books/A.m4b", 64),
                FakeRemoteFileClient.folderEntry("sub", "/books/sub"),
                FakeRemoteFileClient.fileEntry("B.epub", "/books/B.epub", 64),
                FakeRemoteFileClient.fileEntry("cover.jpg", "/books/cover.jpg", 64)
            )
        )
        client.addFolder(
            "/books/sub",
            listOf(
                FakeRemoteFileClient.fileEntry("C.pdf", "/books/sub/C.pdf", 64),
                FakeRemoteFileClient.folderEntry("deeper", "/books/sub/deeper")
            )
        )
        client.addFolder(
            "/books/sub/deeper",
            listOf(FakeRemoteFileClient.fileEntry("D.mp3", "/books/sub/deeper/D.mp3", 64))
        )
    }

    @Test
    fun `large folder scan reports live progress per folder and queues only books`() = runTest {
        val serverId = createSource()
        seedTree()
        val source = sourceRepository.getSource(serverId)!!

        val progress = mutableListOf<Int>()
        val added = FtpQueuePlanner(sourceRepository, transferRepository)
            .plan(source) { found -> progress.add(found) }

        // Media files only: A, B, C, D (cover.jpg filtered before enqueue).
        assertEquals(4, added)
        assertEquals(4, transferRepository.queuedCount(serverId))
        // Progress arrived while scanning, folder by folder, monotonically. The
        // counter counts every file found (cover.jpg included); the queue only
        // takes books.
        assertTrue(progress.isNotEmpty())
        assertTrue(progress == progress.sorted())
        assertEquals(5, progress.last())
        assertEquals(listOf(3, 3, 5), progress)
    }

    @Test
    fun `re-planning the same folder never queues a duplicate`() = runTest {
        val serverId = createSource()
        seedTree()
        val source = sourceRepository.getSource(serverId)!!
        val planner = FtpQueuePlanner(sourceRepository, transferRepository)

        assertEquals(4, planner.plan(source))
        assertEquals(0, planner.plan(source))
        assertEquals(4, transferRepository.queuedCount(serverId))
        assertEquals(4, taskDao.rows.size)
    }

    @Test
    fun `cancelling the scan propagates and still closes the connection`() = runBlocking {
        val serverId = createSource()
        val slowClient = SlowRemoteFileClient()
        val repo = FtpSourceRepository(serverDao, taskDao, cipher) { slowClient }
        val source = repo.getSource(serverId)!!

        val job = launch(Dispatchers.Default) {
            FtpQueuePlanner(repo, transferRepository).plan(source)
        }
        slowClient.entered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(slowClient.closed)
    }
}

/** Remote client whose listing suspends until cancelled — lets the test cancel mid-scan. */
private class SlowRemoteFileClient : RemoteFileClient {
    val entered = CompletableDeferred<Unit>()
    var closed = false
        private set

    override val isConnected: Boolean = true

    override suspend fun connect(credentials: RemoteCredentials): Boolean = true

    override suspend fun listDirectory(path: String): List<FtpEntry> {
        entered.complete(Unit)
        delay(60_000)
        return emptyList()
    }

    override suspend fun stat(remotePath: String): RemoteFileInfo? = null

    override suspend fun download(
        remotePath: String,
        target: File,
        offset: Long,
        expectedSize: Long,
        bufferSize: Int,
        onProgress: suspend (Long) -> Unit
    ): DownloadOutcome = error("not used")

    override suspend fun disconnect() = Unit

    override fun close() {
        closed = true
    }
}