package com.bookrio.data.local.dao

import androidx.room.*
import com.bookrio.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(shelf: ShelfEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(ref: ShelfBookCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<ShelfBookCrossRef>)

    @Update
    suspend fun update(shelf: ShelfEntity)

    @Delete
    suspend fun delete(shelf: ShelfEntity)

    @Query("SELECT * FROM shelves WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ShelfEntity?

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM shelf_books WHERE shelf_id = :shelfId AND book_id = :bookId")
    suspend fun removeBookFromShelf(shelfId: Long, bookId: Long)

    @Query("DELETE FROM shelf_books WHERE shelf_id = :shelfId")
    suspend fun deleteCrossRefsByShelfId(shelfId: Long)

    @Delete
    suspend fun deleteCrossRef(ref: ShelfBookCrossRef)

    @Query("DELETE FROM shelf_books WHERE shelf_id = :shelfId")
    suspend fun clearShelf(shelfId: Long)

    @Transaction
    @Query("SELECT * FROM shelves ORDER BY position, sort_name")
    fun observeAll(): Flow<List<ShelfEntity>>

    @Transaction
    @Query("SELECT * FROM shelves WHERE id = :id")
    fun observeById(id: Long): Flow<ShelfWithBooks?>

    @Transaction
    @Query("SELECT * FROM shelves WHERE type = 'USER' ORDER BY position, sort_name")
    fun observeUserShelves(): Flow<List<ShelfEntity>>

    @Transaction
    @Query("SELECT * FROM shelves WHERE type = 'AUTO' ORDER BY position, sort_name")
    fun observeAutoShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT sb.position, b.* FROM shelf_books sb JOIN books b ON sb.book_id = b.id WHERE sb.shelf_id = :shelfId AND b.is_deleted = 0 ORDER BY sb.position")
    fun observeShelfBooks(shelfId: Long): Flow<List<BookEntity>>

    @Query("SELECT EXISTS (SELECT 1 FROM shelf_books WHERE shelf_id = :shelfId AND book_id = :bookId)")
    suspend fun isBookInShelf(shelfId: Long, bookId: Long): Boolean

    @Query("UPDATE shelf_books SET position = :position WHERE shelf_id = :shelfId AND book_id = :bookId")
    suspend fun updateBookPosition(shelfId: Long, bookId: Long, position: Int)
}
