package com.bookrio.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Volatile
private var INSTANCE: ShelfDatabase? = null

/**
 * iOS database builder. The DB file lives in the app documents directory (managed
 * by the bundled SQLite driver) and uses the same entities/migrations as Android.
 */
fun getShelfDatabase(): ShelfDatabase =
    INSTANCE ?: synchronized(this) {
        INSTANCE ?: build().also { INSTANCE = it }
    }

private fun build(): ShelfDatabase =
    Room.databaseBuilder<ShelfDatabase>(name = ShelfDatabase.DB_NAME)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*ShelfDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigration()
        .build()