package com.bookrio.library.mapper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bookrio.data.local.entity.BookEntity
import com.bookrio.data.local.entity.FormatEntity
import com.bookrio.designsystem.components.BookFormat
import com.bookrio.designsystem.components.BookVisual
import com.bookrio.designsystem.theme.ShelfColors
import kotlin.math.abs
import kotlin.math.sin

/**
 * KMP port of the Android `:library` `DomainMappers`
 * (`library/src/main/java/com/bookrio/library/mapper/DomainMappers.kt`).
 *
 * Differences from the Android original (deliberate, iOS-only):
 *  - no `java.io.File` existence checks / `filesDir/covers/book_<id>.webp`
 *    fallback: the persisted [BookEntity.coverPath] is used as-is,
 *  - only the pieces the library chrome needs are ported (format mapping,
 *    spine colour/width, lean angle, spine text colour, `BookVisual`).
 *
 * Deterministic helpers keep the same book rendered with the same spine colour
 * and lean across launches, exactly like Android.
 */
object BookVisualMapper {

    fun formatEntityToUi(f: FormatEntity): BookFormat = when (f) {
        FormatEntity.EPUB -> BookFormat.EPUB
        FormatEntity.PDF -> BookFormat.PDF
        FormatEntity.MOBI -> BookFormat.MOBI
        FormatEntity.AZW -> BookFormat.AZW
        FormatEntity.AZW3 -> BookFormat.AZW3
        FormatEntity.FB2 -> BookFormat.FB2
        FormatEntity.CBZ -> BookFormat.CBZ
        FormatEntity.CBR -> BookFormat.CBR
        FormatEntity.TXT -> BookFormat.TXT
        FormatEntity.HTML -> BookFormat.HTML
        FormatEntity.RTF -> BookFormat.RTF
        FormatEntity.DOCX -> BookFormat.DOCX
        FormatEntity.MD -> BookFormat.MD
        FormatEntity.M4B -> BookFormat.M4B
        FormatEntity.M4A -> BookFormat.M4A
        FormatEntity.MP3 -> BookFormat.MP3
        FormatEntity.AAC -> BookFormat.AAC
        FormatEntity.FLAC -> BookFormat.FLAC
        FormatEntity.OGG -> BookFormat.OGG
        FormatEntity.OPUS -> BookFormat.OPUS
        FormatEntity.OGG_OPUS -> BookFormat.OPUS
        FormatEntity.WAV -> BookFormat.WAV
        FormatEntity.ZIP -> BookFormat.ZIP
        FormatEntity.UNKNOWN -> BookFormat.UNKNOWN
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

    /** Stored [BookEntity.spineColor] wins; otherwise a stable palette pick. */
    fun pickSpineColor(bookId: Long, savedColor: Int?): Color =
        savedColor?.let(::Color) ?: SPINE_PALETTE[abs(bookId.toInt()) % SPINE_PALETTE.size]

    /** Deterministic spine width (thicker for long/audio books, thinner for short ones). */
    fun pickSpineWidth(book: BookEntity): Dp {
        val file = (book.fileSizeBytes / (1024 * 1024)).toInt()
        val pages = book.pageCount ?: 0
        val audioH = (book.durationMs?.div(3_600_000f) ?: 0f).toInt()
        val signal = (abs(book.id.toInt()) % 5) + file.coerceAtMost(5) +
            (pages / 250).coerceAtMost(5) + audioH
        val widthPx = 9 + signal.coerceIn(0, 18)
        return Dp(widthPx.toFloat())
    }

    /** Deterministic lean, -2.2f..2.2f, matching the Android value. */
    fun pickLean(bookId: Long): Float =
        sin(bookId.toDouble() * 0.73).toFloat() * 2.2f

    fun pickTextColorFor(spine: Color): Color {
        val luma = 0.299f * spine.red + 0.587f * spine.green + 0.114f * spine.blue
        return if (luma > 0.55f) Color.Black else Color.White
    }

    /**
     * Converts a persisted [BookEntity] + optional progress fraction into the
     * [BookVisual] consumed by the common cover card / list row.
     */
    fun toBookVisual(
        book: BookEntity,
        progressPercent: Float?,
    ): BookVisual {
        val spine = pickSpineColor(book.id, book.spineColor)
        return BookVisual(
            id = book.id,
            title = book.title.ifBlank { "(Uten tittel)" },
            author = book.author.ifBlank { "(Ukjent forfatter)" },
            spineColor = spine,
            spineTextColor = pickTextColorFor(spine),
            // iOS: absolute path written at import time — never probed here.
            coverImagePath = book.coverPath?.takeIf { it.isNotBlank() },
            format = formatEntityToUi(book.format),
            progress = progressPercent ?: 0f,
            widthDp = pickSpineWidth(book),
            leanDegrees = pickLean(book.id),
            isDownloaded = book.filePath != null || book.fileUri != null || book.isSample
        )
    }
}