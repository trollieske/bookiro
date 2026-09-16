package com.shelf.reader.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the v10 -> v11 migration that makes SMB and WebDAV durable sources.
 * The migration adds nullable/lifecycle columns, so the exported schema must
 * contain exactly the same columns or upgraded installs fail to open.
 */
class SourceSchemaV11Test {

    private val schemaText: String = run {
        val candidates = listOf(
            File("schemas/com.shelf.reader.data.local.ShelfDatabase/11.json"),
            File("data/schemas/com.shelf.reader.data.local.ShelfDatabase/11.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Schema 11.json not found; run :data:kspDebugKotlin first")
        file.readText()
    }

    private fun tableBlock(table: String): String {
        val marker = "\"tableName\": \"$table\""
        val start = schemaText.indexOf(marker)
        require(start >= 0) { "Table $table not found in schema 11" }
        val next = schemaText.indexOf("\"tableName\":", start + marker.length)
        return if (next < 0) schemaText.substring(start) else schemaText.substring(start, next)
    }

    @Test
    fun `smb_servers carries the durability columns`() {
        val block = tableBlock("smb_servers")
        listOf("state", "last_error", "last_sync_at", "concurrency_override", "charging_only").forEach { column ->
            assertTrue("smb_servers missing column $column", block.contains("\"columnName\": \"$column\""))
        }
    }

    @Test
    fun `webdav_servers carries the durability columns`() {
        val block = tableBlock("webdav_servers")
        listOf("state", "last_error", "last_sync_at", "concurrency_override", "charging_only").forEach { column ->
            assertTrue("webdav_servers missing column $column", block.contains("\"columnName\": \"$column\""))
        }
    }
}