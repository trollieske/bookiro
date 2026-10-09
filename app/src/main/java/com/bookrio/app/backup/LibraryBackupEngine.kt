package com.bookrio.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bookrio.BuildConfig
import com.bookrio.data.local.ShelfDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Robust, streaming export/import of the whole Bookiro library.
 *
 * The archive is a ZIP; media that is already compressed (audio, epub, pdf,
 * images) is deflated at level 0 so the engine never burns CPU recompressing
 * it. Everything is streamed from/to the SAF picker, so a multi-GB library
 * never lands in memory.
 */
class LibraryBackupEngine(private val context: Context) {

    companion object {
        private const val TAG = "LibraryBackupEngine"
        private const val BUFFER = 64 * 1024

        /** Directories under `filesDir` that hold bulk media (restored on demand). */
        private val LARGE_DIRS = setOf(
            "shelf_torrents", "ftp", "smb", "webdav", "calibre", "restored",
        )

        private val STORE_EXTENSIONS = setOf(
            "mp3", "m4b", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma",
            "zip", "cbz", "cbr", "epub", "pdf", "mobi", "azw", "azw3",
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic",
            "mp4", "m4v", "mkv", "avi", "mov", "webm",
        )

        private val MEDIA_URI_REGEX = Regex("\"mediaUri\"\\s*:\\s*\"([^\"]+)\"")
    }

    // ── Roots ─────────────────────────────────────────────────────────────────

    /** App-owned roots and their archive tokens, newest storage first. */
    fun appRoots(): List<BackupRoot> {
        val roots = mutableListOf<BackupRoot>()
        roots += BackupRoot(BackupFormat.TOKEN_FILES, context.filesDir.absolutePath)
        var sawPrimary = false
        context.getExternalFilesDirs(null).forEachIndexed { index, dir ->
            if (dir == null) return@forEachIndexed
            val token = if (index == 0) {
                sawPrimary = true
                BackupFormat.TOKEN_EXT
            } else {
                "${BackupFormat.TOKEN_EXT_PREFIX}${index}@"
            }
            roots += BackupRoot(token, dir.absolutePath)
        }
        if (!sawPrimary) {
            // A restore on a device without external storage must still land the
            // files somewhere instead of silently dropping them.
            roots += BackupRoot(BackupFormat.TOKEN_EXT, File(context.filesDir, "external").absolutePath)
        }
        roots += BackupRoot(BackupFormat.TOKEN_RESTORE, File(context.filesDir, "restored").absolutePath)
        return roots
    }

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportLibrary(
        destination: Uri,
        includeMedia: Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): BackupOutcome = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "bookiro-backup-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val roots = appRoots()
            val dbFile = File(staging, "shelf.db")
            snapshotDatabase(dbFile)
            val dbVersion = readUserVersion(dbFile)
            val externals = if (includeMedia) collectExternalMedia(dbFile, roots) else emptyList()
            tokenizeDatabase(dbFile, roots, externals)

            val entries = ArrayList<BackupEntry>()
            entries += BackupEntry(BackupFormat.DB_ENTRY, BackupSource.Local(dbFile), dbFile.length())
            entries += enumerateAppTree(roots, includeMedia)
            externals.forEach { ext ->
                entries += BackupEntry(
                    "${BackupFormat.MEDIA_DIR}${BackupFormat.TOKEN_RESTORE}/${ext.relPath}",
                    ext.source,
                    ext.bytes,
                )
            }

