package com.bookrio.shared.reader

/**
 * Reader theme colours, byte-for-byte the same values as the Android
 * `readerThemeColors()` (reader/.../pageturn/ReaderTheme.kt) so the same theme
 * string means the same page on both platforms.
 */
internal data class EpubReaderColors(
    val bodyBg: String,
    val textColor: String,
    val headingColor: String,
)

internal fun epubReaderColors(theme: String): EpubReaderColors = when (theme.lowercase()) {
    "sepia" -> EpubReaderColors(bodyBg = "#f4ecd8", textColor = "#5b4636", headingColor = "#3d2e23")
    "dark" -> EpubReaderColors(bodyBg = "#1a1a1a", textColor = "#cccccc", headingColor = "#ffffff")
    "amoled", "black" -> EpubReaderColors(bodyBg = "#000000", textColor = "#999999", headingColor = "#ffffff")
    else -> EpubReaderColors(bodyBg = "#ffffff", textColor = "#121212", headingColor = "#000000")
}

/**
 * The reader document for ONE chapter, ported from the Android
 * `HtmlPageRenderer.buildReaderHtml` / `STABLE_IMAGE_CSS` (reader/.../engine/
 * HtmlPageRenderer.kt). The CSS governs fonts, sizes, colours, margins and
 * stable image pagination; the EPUB source markup is placed verbatim inside
 * `#content-wrapper`, exactly like Android.
 *
 * Differences, both deliberate:
 *  - the highlight UI/JS of the Android renderer is not included (iOS has no
 *    highlight feature in this slice);
 *  - line height comes from `PrefKeys.READER_LINE_HEIGHT` (Android's persisted
 *    default is 140 → 1.4) instead of the hard-coded `1.6` in Android's current
 *    renderer, so the persisted iOS setting actually drives the page.
 *
 * [pageOffset] bakes the page's horizontal offset into the wrapper as a CSS
 * transform (one column `= 100vw`), so a page is never shown at the wrong
 * position — the native host loads this HTML per child view controller.
 */
internal fun buildEpubReaderHtml(
    content: String,
    fontSizeSp: Int,
    lineHeightPct: Int,
    colors: EpubReaderColors,
    lang: String = "en",
    pageOffset: Int = 0,
): String {
    val lineHeight = (lineHeightPct.coerceIn(100, 220) / 100.0).toString()
    val wrapperStyle = if (pageOffset > 0) {
        " style=\"transform: translateX(-${pageOffset * 100}vw);\""
    } else {
        ""
    }
    return """
        <!DOCTYPE html>
        <html lang="${lang.ifEmpty { "en" }}">
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover">
        <style>
          *, *::before, *::after { box-sizing: border-box; }
          html, body {
            margin: 0; padding: 0; height: 100vh; width: 100vw;
            overflow: hidden; background: ${colors.bodyBg};
            -webkit-text-size-adjust: none;
          }
          body {
            color: ${colors.textColor};
            font-family: "Crimson Pro", "EB Garamond", "Palatino", "Georgia", serif;
            font-size: ${fontSizeSp.coerceIn(12, 36)}px;
            line-height: $lineHeight;
            text-rendering: optimizeLegibility;
            -webkit-font-smoothing: antialiased;
          }
          #content-wrapper {
            display: block;
            height: 100vh !important;
            width: 100% !important;
            margin: 0;
            padding: 0;
            -webkit-column-width: 100vw;
            column-width: 100vw !important;
            -webkit-column-gap: 0;
            column-gap: 0 !important;
            column-fill: auto;
            word-wrap: break-word;
            overflow-wrap: break-word;
            hyphens: auto;
            -webkit-hyphens: auto;
            text-align: justify;
            overflow: visible;
            will-change: transform;
            orphans: 1;
            widows: 1;
          }
          h1, h2, h3 { color: ${colors.headingColor}; text-align: center !important; margin: 1.2em 0 0.6em !important; font-weight: 700 !important; line-height: 1.3; }
          h1 { font-size: 1.5em !important; }
          h2 { font-size: 1.3em !important; }
          h3 { font-size: 1.15em !important; }
          p { margin: 0 0 0.6em !important; text-align: justify !important; text-indent: 1.5em !important; line-height: $lineHeight !important; }
          img, svg, image, video, iframe {
            max-width: 100% !important;
            height: auto !important;
            box-sizing: border-box !important;
          }
          img, svg {
            display: block !important;
            margin: 0.8em auto !important;
            break-inside: avoid !important;
            page-break-inside: avoid !important;
          }
          blockquote { border-left: 3px solid ${colors.headingColor}; padding-left: 1.2em; margin: 1.5em 0; font-style: italic; opacity: 0.9; }
          ::selection { background: rgba(255, 205, 90, 0.45); }
        </style>
        </head>
        <body><div id="content-wrapper"$wrapperStyle>$content</div></body>
        </html>
    """.trimIndent()
}