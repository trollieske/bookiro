package com.shelf.reader.calibre.client

/**
 * Picks the best importable acquisition from an OPDS entry.
 *
 * Buy/borrow links and formats Shelf cannot import or play are ignored, so the
 * UI never offers a download that would fail later.
 */
object CalibreAcquisition {

    private val PREFERRED = listOf(
        CalibreFormat.EPUB,
        CalibreFormat.M4B,
        CalibreFormat.MP3,
        CalibreFormat.M4A,
        CalibreFormat.PDF,
        CalibreFormat.MOBI,
        CalibreFormat.AZW3,
        CalibreFormat.CBZ,
        CalibreFormat.CBR,
        CalibreFormat.FLAC,
        CalibreFormat.OGG,
        CalibreFormat.OPUS,
        CalibreFormat.WAV
    )

    fun best(entry: OpdsEntry): CalibreDownload? {
        val resolved = entry.acquisitionLinks.mapNotNull { link ->
            val format = CalibreFormat.fromMimeType(link.type) ?: CalibreFormat.fromFileName(link.href)
                ?: return@mapNotNull null
            CalibreDownload(
                href = link.href,
                fileName = fileNameFor(entry, link, format),
                format = format,
                sizeBytes = null
            )
        }
        return PREFERRED.firstNotNullOfOrNull { format ->
            resolved.firstOrNull { it.format == format }
        }
    }

    /** All distinct importable formats, in preference order. */
    fun all(entry: OpdsEntry): List<CalibreDownload> {
        val resolved = entry.acquisitionLinks.mapNotNull { link ->
            val format = CalibreFormat.fromMimeType(link.type) ?: CalibreFormat.fromFileName(link.href)
                ?: return@mapNotNull null
            CalibreDownload(
                href = link.href,
                fileName = fileNameFor(entry, link, format),
                format = format
            )
        }
        return PREFERRED.mapNotNull { format -> resolved.firstOrNull { it.format == format } }
    }

    fun fileNameFor(entry: OpdsEntry, link: OpdsLink, format: CalibreFormat): String {
        val fromHref = link.href.substringAfterLast('/').substringBefore('?')
            .takeIf { it.contains('.') && it.substringAfterLast('.').equals(format.extension, ignoreCase = true) }
        val base = fromHref?.substringBeforeLast('.')
            ?: link.title?.takeIf { it.isNotBlank() }
            ?: entry.title
        return "${sanitize(base)}.${format.extension}"
    }

    fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[/\\\\\\u0000\\r\\n]"), "_")
            .trim()
            .trim('.')
            .take(120)
        return cleaned.ifBlank { "calibre-book" }
    }
}