            val totalBytes = entries.sumOf { it.bytes }
            val manifest = BackupManifest.current(
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE,
                applicationId = BuildConfig.APPLICATION_ID,
                dbVersion = dbVersion,
                includeMedia = includeMedia,
                counts = libraryCounts(dbFile),
                roots = roots.associate { it.token to it.path },
                totalBytes = totalBytes,
                totalEntries = entries.size,
            )

            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: return@withContext BackupOutcome.Failed("Could not open the destination for writing")
            output.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw, BUFFER)).use { zip ->
                    writeManifest(zip, manifest)
                    var done = 0L
                    entries.forEachIndexed { index, entry ->
                        writeEntry(zip, entry) { delta ->
                            done += delta
                            onProgress(
                                BackupProgress(
                                    label = "EXPORT",
                                    processedBytes = done,
                                    totalBytes = totalBytes,
                                    entriesDone = index,
                                    entriesTotal = entries.size,
                                    current = entry.name,
                                )
                            )
                        }
                    }
                    onProgress(
                        BackupProgress("EXPORT", totalBytes, totalBytes, entries.size, entries.size, null)
                    )
                }
            }
            BackupOutcome.Exported(
                location = destination.lastPathSegment ?: destination.toString(),
                bytes = totalBytes,
                entries = entries.size,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            BackupOutcome.Failed(t.message ?: "Backup failed", t)
        } finally {
            staging.deleteRecursively()
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importLibrary(
        source: Uri,
        onProgress: (BackupProgress) -> Unit,
    ): BackupOutcome = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "bookiro-restore-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val roots = appRoots()
            val tokenToRoot = roots.associate { it.token to it.path }
            var manifest: BackupManifest? = null
            var stagedDb: File? = null
            var mediaFiles = 0
            var missingMedia = 0
            var processed = 0L

            val input = context.contentResolver.openInputStream(source)
                ?: return@withContext BackupOutcome.Failed("Could not read the selected archive")
            input.use { raw ->
                ZipInputStream(BufferedInputStream(raw, BUFFER)).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == BackupFormat.MANIFEST_ENTRY -> {
                                val text = zip.readBytes().toString(Charsets.UTF_8)
                                manifest = BackupManifest.fromJson(text)
                                if (manifest == null) {
                                    return@withContext BackupOutcome.Failed(
                                        "Not a Bookiro backup (unreadable manifest)"
                                    )
                                }
                            }
                            entry.name == BackupFormat.DB_ENTRY -> {
                                val out = File(staging, "shelf.db")
                                out.outputStream().buffered(BUFFER).use { dest ->
                                    processed += copyStream(zip, dest, onProgress, manifest, processed, 0, 0)
                                }
                                stagedDb = out
                            }
                            entry.name.startsWith(BackupFormat.MEDIA_DIR) -> {
                                val rel = entry.name.removePrefix(BackupFormat.MEDIA_DIR)
                                val dest = resolveDestination(rel, tokenToRoot)
                                if (dest == null) {
                                    missingMedia++
                                } else {
                                    dest.parentFile?.mkdirs()
                                    dest.outputStream().buffered(BUFFER).use { out ->
                                        processed += copyStream(zip, out, onProgress, manifest, processed, 0, 0)
                                    }
                                    mediaFiles++
                                }
                            }
                            else -> Unit // unknown entry: ignore for forward compatibility
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val mf = manifest
                ?: return@withContext BackupOutcome.Failed("Not a Bookiro backup (manifest.json missing)")
            if (mf.formatVersion > BackupFormat.FORMAT_VERSION) {
                return@withContext BackupOutcome.Failed(
                    "This backup was made by a newer Bookiro (format ${mf.formatVersion}). " +
                        "Update the app and try again."
                )
            }
            val dbFile = stagedDb
                ?: return@withContext BackupOutcome.Failed("Not a Bookiro backup (shelf.db missing)")

            untokenizeDatabase(dbFile, roots)
            val counts = libraryCounts(dbFile)
            swapInDatabase(dbFile)

            BackupOutcome.Imported(
                bookCount = counts["books"] ?: 0,
                episodeCount = counts["episodes"] ?: 0,
                mediaFiles = mediaFiles,
                missingMedia = missingMedia,
                applicationId = mf.applicationId,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "import failed", t)
            BackupOutcome.Failed(t.message ?: "Restore failed", t)
        } finally {
            staging.deleteRecursively()
        }
    }

    // ── Database snapshot / swap ──────────────────────────────────────────────

    /** Writes a consistent copy of the live database to [dest]. */
    private fun snapshotDatabase(dest: File) {
        val database = ShelfDatabase.getInstance(context)
        val support = database.openHelper.writableDatabase
        // On API 30+ VACUUM INTO yields a compact, self-contained snapshot
        // without closing the live database.
        val vacuumed = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val escaped = dest.absolutePath.replace("'", "''")
                support.execSQL("VACUUM INTO '$escaped'")
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (vacuumed && dest.exists() && dest.length() > 0L) return
        // Fallback: checkpoint the WAL into the main file, then copy it (plus the
        // WAL/SHM siblings so a copy taken mid-checkpoint still recovers).
        runCatching { support.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        val live = context.getDatabasePath("shelf.db")
        live.copyTo(dest, overwrite = true)
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(live.path + suffix)
            if (sidecar.exists()) sidecar.copyTo(File(dest.path + suffix), overwrite = true)
        }
        // Opening the staging copy consolidates any WAL frames into the main file.
        runCatching {
            SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() }
            }
        }
        File(dest.path + "-wal").delete()
        File(dest.path + "-shm").delete()
    }

    /** Replaces the live database with [dbFile]; the caller must restart the app. */
    private fun swapInDatabase(dbFile: File) {
        ShelfDatabase.resetInstance()
        val live = context.getDatabasePath("shelf.db")
        live.parentFile?.mkdirs()
        dbFile.copyTo(live, overwrite = true)
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
    }

    private fun readUserVersion(dbFile: File): Int = runCatching {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else -1
            }
        }
    }.getOrDefault(-1)

    // ── Tokenize / untokenize ─────────────────────────────────────────────────

    private fun tokenizeDatabase(
        dbFile: File,
        roots: List<BackupRoot>,
        externals: List<ExternalMedia>,
    ) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val columns = SqliteTextRewriter.textColumns(db)
            roots.sortedByDescending { it.path.length }.forEach { root ->
                if (root.path.isNotBlank()) {
                    SqliteTextRewriter.replaceAll(db, columns, root.path, root.token)
                }
            }
            externals.forEach { ext ->
                ext.refs.forEach { ref ->
                    SqliteTextRewriter.replaceExternalRef(db, columns, ref, ext.relPath)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }
    }

    private fun untokenizeDatabase(dbFile: File, roots: List<BackupRoot>) {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            val columns = SqliteTextRewriter.textColumns(db)
            roots.sortedByDescending { it.token.length }.forEach { root ->
                SqliteTextRewriter.replaceAll(db, columns, root.token, root.path)
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            db.close()
        }
    }

    // ── File enumeration ──────────────────────────────────────────────────────

    private fun enumerateAppTree(roots: List<BackupRoot>, includeMedia: Boolean): List<BackupEntry> {
        val out = ArrayList<BackupEntry>()
        roots.forEach { root ->
            // `@RESTORE@` lives inside `@FILES@`; enumerating both would archive
            // every relocated file twice.
            if (root.token == BackupFormat.TOKEN_RESTORE) return@forEach
            val dir = File(root.path)
            if (!dir.isDirectory) return@forEach
            val isFilesRoot = root.token == BackupFormat.TOKEN_FILES
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                val top = rel.substringBefore('/')
                val large = top in LARGE_DIRS
                val include = if (isFilesRoot) (!large || includeMedia) else includeMedia
                if (include) {
                    out += BackupEntry(
                        "${BackupFormat.MEDIA_DIR}${root.token}/$rel",
                        BackupSource.Local(file),
                        file.length(),
                    )
                }
            }
        }
        return out
    }

    // ── External (SAF / shared) media ─────────────────────────────────────────

    private fun collectExternalMedia(dbFile: File, roots: List<BackupRoot>): List<ExternalMedia> {
        val refs = LinkedHashSet<String>()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            REFERENCE_QUERIES.forEach { sql ->
                runCatching {
                    db.rawQuery(sql, null).use { c ->
                        while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { refs += it }
                    }
                }
            }
            runCatching {
                db.rawQuery(
                    "SELECT chapters_json FROM books WHERE chapters_json IS NOT NULL",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val json = c.getString(0) ?: continue
                        MEDIA_URI_REGEX.findAll(json).forEach { m -> refs += m.groupValues[1] }
                    }
                }
            }
        } finally {
            db.close()
        }

        val grouped = LinkedHashMap<String, MutableSet<String>>()
        refs.filter { PathTokens.isRelocatableRef(it, roots) }.forEach { ref ->
            grouped.getOrPut(canonicalOf(ref)) { linkedSetOf() }.add(ref)
        }

        val out = ArrayList<ExternalMedia>()
        grouped.forEach { (canonical, variants) ->
            val source = sourceFor(canonical) ?: return@forEach
            // Skip references we cannot actually read (revoked SAF grant, deleted
            // shared file). They are left in the DB untouched instead of poisoning
            // the whole backup.
            if (!isReadable(source)) return@forEach
            val bytes = sizeOf(source)
            val rel = "${shortHash(canonical)}/${safeBaseName(canonical)}"
            out += ExternalMedia(canonical, variants, bytes, rel, source)
        }
        return out
    }

    private fun canonicalOf(value: String): String = when {
        value.startsWith("file://") -> Uri.parse(value).path ?: value
        else -> value
    }

    private fun sourceFor(canonical: String): BackupSource? = runCatching {
        when {
            canonical.startsWith("content://") -> BackupSource.Content(Uri.parse(canonical))
            canonical.startsWith("/") -> BackupSource.Local(File(canonical)).takeIf { File(canonical).isFile }
            else -> null
        }
    }.getOrNull()

    private fun isReadable(source: BackupSource): Boolean = runCatching {
        when (source) {
            is BackupSource.Local -> source.file.canRead()
            is BackupSource.Content -> context.contentResolver.openInputStream(source.uri)?.use { true } ?: false
        }
    }.getOrDefault(false)

    private fun sizeOf(source: BackupSource): Long = runCatching {
        when (source) {
            is BackupSource.Local -> source.file.length()
            is BackupSource.Content -> {
                val length = context.contentResolver.openAssetFileDescriptor(source.uri, "r")
                    ?.use { it.length } ?: -1L
                if (length > 0L) length else querySize(source.uri)
            }
        }
    }.getOrDefault(0L)

    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }
            ?: 0L
    }.getOrDefault(0L)

    private fun safeBaseName(value: String): String {
        val raw = runCatching { Uri.parse(value).lastPathSegment }.getOrNull()
            ?: value.substringAfterLast('/')
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        return cleaned.ifBlank { "data" }.take(80)
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    // ── Zip writing ───────────────────────────────────────────────────────────

    private fun writeManifest(zip: ZipOutputStream, manifest: BackupManifest) {
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY).apply { time = manifest.createdAtEpochMs })
        val bytes = manifest.toJson().toByteArray(Charsets.UTF_8)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeEntry(zip: ZipOutputStream, entry: BackupEntry, onChunk: (Long) -> Unit) {
        zip.setLevel(compressionLevel(entry.name))
        zip.putNextEntry(ZipEntry(entry.name))
        val stream: InputStream = when (val source = entry.source) {
            is BackupSource.Local -> source.file.inputStream()
            is BackupSource.Content -> context.contentResolver.openInputStream(source.uri)
                ?: throw IllegalStateException("Cannot read ${source.uri}")
        }
        stream.use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                onChunk(read.toLong())
            }
        }
        zip.closeEntry()
    }

    private fun compressionLevel(name: String): Int {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext == "db" -> java.util.zip.Deflater.DEFAULT_COMPRESSION
            ext in STORE_EXTENSIONS -> java.util.zip.Deflater.NO_COMPRESSION
            else -> java.util.zip.Deflater.BEST_SPEED
        }
    }

    // ── Zip reading ───────────────────────────────────────────────────────────

    private fun resolveDestination(rel: String, tokenToRoot: Map<String, String>): File? {
        val token = tokenToRoot.keys
            .sortedByDescending { it.length }
            .firstOrNull { rel == it || rel.startsWith("$it/") }
            ?: return null
        val rootPath = tokenToRoot[token] ?: return null
        val suffix = rel.removePrefix(token).trimStart('/')
        if (suffix.contains("..")) return null
        val root = File(rootPath)
        val dest = File(root, suffix)
        return runCatching {
            if (dest.canonicalPath.startsWith(root.canonicalPath)) dest else null
        }.getOrNull()
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        onProgress: (BackupProgress) -> Unit,
        manifest: BackupManifest?,
        baseProcessed: Long,
        entriesDone: Int,
        entriesTotal: Int,
    ): Long {
        var written = 0L
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            onProgress(
                BackupProgress(
                    label = "IMPORT",
                    processedBytes = baseProcessed + written,
                    totalBytes = manifest?.totalBytes ?: 0L,
                    entriesDone = entriesDone,
                    entriesTotal = entriesTotal,
                    current = null,
                )
            )
        }
        return written
    }

    // ── Counts ────────────────────────────────────────────────────────────────

    private fun libraryCounts(dbFile: File): Map<String, Int> {
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val counts = mutableMapOf<String, Int>()
            fun count(key: String, sql: String) {
                runCatching {
                    db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) counts[key] = c.getInt(0) }
                }
            }
            count("books", "SELECT COUNT(*) FROM books WHERE is_deleted = 0")
            count("audiobooks", "SELECT COUNT(*) FROM books WHERE is_deleted = 0 AND type IN ('AUDIOBOOK','MIXED')")
            count("ebooks", "SELECT COUNT(*) FROM books WHERE is_deleted = 0")
            count("progress", "SELECT COUNT(*) FROM reading_progress WHERE progress_percent > 0")
            count("bookmarks", "SELECT COUNT(*) FROM bookmarks")
            count("highlights", "SELECT COUNT(*) FROM highlights")
            count("podcasts", "SELECT COUNT(*) FROM podcast_feeds WHERE is_followed = 1")
            count("episodes", "SELECT COUNT(*) FROM podcast_episodes")
            return counts
        } finally {
            db.close()
        }
    }
}

/** Every DB column that can hold a media reference outside app storage. */
private val REFERENCE_QUERIES = listOf(
    "SELECT file_uri FROM books WHERE file_uri IS NOT NULL",
    "SELECT file_path FROM books WHERE file_path IS NOT NULL",
    "SELECT file_uri FROM audio_tracks WHERE file_uri IS NOT NULL",
    "SELECT file_path FROM audio_tracks WHERE file_path IS NOT NULL",
    "SELECT local_uri FROM podcast_downloads WHERE local_uri IS NOT NULL",
    "SELECT staging_path FROM download_tasks WHERE staging_path IS NOT NULL",
    "SELECT save_path FROM torrent_downloads WHERE save_path IS NOT NULL",
)
