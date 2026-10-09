package com.bookrio.app.backup

import android.database.sqlite.SQLiteDatabase

internal data class TextColumn(val table: String, val column: String)

/**
 * Rewrites path strings across every TEXT column of a SQLite database without
 * knowing the schema. This is what lets a restored backup carry `file_path`,
 * `file_uri`, `chapters_json`, `local_uri`, … all the way to the new install:
 * the rows are updated in place before the database is put back.
 */
internal object SqliteTextRewriter {

    fun textColumns(db: SQLiteDatabase): List<TextColumn> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'",
            null,
        ).use { cursor ->
            val idx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) tables += cursor.getString(idx)
        }
        val out = mutableListOf<TextColumn>()
        for (table in tables) {
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val typeIdx = cursor.getColumnIndex("type")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val type = cursor.getString(typeIdx) ?: ""
                    if (type.contains("TEXT", ignoreCase = true)) out += TextColumn(table, name)
                }
            }
        }
        return out
    }

    /** Replaces every occurrence of [from] with [to] in one column (index-assisted). */
    fun replace(db: SQLiteDatabase, column: TextColumn, from: String, to: String) {
        if (from.isBlank()) return
        db.execSQL(
            "UPDATE `${column.table}` SET `${column.column}` = REPLACE(`${column.column}`, ?, ?) " +
                "WHERE instr(`${column.column}`, ?) > 0",
            arrayOf(from, to, from),
        )
    }

    fun replaceAll(db: SQLiteDatabase, columns: List<TextColumn>, from: String, to: String) {
        columns.forEach { replace(db, it, from, to) }
    }

    /**
     * Replaces an external reference with a `@RESTORE@`-relative path. URI/JSON
     * columns keep a `file://` scheme; plain path columns stay plain.
     */
    fun replaceExternalRef(
        db: SQLiteDatabase,
        columns: List<TextColumn>,
        from: String,
        relPath: String,
    ) {
        columns.forEach { column ->
            val to = if (isUriLike(column)) {
                "file://${BackupFormat.TOKEN_RESTORE}/$relPath"
            } else {
                "${BackupFormat.TOKEN_RESTORE}/$relPath"
            }
            replace(db, column, from, to)
        }
    }

    fun isUriLike(column: TextColumn): Boolean =
        column.column.contains("uri", ignoreCase = true) ||
            column.column.endsWith("_json", ignoreCase = true)
}
