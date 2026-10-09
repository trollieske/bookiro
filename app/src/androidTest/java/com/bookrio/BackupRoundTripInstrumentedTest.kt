package com.bookrio

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookrio.app.backup.BackupOutcome
import com.bookrio.app.backup.LibraryBackupEngine
import com.bookrio.data.local.ShelfDatabase
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.data.local.entity.ImportSourceEntity
import com.bookrio.data.local.entity.ReadingProgressEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end export -> wipe -> import on a real device database. This is the
 * test that proves a user can delete the app and get their library (and their
 * "where I left off" progress) back.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripInstrumentedTest {

    @Test
    fun exportThenImportRestoresBookProgressAndFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = ShelfDatabase.getInstance(context)

        val bookFile = File(context.filesDir, "backup_test/book.epub").apply {
            parentFile?.mkdirs()
            writeText("bookiro backup round-trip")
        }
        val bookId = runBlocking {
            db.bookDao().insert(
                BookEntity(
                    title = "Backup Round Trip",
                    author = "Tester",
                    type = BookTypeEntity.EBOOK,
                    format = FormatEntity.EPUB,
                    filePath = bookFile.absolutePath,
                    fileUri = "file://${bookFile.absolutePath}",
                    importSource = ImportSourceEntity.FILE_PICKER,
                )
            )
        }
        runBlocking {
            db.progressDao().insertOrReplace(
                ReadingProgressEntity(bookId = bookId, progressPercent = 0.42f, positionMs = 1234L)
            )
        }

        val engine = LibraryBackupEngine(context)
        val archive = File(context.cacheDir, "backup_test/archive.zip").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val archiveUri = Uri.fromFile(archive)

        // Metadata-only keeps the test fast; the test file lives under filesDir,
        // which is always archived.
        val exported = runBlocking { engine.exportLibrary(archiveUri, includeMedia = false) {} }
        assertTrue("export must succeed: $exported", exported is BackupOutcome.Exported)
        assertTrue("archive must exist and be non-empty", archive.length() > 0L)

        // Wipe: soft-delete the row and remove the file, as an uninstall would.
        runBlocking { db.bookDao().softDelete(bookId) }
        bookFile.delete()

        val imported = runBlocking { engine.importLibrary(archiveUri) {} }
        assertTrue("import must succeed: $imported", imported is BackupOutcome.Imported)

        // Verify through a fresh raw handle: the Room singleton was reset by import.
        val restored = SQLiteDatabase.openDatabase(
            context.getDatabasePath("shelf.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        try {
            restored.rawQuery(
                "SELECT title, author, file_path, is_deleted FROM books WHERE id = ?",
                arrayOf(bookId.toString()),
            ).use { cursor ->
                assertTrue("book row must be back", cursor.moveToFirst())
                assertEquals("Backup Round Trip", cursor.getString(0))
                assertEquals("Tester", cursor.getString(1))
                assertEquals(bookFile.absolutePath, cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
            }
            restored.rawQuery(
                "SELECT progress_percent, position_ms FROM reading_progress WHERE book_id = ?",
                arrayOf(bookId.toString()),
            ).use { cursor ->
                assertTrue("progress row must be back", cursor.moveToFirst())
                assertEquals(0.42f, cursor.getFloat(0), 0.0001f)
                assertEquals(1234L, cursor.getLong(1))
            }
        } finally {
            restored.close()
        }
        assertTrue("media file must be restored", bookFile.exists())
        assertEquals("bookiro backup round-trip", bookFile.readText())
    }

    @Test
    fun externalFileIsRelocatedIntoAppStorageOnRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A file outside every app root (cacheDir is deliberately excluded from
        // the tree walk) stands in for a SAF/shared reference. The engine must
        // copy it into the archive and rewrite the DB to the @RESTORE@ location.
        val externalFile = File(context.cacheDir, "external_ref/outside.epub").apply {
            parentFile?.mkdirs()
            writeText("outside app storage")
        }
        val db = ShelfDatabase.getInstance(context)
        val bookId = runBlocking {
            db.bookDao().insert(
                BookEntity(
                    title = "External Ref",
                    type = BookTypeEntity.EBOOK,
                    format = FormatEntity.EPUB,
                    filePath = externalFile.absolutePath,
                    fileUri = "file://${externalFile.absolutePath}",
                    importSource = ImportSourceEntity.FILE_PICKER,
                )
            )
        }

        val engine = LibraryBackupEngine(context)
        val archive = File(context.cacheDir, "backup_test/external.zip").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
        }
        val archiveUri = Uri.fromFile(archive)

        val exported = runBlocking { engine.exportLibrary(archiveUri, includeMedia = true) {} }
        assertTrue("export must succeed: $exported", exported is BackupOutcome.Exported)

        runBlocking { db.bookDao().softDelete(bookId) }
        externalFile.delete()

        val imported = runBlocking { engine.importLibrary(archiveUri) {} }
        assertTrue("import must succeed: $imported", imported is BackupOutcome.Imported)

        val restored = SQLiteDatabase.openDatabase(
            context.getDatabasePath("shelf.db").path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        val restoredPath: String
        try {
            restored.rawQuery(
                "SELECT file_path FROM books WHERE id = ?",
                arrayOf(bookId.toString()),
            ).use { cursor ->
                assertTrue("book row must be back", cursor.moveToFirst())
                restoredPath = cursor.getString(0)
            }
        } finally {
            restored.close()
        }
        assertTrue(
            "external file must be relocated under filesDir/restored, was $restoredPath",
            restoredPath.startsWith(File(context.filesDir, "restored").absolutePath),
        )
        assertEquals("outside app storage", File(restoredPath).readText())
    }
}
