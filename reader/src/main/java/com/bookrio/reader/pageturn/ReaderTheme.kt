package com.bookrio.reader.pageturn

/**
 * Theme colours for the reader, shared between [HtmlPageRenderer] and the UI.
 *
 * All colour values are CSS colour strings (hex, rgb, hsl…).
 */
data class ReaderThemeColors(
    val htmlBg: String,
    val bodyBg: String,
    val textColor: String,
    val headingColor: String,
    /** Opaque Android int colour (used for Bitmap paper background). */
    val paperColorInt: Int,
)

fun readerThemeColors(theme: String): ReaderThemeColors = when (theme.lowercase()) {
    "sepia" -> ReaderThemeColors(
        htmlBg       = "#f4ecd8",
        bodyBg       = "#f4ecd8",
        textColor    = "#5b4636",
        headingColor = "#3d2e23",
        paperColorInt = 0xFFf4ecd8.toInt(),
    )
    "dark" -> ReaderThemeColors(
        htmlBg       = "#1a1a1a",
        bodyBg       = "#1a1a1a",
        textColor    = "#cccccc",
        headingColor = "#ffffff",
        paperColorInt = 0xFF1a1a1a.toInt(),
    )
    "amoled", "black" -> ReaderThemeColors(
        htmlBg       = "#000000",
        bodyBg       = "#000000",
        textColor    = "#999999",
        headingColor = "#ffffff",
        paperColorInt = 0xFF000000.toInt(),
    )
    else -> ReaderThemeColors(
        htmlBg       = "#ffffff",
        bodyBg       = "#ffffff",
        textColor    = "#121212",
        headingColor = "#000000",
        paperColorInt = 0xFFffffff.toInt(),
    )
}
