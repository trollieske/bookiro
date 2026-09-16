package com.shelf.reader.ftp.fakes

import com.shelf.reader.data.local.dao.DownloadTaskDao
import com.shelf.reader.data.local.dao.FtpServerDao
import com.shelf.reader.data.local.entity.DownloadStatusEntity
import com.shelf.reader.data.local.entity.DownloadTaskEntity
import com.shelf.reader.data.local.entity.FtpServerEntity
import com.shelf.reader.data.local.entity.FtpSourceStateEntity
import com.shelf.reader.data.local.entity.TransferCounts
import com.shelf.reader.ftp.data.FtpCredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Base64

class FakeCredentialCipher : FtpCredentialCipher {
    override fun encrypt(plaintext: String): String =
        "enc:" + Base64.getEncoder().encodeToString(plaintext.toByteArray())

    override fun decrypt(ciphertext: String?): String? {
        if (ciphertext == null) return null
        if (ciphertext == "BROKEN") return null
        return runCatching {
            String(Base64.getDecoder().decode(ciphertext.removePrefix("enc:")))
        }.getOrNull()
    }
}

class FakeFtpServerDao : FtpServerDao {
    val rows = LinkedHashMap<Long, FtpServerEntity>()
    private val flow = MutableStateFlow<List<FtpServerEntity>>(emptyList())
    private var nextId = 1L

    private fun publish() {
        flow.value = rows.values.sortedBy { it.displayName }
    }

    override suspend fun insert(server: FtpServerEntity): Long {
        val id = if (server.id != 0L) server.id else nextId++
        rows[id] = server.copy(id = id)
        publish()
        return id
    }

    override suspend fun update(server: FtpServerEntity) {
        rows[server.id] = server
        publish()
    }

    override suspend fun delete(server: FtpServerEntity) {
        rows.remove(server.id)
        publish()
    }

    override fun observeAll(): Flow<List<FtpServerEntity>> = flow.map { list -> list.filter { it.isActive } }

    override suspend fun getAll(): List<FtpServerEntity> = rows.values.filter { it.isActive }

    override fun observeById(id: Long): Flow<FtpServerEntity?> = flow.map { it.firstOrNull { s -> s.id == id } }

    override suspend fun getById(id: Long): FtpServerEntity? = rows[id]

    override suspend fun getSyncEnabledServers(): List<FtpServerEntity> =
        rows.values.filter { it.syncEnabled && it.isActive }

    override suspend fun findByNaturalKey(
        host: String,
        port: Int,
        username: String,
        protocol: com.shelf.reader.data.local.entity.ProtocolEntity
    ): FtpServerEntity? = rows.values.firstOrNull {
        it.host == host && it.port == port && it.username == username && it.protocol == protocol
    }

    override suspend fun markSynced(id: Long, now: Long) {}
    override suspend fun updateState(id: Long, state: FtpSourceStateEntity, error: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(state = state, lastError = error) }
        publish()
    }

    override suspend fun markLastSync(id: Long, now: Long) {}
    override suspend fun updateBasePath(id: Long, path: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(basePath = path) }
        publish()
    }

    override suspend fun updateSyncPolicy(id: Long, wifiOnly: Boolean, chargingOnly: Boolean, concurrency: Int, now: Long) {}
    override suspend fun setSyncEnabled(id: Long, enabled: Boolean, now: Long) {}
    override suspend fun deleteById(id: Long) {
        rows.remove(id)
        publish()
    }
}

class FakeDownloadTaskDao : DownloadTaskDao {
    val rows = LinkedHashMap<Long, DownloadTaskEntity>()
    private val flow = MutableStateFlow<List<DownloadTaskEntity>>(emptyList())
    private var nextId = 1L

    private fun publish() {
        flow.value = rows.values.sortedByDescending { it.createdAt }
    }

    override suspend fun insert(task: DownloadTaskEntity): Long {
        val id = if (task.id != 0L) task.id else nextId++
        rows[id] = task.copy(id = id)
        publish()
        return id
    }

    override suspend fun insertIgnore(task: DownloadTaskEntity): Long {
        if (task.serverId != null && rows.values.any { it.serverId == task.serverId && it.remotePath == task.remotePath }) {
            return -1L
        }
        return insert(task)
    }

    override suspend fun update(task: DownloadTaskEntity) {
        rows[task.id] = task
        publish()
    }

    override suspend fun delete(task: DownloadTaskEntity) {
        rows.remove(task.id)
        publish()
    }

    override fun observeAll(): Flow<List<DownloadTaskEntity>> = flow

    override fun observeActive(): Flow<List<DownloadTaskEntity>> =
        flow.map { list -> list.filter { it.status.isActive || it.status.isRunnable } }

    override suspend fun getById(id: Long): DownloadTaskEntity? = rows[id]

