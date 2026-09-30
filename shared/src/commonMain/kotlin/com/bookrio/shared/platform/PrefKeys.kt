package com.bookrio.shared.platform

/**
 * Canonical preference keys for the iOS parity screens.
 *
 * Mirrors the Android `UserPreferencesRepository` defaults and storage keys, so
 * the same setting means the same thing on both platforms. Read/write through
 * [AppPrefs]. Keep the string values stable: they are persisted in NSUserDefaults.
 */
object PrefKeys {
    // Library / look
    const val LIBRARY_VIEW = "library_view"                 // "grid" | "list"
    const val LIB_TAB_COUNTS = "lib_tab_counts"             // true
    const val ONLINE_COVER_LOOKUP = "online_cover_lookup"   // false (off unless user opts in)
    const val BOOKS_SORT_MODE = "books_sort_mode"
    const val BOOKS_SORT_DIR = "books_sort_dir"
    const val AUDIO_SORT_MODE = "audio_sort_mode"
    const val AUDIO_SORT_DIR = "audio_sort_dir"

    // Reader
    const val READER_FONT_SIZE = "reader_font_size"         // 16 sp
    const val READER_LINE_HEIGHT = "reader_line_height"     // 140 (%)
    const val READER_THEME = "reader_theme"                 // "light" | "sepia" | "dark"

    // Audio
    const val AUDIO_SPEED_MILLIS = "audio_speed_millis"     // 1000
    const val AUDIO_SKIP_BACK = "audio_skip_back"           // 10 s
    const val AUDIO_SKIP_FWD = "audio_skip_fwd"             // 30 s
    const val AUDIO_FADE_OUT = "audio_fade_out"             // true
    const val AUDIO_PLAY_NEXT = "audio_play_next"           // false

    // Podcasts
    const val PODCAST_SPEED_MILLIS = "podcast_speed_millis" // 1000
}