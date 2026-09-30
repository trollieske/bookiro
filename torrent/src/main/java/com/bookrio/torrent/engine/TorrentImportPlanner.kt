package com.bookrio.torrent.engine

import com.bookrio.core.domain.model.BookFormat
import java.io.File

/**
 * One file as declared by the torrent metadata (`TorrentInfo.files()`).
 *
 * Pure data so that the import plan can be unit-tested on the JVM without the
 * libtorrent4j native session.
 */
data class TorrentFileSpec(
    val relativePath: String,
    val sizeBytes: Long,
    val isPadFile: Boolean = false
)

/** A torrent-owned file with the size the torrent metadata requires. */
data class TorrentPlannedFile(
    val file: File,
    val expectedSizeBytes: Long,
    val isPadFile: Boolean = false
)

/**
 * How a completed torrent's own files are handed to the canonical importer.
 *
 * DIRECTORY: scan [TorrentImportPlan.target] (the torrent's own file/folder).
 * FILE_LIST: pass the exact [TorrentImportPlan.files] list to `importUris`
 * because the own files share the (possibly shared) save root as parent.
 */
enum class TorrentImportScope { DIRECTORY, FILE_LIST }

/**
 * Import scope for ONE completed torrent, derived only from the torrent's own
 * file set. It never points at the shared save root when the torrent has its own
 * subfolder or is a single file, so completing torrent B can no longer import
 * torrent A's still-downloading siblings.
 *
 * [target] is only used when [scope] is DIRECTORY; FILE_LIST hands the exact
 * [files] list to `importUris` instead.
 */
data class TorrentImportPlan(
    val entries: List<TorrentPlannedFile>,
    val target: File,
    val scope: TorrentImportScope,
    /**
     * Every declared torrent file under the save root, pads included. Compared
     * against the folder contents by [foreignImportCandidates] so a DIRECTORY
     * scan can be rejected when another torrent shares the same folder name.
     */
    val declaredFiles: List<File> = emptyList()
) {
    val files: List<File> get() = entries.map { it.file }
}

/** Result of checking the torrent's own files on disk before hand-off. */
data class TorrentFileVerification(
    val missing: List<File>,
    val wrongSize: List<File>,
    val checkedCount: Int = 0
) {
    /** An empty file set is never complete: there is nothing to import. */
    val complete: Boolean
        get() = checkedCount > 0 && missing.isEmpty() && wrongSize.isEmpty()
}

object TorrentImportPlanner {

    /** Marker prefix for a torrent whose import already ran (idempotence guard). */
    const val IMPORTED_PREFIX = "IMPORTED"

    /** Recorded when the torrent's own files are missing or not fully written yet. */
    const val STATUS_VERIFY_FAILED = "VERIFY_FAILED"

    /** Recorded when the canonical importer itself threw. */
    const val STATUS_IMPORT_FAILED = "IMPORT_FAILED"

    /** Recorded when the session cannot expose the torrent file list (never scan the shared root instead). */
    const val STATUS_METADATA_UNAVAILABLE = "METADATA_UNAVAILABLE"

    /**
     * Builds the import scope for one torrent from its declared file set.
     *
     * - single file -> hand the file itself to the importer;
     * - several files sharing one subfolder below the save root -> scan that folder;
     * - files that share only the (possibly shared) save root -> exact file list,
     *   so the shared root is never scanned.
     *
     * Pure: no disk access, no libtorrent bindings.
     */
    fun plan(savePath: File, specs: List<TorrentFileSpec>): TorrentImportPlan {
        val root = savePath.absoluteFile
        val declared = specs
            .asSequence()
            .filter { isSafeRelativePath(it.relativePath) }
            .map { TorrentPlannedFile(File(root, it.relativePath), it.sizeBytes, it.isPadFile) }
            .toList()
        val entries = declared.filter { !it.isPadFile }

        if (entries.isEmpty()) {
            return TorrentImportPlan(emptyList(), root, TorrentImportScope.DIRECTORY, declared.map { it.file })
        }
        if (entries.size == 1) {
            return TorrentImportPlan(
                entries,
                entries[0].file,
                TorrentImportScope.DIRECTORY,
                declared.map { it.file }
            )
        }

        val ownFolder = commonParent(entries.map { it.file })
        val scope = if (ownFolder != null && ownFolder.absolutePath != root.absolutePath) {
            TorrentImportScope.DIRECTORY
        } else {
            TorrentImportScope.FILE_LIST
        }
        val target = if (scope == TorrentImportScope.DIRECTORY) ownFolder!! else root
        return TorrentImportPlan(entries, target, scope, declared.map { it.file })
    }

