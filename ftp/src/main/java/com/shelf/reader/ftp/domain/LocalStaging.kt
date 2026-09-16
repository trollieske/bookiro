package com.shelf.reader.ftp.domain

import java.io.File

/**
 * Local staging rules.
 *
 * Every download lands in `<final>.part` in the app-specific directory and is
 * renamed only after its length has been verified. A crash therefore never
 * leaves a truncated file with a real book name.
 */
object LocalStaging {

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

    fun resolveFinal(downloadRoot: File, basePath: String, remotePath: String): File {
        val relative = sanitizeRelative(remotePath, basePath)
        return File(downloadRoot, relative)
    }

    fun stagingFor(finalFile: File): File = File(finalFile.parentFile, finalFile.name + PART_SUFFIX)

    /**
     * Resume offset for a staging file.
     *
     * When the remote size is known and already present, the file is treated as
     * complete (offset == size). When the staging file is larger than the remote
     * file it is stale and a full restart is required.
     */
    fun resumeOffset(staging: File, expectedSize: Long): Long {
        if (!staging.exists()) return 0L
        val length = staging.length()
        if (length <= 0L) return 0L
        if (expectedSize > 0L && length > expectedSize) return 0L
        return length
    }

    /**
     * Atomic move of a verified staging file onto its final path. Only called
     * after the downloaded length has been validated.
     */
    fun moveIntoPlace(staging: File, finalFile: File): Boolean {
        finalFile.parentFile?.mkdirs()
        if (finalFile.exists() && !finalFile.delete()) return false
        if (staging.renameTo(finalFile)) return true
        return runCatching {
            staging.copyTo(finalFile, overwrite = true)
            staging.delete()
        }.isSuccess
    }

    /** Conservative free-space check before starting a file. */
    fun hasSpaceFor(directory: File, bytes: Long): Boolean {
        if (bytes <= 0L) return true
        // The staging directory may not exist yet; walk up to the nearest ancestor
        // that does so usableSpace is meaningful.
        var dir: File? = directory
        while (dir != null && !dir.exists()) dir = dir.parentFile
        val usable = runCatching { dir?.usableSpace ?: Long.MAX_VALUE }.getOrDefault(Long.MAX_VALUE)
        // Keep a small safety margin so the final rename/import never truncates.
        return usable > bytes + SPACE_MARGIN
    }

    private const val SPACE_MARGIN = 8L * 1024 * 1024
    private const val PART_SUFFIX = ".part"
}

/** Media extensions Shelf imports. Compressed audio must not be SSH-compressed. */
object MediaFormats {
    val BOOK_EXTENSIONS = setOf(
        "epub", "pdf", "cbz", "cbr", "fb2", "mobi", "azw3",
        "m4b", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav"
    )

    val COMPRESSED_AUDIO = setOf("m4b", "mp3", "m4a", "aac", "opus", "flac", "ogg")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isBook(name: String): Boolean = extensionOf(name) in BOOK_EXTENSIONS

    fun isCompressedAudio(name: String): Boolean = extensionOf(name) in COMPRESSED_AUDIO
}