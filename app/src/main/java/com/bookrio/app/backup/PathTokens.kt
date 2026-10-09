package com.bookrio.app.backup

/** One app-owned storage root and the token that stands in for it in an archive. */
data class BackupRoot(val token: String, val path: String)

/**
 * Pure, JVM-testable path tokenization used to make a backup independent of the
 * install's package name and data directory.
 *
 * Absolute app paths are replaced with `@FILES@` / `@EXT@` / … before the archive
 * is written, and the tokens are resolved to the *current* roots on restore. A
 * file that lived outside app storage is relocated via `@RESTORE@`.
 */
object PathTokens {

    fun tokenize(value: String, roots: List<BackupRoot>): String {
        var out = value
        roots.sortedByDescending { it.path.length }.forEach { root ->
            if (root.path.isNotBlank()) out = out.replace(root.path, root.token)
        }
        return out
    }

    fun resolve(value: String, roots: List<BackupRoot>): String {
        var out = value
        roots.sortedByDescending { it.token.length }.forEach { root ->
            out = out.replace(root.token, root.path)
        }
        return out
    }

    /** Streams/URLs that are resolved at playback time and never archived. */
    fun isRemote(value: String): Boolean {
        val v = value.trim().lowercase()
        return v.startsWith("http://") || v.startsWith("https://") ||
            v.startsWith("magnet:") || v.startsWith("rtsp://") || v.startsWith("rtmp://")
    }

    fun isUnderAppRoot(value: String, roots: List<BackupRoot>): Boolean =
        roots.any { it.path.isNotBlank() && value.contains(it.path) }

    /**
     * A readable local reference that is not app-owned and therefore must be
     * copied into the archive (SAF `content://`, `file://`, or a raw path).
     */
    fun isRelocatableRef(value: String, roots: List<BackupRoot>): Boolean {
        if (value.isBlank() || isRemote(value)) return false
        if (isUnderAppRoot(value, roots)) return false
        val v = value.trim()
        return v.startsWith("content://") || v.startsWith("file://") || v.startsWith("/")
    }
}