    /**
     * Downgrades a DIRECTORY plan to FILE_LIST when the folder is not exclusive:
     * a DIRECTORY hand-off calls `walkTopDown()` on [TorrentImportPlan.target], so
     * if ANY importable candidate under it is not one of this torrent's declared
     * files (companions and pad files included), the folder contains foreign
     * files — e.g. a second torrent that happens to use the same subfolder name.
     *
     * Pure w.r.t. the plan: the candidate list comes from
     * [foreignImportCandidates] and can also be supplied by a test directly.
     */
    fun resolveScope(plan: TorrentImportPlan, foreignImportCandidates: List<File>): TorrentImportPlan {
        if (plan.scope != TorrentImportScope.DIRECTORY || foreignImportCandidates.isEmpty()) return plan
        return plan.copy(scope = TorrentImportScope.FILE_LIST)
    }

    /**
     * Files under [target] that the canonical importer would treat as books/audio
     * but that are NOT part of [ownFiles]. Empty means the folder is exclusive to
     * this torrent and a DIRECTORY scan is safe (and keeps `.cue` companions
     * working). Non-candidate companions (`.nfo`, images, …) do not trigger the
     * fallback.
     */
    fun foreignImportCandidates(target: File, ownFiles: Collection<File>): List<File> {
        if (!target.isDirectory) return emptyList()
        val ownPaths = ownFiles.mapTo(HashSet()) { it.absolutePath }
        return target.walkTopDown()
            .filter { it.isFile }
            .filter { it.absolutePath !in ownPaths }
            .filter { isImportCandidate(it.name) }
            .toList()
    }

    /** Mirrors what `importDirectoryOrArchive` would pick up from a folder scan. */
    internal fun isImportCandidate(fileName: String): Boolean {
        val lower = fileName.lowercase()
        if (lower.endsWith(".cue")) return true
        return BookFormat.fromFilename(fileName) != BookFormat.UNKNOWN
    }

    /**
     * Exact-length verification mirroring the FTP hand-off check: a file is
     * complete only when it exists and its on-disk length equals the metadata
     * size. Expected size 0 means "any length" (empty companion files).
     */
    fun verify(entries: List<TorrentPlannedFile>): TorrentFileVerification {
        val missing = mutableListOf<File>()
        val wrongSize = mutableListOf<File>()
        for (entry in entries) {
            if (!entry.file.isFile) {
                missing.add(entry.file)
                continue
            }
            if (entry.expectedSizeBytes > 0L && entry.file.length() != entry.expectedSizeBytes) {
                wrongSize.add(entry.file)
            }
        }
        return TorrentFileVerification(missing, wrongSize, checkedCount = entries.size)
    }

    /** True once the import for this torrent ran (status is set only after hand-off). */
    fun isAlreadyImported(importStatus: String?): Boolean =
        importStatus?.startsWith(IMPORTED_PREFIX) == true

    /**
     * A COMPLETED torrent whose import never finished (crashed app, failed
     * verify) is retried on the next engine start. Successfully imported
     * torrents and rows the user excluded from auto-import are never retried.
     */
    fun shouldRetryImport(autoImport: Boolean, importStatus: String?): Boolean =
        autoImport && !isAlreadyImported(importStatus)

    fun importedStatus(bookCount: Int): String = "$IMPORTED_PREFIX:$bookCount"

    /** Observable failure, counts only — never paths or torrent file names. */
    fun verifyFailedStatus(missingCount: Int, wrongSizeCount: Int): String =
        "$STATUS_VERIFY_FAILED:$missingCount:$wrongSizeCount"

    /** Rejects absolute paths and `..` traversal so metadata cannot escape the save path. */
    internal fun isSafeRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.startsWith("/") || relativePath.contains('\\')) return false
        val segments = relativePath.split('/')
        return segments.isNotEmpty() && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Deepest directory that contains every given file, or null when there is none. */
    private fun commonParent(files: List<File>): File? {
        var candidate = files.first().parentFile ?: return null
        for (file in files) {
            var parent = file.parentFile ?: return null
            while (parent.absolutePath != candidate.absolutePath &&
                !parent.absolutePath.startsWith(candidate.absolutePath + File.separator)
            ) {
                candidate = candidate.parentFile ?: return null
            }
        }
        return candidate
    }
}
