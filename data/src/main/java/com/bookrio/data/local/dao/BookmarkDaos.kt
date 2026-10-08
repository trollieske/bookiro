package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.BookmarkTypeEntity
import com.bookrio.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookmarkEntity?

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY COALESCE(position_percent, 0) ASC, created_at ASC")
    suspend fun getForBook(bookId: Long): List<BookmarkEntity>

    @Transaction
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY COALESCE(position_percent, 0) ASC, created_at ASC")
    fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND type = :type ORDER BY COALESCE(position_percent, 0) ASC")
    fun observeByBookAndType(bookId: Long, type: BookmarkTypeEntity): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS (SELECT 1 FROM bookmarks WHERE book_id = :bookId AND ABS(COALESCE(position_percent, -1) - :pct) < 0.01 LIMIT 1)")
    suspend fun existsNear(bookId: Long, pct: Float): Boolean

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND ABS(COALESCE(position_percent, -1) - :pct) < 0.01 LIMIT 1")
    suspend fun getNear(bookId: Long, pct: Float): BookmarkEntity?

    /**
     * Eksakt treff for side-bokmerke-toggle: samme bok + samme seksjon (kapittel-
     * indeks) + samme lokale sideindeks — sikrer ingen duplikater ved lagring.
     */
    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId AND type = :type AND chapter_index = :chapterIndex AND page_index = :pageIndex LIMIT 1")
    suspend fun getByBookSectionPage(bookId: Long, type: BookmarkTypeEntity, chapterIndex: Int, pageIndex: Int): BookmarkEntity?

    @Transaction
    @Query("SELECT * FROM bookmarks ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE type = 'CHAPTER' AND book_id = :bookId ORDER BY chapter_index")
    fun observeChapterBookmarks(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>
}

@Dao
interface HighlightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Update
    suspend fun update(highlight: HighlightEntity)

    @Delete
    suspend fun delete(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM highlights WHERE book_id = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM highlights WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HighlightEntity?

    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY COALESCE(position_percent, 0) ASC, created_at ASC")
    suspend fun getForBook(bookId: Long): List<HighlightEntity>

    @Transaction
    @Query("SELECT * FROM highlights WHERE book_id = :bookId ORDER BY COALESCE(position_percent, 0) ASC, created_at ASC")
    fun observeByBook(bookId: Long): Flow<List<HighlightEntity>>

    @Transaction
    @Query("SELECT * FROM highlights ORDER BY updated_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY created_at DESC")
    fun observeAll(): Flow<List<HighlightEntity>>
}
