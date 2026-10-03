package com.bookrio.reader.readium

import android.content.Context
import android.util.Log
import com.bookrio.reader.R
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Opens an EPUB (or other Readium-supported publication) using the Readium Kotlin
 * Toolkit 3.0.3 `PublicationOpener`.
 *
 * Sources are either a local [filePath] (books imported from a file picker / copied
 * to storage) or a SAF [fileUri] `content://` URI (books imported from a library
 * folder). Readium reads the URI directly through its ContentResolver resource
 * factory, so scoped-storage EPUBs no longer need a local copy.
 *
 * Readium does the EPUB parsing and layout; Bookiro does not own pagination.
 * PDF factory is intentionally null here — PDF/CBZ keep the existing Bookiro
 * renderer and are not routed through this opener.
 */
internal object ReadiumPublicationOpener {

    private const val TAG = "ReadiumOpener"

    suspend fun open(context: Context, filePath: String?, fileUri: String?): Result<Publication> {
        return runCatching {
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
            val parser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
            )
            val opener = PublicationOpener(parser)

            val asset = when {
                !filePath.isNullOrBlank() -> {
                    val file = File(filePath)
                    if (!file.canRead()) {
                        error(context.getString(R.string.rdr_error_cannot_read_file_path, filePath))
                    }
                    assetRetriever.retrieve(file).getOrElse { error ->
                        Log.e(TAG, "asset retrieve failed: $error")
                        error(context.getString(R.string.rdr_error_cannot_read_book, error))
                    }
                }
                !fileUri.isNullOrBlank() -> {
                    val url = AbsoluteUrl(fileUri)
                        ?: error(context.getString(R.string.rdr_error_file_not_found))
                    assetRetriever.retrieve(url).getOrElse { error ->
                        Log.e(TAG, "asset retrieve (uri) failed: $error")
                        error(context.getString(R.string.rdr_error_cannot_read_book, error))
                    }
                }
                else -> error(context.getString(R.string.rdr_error_file_not_found))
            }

            opener.open(asset, allowUserInteraction = true).getOrElse { error ->
                Log.e(TAG, "publication open failed: $error")
                error(context.getString(R.string.rdr_error_cannot_open_book_reason, error))
            }
        }
    }
}