package com.shelf.reader.ftp.worker

import android.content.Context
import com.shelf.reader.ftp.data.FtpGraph
import com.shelf.reader.ftp.data.FtpServerStore
import com.shelf.reader.ftp.data.LegacyServerInput

/**
 * One-shot, idempotent migration from the legacy encrypted-preferences server
 * blob into Room.
 *
 * The migration flag is only set after every legacy server is verified to exist
 * in Room, and the legacy blob is cleared only after that. A process death in
 * the middle simply re-runs the migration; the natural-key upsert prevents
 * duplicates.
 */
object FtpLegacyMigration {

    suspend fun runIfNeeded(context: Context): Int {
        val store = FtpServerStore(context)
        if (store.isMigrationComplete()) return 0

        val legacy = store.readLegacy()
        if (legacy.isEmpty()) {
            store.markMigrationComplete()
            return 0
        }

        val graph = FtpGraph.get(context)
        val inputs = legacy.map {
            LegacyServerInput(
                name = it.name.ifBlank { it.server },
                host = it.server,
                port = it.port,
                username = it.username,
                password = it.password,
                protocol = it.protocol,
                passiveMode = it.usePassiveMode,
                basePath = it.defaultRemotePath.ifBlank { "/" }
            )
        }

        val report = graph.sourceRepository.migrateLegacy(inputs)

        // Verify every server is now present in Room before declaring success.
        val allPresent = inputs.all { input ->
            val port = if (input.port > 0) input.port else input.protocol.defaultPort
            graph.sourceRepository.findSource(input.host, port, input.username, input.protocol) != null
        }

        if (report.failed == 0 && allPresent) {
            store.markMigrationComplete()
            store.clearLegacy()
        }
        return report.inserted + report.reused
    }
}