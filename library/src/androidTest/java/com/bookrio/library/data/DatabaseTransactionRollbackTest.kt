package com.bookrio.library.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookrio.core.dispatchers.DefaultDispatcherProvider
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.BookmarkEntity
import com.bookrio.data.local.entity.FormatEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room proof of transaction behaviour that the JVM fake harness cannot exercise:
 * the duplicate merge is atomic, so a failure rolls back the annotation transfer and the
 * soft-delete together.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTransactionRollbackTest {

    private lateinit var db: ShelfDatabase
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, ShelfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ebook(fileUri: String) = BookEntity(
        title = "Book",
        sortTitle = "Book",
        author = "Author",
        sortAuthor = "Author",
        type = BookTypeEntity.EBOOK,
        format = FormatEntity.EPUB,
        fileUri = fileUri
    )

    @Test
    fun failingTransaction_rollsBack_bookmarkTransferAndSoftDelete() = runTest {
        val id = db.bookDao().insert(ebook("content://x"))
        db.bookmarkDao().insert(BookmarkEntity(bookId = id, title = "keep me"))

        runCatching {
            db.withTransaction {
                db.bookmarkDao().deleteByBook(id)
                db.bookDao().softDelete(id)
                throw RuntimeException("boom")
            }
        }

        // The failed transaction left nothing behind.
        assertEquals(1, db.bookmarkDao().getForBook(id).size)
        assertFalse(db.bookDao().getById(id)!!.isDeleted)
    }

    @Test
    fun deduplicateLibrary_transfersAnnotationsAndSoftDeletes_onRealRoom() = runTest {
        val survivor = db.bookDao().insert(ebook("content://same"))
        val dup = db.bookDao().insert(ebook("content://same"))
        db.bookmarkDao().insert(BookmarkEntity(bookId = dup, title = "annotation"))

        val repo = BookImportRepository(ctx, db, DefaultDispatcherProvider)
        val removed = repo.deduplicateLibrary()

        assertEquals(1, removed)
        assertTrue(db.bookDao().getById(dup)!!.isDeleted)
        assertEquals(1, db.bookmarkDao().getForBook(survivor).size)
        assertEquals(0, db.bookmarkDao().getForBook(dup).size)
    }
}