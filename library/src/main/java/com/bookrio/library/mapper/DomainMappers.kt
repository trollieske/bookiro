package com.bookrio.library.mapper

import androidx.compose.ui.graphics.Color
import com.bookrio.core.domain.model.BookFormat as CoreBookFormat
import com.bookrio.core.domain.model.BookType as CoreBookType
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.BookTypeEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.designsystem.components.BookFormat as UiBookFormat
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.designsystem.theme.ShelfColors
import kotlin.math.abs
import kotlin.math.sin

object DomainMappers {

    fun bookEntityTypeToCore(t: BookTypeEntity): CoreBookType = when (t) {
        BookTypeEntity.EBOOK -> CoreBookType.EBOOK
        BookTypeEntity.AUDIOBOOK -> CoreBookType.AUDIOBOOK
        BookTypeEntity.MIXED -> CoreBookType.MIXED
    }

    fun coreTypeToEntity(t: CoreBookType): BookTypeEntity = when (t) {
        CoreBookType.EBOOK -> BookTypeEntity.EBOOK
        CoreBookType.AUDIOBOOK -> BookTypeEntity.AUDIOBOOK
        CoreBookType.MIXED -> BookTypeEntity.MIXED
    }

    fun formatEntityToCore(f: FormatEntity): CoreBookFormat = when (f) {
        FormatEntity.EPUB -> CoreBookFormat.EPUB
        FormatEntity.PDF -> CoreBookFormat.PDF
        FormatEntity.MOBI -> CoreBookFormat.MOBI
        FormatEntity.AZW -> CoreBookFormat.AZW
        FormatEntity.AZW3 -> CoreBookFormat.AZW3
        FormatEntity.FB2 -> CoreBookFormat.FB2
        FormatEntity.CBZ -> CoreBookFormat.CBZ
        FormatEntity.CBR -> CoreBookFormat.CBR
        FormatEntity.TXT -> CoreBookFormat.TXT
        FormatEntity.HTML -> CoreBookFormat.HTML
        FormatEntity.RTF -> CoreBookFormat.RTF
        FormatEntity.DOCX -> CoreBookFormat.DOCX
        FormatEntity.MD -> CoreBookFormat.MD
        FormatEntity.M4B -> CoreBookFormat.M4B
        FormatEntity.M4A -> CoreBookFormat.M4A
        FormatEntity.MP3 -> CoreBookFormat.MP3
        FormatEntity.AAC -> CoreBookFormat.AAC
        FormatEntity.FLAC -> CoreBookFormat.FLAC
        FormatEntity.OGG -> CoreBookFormat.OGG
        FormatEntity.OPUS -> CoreBookFormat.OPUS
        FormatEntity.OGG_OPUS -> CoreBookFormat.OPUS
        FormatEntity.WAV -> CoreBookFormat.WAV
        FormatEntity.ZIP -> CoreBookFormat.ZIP
        FormatEntity.UNKNOWN -> CoreBookFormat.UNKNOWN
    }

    fun coreFormatToEntity(f: CoreBookFormat): FormatEntity = when (f) {
        CoreBookFormat.EPUB -> FormatEntity.EPUB
        CoreBookFormat.PDF -> FormatEntity.PDF
        CoreBookFormat.MOBI -> FormatEntity.MOBI
        CoreBookFormat.AZW -> FormatEntity.AZW
        CoreBookFormat.AZW3 -> FormatEntity.AZW3
        CoreBookFormat.FB2 -> FormatEntity.FB2
        CoreBookFormat.CBZ -> FormatEntity.CBZ
        CoreBookFormat.CBR -> FormatEntity.CBR
        CoreBookFormat.TXT -> FormatEntity.TXT
        CoreBookFormat.HTML -> FormatEntity.HTML
        CoreBookFormat.RTF -> FormatEntity.RTF
        CoreBookFormat.DOCX -> FormatEntity.DOCX
        CoreBookFormat.MD -> FormatEntity.MD
        CoreBookFormat.M4B -> FormatEntity.M4B
        CoreBookFormat.M4A -> FormatEntity.M4A
        CoreBookFormat.MP3 -> FormatEntity.MP3
        CoreBookFormat.AAC -> FormatEntity.AAC
        CoreBookFormat.FLAC -> FormatEntity.FLAC
        CoreBookFormat.OGG -> FormatEntity.OGG
        CoreBookFormat.OPUS -> FormatEntity.OPUS
        CoreBookFormat.WAV -> FormatEntity.WAV
        CoreBookFormat.ZIP -> FormatEntity.ZIP
        CoreBookFormat.UNKNOWN -> FormatEntity.UNKNOWN
    }

