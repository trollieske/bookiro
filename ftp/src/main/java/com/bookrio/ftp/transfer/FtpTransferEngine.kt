package com.bookrio.ftp.transfer

import com.bookrio.data.local.entity.DownloadStatusEntity
import com.bookrio.data.local.entity.DownloadTaskEntity
import com.bookrio.data.local.entity.FtpSourceStateEntity
import com.bookrio.ftp.client.FtpErrorKind
import com.bookrio.ftp.client.FtpException
import com.bookrio.ftp.client.RemoteFileClient
import com.bookrio.ftp.data.FtpSource
import com.bookrio.ftp.data.FtpSourceRepository
import com.bookrio.ftp.data.FtpTransferRepository
import com.bookrio.ftp.domain.LocalStaging
import com.bookrio.ftp.domain.ProgressThrottler
import com.bookrio.ftp.domain.TransferPolicy
import com.bookrio.ftp.domain.TransferPolicyResolver
import com.bookrio.ftp.domain.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

enum class StopReason { QUEUE_EMPTY, AUTH_FAILURE, CONNECTION_LOST, STORAGE_FULL, USER_CANCELLED }

data class TransferRunResult(
    val completed: Int,
    val failed: Int,
    val reason: StopReason
)

private enum class FileOutcome {
    COMPLETED,
    RETRY_SCHEDULED,
    FAILED,
    NEEDS_RECONNECT,
    AUTH_FAILED,
    STORAGE_FULL,
    CANCELLED
}

/**
 * Persistent, screen-independent transfer engine.
 *
 * The engine owns no socket beyond one connection per transfer lane, and writes
 * every meaningful state change back to Room. It can be cancelled safely at any
 * point: the next worker start rehydrates active rows.
 */
