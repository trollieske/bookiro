package com.shelf.reader.ftp.transfer

import java.io.File

/**
 * Import boundary for downloaded files. Kept behind an interface so the transfer
 * engine can be unit-tested without Room/Android.
 */
interface FtpImporter {
    suspend fun findImportedBookId(localPath: String): Long?
    suspend fun import(localFile: File, serverId: Long, remotePath: String): Long?
    suspend fun consolidateFragmentedAudiobooks(): Int
}