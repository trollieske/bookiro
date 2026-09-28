package com.bookrio.ftp.transfer

import java.io.File

/**
 * Import boundary for downloaded files. Kept behind an interface so the transfer
 * engine can be unit-tested without Room/Android.
 */
interface FtpImporter {
    suspend fun findImportedBookId(localPath: String): Long?

    /**
     * Imports a batch of files that belong together (for example one audiobook
     * folder) and returns the created/updated book id, or null on failure.
     *
     * Batching matters: importing track-by-track makes the library layer reload
     * and re-consolidate the whole library for every single file.
     */
    suspend fun importBatch(localFiles: List<File>, serverId: Long, remotePath: String?): Long?

    suspend fun consolidateFragmentedAudiobooks(): Int
}