    override fun observeForServer(serverId: Long): Flow<List<DownloadTaskEntity>> =
        flow.map { list -> list.filter { it.serverId == serverId } }

    override suspend fun getByRemote(serverId: Long, remotePath: String): DownloadTaskEntity? =
        rows.values.firstOrNull { it.serverId == serverId && it.remotePath == remotePath }

    override suspend fun importingForServer(serverId: Long): List<DownloadTaskEntity> =
        rows.values.filter { it.serverId == serverId && it.status == DownloadStatusEntity.IMPORTING }

    override suspend fun getNextPending(): DownloadTaskEntity? =
        rows.values.filter { it.status == DownloadStatusEntity.PENDING }.minByOrNull { it.createdAt }

    override suspend fun getRunning(): DownloadTaskEntity? =
        rows.values.firstOrNull { it.status == DownloadStatusEntity.RUNNING }

    override suspend fun nextRunnable(serverId: Long, now: Long): DownloadTaskEntity? =
        rows.values
            .filter { it.serverId == serverId }
            .filter {
                it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING ||
                    (it.status == DownloadStatusEntity.RETRYING && (it.nextAttemptAt == null || it.nextAttemptAt!! <= now))
            }
            .minWithOrNull(compareByDescending<DownloadTaskEntity> { it.priority }.thenBy { it.createdAt })

    override suspend fun claim(id: Long, now: Long): Int {
        val task = rows[id] ?: return 0
        return if (task.status == DownloadStatusEntity.QUEUED || task.status == DownloadStatusEntity.PENDING ||
            task.status == DownloadStatusEntity.RETRYING
        ) {
            rows[id] = task.copy(status = DownloadStatusEntity.RUNNING, startedAt = now)
            publish()
            1
        } else 0
    }

    override suspend fun updateProgress(id: Long, bytes: Long, speed: Long, now: Long) {
        rows[id]?.let { rows[id] = it.copy(downloadedBytes = bytes, bytesPerSec = speed, lastProgressAt = now) }
        publish()
    }