    /**
     * Maps a persisted CoreFormat into the UI-level enum currently used by the
     * [BookVisual] component. In a later phase this class can be collapsed into
     * a single domain format; for now we bridge at render time.
     */
    fun coreFormatToUi(f: CoreBookFormat): UiBookFormat = when (f) {
        CoreBookFormat.EPUB -> UiBookFormat.EPUB
        CoreBookFormat.PDF -> UiBookFormat.PDF
        CoreBookFormat.MOBI -> UiBookFormat.MOBI
        CoreBookFormat.AZW -> UiBookFormat.AZW
        CoreBookFormat.AZW3 -> UiBookFormat.AZW3
        CoreBookFormat.FB2 -> UiBookFormat.FB2
        CoreBookFormat.CBZ -> UiBookFormat.CBZ
        CoreBookFormat.CBR -> UiBookFormat.CBR
        CoreBookFormat.TXT -> UiBookFormat.TXT
        CoreBookFormat.HTML -> UiBookFormat.HTML
        CoreBookFormat.RTF -> UiBookFormat.RTF
        CoreBookFormat.DOCX -> UiBookFormat.DOCX
        CoreBookFormat.MD -> UiBookFormat.MD
        CoreBookFormat.M4B -> UiBookFormat.M4B
        CoreBookFormat.M4A -> UiBookFormat.M4A
        CoreBookFormat.MP3 -> UiBookFormat.MP3
        CoreBookFormat.AAC -> UiBookFormat.AAC
        CoreBookFormat.FLAC -> UiBookFormat.FLAC
        CoreBookFormat.OGG -> UiBookFormat.OGG
        CoreBookFormat.OPUS -> UiBookFormat.OPUS
        CoreBookFormat.WAV -> UiBookFormat.WAV
        CoreBookFormat.ZIP -> UiBookFormat.ZIP
        CoreBookFormat.UNKNOWN -> UiBookFormat.UNKNOWN
    }

    private val SPINE_PALETTE = listOf(
        ShelfColors.SpineBurgundy,
        ShelfColors.SpineNavy,
        ShelfColors.SpineForest,
        ShelfColors.SpineSienna,
        ShelfColors.SpineSlate,
        ShelfColors.SpineDustyRose,
        ShelfColors.SpineMustard,
        ShelfColors.SpineTeal,
        ShelfColors.SpinePlum,
        ShelfColors.SpineRust,
        Color(0xFF2E4057),
        Color(0xFF7A3E65),
        Color(0xFF3D6B5F),
        Color(0xFF8B4513),
        Color(0xFF5C4033),
        Color(0xFF40514E),
        Color(0xFF6A3805),
        Color(0xFF3E2723),
        Color(0xFF5B2C6F),
        Color(0xFF7B3F00)
    )

    /**
     * Deterministic spine color from a book ID + optional saved color.
     *
     * Stored [spineColor] is honored first; otherwise we index into a fixed
     * palette using the (absolute-id-mod-size) hash so the same book always
     * renders the same spine color across launches.
     */
    fun pickSpineColor(bookId: Long, savedColor: Int?): Color =
        savedColor?.let(::Color) ?: SPINE_PALETTE[abs(bookId.toInt()).mod(SPINE_PALETTE.size)]

    /**
     * Deterministic spine width so books feel varied on the shelf
     * (thicker for long/audio books, thinner for short PDFs/TXTs).
     */
    fun pickSpineWidth(book: BookEntity): androidx.compose.ui.unit.Dp {
        val file = (book.fileSizeBytes / (1024 * 1024)).toInt()
        val pages = book.pageCount ?: 0
        val audioH = (book.durationMs?.div(3_600_000f) ?: 0f).toInt()
        val signal = (abs(book.id.toInt()) % 5) + file.coerceAtMost(5) +
                (pages / 250).coerceAtMost(5) + audioH
        val widthPx = 9 + (signal.coerceIn(0, 18))
        return androidx.compose.ui.unit.Dp(widthPx.toFloat())
    }

    /**
     * Deterministic lean angle so books look naturally placed (no uniform
     * straight row). Range -2.2° → +2.2°, keeps things Apple-calm.
     */
    fun pickLean(bookId: Long): Float =
        (sin(bookId.toDouble() * 0.73).toFloat()) * 2.2f

    fun pickTextColorFor(spine: Color): Color {
        // Luma (YIQ) to pick black/white: avoids purple-on-black issues
        val luma = 0.299f * spine.red + 0.587f * spine.green + 0.114f * spine.blue
        return if (luma > 0.55f) Color.Black else Color.White
    }

    /**
     * Converts a [BookEntity] + optional reading progress % into the visual
     * model consumed by the [BookSpine] / [BookCoverCard] composables.
     */
    fun toBookVisual(
        book: BookEntity,
        progressPercent: Float?,
        filesDir: java.io.File? = null
    ): BookVisual {
        val spine = pickSpineColor(book.id, book.spineColor)
        val format = coreFormatToUi(formatEntityToCore(book.format))
        val width = pickSpineWidth(book)
        val resolvedCover = book.coverPath?.takeIf { java.io.File(it).exists() }
            ?: filesDir?.let { java.io.File(it, "covers/book_${book.id}.webp") }?.takeIf { it.exists() }?.absolutePath
        return BookVisual(
            id = book.id,
            title = book.title.ifBlank { "—" },
            author = book.author,
            spineColor = spine,
            spineTextColor = pickTextColorFor(spine),
            coverImagePath = resolvedCover,
            format = format,
            progress = progressPercent ?: 0f,
            widthDp = width,
            leanDegrees = pickLean(book.id),
            isDownloaded = book.filePath != null || book.fileUri != null || book.isSample
        )
    }
}
