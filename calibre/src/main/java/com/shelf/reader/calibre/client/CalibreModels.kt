package com.shelf.reader.calibre.client

/** A link inside an OPDS feed or entry. */
data class OpdsLink(
    val rel: String?,
    val href: String,
    val type: String? = null,
    val title: String? = null
) {
    val isAcquisition: Boolean
        get() = rel == ACQUISITION_PREFIX || rel == "$ACQUISITION_PREFIX/open-access"

    val isNavigation: Boolean
        get() = rel == REL_SUBSECTION || rel == REL_CRAWLABLE
}

/** A single OPDS entry (a book, a series, an author, …). */
data class OpdsEntry(
    val id: String?,
    val title: String,
    val authors: List<String> = emptyList(),
    val links: List<OpdsLink> = emptyList(),
    val categories: List<String> = emptyList(),
    val language: String? = null,
    val updated: String? = null,
    val summary: String? = null,
    val publisher: String? = null,
    val series: String? = null
) {
    val navigationLinks: List<OpdsLink> get() = links.filter { it.isNavigation }

    val acquisitionLinks: List<OpdsLink> get() = links.filter { it.isAcquisition }

    val isNavigation: Boolean
        get() = navigationLinks.isNotEmpty() && acquisitionLinks.isEmpty()
}

/** A parsed OPDS feed. */
data class OpdsFeed(
    val id: String?,
    val title: String?,
    val links: List<OpdsLink> = emptyList(),
    val entries: List<OpdsEntry> = emptyList()
) {
    val nextPage: String? get() = links.firstOrNull { it.rel == REL_NEXT }?.href
    val searchTemplate: String? get() = links.firstOrNull { it.rel == REL_SEARCH }?.href
}

/** A book format Shelf is able to import and play/read. */
enum class CalibreFormat(val extension: String, val mimeTypes: Set<String>) {
    EPUB("epub", setOf("application/epub+zip", "application/epub")),
    PDF("pdf", setOf("application/pdf")),
    MOBI("mobi", setOf("application/x-mobipocket-ebook")),
    AZW3("azw3", setOf("application/vnd.amazon.ebook")),
    CBZ("cbz", setOf("application/vnd.comicbook+zip", "application/x-cbz")),
    CBR("cbr", setOf("application/vnd.comicbook-rar", "application/x-cbr")),
    M4B("m4b", setOf("audio/x-m4b", "audio/mp4", "audio/m4b")),
    M4A("m4a", setOf("audio/x-m4a")),
    MP3("mp3", setOf("audio/mpeg", "audio/mp3")),
    FLAC("flac", setOf("audio/flac", "audio/x-flac")),
    OGG("ogg", setOf("audio/ogg")),
    OPUS("opus", setOf("audio/opus")),
    WAV("wav", setOf("audio/wav", "audio/x-wav"));

    companion object {
        private val byMime: Map<String, CalibreFormat> =
            entries.flatMap { f -> f.mimeTypes.map { it.lowercase() to f } }.toMap()

        private val byExtension: Map<String, CalibreFormat> = entries.associateBy { it.extension }

        fun fromMimeType(mime: String?): CalibreFormat? {
            if (mime.isNullOrBlank()) return null
            val clean = mime.substringBefore(';').trim().lowercase()
            return byMime[clean]
        }

        fun fromFileName(name: String): CalibreFormat? =
            byExtension[name.substringAfterLast('.', "").lowercase()]

        /** Shelf imports these extensions; anything else must not be presented as importable. */
        fun isImportable(fileName: String): Boolean = fromFileName(fileName) != null
    }
}

/** A downloadable acquisition resolved from an OPDS entry. */
data class CalibreDownload(
    val href: String,
    val fileName: String,
    val format: CalibreFormat,
    val sizeBytes: Long? = null
)

const val ACQUISITION_PREFIX = "http://opds-spec.org/acquisition"
const val REL_SUBSECTION = "subsection"
const val REL_CRAWLABLE = "http://opds-spec.org/crawlable"
const val REL_NEXT = "next"
const val REL_SEARCH = "search"