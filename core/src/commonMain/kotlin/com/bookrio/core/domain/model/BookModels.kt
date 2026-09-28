package com.bookrio.core.domain.model

enum class BookType { EBOOK, AUDIOBOOK, MIXED }

enum class BookFormat(val ext: Set<String>, val isAudio: Boolean) {
    EPUB(setOf("epub"), false),
    PDF(setOf("pdf"), false),
    MOBI(setOf("mobi", "prc"), false),
    AZW(setOf("azw"), false),
    AZW3(setOf("azw3", "kf8"), false),
    FB2(setOf("fb2", "fb2.zip"), false),
    CBZ(setOf("cbz"), false),
    CBR(setOf("cbr"), false),
    TXT(setOf("txt"), false),
    HTML(setOf("html", "htm", "xhtml"), false),
    RTF(setOf("rtf"), false),
    DOCX(setOf("docx"), false),
    MD(setOf("md", "markdown"), false),

    M4B(setOf("m4b"), true),
    M4A(setOf("m4a"), true),
    MP3(setOf("mp3"), true),
    AAC(setOf("aac"), true),
    FLAC(setOf("flac"), true),
    OGG(setOf("ogg"), true),
    OPUS(setOf("opus"), true),
    WAV(setOf("wav"), true),
    ZIP(setOf("zip"), false),
    UNKNOWN(emptySet(), false);

    val primaryExt: String get() = ext.firstOrNull() ?: name.lowercase()

    companion object {
        private val byExt: Map<String, BookFormat> = entries
            .flatMap { f -> f.ext.map { it to f } }
            .toMap()

        fun fromFilename(filename: String): BookFormat {
            val lower = filename.lowercase()
            if (lower.endsWith(".fb2.zip")) return FB2
            val dot = lower.lastIndexOf('.')
            if (dot < 0) return UNKNOWN
            val ext = lower.substring(dot + 1)
            return byExt[ext] ?: UNKNOWN
        }

        val SUPPORTED_BOOK: Set<BookFormat> =
            setOf(EPUB, PDF, MOBI, AZW, AZW3, FB2, CBZ, CBR, TXT, HTML, RTF, DOCX, MD, ZIP)
        val SUPPORTED_AUDIO: Set<BookFormat> =
            setOf(M4B, M4A, MP3, AAC, FLAC, OGG, OPUS, WAV)
        val ALL_SUPPORTED: Set<BookFormat> = SUPPORTED_BOOK + SUPPORTED_AUDIO
    }
}

enum class LibraryViewType(val storageKey: String) {
    SHELF("shelf"), GRID("grid"), LIST("list");

    companion object {
        fun fromStorage(key: String?): LibraryViewType =
            key?.let { k -> entries.firstOrNull { it.storageKey == k } } ?: SHELF
    }
}

enum class AutoShelf(val id: String, val label: String) {
    RECENTLY_ADDED("auto_recent", "Nylig lagt til"),
    IN_PROGRESS("auto_progress", "Pågår"),
    FINISHED("auto_finished", "Ferdig"),
    AUDIOBOOKS("auto_audio", "Lydbøker"),
    EBOOKS("auto_books", "Ebøker"),
    FAVORITES("auto_favs", "Favoritter"),
    SERIES("auto_series", "Serier");

    companion object {
        fun fromId(id: String): AutoShelf? = entries.firstOrNull { it.id == id }
    }
}

enum class DarkModePref(val intValue: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2),
    TRUE_BLACK(3);

    companion object {
        fun fromInt(i: Int): DarkModePref = entries.firstOrNull { it.intValue == i } ?: FOLLOW_SYSTEM
    }
}

data class ChapterInfo(
    val title: String,
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val index: Int = 0,
    val href: String? = null
)

data class BookMetadata(
    val title: String?,
    val author: String?,
    val series: String?,
    val seriesIndex: Float?,
    val description: String?,
    val publisher: String?,
    val publishedDate: String?,
    val language: String?,
    val isbn: String?,
    val pageCount: Int?,
    val durationMs: Long?,
    val chapters: List<ChapterInfo>,
    val album: String? = null,
    val albumArtist: String? = null
)
