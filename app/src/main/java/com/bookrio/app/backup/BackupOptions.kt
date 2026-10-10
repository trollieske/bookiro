package com.bookrio.app.backup

import org.json.JSONObject

/** What a backup includes. The archive is always valid for its selection. */
data class BackupOptions(
    val includeCovers: Boolean = true,
    val includeConverted: Boolean = true,
    val includeRemoteDownloads: Boolean = true,
    val includeTorrents: Boolean = true,
    val includePodcastDownloads: Boolean = true,
    val includeExternalMedia: Boolean = true,
    val includeSources: Boolean = true,
    val includeReadingHistory: Boolean = true,
    val includeAnnotations: Boolean = true,
) {
    /** True when any bulk media category is selected (drives the size warning). */
    val includesMedia: Boolean
        get() = includeCovers || includeConverted || includeRemoteDownloads ||
            includeTorrents || includePodcastDownloads || includeExternalMedia

    fun toJson(): String = JSONObject()
        .put("covers", includeCovers)
        .put("converted", includeConverted)
        .put("remote", includeRemoteDownloads)
        .put("torrents", includeTorrents)
        .put("podcasts", includePodcastDownloads)
        .put("external", includeExternalMedia)
        .put("sources", includeSources)
        .put("history", includeReadingHistory)
        .put("annotations", includeAnnotations)
        .toString()

    companion object {
        fun fromJson(text: String?): BackupOptions {
            if (text.isNullOrBlank()) return BackupOptions()
            return runCatching {
                val o = JSONObject(text)
                BackupOptions(
                    includeCovers = o.optBoolean("covers", true),
                    includeConverted = o.optBoolean("converted", true),
                    includeRemoteDownloads = o.optBoolean("remote", true),
                    includeTorrents = o.optBoolean("torrents", true),
                    includePodcastDownloads = o.optBoolean("podcasts", true),
                    includeExternalMedia = o.optBoolean("external", true),
                    includeSources = o.optBoolean("sources", true),
                    includeReadingHistory = o.optBoolean("history", true),
                    includeAnnotations = o.optBoolean("annotations", true),
                )
            }.getOrDefault(BackupOptions())
        }

        fun everything() = BackupOptions()

        fun libraryOnly() = BackupOptions(
            includeCovers = true,
            includeConverted = true,
            includeRemoteDownloads = false,
            includeTorrents = false,
            includePodcastDownloads = false,
            includeExternalMedia = false,
            includeSources = false,
            includeReadingHistory = false,
            includeAnnotations = true,
        )
    }
}

/** Which preset the user last picked; CUSTOM exposes the individual toggles. */
enum class BackupPreset { EVERYTHING, LIBRARY_ONLY, CUSTOM }

object BackupPresets {
    fun apply(preset: BackupPreset, current: BackupOptions): BackupOptions = when (preset) {
        BackupPreset.EVERYTHING -> BackupOptions.everything()
        BackupPreset.LIBRARY_ONLY -> BackupOptions.libraryOnly()
        BackupPreset.CUSTOM -> current
    }

    /** Best-effort reverse lookup so a restored options blob selects the right chip. */
    fun match(options: BackupOptions): BackupPreset = when {
        options == BackupOptions.everything() -> BackupPreset.EVERYTHING
        options == BackupOptions.libraryOnly() -> BackupPreset.LIBRARY_ONLY
        else -> BackupPreset.CUSTOM
    }
}

/** Bulk media categories carried by the archive, used for filtering. */
enum class MediaCategory { CORE, COVERS, CONVERTED, REMOTE, TORRENT, PODCAST, EXTERNAL }

/** Whether [options] wants [category] archived. */
fun BackupOptions.includes(category: MediaCategory): Boolean = when (category) {
    MediaCategory.CORE -> true
    MediaCategory.COVERS -> includeCovers
    MediaCategory.CONVERTED -> includeConverted
    MediaCategory.REMOTE -> includeRemoteDownloads
    MediaCategory.TORRENT -> includeTorrents
    MediaCategory.PODCAST -> includePodcastDownloads
    MediaCategory.EXTERNAL -> includeExternalMedia
}
