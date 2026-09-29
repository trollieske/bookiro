package com.bookrio.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SeedCallback(
    private val dbProvider: () -> ShelfDatabase
) : RoomDatabase.Callback() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(connection: SQLiteConnection) {
        super.onCreate(connection)
        scope.launch { performSeed(dbProvider()) }
    }

    internal fun performSeed(db: ShelfDatabase) {
        // Intentionally empty: users import their own files (SAF on Android,
        // document picker on iOS) or sync from a remote source.
    }
}