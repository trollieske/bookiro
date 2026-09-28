package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.AudioTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: AudioTrackEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<AudioTrackEntity>): List<Long>

    @Query("SELECT * FROM audio_tracks WHERE book_id = :bookId ORDER BY disc_number ASC, track_number ASC, file_path ASC")
    fun observeTracksForBook(bookId: Long): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks WHERE book_id = :bookId ORDER BY disc_number ASC, track_number ASC, file_path ASC")
    suspend fun getTracksForBook(bookId: Long): List<AudioTrackEntity>

    @Query("SELECT * FROM audio_tracks WHERE file_path = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): AudioTrackEntity?

    @Query("SELECT * FROM audio_tracks WHERE remote_path = :remotePath LIMIT 1")
    suspend fun getByRemotePath(remotePath: String): AudioTrackEntity?

    @Query("DELETE FROM audio_tracks WHERE book_id = :bookId")
    suspend fun deleteTracksForBook(bookId: Long)
}
