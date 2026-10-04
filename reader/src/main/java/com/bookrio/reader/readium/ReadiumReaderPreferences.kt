package com.bookrio.reader.readium

import android.content.Context
import androidx.core.content.edit
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme

/**
 * Bookiro's EPUB reading preferences, mapped 1:1 onto Readium Kotlin Toolkit 3.0.3
 * `EpubPreferences` — and **only** onto fields that 3.0.3 actually implements.
 *
 * Verified against the pinned 3.0.3 sources (`EpubPreferences.kt`, `EpubSettings.kt`,
 * `UserProperties`, `ReadiumCSS-*.css`):
 *
 *  - [fontScale]      -> `EpubPreferences.fontSize` -> `--USER__fontSize` as a *percentage*
 *                        of the base font size (1.0 == 100 %). This requires the navigator
 *                        configuration `useReadiumCssFontSize = true`, which is the 3.0.3 default.
 *  - [themeName]      -> `EpubPreferences.theme` -> `readium-night-on` / `readium-sepia-on`
 *                        (light = no appearance class).
 *  - [pageMarginFactor] -> `EpubPreferences.pageMargins` -> `--USER__pageMargins`, a factor
 *                        applied to Readium CSS's `--RS__pageGutter` (20 px on phones,
 *                        30+ px on wider viewports). 0.0 removes the gutter entirely.
 *  - [lineHeightFactor] -> `EpubPreferences.lineHeight` -> `--USER__lineHeight`. Readium 3.0.3
 *                        gates every `lineHeight`/spacing rule behind `advancedSettings`, which
 *                        `EpubSettings.kt` computes as `!publisherStyles`. So a submitted line
 *                        height also flips `publisherStyles = false`; leaving it `null` keeps the
 *                        publisher's own line height and does not touch `publisherStyles`.
 *  - [scroll]         -> `EpubPreferences.scroll` -> paged (column) vs scrolling overflow.
 *                        The navigator invalidates its pager and restores `currentLocator`,
 *                        so the reading position is preserved across paged <-> scroll.
 *
 * Deliberately absent: font family, column count, text align, paragraph spacing/indent,
 * word/letter spacing. Bookiro only shows controls it can honour (no fake UI).
 */
internal data class ReadiumReaderPreferences(
    val fontScale: Double = DEFAULT_FONT_SCALE,
    val themeName: String = THEME_DARK,
    val pageMarginFactor: Double = DEFAULT_PAGE_MARGIN_FACTOR,
    val lineHeightFactor: Double? = DEFAULT_LINE_HEIGHT_FACTOR,
    val scroll: Boolean = false,
) {

    /** Maps Bookiro's stored preferences to the Readium EpubPreferences that implement them. */
    fun toEpubPreferences(): EpubPreferences = EpubPreferences(
        fontSize = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        theme = themeForStorageName(themeName),
        pageMargins = pageMarginFactor.coerceIn(MIN_PAGE_MARGIN_FACTOR, MAX_PAGE_MARGIN_FACTOR),
        lineHeight = lineHeightFactor?.coerceIn(MIN_LINE_HEIGHT_FACTOR, MAX_LINE_HEIGHT_FACTOR),
        // lineHeight only renders with Readium CSS "advanced settings", which 3.0.3 derives
        // from `!publisherStyles`. Leave the publisher in charge when we submit no line height.
        publisherStyles = if (lineHeightFactor == null) null else false,
        scroll = scroll,
    )

    companion object {
        /** Defaults for a fresh install. */
        val DEFAULT = ReadiumReaderPreferences()

        const val DEFAULT_FONT_SCALE = 1.0
        const val MIN_FONT_SCALE = 0.6
        const val MAX_FONT_SCALE = 2.5
        const val FONT_SCALE_STEP = 0.1

        const val THEME_LIGHT = "light"
        const val THEME_SEPIA = "sepia"
        const val THEME_DARK = "dark"

        const val DEFAULT_PAGE_MARGIN_FACTOR = 1.0
        const val MIN_PAGE_MARGIN_FACTOR = 0.0
        const val MAX_PAGE_MARGIN_FACTOR = 2.0

        val DEFAULT_LINE_HEIGHT_FACTOR: Double? = null
        const val MIN_LINE_HEIGHT_FACTOR = 1.0
        const val MAX_LINE_HEIGHT_FACTOR = 2.0

        /** The 16 sp legacy default equals 100 %. */
        const val LEGACY_BASE_FONT_SP = 16

        /**
         * Legacy Bookiro stored font size in sp (10..32). Readium's `fontSize` is a ratio,
         * so the shared prefs value is the only sensible bridge: sp / 16.
         */
        fun fontScaleForLegacySp(sp: Int): Double =
            (sp.toDouble() / LEGACY_BASE_FONT_SP).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

        fun legacySpForFontScale(scale: Double): Int =
            (scale * LEGACY_BASE_FONT_SP).toInt().coerceIn(10, 32)

        /** Legacy reader themes were `light`, `sepia`, `dark`, `black` (AMOLED). */
        fun themeForStorageName(name: String?): Theme = when (name?.lowercase()) {
            THEME_LIGHT -> Theme.LIGHT
            THEME_SEPIA -> Theme.SEPIA
            // "black" (old AMOLED theme) and anything unknown fall back to the OLED night theme.
            else -> Theme.DARK
        }

        fun storageNameForTheme(theme: Theme): String = when (theme) {
            Theme.LIGHT -> THEME_LIGHT
            Theme.SEPIA -> THEME_SEPIA
            Theme.DARK -> THEME_DARK
        }
    }
}

