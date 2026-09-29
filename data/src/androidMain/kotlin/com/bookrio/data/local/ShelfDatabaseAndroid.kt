package com.bookrio.data.local

import com.bookrio.data.local.getInstance
import android.content.Context
import androidx.room.Room

private class DbHolder {
    @Volatile var db: ShelfDatabase? = null
}

@Volatile
private var INSTANCE: ShelfDatabase? = null

/**
 * Android database builder. Keeps the current DB file location (`shelf.db` in the
 * app data dir) so existing installs do not reset.
 *
 * Exposed as an extension on the companion so Android call sites keep using
 * `ShelfDatabase.getInstance(context)` unchanged.
 */
fun ShelfDatabase.Companion.getInstance(context: Context): ShelfDatabase =
    INSTANCE ?: synchronized(this) {
        INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
    }

private fun build(context: Context): ShelfDatabase {
    val holder = DbHolder()
    val base = Room.databaseBuilder(
        context.applicationContext,
        ShelfDatabase::class.java,
        ShelfDatabase.DB_NAME
    )
        .addMigrations(*ShelfDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigration()
    val db = runCatching {
        base
            .addCallback(SeedCallback { holder.db ?: error("DB not assigned during onCreate") })
            .build()
    }.getOrElse { _: Throwable ->
        runCatching { context.deleteDatabase(ShelfDatabase.DB_NAME) }
        base.build()
    }
    holder.db = db
    return db
}