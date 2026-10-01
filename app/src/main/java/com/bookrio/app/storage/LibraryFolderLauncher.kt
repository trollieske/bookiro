package com.bookrio.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Åpner bibliotekmappen (en SAF tree-URI) i systemets filbehandler.
 *
 * Rekkefølge, første som lykkes vinner:
 *  1. VIEW på selve mappe-dokumentet med directory-MIME — håndteres av
 *     DocumentsUI (verifisert på stock API 35) og de fleste OEM-filbehandlere.
 *  2. VIEW på dokument-URI-en med «resource/folder» — enkelte OEM-filbehandlere.
 *
 * Resultatet skiller «ingen behandler» fra «mistenkt tilgang», slik at UI-et kan
 * si ifra på riktig måte i stedet for å gjette.
 */
object LibraryFolderLauncher {

    /** MIME-type enkelte OEM-filbehandlere bruker for mapper. */
    private const val RESOURCE_FOLDER = "resource/folder"

    enum class Result { OK, NO_HANDLER, NO_PERMISSION }

    fun open(ctx: Context, treeUri: Uri): Result {
        val docUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrNull()

        // Fallbacken bruker dokument-URI-en når den finnes; tree-URI-en er en
        // dårlig match for «resource/folder»-behandlere.
        val folderUri = docUri ?: treeUri
        val attempts = buildList {
            if (docUri != null) {
                add(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
            add(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(folderUri, RESOURCE_FOLDER)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }

        for (intent in attempts) {
            when (val outcome = ctx.launchSafely(intent)) {
                Result.OK -> return Result.OK
                Result.NO_PERMISSION -> return Result.NO_PERMISSION
                Result.NO_HANDLER -> continue
            }
        }
        return Result.NO_HANDLER
    }

    private fun Context.launchSafely(intent: Intent): Result = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Result.OK
    } catch (_: SecurityException) {
        // URI-tilgangen er borte (f.eks. etter en gjenoppretting) — ikke «ingen app».
        Result.NO_PERMISSION
    } catch (_: Exception) {
        Result.NO_HANDLER
    }
}