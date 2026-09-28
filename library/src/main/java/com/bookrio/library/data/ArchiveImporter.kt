package com.bookrio.library.data

import android.content.Context
import android.util.Log
import com.bookrio.core.domain.model.BookFormat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ArchiveImporter {
    private const val TAG = "ArchiveImporter"

    fun unpackArchiveIfMultiBook(ctx: Context, file: File): File? {
        if (!file.exists() || !file.isFile) return null
        val lowerName = file.name.lowercase()
        if (!lowerName.endsWith(".zip") && !lowerName.endsWith(".cbz")) return null

        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val bookEntries = entries.filter { entry ->
                    if (entry.isDirectory) return@filter false
                    val format = BookFormat.fromFilename(entry.name)
                    format != BookFormat.UNKNOWN && format != BookFormat.ZIP
                }

                // If it only contains images (CBZ comic), do NOT unpack into separate books
                if (bookEntries.isEmpty()) {
                    return null
                }

                // If it contains only 1 single book file at root with no subfolders, no need to unpack as directory
                if (bookEntries.size == 1 && !bookEntries.first().name.contains('/')) {
                    return null
                }

                Log.i(TAG, "Unpacking archive '${file.name}' containing ${bookEntries.size} books/tracks")

                val unpackedDir = File(
                    ctx.getExternalFilesDir("unpacked_imports") ?: ctx.filesDir,
                    file.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                ).apply { mkdirs() }

                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val outFile = File(unpackedDir, entry.name)
                    outFile.parentFile?.mkdirs()

                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                return unpackedDir
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unpacking archive ${file.name}", e)
        }
        return null
    }
}