class FtpTransferEngine(
    private val sourceRepository: FtpSourceRepository,
    private val transferRepository: FtpTransferRepository,
    private val importer: FtpImporter,
    private val runtime: FtpTransferRuntime,
    private val transportProvider: () -> TransportType,
    private val onUpdate: suspend () -> Unit,
    private val powerProvider: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES
) {

    suspend fun run(serverId: Long, isStopped: () -> Boolean): TransferRunResult {
        val source = sourceRepository.getSource(serverId)
            ?: return TransferRunResult(0, 0, StopReason.QUEUE_EMPTY)
        val credentials = sourceRepository.credentialsFor(serverId)
        if (credentials == null) {
            sourceRepository.markState(serverId, FtpSourceStateEntity.NEEDS_AUTH, "Sign-in required")
            return TransferRunResult(0, 0, StopReason.AUTH_FAILURE)
        }

        // A fresh worker start means any previous RUNNING row is stale.
        transferRepository.rehydrateActive(serverId)

        val policy = TransferPolicyResolver.resolve(
            transport = transportProvider(),
            userOverride = source.concurrencyOverride,
            powered = powerProvider()
        )

        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val stop = AtomicReference<StopReason?>(null)
        val authFailed = AtomicBoolean(false)
        val storageFull = AtomicBoolean(false)
        val connectionFailures = AtomicInteger(0)
        val downloadsDone = AtomicBoolean(false)

        coroutineScope {
            // Imports run off the download lanes: lanes only fetch + verify + atomically
            // move files, and one importer batches them per folder. This keeps the
            // network lanes busy and avoids the library layer's per-file full scan.
            val importJob = launch(Dispatchers.IO) {
                importLoop(serverId, source, downloadsDone, isStopped)
                if (!isStopped()) {
                    runCatching { importer.consolidateFragmentedAudiobooks() }
                }
            }

            val lanes = (0 until policy.concurrency).map {
                launch(Dispatchers.IO) {
                    var client: RemoteFileClient? = null
                    try {
                        while (!isStopped() && !authFailed.get() && stop.get() == null) {
                            val task = transferRepository.nextRunnable(serverId) ?: break
                            if (!transferRepository.claim(task.id)) continue

                            if (client == null || !client.isConnected) {
                                val fresh = sourceRepository.clientFactory.create()
                                val connected = try {
                                    fresh.connect(credentials)
                                } catch (e: FtpException) {
                                    if (e.kind == FtpErrorKind.AUTH) {
                                        authFailed.set(true)
                                        sourceRepository.markState(serverId, FtpSourceStateEntity.NEEDS_AUTH, "Sign-in required")
                                        transferRepository.fail(task.id, "Sign-in required", "auth")
                                    }
                                    false
                                } catch (_: Throwable) {
                                    false
                                }

                                if (!connected) {
                                    runCatching { fresh.close() }
                                    client = null
                                    if (authFailed.get()) {
                                        stop.compareAndSet(null, StopReason.AUTH_FAILURE)
                                        break
                                    }
                                    val failures = connectionFailures.incrementAndGet()
                                    val backoff = backoffMs(task.retryCount)
                                    scheduleRetry(task, "Connection lost", "network", backoff)
                                    if (failures >= MAX_CONNECTION_FAILURES) {
                                        sourceRepository.markState(
                                            serverId,
                                            FtpSourceStateEntity.CONNECTION_ERROR,
                                            "Could not connect"
                                        )
                                        stop.compareAndSet(null, StopReason.CONNECTION_LOST)
                                    } else {
                                        delay(backoff)
                                    }
                                    continue
                                }
                                connectionFailures.set(0)
                                sourceRepository.markConnected(serverId)
                                client = fresh
                            }

                            when (downloadOne(client!!, source, task, policy, isStopped)) {
                                FileOutcome.COMPLETED -> {
                                    completed.incrementAndGet()
                                    connectionFailures.set(0)
                                }
                                FileOutcome.RETRY_SCHEDULED -> Unit
                                FileOutcome.FAILED -> failed.incrementAndGet()
                                FileOutcome.NEEDS_RECONNECT -> {
                                    runCatching { client?.close() }
                                    client = null
                                }
                                FileOutcome.AUTH_FAILED -> {
                                    authFailed.set(true)
                                    stop.compareAndSet(null, StopReason.AUTH_FAILURE)
                                }
                                FileOutcome.STORAGE_FULL -> {
                                    storageFull.set(true)
                                    stop.compareAndSet(null, StopReason.STORAGE_FULL)
                                }
                                FileOutcome.CANCELLED -> stop.compareAndSet(null, StopReason.USER_CANCELLED)
                            }
                        }
                    } finally {
                        runCatching { client?.close() }
                    }
                }
            }
            lanes.joinAll()
            downloadsDone.set(true)
            importJob.join()
        }

        runtime.clearServer(serverId)
        runCatching { onUpdate() }

        val reason = stop.get()
            ?: if (isStopped()) StopReason.USER_CANCELLED
            else if (storageFull.get()) StopReason.STORAGE_FULL
            else StopReason.QUEUE_EMPTY
        return TransferRunResult(completed.get(), failed.get(), reason)
    }

    private suspend fun downloadOne(
        client: RemoteFileClient,
        source: FtpSource,
        task: DownloadTaskEntity,
        policy: TransferPolicy,
        isStopped: () -> Boolean
    ): FileOutcome {
        val finalFile = task.localPath?.let { File(it) }
            ?: run {
                transferRepository.fail(task.id, "Missing local path", "internal")
                return FileOutcome.FAILED
            }
        val staging = task.stagingPath?.let { File(it) } ?: LocalStaging.stagingFor(finalFile)
        if (task.stagingPath.isNullOrBlank()) {
            transferRepository.setStagingPath(task.id, staging.absolutePath)
        }

        val expectedSize = task.sizeBytes
        val stagingDir = staging.parentFile ?: finalFile.parentFile ?: return FileOutcome.FAILED

        // Already fully present (e.g. reset row or previous completed run).
        if (finalFile.exists() && (expectedSize <= 0L || finalFile.length() == expectedSize)) {
            return handOffToImport(task)
        }
        if (finalFile.exists() && expectedSize > 0L && finalFile.length() != expectedSize) {
            finalFile.delete()
        }

        if (!LocalStaging.hasSpaceFor(stagingDir, expectedSize)) {
            transferRepository.fail(task.id, "Storage is full", "storage")
            return FileOutcome.STORAGE_FULL
        }

        val offset = LocalStaging.resumeOffset(staging, expectedSize)
        val throttler = ProgressThrottler()
        val startedAt = clock()
        val startBytes = offset
        var latestBytes = offset

        if (offset > 0L) {
            transferRepository.updateProgress(task.id, offset, 0L)
        }

        try {
            client.download(
                remotePath = task.remotePath,
                target = staging,
                offset = offset,
                expectedSize = expectedSize,
                bufferSize = policy.bufferSizeBytes
            ) { written ->
                latestBytes = written
                val now = clock()
                val elapsed = (now - startedAt).coerceAtLeast(1L)
                val speed = ((written - startBytes).coerceAtLeast(0L) * 1000L) / elapsed
                runtime.update(
                    ActiveTransfer(
                        taskId = task.id,
                        serverId = source.id,
                        name = task.remoteName,
                        downloadedBytes = written,
                        totalBytes = if (expectedSize > 0) expectedSize else written,
                        bytesPerSec = speed
                    )
                )
                if (throttler.shouldPersist(written, now)) {
                    transferRepository.updateProgress(task.id, written, speed)
                    runCatching { onUpdate() }
                }
                if (isStopped()) throw kotlinx.coroutines.CancellationException("stopped")
            }

            // Exact flush before verifying.
            throttler.markPersisted(latestBytes)
            transferRepository.updateProgress(task.id, latestBytes, 0L)

            val stagedLength = if (staging.exists()) staging.length() else 0L
            if (expectedSize > 0L && stagedLength != expectedSize) {
                // Short read. Keep the .part for a real resume on the next attempt.
                scheduleRetry(task, "Transfer stopped early", "verify", backoffMs(task.retryCount))
                return FileOutcome.NEEDS_RECONNECT
            }

            transferRepository.transition(task.id, DownloadStatusEntity.VERIFYING)
            if (staging.exists() && !LocalStaging.moveIntoPlace(staging, finalFile)) {
                transferRepository.fail(task.id, "Could not finalize the file", "verify")
                return FileOutcome.FAILED
            }
            if (!finalFile.exists() || finalFile.length() <= 0L) {
                transferRepository.fail(task.id, "Downloaded file is empty", "verify")
                return FileOutcome.FAILED
            }
            return handOffToImport(task)
        } catch (e: kotlinx.coroutines.CancellationException) {
            flushProgress(task.id, latestBytes)
            runtime.remove(task.id)
            throw e
        } catch (e: FtpException) {
            flushProgress(task.id, latestBytes)
            runtime.remove(task.id)
            return handleFailure(task, e)
        } catch (e: Throwable) {
            flushProgress(task.id, latestBytes)
            runtime.remove(task.id)
            if (isStorageFull(e)) {
                transferRepository.fail(task.id, "Storage is full", "storage")
                return FileOutcome.STORAGE_FULL
            }
            return handleFailure(
                task,
                FtpException(FtpErrorKind.UNKNOWN, e.message ?: "Transfer failed", e)
            )
        }
    }

    /** Persist the exact last byte count even when the scope is being cancelled. */
    private suspend fun flushProgress(taskId: Long, bytes: Long) {
        withContext(NonCancellable) {
            runCatching { transferRepository.updateProgress(taskId, bytes, 0L) }
        }
    }

    private suspend fun handOffToImport(task: DownloadTaskEntity): FileOutcome {
        transferRepository.transition(task.id, DownloadStatusEntity.VERIFYING)
        transferRepository.transition(task.id, DownloadStatusEntity.IMPORTING)
        runtime.remove(task.id)
        return FileOutcome.COMPLETED
    }

    /**
     * Single importer coroutine. It batches all IMPORTING rows of a server by
     * parent folder so one audiobook folder becomes one library call instead of
     * one per track. Download lanes are never blocked by this.
     */
    private suspend fun importLoop(
        serverId: Long,
        source: FtpSource,
        downloadsDone: AtomicBoolean,
        isStopped: () -> Boolean
    ) {
        while (!isStopped()) {
            val pending = transferRepository.importing(serverId)
            if (pending.isNotEmpty()) {
                importPending(source, pending)
            } else if (downloadsDone.get()) {
                break
            } else {
                delay(IMPORT_POLL_MS)
            }
        }
        val remaining = transferRepository.importing(serverId)
        if (remaining.isNotEmpty()) importPending(source, remaining)
    }

    private suspend fun importPending(source: FtpSource, tasks: List<DownloadTaskEntity>) {
        val groups = tasks
            .filter { it.localPath != null }
            .groupBy { File(it.localPath!!).parentFile?.absolutePath ?: it.localPath!! }

        for ((_, group) in groups) {
            if (!group.first().autoImport) {
                group.forEach { runCatching { transferRepository.markCompleted(it.id, null) } }
                continue
            }
            val files = group.mapNotNull { it.localPath }.map { File(it) }.filter { it.exists() }
            if (files.isEmpty()) {
                group.forEach { runCatching { transferRepository.fail(it.id, "File missing before import", "import") } }
                continue
            }
            val alreadyImported = importer.findImportedBookId(files.first().absolutePath)
            val bookId = alreadyImported ?: runCatching {
                importer.importBatch(files, source.id, group.first().remotePath)
            }.getOrNull()
            if (bookId == null) {
                group.forEach { runCatching { transferRepository.fail(it.id, "Import failed", "import") } }
            } else {
                group.forEach { runCatching { transferRepository.markCompleted(it.id, bookId) } }
            }
        }
        runCatching { onUpdate() }
    }

    private suspend fun handleFailure(task: DownloadTaskEntity, e: FtpException): FileOutcome {
        val kind = e.kind
        if (kind == FtpErrorKind.AUTH) {
            transferRepository.fail(task.id, "Sign-in failed", "auth")
            return FileOutcome.AUTH_FAILED
        }
        if (kind == FtpErrorKind.STORAGE || isStorageFull(e)) {
            transferRepository.fail(task.id, "Storage is full", "storage")
            return FileOutcome.STORAGE_FULL
        }
        if (kind == FtpErrorKind.NOT_FOUND) {
            transferRepository.fail(task.id, "File no longer exists on the server", "not_found")
            return FileOutcome.FAILED
        }
        scheduleRetry(task, e.message ?: "Transfer failed", kind.name.lowercase(), backoffMs(task.retryCount))
        return if (kind == FtpErrorKind.NETWORK) FileOutcome.NEEDS_RECONNECT else FileOutcome.RETRY_SCHEDULED
    }

    private suspend fun scheduleRetry(
        task: DownloadTaskEntity,
        error: String,
        kind: String,
        backoffMs: Long
    ) {
        if (task.retryCount >= maxRetries) {
            transferRepository.fail(task.id, error, kind)
            return
        }
        transferRepository.markRetrying(task.id, error, kind, clock() + backoffMs)
    }

    private fun backoffMs(retryCount: Int): Long =
        min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl retryCount.coerceIn(0, 6)))

    private fun isStorageFull(t: Throwable): Boolean {
        val message = (t.message ?: "").lowercase()
        return message.contains("enospc") ||
            message.contains("no space") ||
            message.contains("storage is full") ||
            message.contains("disk full")
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 3
        private const val MAX_CONNECTION_FAILURES = 3
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val IMPORT_POLL_MS = 750L
    }
}