    override suspend fun setStatus(id: Long, status: DownloadStatusEntity, error: String?, errorKind: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(status = status, errorMessage = error, errorKind = errorKind) }
        publish()
    }

    override suspend fun markRetrying(id: Long, error: String?, errorKind: String?, nextAttemptAt: Long?, now: Long) {
        rows[id]?.let {
            rows[id] = it.copy(
                status = DownloadStatusEntity.RETRYING,
                retryCount = it.retryCount + 1,
                errorMessage = error,
                errorKind = errorKind,
                nextAttemptAt = nextAttemptAt
            )
        }
        publish()
    }

    override suspend fun markCompleted(id: Long, bookId: Long?, now: Long) {
        rows[id]?.let {
            rows[id] = it.copy(
                status = DownloadStatusEntity.COMPLETED,
                downloadedBytes = it.sizeBytes,
                importedBookId = bookId,
                completedAt = now,
                errorMessage = null,
                errorKind = null
            )
        }
        publish()
    }

    override suspend fun requeue(id: Long, now: Long) {
        rows[id]?.let {
            rows[id] = it.copy(
                status = DownloadStatusEntity.QUEUED,
                downloadedBytes = 0,
                bytesPerSec = 0,
                retryCount = 0,
                errorMessage = null,
                errorKind = null,
                startedAt = null,
                completedAt = null,
                nextAttemptAt = null
            )
        }
        publish()
    }

    override suspend fun updateRemoteMeta(id: Long, size: Long, mtime: Long, localPath: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(sizeBytes = size, remoteMtime = mtime, localPath = localPath) }
        publish()
    }

    override suspend fun setStagingPath(id: Long, staging: String?, now: Long) {
        rows[id]?.let { rows[id] = it.copy(stagingPath = staging) }
        publish()
    }

    override suspend fun rehydrateActiveForServer(serverId: Long, now: Long): Int {
        var count = 0
        rows.values.filter { it.serverId == serverId }.forEach { task ->
            if (task.status == DownloadStatusEntity.RUNNING || task.status == DownloadStatusEntity.VERIFYING ||
                task.status == DownloadStatusEntity.IMPORTING
            ) {
                rows[task.id] = task.copy(status = DownloadStatusEntity.QUEUED)
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    override suspend fun rehydrateAllActive(now: Long): Int {
        var count = 0
        rows.values.toList().forEach { task ->
            if (task.status == DownloadStatusEntity.RUNNING || task.status == DownloadStatusEntity.VERIFYING ||
                task.status == DownloadStatusEntity.IMPORTING
            ) {
                rows[task.id] = task.copy(status = DownloadStatusEntity.QUEUED)
                count++
            }
        }
        if (count > 0) publish()
        return count
    }

    override suspend fun setPausedForServer(serverId: Long, paused: Boolean, now: Long) {
        rows.values.filter { it.serverId == serverId }.forEach { task ->
            if (task.status in setOf(
                    DownloadStatusEntity.QUEUED, DownloadStatusEntity.PENDING, DownloadStatusEntity.RUNNING,
                    DownloadStatusEntity.RETRYING, DownloadStatusEntity.WAITING_FOR_NETWORK,
                    DownloadStatusEntity.PAUSED, DownloadStatusEntity.PAUSED_BY_USER
                )
            ) {
                rows[task.id] = task.copy(
                    status = if (paused) DownloadStatusEntity.PAUSED_BY_USER else DownloadStatusEntity.QUEUED
                )
            }
        }
        publish()
    }

    override suspend fun cancelForServer(serverId: Long, now: Long) {
        rows.values.filter { it.serverId == serverId }.forEach { task ->
            if (task.status != DownloadStatusEntity.COMPLETED &&
                task.status != DownloadStatusEntity.CANCELLED &&
                task.status != DownloadStatusEntity.FAILED
            ) {
                rows[task.id] = task.copy(status = DownloadStatusEntity.CANCELLED)
            }
        }
        publish()
    }

    override suspend fun cancel(id: Long) {
        rows[id]?.let { rows[id] = it.copy(status = DownloadStatusEntity.CANCELLED) }
        publish()
    }

    override suspend fun deleteForServer(serverId: Long) {
        rows.entries.removeIf { it.value.serverId == serverId }
        publish()
    }

    override suspend fun clearCompleted(): Int {
        val toRemove = rows.values.filter { it.status == DownloadStatusEntity.COMPLETED }
        toRemove.forEach { rows.remove(it.id) }
        if (toRemove.isNotEmpty()) publish()
        return toRemove.size
    }

    override fun observeActiveCount(): Flow<Int> =
        flow.map { list -> list.count { it.status.isActive || it.status.isRunnable } }

    override suspend fun queuedCountForServer(serverId: Long): Int =
        rows.values.count {
            it.serverId == serverId &&
                (it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING ||
                    it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK)
        }

    override suspend fun failedCountForServer(serverId: Long): Int =
        rows.values.count { it.serverId == serverId && it.status == DownloadStatusEntity.FAILED }

    override suspend fun countsForServer(serverId: Long): TransferCounts {
        val tasks = rows.values.filter { it.serverId == serverId }
        return TransferCounts(
            total = tasks.size,
            queued = tasks.count { it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING },
            running = tasks.count { it.status.isActive },
            completed = tasks.count { it.status == DownloadStatusEntity.COMPLETED },
            failed = tasks.count { it.status == DownloadStatusEntity.FAILED },
            paused = tasks.count { it.status == DownloadStatusEntity.PAUSED || it.status == DownloadStatusEntity.PAUSED_BY_USER },
            retrying = tasks.count { it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK }
        )
    }

    override suspend fun activeServerIds(): List<Long> =
        rows.values.filter { it.status.isActive }.mapNotNull { it.serverId }.distinct()

    override suspend fun runnableServerIds(): List<Long> =
        rows.values
            .filter {
                it.serverId != null && (
                    it.status.isRunnable || it.status.isActive ||
                        it.status == DownloadStatusEntity.RETRYING ||
                        it.status == DownloadStatusEntity.WAITING_FOR_NETWORK
                    )
            }
            .mapNotNull { it.serverId }
            .distinct()

    override fun observeForSource(kind: String, ref: String): Flow<List<DownloadTaskEntity>> =
        flow.map { list -> list.filter { it.sourceKind == kind && it.sourceRef == ref } }

    override suspend fun getBySourceRemote(kind: String, ref: String, remotePath: String): DownloadTaskEntity? =
        rows.values.firstOrNull { it.sourceKind == kind && it.sourceRef == ref && it.remotePath == remotePath }

    override suspend fun nextRunnableForSource(kind: String, ref: String, now: Long): DownloadTaskEntity? =
        rows.values
            .filter { it.sourceKind == kind && it.sourceRef == ref }
            .filter {
                it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING ||
                    (it.status == DownloadStatusEntity.RETRYING && (it.nextAttemptAt == null || it.nextAttemptAt!! <= now))
            }
            .minWithOrNull(compareByDescending<DownloadTaskEntity> { it.priority }.thenBy { it.createdAt })

    override suspend fun importingForSource(kind: String, ref: String): List<DownloadTaskEntity> =
        rows.values.filter { it.sourceKind == kind && it.sourceRef == ref && it.status == DownloadStatusEntity.IMPORTING }

    override suspend fun runnableCountForSource(kind: String, ref: String): Int =
        rows.values.count {
            it.sourceKind == kind && it.sourceRef == ref && (
                it.status.isRunnable || it.status.isActive ||
                    it.status == DownloadStatusEntity.RETRYING ||
                    it.status == DownloadStatusEntity.WAITING_FOR_NETWORK
                )
        }

    override suspend fun runnableRefsForKind(kind: String): List<String> =
        rows.values
            .filter { it.sourceKind == kind }
            .filter {
                it.status.isRunnable || it.status.isActive ||
                    it.status == DownloadStatusEntity.RETRYING ||
                    it.status == DownloadStatusEntity.WAITING_FOR_NETWORK
            }
            .mapNotNull { it.sourceRef }
            .distinct()

    override suspend fun setSource(id: Long, kind: String, ref: String, now: Long) {
        rows[id]?.let { rows[id] = it.copy(sourceKind = kind, sourceRef = ref) }
        publish()
    }

    override suspend fun cancelForSource(kind: String, ref: String, now: Long) {
        rows.values.filter { it.sourceKind == kind && it.sourceRef == ref }.forEach { task ->
            if (task.status != DownloadStatusEntity.COMPLETED &&
                task.status != DownloadStatusEntity.CANCELLED &&
                task.status != DownloadStatusEntity.FAILED
            ) {
                rows[task.id] = task.copy(status = DownloadStatusEntity.CANCELLED)
            }
        }
        publish()
    }

    override suspend fun deleteForSource(kind: String, ref: String) {
        rows.entries.removeIf { it.value.sourceKind == kind && it.value.sourceRef == ref }
        publish()
    }

    override suspend fun rehydrateActiveForSource(kind: String, ref: String, now: Long): Int {
        var count = 0
        rows.values
            .filter { it.sourceKind == kind && it.sourceRef == ref }
            .forEach { task ->
                if (task.status == DownloadStatusEntity.RUNNING || task.status == DownloadStatusEntity.VERIFYING ||
                    task.status == DownloadStatusEntity.IMPORTING
                ) {
                    rows[task.id] = task.copy(status = DownloadStatusEntity.QUEUED)
                    count++
                }
            }
        if (count > 0) publish()
        return count
    }

    override suspend fun setPausedForSource(kind: String, ref: String, paused: Boolean, now: Long) {
        rows.values.filter { it.sourceKind == kind && it.sourceRef == ref }.forEach { task ->
            if (task.status in setOf(
                    DownloadStatusEntity.QUEUED, DownloadStatusEntity.PENDING, DownloadStatusEntity.RUNNING,
                    DownloadStatusEntity.RETRYING, DownloadStatusEntity.WAITING_FOR_NETWORK,
                    DownloadStatusEntity.PAUSED, DownloadStatusEntity.PAUSED_BY_USER
                )
            ) {
                rows[task.id] = task.copy(
                    status = if (paused) DownloadStatusEntity.PAUSED_BY_USER else DownloadStatusEntity.QUEUED
                )
            }
        }
        publish()
    }

    override suspend fun countsForSource(kind: String, ref: String): TransferCounts {
        val tasks = rows.values.filter { it.sourceKind == kind && it.sourceRef == ref }
        return TransferCounts(
            total = tasks.size,
            queued = tasks.count { it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING },
            running = tasks.count { it.status.isActive },
            completed = tasks.count { it.status == DownloadStatusEntity.COMPLETED },
            failed = tasks.count { it.status == DownloadStatusEntity.FAILED },
            paused = tasks.count { it.status == DownloadStatusEntity.PAUSED || it.status == DownloadStatusEntity.PAUSED_BY_USER },
            retrying = tasks.count { it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK }
        )
    }

    override fun observeCountsForKind(kind: String): Flow<List<com.shelf.reader.data.transfer.SourceCounts>> =
        flow.map { list ->
            list.filter { it.sourceKind == kind && it.sourceRef != null }
                .groupBy { it.sourceRef!! }
                .map { (ref, tasks) ->
                    com.shelf.reader.data.transfer.SourceCounts(
                        sourceRef = ref,
                        total = tasks.size,
                        queued = tasks.count { it.status == DownloadStatusEntity.QUEUED || it.status == DownloadStatusEntity.PENDING },
                        running = tasks.count { it.status.isActive },
                        completed = tasks.count { it.status == DownloadStatusEntity.COMPLETED },
                        failed = tasks.count { it.status == DownloadStatusEntity.FAILED },
                        paused = tasks.count { it.status == DownloadStatusEntity.PAUSED || it.status == DownloadStatusEntity.PAUSED_BY_USER },
                        retrying = tasks.count { it.status == DownloadStatusEntity.RETRYING || it.status == DownloadStatusEntity.WAITING_FOR_NETWORK }
                    )
                }
        }
}