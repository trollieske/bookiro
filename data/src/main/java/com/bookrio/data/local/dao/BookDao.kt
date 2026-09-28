package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<BookEntity>): List<Long>

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE books SET is_deleted = 1, last_modified_at = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    /**
     * Updates ONLY cover_path and spine_color without touching last_modified_at.
     * This is critical: using @Update triggers observeAll() and causes the library
     * to reorder books every time a cover is generated. This targeted update is silent.
     */
    @Query("UPDATE books SET cover_path = :coverPath, spine_color = :spineColor WHERE id = :id")
    suspend fun updateCoverSilently(id: Long, coverPath: String, spineColor: Int)

    /**
     * Silent metadata enrichment update. Updates title, author, sort fields, isbn,
     * publisher, published_date and description without touching last_modified_at.
     * Used when online lookup fills in gaps like "Ukjent forfatter" for a book like Dune.
     */
    @Query("""
        UPDATE books SET
            title = :title,
            sort_title = :sortTitle,
            author = :author,
            sort_author = :sortAuthor,
            isbn = :isbn,
            publisher = :publisher,
            published_date = :publishedDate,
            description = :description
        WHERE id = :id
    """)
    suspend fun enrichMetadataSilently(
        id: Long,
        title: String,
        sortTitle: String,
        author: String,
        sortAuthor: String,
        isbn: String?,
        publisher: String?,
        publishedDate: String?,
        description: String?
    )

    @Query("SELECT * FROM books WHERE is_deleted = 0 ORDER BY last_opened_at DESC, date_added DESC, id ASC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 ORDER BY date_added DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int = 20): Flow<List<BookEntity>>

    @Query("""
        SELECT b.* FROM books b 
        LEFT JOIN reading_progress p ON p.book_id = b.id
        WHERE b.is_deleted = 0 
          AND (p.progress_percent IS NULL OR p.progress_percent > 0)
          AND (p.progress_percent IS NULL OR p.progress_percent < 1)
        ORDER BY p.updated_at DESC, b.last_opened_at DESC
    """)
    fun observeInProgress(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 AND date_finished IS NOT NULL ORDER BY date_finished DESC")
    fun observeFinished(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 AND type IN ('AUDIOBOOK','MIXED') ORDER BY last_opened_at DESC")
    fun observeAudiobooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 AND type IN ('EBOOK','MIXED') ORDER BY last_opened_at DESC")
    fun observeEbooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeById(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE file_hash = :hash AND is_deleted = 0 LIMIT 1")
    suspend fun getByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE file_path = :path AND is_deleted = 0 LIMIT 1")
    suspend fun getByPath(path: String): BookEntity?

    @Query("""
        SELECT * FROM books WHERE is_deleted = 0
          AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' 
               OR series LIKE '%' || :query || '%')
        ORDER BY title ASC
    """)
    fun search(query: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 AND series IS NOT NULL ORDER BY series, series_index, sort_title")
    fun observeAllBySeries(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE is_deleted = 0 AND is_sample = 1")
    suspend fun getAllSamples(): List<BookEntity>

    @Query("SELECT * FROM books WHERE is_deleted = 0")
    suspend fun getAllOnce(): List<BookEntity>

    @Query("DELETE FROM books")
    suspend fun deleteAll()
}
