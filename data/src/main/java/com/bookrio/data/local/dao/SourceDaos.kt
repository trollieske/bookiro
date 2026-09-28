package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SmbServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: SmbServerEntity): Long

    @Update
    suspend fun update(server: SmbServerEntity)

    @Delete
    suspend fun delete(server: SmbServerEntity)

    @Query("SELECT * FROM smb_servers WHERE is_active = 1 ORDER BY position, display_name")
    fun observeAll(): Flow<List<SmbServerEntity>>

    @Query("SELECT * FROM smb_servers WHERE is_active = 1 ORDER BY position, display_name")
    suspend fun getAll(): List<SmbServerEntity>

    @Query("SELECT * FROM smb_servers WHERE id = :id")
    fun observeById(id: Long): Flow<SmbServerEntity?>

    @Query("SELECT * FROM smb_servers WHERE id = :id")
    suspend fun getById(id: Long): SmbServerEntity?

    @Query("SELECT * FROM smb_servers WHERE sync_enabled = 1 AND is_active = 1")
    suspend fun getSyncEnabledServers(): List<SmbServerEntity>

    @Query("UPDATE smb_servers SET sync_last_check_at = :now WHERE id = :id")
    suspend fun markSynced(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM smb_servers WHERE host = :host AND port = :port AND share_name = :shareName AND username = :username LIMIT 1")
    suspend fun findByNaturalKey(host: String, port: Int, shareName: String, username: String): SmbServerEntity?

    @Query("UPDATE smb_servers SET state = :state, last_error = :error, updated_at = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: RemoteSourceStateEntity, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE smb_servers SET last_sync_at = :now, sync_last_check_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markLastSync(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE smb_servers SET base_path = :path, updated_at = :now WHERE id = :id")
    suspend fun updateBasePath(id: Long, path: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE smb_servers SET sync_enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE smb_servers SET sync_wifi_only = :wifiOnly, charging_only = :chargingOnly, concurrency_override = :concurrency, updated_at = :now WHERE id = :id")
    suspend fun updateSyncPolicy(id: Long, wifiOnly: Boolean, chargingOnly: Boolean, concurrency: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM smb_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface WebdavServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: WebdavServerEntity): Long

    @Update
    suspend fun update(server: WebdavServerEntity)

    @Delete
    suspend fun delete(server: WebdavServerEntity)

    @Query("SELECT * FROM webdav_servers WHERE is_active = 1 ORDER BY position, display_name")
    fun observeAll(): Flow<List<WebdavServerEntity>>

    @Query("SELECT * FROM webdav_servers WHERE is_active = 1 ORDER BY position, display_name")
    suspend fun getAll(): List<WebdavServerEntity>

    @Query("SELECT * FROM webdav_servers WHERE id = :id")
    fun observeById(id: Long): Flow<WebdavServerEntity?>

    @Query("SELECT * FROM webdav_servers WHERE id = :id")
    suspend fun getById(id: Long): WebdavServerEntity?

    @Query("SELECT * FROM webdav_servers WHERE sync_enabled = 1 AND is_active = 1")
    suspend fun getSyncEnabledServers(): List<WebdavServerEntity>

    @Query("UPDATE webdav_servers SET sync_last_check_at = :now WHERE id = :id")
    suspend fun markSynced(id: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM webdav_servers WHERE base_url = :baseUrl AND username = :username LIMIT 1")
    suspend fun findByNaturalKey(baseUrl: String, username: String): WebdavServerEntity?

    @Query("UPDATE webdav_servers SET state = :state, last_error = :error, updated_at = :now WHERE id = :id")
    suspend fun updateState(id: Long, state: RemoteSourceStateEntity, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE webdav_servers SET last_sync_at = :now, sync_last_check_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markLastSync(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE webdav_servers SET base_path = :path, updated_at = :now WHERE id = :id")
    suspend fun updateBasePath(id: Long, path: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE webdav_servers SET sync_enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setSyncEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE webdav_servers SET sync_wifi_only = :wifiOnly, charging_only = :chargingOnly, concurrency_override = :concurrency, updated_at = :now WHERE id = :id")
    suspend fun updateSyncPolicy(id: Long, wifiOnly: Boolean, chargingOnly: Boolean, concurrency: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM webdav_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface CalibreServerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(server: CalibreServerEntity): Long

    @Update
    suspend fun update(server: CalibreServerEntity)

    @Delete
    suspend fun delete(server: CalibreServerEntity)

    @Query("SELECT * FROM calibre_servers WHERE is_active = 1 ORDER BY display_name")
    fun observeAll(): Flow<List<CalibreServerEntity>>

    @Query("SELECT * FROM calibre_servers WHERE is_active = 1 ORDER BY display_name")
    suspend fun getAll(): List<CalibreServerEntity>

    @Query("SELECT * FROM calibre_servers WHERE id = :id")
    fun observeById(id: Long): Flow<CalibreServerEntity?>

    @Query("SELECT * FROM calibre_servers WHERE id = :id")
    suspend fun getById(id: Long): CalibreServerEntity?

    @Query("SELECT * FROM calibre_servers WHERE base_url = :baseUrl LIMIT 1")
    suspend fun findByBaseUrl(baseUrl: String): CalibreServerEntity?

    @Query("SELECT * FROM calibre_servers WHERE sync_enabled = 1 AND is_active = 1")
    suspend fun getSyncEnabledServers(): List<CalibreServerEntity>

    @Query("UPDATE calibre_servers SET state = :state, last_error = :error, updated_at = :now WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: CalibreSourceStateEntity,
        error: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE calibre_servers SET last_sync_at = :now, sync_last_check_at = :now, updated_at = :now WHERE id = :id")
    suspend fun markLastSync(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM calibre_servers WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TorrentDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: TorrentDownloadEntity): Long

    @Update
    suspend fun update(download: TorrentDownloadEntity)

    @Delete
    suspend fun delete(download: TorrentDownloadEntity)

    @Query("SELECT * FROM torrent_downloads ORDER BY status IN ('RUNNING','PENDING') DESC, priority DESC, created_at DESC")
    fun observeAll(): Flow<List<TorrentDownloadEntity>>

    @Query("SELECT * FROM torrent_downloads")
    suspend fun getAllOnce(): List<TorrentDownloadEntity>

    @Query("SELECT * FROM torrent_downloads WHERE status IN ('RUNNING','PENDING','PAUSED') ORDER BY priority DESC, created_at DESC")
    fun observeActive(): Flow<List<TorrentDownloadEntity>>

    @Query("SELECT * FROM torrent_downloads WHERE status = 'COMPLETED' ORDER BY completed_at DESC LIMIT :limit")
    fun observeCompleted(limit: Int = 50): Flow<List<TorrentDownloadEntity>>

    @Query("SELECT * FROM torrent_downloads WHERE id = :id")
    suspend fun getById(id: Long): TorrentDownloadEntity?

    @Query("SELECT * FROM torrent_downloads WHERE info_hash = :infoHash")
    suspend fun getByInfoHash(infoHash: String): TorrentDownloadEntity?

    @Query("SELECT * FROM torrent_downloads WHERE status = 'PENDING' ORDER BY priority DESC, created_at ASC LIMIT 1")
    suspend fun getNextPending(): TorrentDownloadEntity?

    @Query("SELECT * FROM torrent_downloads WHERE status = 'RUNNING'")
    suspend fun getRunning(): List<TorrentDownloadEntity>

    @Query("UPDATE torrent_downloads SET status = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)

    @Query("UPDATE torrent_downloads SET is_paused = :paused WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean)

    @Query("UPDATE torrent_downloads SET status = 'PAUSED', is_paused = 1 WHERE status = 'RUNNING'")
    suspend fun pauseAll()

    @Query("UPDATE torrent_downloads SET status = 'PENDING', is_paused = 0 WHERE status IN ('PAUSED','PENDING') AND is_paused = 1")
    suspend fun resumeAll()

    @Query("SELECT COUNT(*) FROM torrent_downloads WHERE status IN ('RUNNING','PENDING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT SUM(download_speed_bps) FROM torrent_downloads WHERE status = 'RUNNING'")
    fun observeTotalDownloadSpeed(): Flow<Long?>

    @Query("SELECT SUM(upload_speed_bps) FROM torrent_downloads WHERE status = 'RUNNING'")
    fun observeTotalUploadSpeed(): Flow<Long?>
}
