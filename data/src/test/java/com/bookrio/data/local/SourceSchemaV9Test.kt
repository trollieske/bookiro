package com.bookrio.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the v8 -> v9 migration: one shared transfer queue for all remote
 * sources plus the Calibre Content Server table. The migration SQL must add
 * exactly the columns/indices Room generates, or upgrading installs throw when
 * the database is opened.
 */
class SourceSchemaV9Test {

    private val schemaText: String = run {
        val candidates = listOf(
            File("schemas/com.bookrio.data.local.ShelfDatabase/9.json"),
            File("data/schemas/com.bookrio.data.local.ShelfDatabase/9.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Schema 9.json not found; run :data:kspDebugKotlin first")
        file.readText()
    }

    private fun tableBlock(table: String): String {
        val marker = "\"tableName\": \"$table\""
        val start = schemaText.indexOf(marker)
        require(start >= 0) { "Table $table not found in schema 9" }
        val next = schemaText.indexOf("\"tableName\":", start + marker.length)
        return if (next < 0) schemaText.substring(start) else schemaText.substring(start, next)
    }

    @Test
    fun `download_tasks carries the shared source identity columns`() {
        val block = tableBlock("download_tasks")
        assertTrue("source_kind missing", block.contains("\"columnName\": \"source_kind\""))
        assertTrue("source_ref missing", block.contains("\"columnName\": \"source_ref\""))
    }

    @Test
    fun `download_tasks enforces one row per source and remote path`() {
        val block = tableBlock("download_tasks")
        assertTrue(
            "missing unique index on (source_kind, source_ref, remote_path)",
            block.contains("\"name\": \"index_download_tasks_source_ref_path\"") &&
                Regex("\"index_download_tasks_source_ref_path\"[\\s\\S]{0,300}?\"unique\": true")
                    .containsMatchIn(block)
        )
    }

    @Test
    fun `calibre_servers carries credentials and lifecycle columns`() {
        val block = tableBlock("calibre_servers")
        listOf(
            "base_url", "username", "password_encrypted", "state", "last_error",
            "sync_enabled", "sync_wifi_only", "charging_only", "last_sync_at",
            "last_connected_at", "is_active", "created_at", "updated_at"
        ).forEach { column ->
            assertTrue("calibre_servers missing column $column", block.contains("\"columnName\": \"$column\""))
        }
    }
}