package com.bookrio.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * UI-level book format used by the shelf/cover renderers.
 *
 * Extracted from the Android-only `BookComponents.kt` into `commonMain` so the
 * KMP iOS screens can build the exact same [BookVisual] model. The composables
 * that draw the Android canvas (BookSpine, BookCoverCard, RealisticBookshelfCanvas)
 * stay in `androidMain`; only the model moved.
 */
enum class BookFormat(val badge: String) {
    EPUB("EPUB"), PDF("PDF"), MOBI("MOBI"), AZW("AZW"), AZW3("AZW3"),
    FB2("FB2"), CBZ("CBZ"), CBR("CBR"), TXT("TXT"),
    HTML("HTML"), DOCX("DOCX"), MD("MD"), RTF("RTF"),
    M4B("M4B"), M4A("M4A"), MP3("MP3"), AAC("AAC"),
    FLAC("FLAC"), OGG("OGG"), OPUS("OPUS"), WAV("WAV"),
    ZIP("ZIP"), MIXED("MIXED"), UNKNOWN("");

    val isAudio: Boolean get() = this in setOf(M4B, M4A, MP3, AAC, FLAC, OGG, OPUS, WAV, MIXED)
}

/** Platform-independent visual description of one shelf item / cover card. */
data class BookVisual(
    val id: Long,
    val title: String,
    val author: String,
    val spineColor: Color,
    val spineTextColor: Color = Color.White,
    val coverImagePath: String? = null,
    val format: BookFormat = BookFormat.EPUB,
    val progress: Float = 0f,
    val widthDp: Dp = 12.dp,
    val heightRatio: Float = 1.55f,
    val leanDegrees: Float = 0f,
    val isDownloaded: Boolean = true
)

/**
 * Strips scene-release/libgen noise from an imported filename. Pure Kotlin, so the
 * same cleanup runs on Android and iOS.
 */
fun cleanBookTitle(raw: String): String {
    var clean = raw
        .replace(Regex("""_libgen\.[a-z]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""_z-lib\.[a-z]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\([^)]*z-library[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^\]]*libgen[^\]]*\]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\.(epub|pdf|mobi|azw3?|cbz)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^\d+[-_.]\d+[-_.]?\s*"""), "")
        .replace("_", " ")
        .trim()
    if (clean.contains(" - ")) {
        val parts = clean.split(" - ", limit = 2)
        if (parts[0].length < parts[1].length && (parts[0].contains(",") || parts[0].split(" ").size in 1..3)) {
            clean = parts[1].trim()
        }
    }
    return clean.ifBlank { raw }
}

/** Falls back to the same localized placeholder the Android UI shows. */
fun cleanBookAuthor(raw: String): String {
    var clean = raw.replace("_", " ").trim()
    if (clean.isBlank() || clean.contains("Ukjent", ignoreCase = true)) {
        clean = "Ukjent forfatter"
    }
    return clean
}