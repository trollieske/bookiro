package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(progress: ReadingProgressEntity)

    @Update
    suspend fun update(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    fun observeByBook(bookId: Long): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE book_id = :bookId LIMIT 1")
    suspend fun getByBook(bookId: Long): ReadingProgressEntity?

    @Query("DELETE FROM reading_progress WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Transaction
    suspend fun upsertForBook(
        bookId: Long,
        updater: (ReadingProgressEntity) -> ReadingProgressEntity,
        creator: () -> ReadingProgressEntity
    ) {
        val existing = getByBook(bookId)
        if (existing != null) insertOrReplace(updater(existing))
        else insertOrReplace(creator())
    }
}


