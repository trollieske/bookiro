package com.shelf.reader.data.transfer

import java.io.File

/**
 * Local staging rules shared by every remote file source.
 *
 * A download always lands in `<final>.part` and is renamed only after its length
 * has been verified, so a crash never leaves a truncated file with a real book
 * name and an incomplete file is never imported.
 */
object RemoteStaging {

    fun sanitizeRelative(remotePath: String, basePath: String): String {
        val base = if (basePath.isBlank() || basePath == "/") "/" else basePath.trimEnd('/')
        val raw = if (base != "/" && remotePath.startsWith(base)) {
            remotePath.removePrefix(base)
        } else {
            remotePath
        }.removePrefix("/")

        val segments = raw.split('/')
            .filter { it.isNotBlank() && it != "." }
            .filter { it != ".." } // never escape the staging root
            .map { it.replace(Regex("[/\\\\\\u0000]"), "_") }
        return segments.joinToString("/").ifBlank { "download.bin" }
    }

    fun resolveFinal(downloadRoot: File, basePath: String, remotePath: String): File =
        File(downloadRoot, sanitizeRelative(remotePath, basePath))

    fun stagingFor(finalFile: File): File = File(finalFile.parentFile, finalFile.name + PART_SUFFIX)

    /** Resume offset validated against the known remote size. */
    fun resumeOffset(staging: File, expectedSize: Long): Long {
        if (!staging.exists()) return 0L
        val length = staging.length()
        if (length <= 0L) return 0L
        if (expectedSize > 0L && length > expectedSize) return 0L
        return length
    }

    fun moveIntoPlace(staging: File, finalFile: File): Boolean {
        finalFile.parentFile?.mkdirs()
        if (!staging.exists()) return false
        if (finalFile.exists() && !finalFile.delete()) return false
        if (staging.renameTo(finalFile)) return true
        return runCatching {
            staging.copyTo(finalFile, overwrite = true)
            staging.delete()
        }.isSuccess
    }

    private const val PART_SUFFIX = ".part"
}

/** Extensions Shelf imports. Compressed audio must not be transport-compressed. */
object RemoteMediaFormats {
    val BOOK_EXTENSIONS = setOf(
        "epub", "pdf", "cbz", "cbr", "fb2", "mobi", "azw", "azw3",
        "m4b", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "zip"
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isBook(name: String): Boolean = extensionOf(name) in BOOK_EXTENSIONS
}

/** A single remote file discovered by a browser or a sync walk. */
data class RemoteFileRef(
    val path: String,
    val name: String,
    val sizeBytes: Long = 0L,
    val modifiedEpochSec: Long = 0L
)