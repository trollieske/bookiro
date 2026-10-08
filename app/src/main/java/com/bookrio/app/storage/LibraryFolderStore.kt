package com.bookrio.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bookrio.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.first

/**
 * Single place that owns the persisted SAF grant for the library folder.
 * Shared by the first-run onboarding and the Settings screen so the two never
 * disagree about what "folder set" means.
 */
object LibraryFolderStore {

    /**
     * Persists [uri] as the library folder and keeps read (and, when possible,
     * write) access across restarts. Returns false when no persistable grant
     * could be taken, so callers can surface real failure instead of false success.
     * Passing null clears the folder.
     */
    suspend fun setLibraryFolder(
        ctx: Context,
        prefs: UserPreferencesRepository,
        uri: Uri?
    ): Boolean {
        val previous = runCatching { prefs.libraryFolderUri.first() }.getOrNull()
        if (uri == null) {
            releasePersistedGrant(ctx, previous)
            prefs.setLibraryFolderUri(null)
            return true
        }
        // Write access is not always granted; fall back to read-only.
        val granted = runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess || runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!granted) return false
        if (previous != null && previous != uri.toString()) releasePersistedGrant(ctx, previous)
        prefs.setLibraryFolderUri(uri.toString())
        return true
    }

    /** Releases an old persistable URI grant so we do not leak the grant budget. */
    fun releasePersistedGrant(ctx: Context, uriString: String?) {
        val parsed = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        runCatching {
            ctx.contentResolver.releasePersistableUriPermission(
                parsed,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}