package com.bookrio.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the FTP durability schema. The migration (7 -> 8) must add exactly the
 * columns/indices that Room generates, otherwise opening the DB on an upgraded
 * install throws at runtime.
 *
 * Parsed with plain string checks (the unit-test android.jar does not provide a
 * working org.json), scoped to each table's block in the exported schema JSON.
 */
class FtpSchemaTest {

    private val schemaText: String = run {
        val candidates = listOf(
            File("schemas/com.bookrio.data.local.ShelfDatabase/8.json"),
            File("data/schemas/com.bookrio.data.local.ShelfDatabase/8.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Schema 8.json not found; run :data:kspDebugKotlin first")
        file.readText()
    }

    private fun tableBlock(table: String): String {
        val marker = "\"tableName\": \"$table\""
        val start = schemaText.indexOf(marker)
        require(start >= 0) { "Table $table not found in schema 8" }
        val next = schemaText.indexOf("\"tableName\":", start + marker.length)
        return if (next < 0) schemaText.substring(start) else schemaText.substring(start, next)
    }

    private fun assertColumns(table: String, columns: List<String>) {
        val block = tableBlock(table)
        columns.forEach { column ->
            assertTrue("$table missing column $column", block.contains("\"columnName\": \"$column\""))
        }
    }

    @Test
    fun `ftp_servers carries the durability columns`() {
        assertColumns(
            "ftp_servers",
            listOf("state", "last_error", "concurrency_override", "charging_only", "last_sync_at")
        )
    }

    @Test
    fun `download_tasks carries resume and progress columns`() {
        assertColumns(
            "download_tasks",
            listOf(
                "remote_mtime", "staging_path", "last_progress_at",
                "updated_at", "error_kind", "bytes_per_sec", "next_attempt_at"
            )
        )
    }

    @Test
    fun `download_tasks enforces one row per server and remote path`() {
        val block = tableBlock("download_tasks")
        assertTrue(
            "missing unique index on (server_id, remote_path)",
            block.contains("\"name\": \"index_download_tasks_server_id_remote_path\"") &&
                Regex("\"index_download_tasks_server_id_remote_path\"[\\s\\S]{0,300}?\"unique\": true")
                    .containsMatchIn(block)
        )
    }
}