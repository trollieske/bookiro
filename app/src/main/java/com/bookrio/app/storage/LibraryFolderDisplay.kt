package com.bookrio.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.bookrio.core.storage.LibraryFolderNames

/**
 * Human-readable name for a SAF library-folder tree URI.
 *
 * [LibraryFolderNames.treeDocumentPath] parses the URI path, which works for the
 * external-storage provider (`primary:Books/Bookiro`) but not for providers whose
 * document id is numeric — e.g. the Downloads/MediaStore tree
 * `…/tree/msf%3A11365` decoded to `11365`. There we must ask the provider for the
 * document's DISPLAY_NAME.
 */
object LibraryFolderDisplay {

    fun nameFor(context: Context, treeUriString: String?): String? {
        val raw = treeUriString?.trim().orEmpty()
        if (raw.isBlank()) return null

        var fromProvider: String? = null
        runCatching {
            val tree = Uri.parse(raw)
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) fromProvider = c.getString(0)
            }
        }
        if (!fromProvider.isNullOrBlank()) return fromProvider

        return LibraryFolderNames.treeDocumentPath(raw)
    }
}