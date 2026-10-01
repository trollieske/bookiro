package com.bookrio.reader.readium

import android.content.Context
import android.util.Log
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Opens an EPUB (or other Readium-supported publication) from a local file using
 * the Readium Kotlin Toolkit 3.0.3 `PublicationOpener`.
 *
 * Readium does the EPUB parsing and layout; Bookiro does not own pagination.
 * PDF factory is intentionally null here — PDF/CBZ keep the existing Bookiro
 * renderer and are not routed through this opener.
 */
internal object ReadiumPublicationOpener {

    private const val TAG = "ReadiumOpener"

    suspend fun open(context: Context, filePath: String): Result<Publication> {
        val file = File(filePath)
        if (!file.canRead()) {
            return Result.failure(IllegalStateException("Filen kan ikke leses: $filePath"))
        }
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
            val asset = assetRetriever.retrieve(file).getOrElse { error ->
                Log.e(TAG, "asset retrieve failed: $error")
                error("Kan ikke lese boken: $error")
            }
            opener.open(asset, allowUserInteraction = true).getOrElse { error ->
                Log.e(TAG, "publication open failed: $error")
                error("Kan ikke åpne boken: $error")
            }
        }
    }
}