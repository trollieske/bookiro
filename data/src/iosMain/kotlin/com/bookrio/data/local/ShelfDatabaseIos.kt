package com.bookrio.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.concurrent.Volatile
import platform.Foundation.NSHomeDirectory

@Volatile
private var INSTANCE: ShelfDatabase? = null

/**
 * iOS database builder. The DB file lives in the app documents directory (managed
 * by the bundled SQLite driver) and uses the same entities/migrations as Android.
 * Confined to the main thread by the iOS app, so no lock is needed.
 */
fun getShelfDatabase(): ShelfDatabase {
    INSTANCE?.let { return it }
    val db = build()
    INSTANCE = db
    return db
}

/**
 * Room's non-Android builder needs a real path, not a bare file name (a relative
 * name resolves against a read-only cwd and fails with "Unable to open database").
 */
private fun databasePath(): String = "${NSHomeDirectory()}/Documents/${ShelfDatabase.DB_NAME}"

private fun build(): ShelfDatabase =
    Room.databaseBuilder<ShelfDatabase>(name = databasePath())
        .setDriver(BundledSQLiteDriver())
        .addMigrations(*ShelfDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()