/**
 * Persists [ReadiumReaderPreferences] in a reader-private SharedPreferences file.
 * No data-module schema is touched; the EPUB reader owns its own appearance prefs.
 */
internal class ReadiumPreferencesStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): ReadiumReaderPreferences = ReadiumReaderPreferences(
        fontScale = runCatching {
            prefs.getFloat(KEY_FONT_SCALE, ReadiumReaderPreferences.DEFAULT_FONT_SCALE.toFloat())
                .toDouble()
        }.getOrDefault(ReadiumReaderPreferences.DEFAULT_FONT_SCALE),
        themeName = prefs.getString(KEY_THEME, ReadiumReaderPreferences.THEME_DARK)
            ?: ReadiumReaderPreferences.THEME_DARK,
        pageMarginFactor = runCatching {
            prefs.getFloat(
                KEY_PAGE_MARGINS,
                ReadiumReaderPreferences.DEFAULT_PAGE_MARGIN_FACTOR.toFloat(),
            ).toDouble()
        }.getOrDefault(ReadiumReaderPreferences.DEFAULT_PAGE_MARGIN_FACTOR),
        lineHeightFactor = if (prefs.contains(KEY_LINE_HEIGHT)) {
            runCatching { prefs.getFloat(KEY_LINE_HEIGHT, 1.5f).toDouble() }.getOrNull()
        } else {
            ReadiumReaderPreferences.DEFAULT_LINE_HEIGHT_FACTOR
        },
        scroll = prefs.getBoolean(KEY_SCROLL, false),
    )

    fun save(preferences: ReadiumReaderPreferences) {
        prefs.edit {
            putFloat(KEY_FONT_SCALE, preferences.fontScale.toFloat())
            putString(KEY_THEME, preferences.themeName)
            putFloat(KEY_PAGE_MARGINS, preferences.pageMarginFactor.toFloat())
            val lineHeight = preferences.lineHeightFactor
            if (lineHeight == null) {
                remove(KEY_LINE_HEIGHT)
            } else {
                putFloat(KEY_LINE_HEIGHT, lineHeight.toFloat())
            }
            putBoolean(KEY_SCROLL, preferences.scroll)
        }
    }

    companion object {
        const val FILE_NAME = "bookiro_epub_reader"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_THEME = "theme"
        private const val KEY_PAGE_MARGINS = "page_margins"
        private const val KEY_LINE_HEIGHT = "line_height"
        private const val KEY_SCROLL = "scroll"
    }
}