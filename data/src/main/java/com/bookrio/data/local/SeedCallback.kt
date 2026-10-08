package com.bookrio.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Room callback invoked when the database is created.
 *
 * Bookiro starts with a **clean slate**: the user adds their own files via local
 * SAF import or a configured source. The former bundled demo/sample rows (and the
 * sub-1 KB sample assets) were removed because they were stubs that could not be
 * read or played.
 */
class SeedCallback(
    private val dbProvider: () -> ShelfDatabase
) : RoomDatabase.Callback() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        scope.launch { performSeed(dbProvider()) }
    }

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun performSeed(db: ShelfDatabase) {
        // Intentionally empty: nothing is seeded.
    }
}