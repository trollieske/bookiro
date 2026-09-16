package com.shelf.reader.data.local.dao

import androidx.room.*
import com.shelf.reader.data.local.entity.*
import kotlinx.coroutines.flow.Flow

/**
 * Authoritative persistence for stored FTP/FTPS/SFTP sources.
 *
 * The source row is durable data; an active connection is a short-lived I/O
 * resource that must never be owned by a Compose screen or ViewModel.
 */
@Dao
interface FtpServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: FtpServerEntity): Long

    @Update
    suspend fun update(server: FtpServerEntity)

    @Delete
    suspend fun delete(server: FtpServerEntity)

    @Query("SELECT * FROM ftp_servers WHERE is_active = 1 ORDER BY position, display_name")
    fun observeAll(): Flow<List<FtpServerEntity>>

    @Query("SELECT * FROM ftp_servers WHERE is_active = 1 ORDER BY position, display_name")
    suspend fun getAll(): List<FtpServerEntity>

    @Query("SELECT * FROM ftp_servers WHERE id = :id")
    fun observeById(id: Long): Flow<FtpServerEntity?>

    @Query("SELECT * FROM ftp_servers WHERE id = :id")
    suspend fun getById(id: Long): FtpServerEntity?

    @Query("SELECT * FROM ftp_servers WHERE sync_enabled = 1 AND is_active = 1")
    suspend fun getSyncEnabledServers(): List<FtpServerEntity>

    /**
     * Natural-key lookup used both for migration idempotency and for "the same
     * server must not be stored twice".
     */
    @Query(
        "SELECT * FROM ftp_servers WHERE host = :host AND port = :port " +
            "AND username = :username AND protocol = :protocol LIMIT 1"
    )
    suspend fun findByNaturalKey(
        host: String,
        port: Int,
        username: String,
        protocol: ProtocolEntity
    ): FtpServerEntity?

    @Query("UPDATE ftp_servers SET sync_last_check_at = :now WHERE id = :id")
    suspend fun markSynced(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE ftp_servers SET state = :state, last_error = :error, updated_at = :now WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: FtpSourceStateEntity,
        error: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE ftp_servers SET last_sync_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markLastSync(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE ftp_servers SET base_path = :path, updated_at = :now WHERE id = :id")
    suspend fun updateBasePath(id: Long, path: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE ftp_servers SET sync_wifi_only = :wifiOnly, charging_only = :chargingOnly, " +
        "concurrency_override = :concurrency, updated_at = :now WHERE id = :id")
    suspend fun updateSyncPolicy(
        id: Long,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        concurrency: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE ftp_servers SET sync_enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM ftp_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * Authoritative persistence for transfer queue, bytes, speed, errors and status.
 *
 * Status is never a boolean. See [DownloadStatusEntity] and the transfer state
 * machine for the valid transitions.
 */
@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    /**
     * Insert that refuses to create a second row for the same
     * `(server_id, remote_path)`. Returns `-1` when the row already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Delete
    suspend fun delete(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY status IN ('RUNNING','PENDING','QUEUED') DESC, priority DESC, created_at DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query(
        "SELECT * FROM download_tasks WHERE status IN ('QUEUED','PENDING','RUNNING','PAUSED','PAUSED_BY_USER','RETRYING','WAITING_FOR_NETWORK','VERIFYING','IMPORTING') " +
            "ORDER BY priority DESC, created_at DESC"
    )
    fun observeActive(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE server_id = :serverId ORDER BY priority DESC, created_at ASC")
    fun observeForServer(serverId: Long): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE server_id = :serverId AND remote_path = :remotePath LIMIT 1")
    suspend fun getByRemote(serverId: Long, remotePath: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE server_id = :serverId AND status = 'IMPORTING' ORDER BY created_at ASC")
    suspend fun importingForServer(serverId: Long): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC LIMIT 1")
    suspend fun getNextPending(): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'RUNNING' LIMIT 1")
    suspend fun getRunning(): DownloadTaskEntity?

    /**
     * Next runnable item for a server. `RETRYING` is included only when its
     * backoff deadline has passed.
     */
    @Query(
        "SELECT * FROM download_tasks WHERE server_id = :serverId AND (" +
            "status IN ('QUEUED','PENDING') OR (status = 'RETRYING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))" +
            ") ORDER BY priority DESC, created_at ASC LIMIT 1"
    )
    suspend fun nextRunnable(serverId: Long, now: Long = System.currentTimeMillis()): DownloadTaskEntity?

    /** Atomically claim a queued task. Returns 1 when this caller won the race. */
    @Query(
        "UPDATE download_tasks SET status = 'RUNNING', started_at = :now, updated_at = :now " +
            "WHERE id = :id AND status IN ('QUEUED','PENDING','RETRYING')"
    )
    suspend fun claim(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE download_tasks SET downloaded_bytes = :bytes, bytes_per_sec = :speed, " +
        "last_progress_at = :now, updated_at = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, speed: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = :status, error_message = :error, error_kind = :errorKind, " +
        "updated_at = :now WHERE id = :id")
    suspend fun setStatus(
        id: Long,
        status: DownloadStatusEntity,
        error: String? = null,
        errorKind: String? = null,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE download_tasks SET status = 'RETRYING', retry_count = retry_count + 1, " +
            "error_message = :error, error_kind = :errorKind, next_attempt_at = :nextAttemptAt, " +
            "updated_at = :now WHERE id = :id"
    )
    suspend fun markRetrying(
        id: Long,
        error: String?,
        errorKind: String?,
        nextAttemptAt: Long?,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE download_tasks SET status = 'COMPLETED', downloaded_bytes = size_bytes, " +
            "bytes_per_sec = 0, error_message = NULL, error_kind = NULL, completed_at = :now, " +
            "updated_at = :now, imported_book_id = :bookId WHERE id = :id"
    )
    suspend fun markCompleted(id: Long, bookId: Long?, now: Long = System.currentTimeMillis())

    /** Reset a terminal/failed row so it can be downloaded again. */
    @Query(
        "UPDATE download_tasks SET status = 'QUEUED', downloaded_bytes = 0, bytes_per_sec = 0, " +
            "retry_count = 0, error_message = NULL, error_kind = NULL, started_at = NULL, " +
            "completed_at = NULL, next_attempt_at = NULL, updated_at = :now WHERE id = :id"
    )
    suspend fun requeue(id: Long, now: Long = System.currentTimeMillis())

    /** Keep an existing row's remote metadata fresh without losing progress. */
    @Query("UPDATE download_tasks SET size_bytes = :size, remote_mtime = :mtime, " +
        "local_path = :localPath, updated_at = :now WHERE id = :id")
    suspend fun updateRemoteMeta(
        id: Long,
        size: Long,
        mtime: Long,
        localPath: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE download_tasks SET staging_path = :staging, updated_at = :now WHERE id = :id")
    suspend fun setStagingPath(id: Long, staging: String?, now: Long = System.currentTimeMillis())

    /**
     * Process-death recovery: any task that claims to be running but has no
     * live work is re-queued (resume is validated later from the `.part` file).
     */
    @Query(
        "UPDATE download_tasks SET status = 'QUEUED', error_message = NULL, updated_at = :now " +
            "WHERE server_id = :serverId AND status IN ('RUNNING','VERIFYING','IMPORTING')"
    )
    suspend fun rehydrateActiveForServer(serverId: Long, now: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE download_tasks SET status = 'QUEUED', error_message = NULL, error_kind = NULL, " +
            "next_attempt_at = NULL, updated_at = :now " +
            "WHERE status IN ('RUNNING','VERIFYING','IMPORTING')"
    )
    suspend fun rehydrateAllActive(now: Long = System.currentTimeMillis()): Int

    @Query(
        "UPDATE download_tasks SET status = CASE WHEN :paused = 1 THEN 'PAUSED_BY_USER' ELSE 'QUEUED' END, updated_at = :now " +
            "WHERE server_id = :serverId AND status IN ('QUEUED','PENDING','RUNNING','VERIFYING','IMPORTING','RETRYING','WAITING_FOR_NETWORK','PAUSED','PAUSED_BY_USER')"
    )
    suspend fun setPausedForServer(serverId: Long, paused: Boolean, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE download_tasks SET status = 'CANCELLED', updated_at = :now " +
            "WHERE server_id = :serverId AND status NOT IN ('COMPLETED','CANCELLED','FAILED')"
    )
    suspend fun cancelForServer(serverId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)

    @Query("DELETE FROM download_tasks WHERE server_id = :serverId")
    suspend fun deleteForServer(serverId: Long)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED' AND completed_at IS NOT NULL")
    suspend fun clearCompleted(): Int

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('RUNNING','PENDING','QUEUED')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_tasks WHERE server_id = :serverId AND status IN ('QUEUED','PENDING','RETRYING','WAITING_FOR_NETWORK')")
    suspend fun queuedCountForServer(serverId: Long): Int

    @Query("SELECT COUNT(*) FROM download_tasks WHERE server_id = :serverId AND status = 'FAILED'")
    suspend fun failedCountForServer(serverId: Long): Int

    @Query(
        "SELECT COUNT(*) AS total, " +
            "IFNULL(SUM(CASE WHEN status IN ('QUEUED','PENDING') THEN 1 ELSE 0 END), 0) AS queued, " +
            "IFNULL(SUM(CASE WHEN status IN ('RUNNING','VERIFYING','IMPORTING') THEN 1 ELSE 0 END), 0) AS running, " +
            "IFNULL(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completed, " +
            "IFNULL(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed, " +
            "IFNULL(SUM(CASE WHEN status IN ('PAUSED','PAUSED_BY_USER') THEN 1 ELSE 0 END), 0) AS paused, " +
            "IFNULL(SUM(CASE WHEN status IN ('RETRYING','WAITING_FOR_NETWORK') THEN 1 ELSE 0 END), 0) AS retrying " +
            "FROM download_tasks WHERE server_id = :serverId"
    )
    suspend fun countsForServer(serverId: Long): TransferCounts
    @Query("SELECT DISTINCT server_id FROM download_tasks WHERE server_id IS NOT NULL AND status IN ('RUNNING','VERIFYING','IMPORTING')")
    suspend fun activeServerIds(): List<Long>

    /** Servers with work that should be running (queued, retrying or mid-flight). */
    @Query(
        "SELECT DISTINCT server_id FROM download_tasks WHERE server_id IS NOT NULL AND status IN " +
            "('QUEUED','PENDING','RETRYING','WAITING_FOR_NETWORK','RUNNING','VERIFYING','IMPORTING')"
    )
    suspend fun runnableServerIds(): List<Long>

    // ---- Generic, kind/ref-keyed queries for the shared queue (SMB/WebDAV/Calibre) ----

    @Query("SELECT * FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref ORDER BY priority DESC, created_at DESC")
    fun observeForSource(kind: String, ref: String): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref AND remote_path = :remotePath LIMIT 1")
    suspend fun getBySourceRemote(kind: String, ref: String, remotePath: String): DownloadTaskEntity?

    @Query(
        "SELECT * FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref AND (" +
            "status IN ('QUEUED','PENDING') OR (status = 'RETRYING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))" +
            ") ORDER BY priority DESC, created_at ASC LIMIT 1"
    )
    suspend fun nextRunnableForSource(kind: String, ref: String, now: Long = System.currentTimeMillis()): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref AND status = 'IMPORTING' ORDER BY created_at ASC")
    suspend fun importingForSource(kind: String, ref: String): List<DownloadTaskEntity>

    @Query(
        "SELECT COUNT(*) FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref AND status IN " +
            "('QUEUED','PENDING','RETRYING','WAITING_FOR_NETWORK','RUNNING','VERIFYING','IMPORTING')"
    )
    suspend fun runnableCountForSource(kind: String, ref: String): Int

    @Query("SELECT DISTINCT source_ref FROM download_tasks WHERE source_kind = :kind AND status IN ('QUEUED','PENDING','RETRYING','WAITING_FOR_NETWORK','RUNNING','VERIFYING','IMPORTING')")
    suspend fun runnableRefsForKind(kind: String): List<String>

    @Query("UPDATE download_tasks SET source_kind = :kind, source_ref = :ref, updated_at = :now WHERE id = :id")
    suspend fun setSource(id: Long, kind: String, ref: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE download_tasks SET status = 'CANCELLED', updated_at = :now WHERE source_kind = :kind AND source_ref = :ref AND status NOT IN ('COMPLETED','CANCELLED','FAILED')")
    suspend fun cancelForSource(kind: String, ref: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM download_tasks WHERE source_kind = :kind AND source_ref = :ref")
    suspend fun deleteForSource(kind: String, ref: String)
}

@Dao
interface CachedPathDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(path: CachedPathEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(paths: List<CachedPathEntity>)

    @Query("SELECT * FROM cached_paths WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name ASC")
    fun observeByParent(serverId: Long, parentPath: String): Flow<List<CachedPathEntity>>

    @Query("SELECT * FROM cached_paths WHERE server_id = :serverId AND parent_path = :parentPath ORDER BY is_directory DESC, name ASC")
    suspend fun getByParent(serverId: Long, parentPath: String): List<CachedPathEntity>

    @Query("DELETE FROM cached_paths WHERE server_id = :serverId AND parent_path LIKE :path || '%'")
    suspend fun invalidateUnder(serverId: Long, path: String)

    @Query("DELETE FROM cached_paths WHERE server_id = :serverId")
    suspend fun deleteForServer(serverId: Long)
}

@Dao
interface SyncHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SyncHistoryEntity): Long

    @Update
    suspend fun update(history: SyncHistoryEntity)

    @Query("SELECT * FROM sync_history WHERE server_id = :serverId ORDER BY started_at DESC LIMIT :limit")
    fun observeForServer(serverId: Long, limit: Int = 20): Flow<List<SyncHistoryEntity>>

    @Query("SELECT * FROM sync_history ORDER BY started_at DESC LIMIT 50")
    fun observeAll(): Flow<List<SyncHistoryEntity>>
}