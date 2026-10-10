package com.bookrio.app.backup

import org.json.JSONObject

/**
 * On-disk contract for a Bookiro archive.
 *
 * A backup is a single ZIP stream:
 *
 * ```
 * manifest.json            - this metadata (first entry, so import can plan)
 * db/shelf.db              - consistent SQLite snapshot, app paths tokenized
 * media/@FILES@/<rel>      - app-private files (covers, converted, downloads, …)
 * media/@EXT@/<rel>        - external app files dir (e.g. unpacked imports)
 * media/@RESTORE@/<hash>/  - files that lived outside app storage (SAF/shared),
 *                            relocated into the app on restore
 * ```
 *
 * Paths inside the DB are rewritten to tokens (`@FILES@`, …, `@RESTORE@/…`)
 * during export and resolved to the *current* app roots during import, so a
 * backup survives an uninstall/reinstall and even a package-name change
 * (`com.bookiro` <-> `com.bookiro.play`).
 */
object BackupFormat {
    const val FORMAT = "bookiro.backup"
    const val FORMAT_VERSION = 1

    const val MANIFEST_ENTRY = "manifest.json"
    const val DB_ENTRY = "db/shelf.db"
    const val MEDIA_DIR = "media/"

    /** App-private files root (`Context.getFilesDir()`). */
    const val TOKEN_FILES = "@FILES@"

    /** Primary external files root (`Context.getExternalFilesDir(null)`). */
    const val TOKEN_EXT = "@EXT@"

    /** Prefix for more external volumes: `@EXT1@`, `@EXT2@`, … */
    const val TOKEN_EXT_PREFIX = "@EXT"

    /** Files copied from outside app storage (SAF `content://`, shared paths). */
    const val TOKEN_RESTORE = "@RESTORE@"

    /** Archive name root, timestamp is appended by the engine. */
    const val FILE_PREFIX = "bookiro-backup-"
    const val FILE_EXTENSION = ".zip"

    fun backupFileName(epochMs: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return "$FILE_PREFIX${fmt.format(java.util.Date(epochMs))}$FILE_EXTENSION"
    }
}

/** Metadata written to `manifest.json`; also the import's progress contract. */
data class BackupManifest(
    val formatVersion: Int,
    val createdAtEpochMs: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val applicationId: String,
    val dbVersion: Int,
    val includeMedia: Boolean,
    val counts: Map<String, Int>,
    val roots: Map<String, String>,
    val totalBytes: Long,
    val totalEntries: Int,
) {
    fun toJson(): String {
        val countsObj = JSONObject().apply {
            counts.forEach { (k, v) -> put(k, v) }
        }
        val rootsObj = JSONObject().apply {
            roots.forEach { (k, v) -> put(k, v) }
        }
        return JSONObject()
            .put("format", BackupFormat.FORMAT)
            .put("formatVersion", formatVersion)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("appVersionName", appVersionName)
            .put("appVersionCode", appVersionCode)
            .put("applicationId", applicationId)
            .put("dbVersion", dbVersion)
            .put("includeMedia", includeMedia)
            .put("counts", countsObj)
            .put("roots", rootsObj)
            .put("totalBytes", totalBytes)
            .put("totalEntries", totalEntries)
            .toString(2)
    }

    companion object {
        fun fromJson(text: String): BackupManifest? = runCatching {
            val obj = JSONObject(text)
            require(obj.optString("format") == BackupFormat.FORMAT) { "not a Bookiro archive" }
            val countsJson = obj.optJSONObject("counts")
            val counts = buildMap {
                countsJson?.keys()?.forEach { key -> put(key, countsJson.optInt(key)) }
            }
            val rootsJson = obj.optJSONObject("roots")
            val roots = buildMap {
                rootsJson?.keys()?.forEach { key -> put(key, rootsJson.optString(key)) }
            }
            BackupManifest(
                formatVersion = obj.optInt("formatVersion", -1),
                createdAtEpochMs = obj.optLong("createdAtEpochMs", 0L),
                appVersionName = obj.optString("appVersionName", ""),
                appVersionCode = obj.optInt("appVersionCode", 0),
                applicationId = obj.optString("applicationId", ""),
                dbVersion = obj.optInt("dbVersion", -1),
                includeMedia = obj.optBoolean("includeMedia", false),
                counts = counts,
                roots = roots,
                totalBytes = obj.optLong("totalBytes", 0L),
                totalEntries = obj.optInt("totalEntries", 0),
            )
        }.getOrNull()

        fun current(
            appVersionName: String,
            appVersionCode: Int,
            applicationId: String,
            dbVersion: Int,
            includeMedia: Boolean,
            counts: Map<String, Int>,
            roots: Map<String, String>,
            totalBytes: Long,
            totalEntries: Int,
        ): BackupManifest = BackupManifest(
            formatVersion = BackupFormat.FORMAT_VERSION,
            createdAtEpochMs = System.currentTimeMillis(),
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            applicationId = applicationId,
            dbVersion = dbVersion,
            includeMedia = includeMedia,
            counts = counts,
            roots = roots,
            totalBytes = totalBytes,
            totalEntries = totalEntries,
        )
    }
}

/** Live progress emitted by backup/restore, throttled by the caller. */
data class BackupProgress(
    val label: String,
    val processedBytes: Long,
    val totalBytes: Long,
    val entriesDone: Int,
    val entriesTotal: Int,
    val current: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (processedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

sealed interface BackupOutcome {
    /** Export finished; [location] is a human-readable display path. */
    data class Exported(val location: String, val bytes: Long, val entries: Int) : BackupOutcome

    /** Import finished; the app must restart before the new database is used. */
    data class Imported(
        val bookCount: Int,
        val episodeCount: Int,
        val mediaFiles: Int,
        val missingMedia: Int,
        val applicationId: String,
    ) : BackupOutcome

    data class Failed(val message: String, val cause: Throwable? = null) : BackupOutcome
}

/** Small, immutable description of one archive entry source. */
internal sealed interface BackupSource {
    data class Local(val file: java.io.File) : BackupSource
    data class Content(val uri: android.net.Uri) : BackupSource
}

/** One planned archive entry. */
internal data class BackupEntry(
    val name: String,
    val source: BackupSource,
    val bytes: Long,
)

/** A file referenced by the DB that is not under an app root (SAF/shared). */
internal data class ExternalMedia(
    val canonical: String,
    val refs: Set<String>,
    val bytes: Long,
    val relPath: String,
    val source: BackupSource,
)

/** Convenience: a compact, human-readable summary of a count map. */
fun Map<String, Int>.summary(): String =
    entries.joinToString(" · ") { (k, v) -> "$k=$v" }
