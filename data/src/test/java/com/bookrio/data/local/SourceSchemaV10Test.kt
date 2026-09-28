package com.bookrio.data.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the v9 -> v10 migration (torrent seeding policy). The migration adds a
 * nullable `seed_policy` column, so the exported schema must contain it too or
 * upgraded installs fail when the database is opened.
 */
class SourceSchemaV10Test {

    private val schemaText: String = run {
        val candidates = listOf(
            File("schemas/com.bookrio.data.local.ShelfDatabase/10.json"),
            File("data/schemas/com.bookrio.data.local.ShelfDatabase/10.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Schema 10.json not found; run :data:kspDebugKotlin first")
        file.readText()
    }

    @Test
    fun `torrent_downloads carries the seed policy column`() {
        val marker = "\"tableName\": \"torrent_downloads\""
        val start = schemaText.indexOf(marker)
        require(start >= 0) { "torrent_downloads not found in schema 10" }
        val next = schemaText.indexOf("\"tableName\":", start + marker.length)
        val block = if (next < 0) schemaText.substring(start) else schemaText.substring(start, next)
        assertTrue("seed_policy missing", block.contains("\"columnName\": \"seed_policy\""))
    }
}