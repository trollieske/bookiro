package com.bookrio.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bookrio.core.domain.model.DarkModePref
import com.bookrio.core.domain.model.LibrarySortMode
import com.bookrio.core.domain.model.LibraryViewType
import com.bookrio.core.domain.model.SortDirection
import com.bookrio.core.domain.model.defaultDirectionFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "shelf_prefs")

/**
 * User-editable preferences stored via Jetpack DataStore.
 *
 * - Small / typed values only (enums, ints, booleans, strings)
 * - Cover cache paths, Room DB, FTP credentials live elsewhere (File / EncryptedSharedPreferences)
 */
class UserPreferencesRepository(private val context: Context) {

    private val store get() = context.dataStore.data

    // ---- Appearance ----
    val libraryViewType: Flow<LibraryViewType> = store.map { p ->
        LibraryViewType.fromStorage(p[Keys.LIBRARY_VIEW])
    }

    val darkMode: Flow<DarkModePref> = store.map { p ->
        p[Keys.DARK_MODE]?.let { DarkModePref.fromInt(it) } ?: DarkModePref.FOLLOW_SYSTEM
    }

    val dynamicColors: Flow<Boolean> = store.map { it[Keys.DYNAMIC_COLORS] ?: false }
    val trueBlack: Flow<Boolean> = store.map { it[Keys.AMOLED_BLACK] ?: false }

    // ---- Reader defaults ----
    val readerFontSizeSp: Flow<Int> = store.map { it[Keys.READER_FONT_SIZE] ?: 16 }
    val readerLineHeight: Flow<Float> = store.map { (it[Keys.READER_LINE_HEIGHT_PCT] ?: 140) / 100f }
    val readerTheme: Flow<String> = store.map { it[Keys.READER_THEME] ?: "light" }

    // ---- Audiobook defaults ----
    val audioSpeed: Flow<Float> = store.map { (it[Keys.AUDIO_SPEED_MILLIS] ?: 1000) / 1000f }
    val audioSkipBackSec: Flow<Int> = store.map { it[Keys.AUDIO_SKIP_BACK] ?: 10 }
    val audioSkipFwdSec: Flow<Int> = store.map { it[Keys.AUDIO_SKIP_FWD] ?: 30 }
    val autoSleepFadeOut: Flow<Boolean> = store.map { it[Keys.AUDIO_FADE_OUT] ?: true }
    val autoPlayNextInSeries: Flow<Boolean> = store.map { it[Keys.AUDIO_PLAY_NEXT] ?: false }

    // ---- Storage / Import ----
    val libraryFolderUri: Flow<String?> = store.map { it[Keys.LIBRARY_FOLDER_URI] }
    val watchLibraryFolder: Flow<Boolean> = store.map { it[Keys.WATCH_LIBRARY_FOLDER] ?: false }
    val ftpSyncEnabled: Flow<Boolean> = store.map { it[Keys.FTP_SYNC_ENABLED] ?: false }
    val ftpWifiOnly: Flow<Boolean> = store.map { it[Keys.FTP_WIFI_ONLY] ?: true }
    val ftpChargingOnly: Flow<Boolean> = store.map { it[Keys.FTP_CHARGING_ONLY] ?: false }
    val ftpIntervalMinutes: Flow<Int> = store.map { it[Keys.FTP_INTERVAL_MINUTES] ?: 360 }
    val ftpMaxConcurrency: Flow<Int> = store.map { it[Keys.FTP_MAX_CONCURRENCY] ?: 6 }

    val smbSyncEnabled: Flow<Boolean> = store.map { it[Keys.SMB_SYNC_ENABLED] ?: false }
    val smbWifiOnly: Flow<Boolean> = store.map { it[Keys.SMB_WIFI_ONLY] ?: true }
    val smbChargingOnly: Flow<Boolean> = store.map { it[Keys.SMB_CHARGING_ONLY] ?: false }
    val smbIntervalMinutes: Flow<Int> = store.map { it[Keys.SMB_INTERVAL_MINUTES] ?: 360 }

    val webdavSyncEnabled: Flow<Boolean> = store.map { it[Keys.WEBDAV_SYNC_ENABLED] ?: false }
    val webdavWifiOnly: Flow<Boolean> = store.map { it[Keys.WEBDAV_WIFI_ONLY] ?: true }
    val webdavChargingOnly: Flow<Boolean> = store.map { it[Keys.WEBDAV_CHARGING_ONLY] ?: false }
    val webdavIntervalMinutes: Flow<Int> = store.map { it[Keys.WEBDAV_INTERVAL_MINUTES] ?: 360 }

    val torrentBackgroundEnabled: Flow<Boolean> = store.map { it[Keys.TORRENT_BG_ENABLED] ?: false }
    val torrentWifiOnly: Flow<Boolean> = store.map { it[Keys.TORRENT_WIFI_ONLY] ?: true }
    val torrentChargingOnly: Flow<Boolean> = store.map { it[Keys.TORRENT_CHARGING_ONLY] ?: false }
    val torrentMinBatteryPct: Flow<Int> = store.map { it[Keys.TORRENT_MIN_BATTERY] ?: 20 }

    val libraryFormatFilterEnabled: Flow<Boolean> = store.map { it[Keys.LIB_FORMAT_FILTER] ?: true }
    val libraryTabCountsEnabled: Flow<Boolean> = store.map { it[Keys.LIB_TAB_COUNTS] ?: true }

    // ---- Immersion Handoff ----
    val handoffPrecision: Flow<String> = store.map { it[Keys.HANDOFF_PRECISION] ?: com.bookrio.data.local.entity.HandoffPrecisionEntity.SMART.name }
    val handoffToastEnabled: Flow<Boolean> = store.map { it[Keys.HANDOFF_TOAST] ?: true }

    // ---- Cover / Online lookups ----
    val onlineCoverLookup: Flow<Boolean> = store.map { it[Keys.ONLINE_COVER_LOOKUP] ?: false }
    val hasSeenOnboarding: Flow<Boolean> = store.map { it[Keys.SEEN_ONBOARDING] ?: false }

    // ---- Podcasts ----
    /** Podcast discovery storefront. Empty string means "never chosen by the user". */
    val podcastCountry: Flow<String> = store.map { it[Keys.PODCAST_COUNTRY] ?: "" }

    /** Global podcast playback speed, remembered across episodes. */
    val podcastSpeed: Flow<Float> = store.map { (it[Keys.PODCAST_SPEED_MILLIS] ?: 1000) / 1000f }

    // ---- Sort (persisted per media tab; direction falls back to the mode default) ----
    val booksSortMode: Flow<LibrarySortMode> = store.map { LibrarySortMode.from(it[Keys.SORT_MODE_BOOKS]) }
    val audioSortMode: Flow<LibrarySortMode> = store.map { LibrarySortMode.from(it[Keys.SORT_MODE_AUDIO]) }
    val booksSortDirection: Flow<SortDirection> = combine(booksSortMode, store) { mode, p ->
        p[Keys.SORT_DIR_BOOKS]?.let { SortDirection.from(it) } ?: defaultDirectionFor(mode)
    }
    val audioSortDirection: Flow<SortDirection> = combine(audioSortMode, store) { mode, p ->
        p[Keys.SORT_DIR_AUDIO]?.let { SortDirection.from(it) } ?: defaultDirectionFor(mode)
    }

    // ---- (Legacy Leserytme/goal keys remain dormant in the DataStore file) ----

    // ---- Writes ----

    suspend fun setLibraryViewType(t: LibraryViewType) = edit(Keys.LIBRARY_VIEW, t.storageKey)
    suspend fun setDarkMode(d: DarkModePref) = edit(Keys.DARK_MODE, d.intValue)
    suspend fun setDynamicColors(enabled: Boolean) = edit(Keys.DYNAMIC_COLORS, enabled)
    suspend fun setAmoledBlack(enabled: Boolean) = edit(Keys.AMOLED_BLACK, enabled)
    suspend fun setReaderFontSizeSp(sp: Int) = edit(Keys.READER_FONT_SIZE, sp.coerceIn(10, 32))
    suspend fun setReaderLineHeight(pct: Int) = edit(Keys.READER_LINE_HEIGHT_PCT, pct.coerceIn(100, 220))
    suspend fun setReaderTheme(name: String) = edit(Keys.READER_THEME, name)
    suspend fun setAudioSpeed(ratio: Float) = edit(Keys.AUDIO_SPEED_MILLIS, (ratio * 1000).toInt().coerceIn(500, 3000))
    suspend fun setAudioSkipBack(sec: Int) = edit(Keys.AUDIO_SKIP_BACK, sec.coerceIn(5, 60))
    suspend fun setAudioSkipFwd(sec: Int) = edit(Keys.AUDIO_SKIP_FWD, sec.coerceIn(10, 120))
    suspend fun setAudioFadeOut(enabled: Boolean) = edit(Keys.AUDIO_FADE_OUT, enabled)
    suspend fun setAutoPlayNext(enabled: Boolean) = edit(Keys.AUDIO_PLAY_NEXT, enabled)
    suspend fun setLibraryFolderUri(uri: String?) {
        context.dataStore.edit { if (uri == null) it.remove(Keys.LIBRARY_FOLDER_URI) else it[Keys.LIBRARY_FOLDER_URI] = uri }
    }
    suspend fun setWatchLibraryFolder(enabled: Boolean) = edit(Keys.WATCH_LIBRARY_FOLDER, enabled)
    suspend fun setFtpSyncEnabled(enabled: Boolean) = edit(Keys.FTP_SYNC_ENABLED, enabled)
    suspend fun setFtpWifiOnly(enabled: Boolean) = edit(Keys.FTP_WIFI_ONLY, enabled)
    suspend fun setFtpChargingOnly(enabled: Boolean) = edit(Keys.FTP_CHARGING_ONLY, enabled)
    suspend fun setFtpIntervalMinutes(min: Int) = edit(Keys.FTP_INTERVAL_MINUTES, min.coerceIn(15, 1440))
    suspend fun setFtpMaxConcurrency(count: Int) = edit(Keys.FTP_MAX_CONCURRENCY, count.coerceIn(1, 15))

    suspend fun setSmbSyncEnabled(enabled: Boolean) = edit(Keys.SMB_SYNC_ENABLED, enabled)
    suspend fun setSmbWifiOnly(enabled: Boolean) = edit(Keys.SMB_WIFI_ONLY, enabled)
    suspend fun setSmbChargingOnly(enabled: Boolean) = edit(Keys.SMB_CHARGING_ONLY, enabled)
    suspend fun setSmbIntervalMinutes(min: Int) = edit(Keys.SMB_INTERVAL_MINUTES, min.coerceIn(15, 1440))

    suspend fun setWebdavSyncEnabled(enabled: Boolean) = edit(Keys.WEBDAV_SYNC_ENABLED, enabled)
    suspend fun setWebdavWifiOnly(enabled: Boolean) = edit(Keys.WEBDAV_WIFI_ONLY, enabled)
    suspend fun setWebdavChargingOnly(enabled: Boolean) = edit(Keys.WEBDAV_CHARGING_ONLY, enabled)
    suspend fun setWebdavIntervalMinutes(min: Int) = edit(Keys.WEBDAV_INTERVAL_MINUTES, min.coerceIn(15, 1440))

    suspend fun setTorrentBackgroundEnabled(enabled: Boolean) = edit(Keys.TORRENT_BG_ENABLED, enabled)
    suspend fun setTorrentWifiOnly(enabled: Boolean) = edit(Keys.TORRENT_WIFI_ONLY, enabled)
    suspend fun setTorrentChargingOnly(enabled: Boolean) = edit(Keys.TORRENT_CHARGING_ONLY, enabled)
    suspend fun setTorrentMinBatteryPct(pct: Int) = edit(Keys.TORRENT_MIN_BATTERY, pct.coerceIn(0, 100))

    suspend fun setLibraryFormatFilter(enabled: Boolean) = edit(Keys.LIB_FORMAT_FILTER, enabled)
    suspend fun setLibraryTabCounts(enabled: Boolean) = edit(Keys.LIB_TAB_COUNTS, enabled)
    suspend fun setHandoffPrecision(value: String) = edit(Keys.HANDOFF_PRECISION, value)
    suspend fun setHandoffToast(enabled: Boolean) = edit(Keys.HANDOFF_TOAST, enabled)
    suspend fun setOnlineCoverLookup(enabled: Boolean) = edit(Keys.ONLINE_COVER_LOOKUP, enabled)
    suspend fun markOnboardingSeen() = edit(Keys.SEEN_ONBOARDING, true)

    suspend fun setPodcastCountry(code: String) = edit(Keys.PODCAST_COUNTRY, code)

    suspend fun setPodcastSpeed(ratio: Float) =
        edit(Keys.PODCAST_SPEED_MILLIS, (ratio * 1000).toInt().coerceIn(500, 3000))

    suspend fun setBooksSortMode(mode: LibrarySortMode) = edit(Keys.SORT_MODE_BOOKS, mode.storage)
    suspend fun setAudioSortMode(mode: LibrarySortMode) = edit(Keys.SORT_MODE_AUDIO, mode.storage)
    suspend fun setBooksSortDirection(d: SortDirection) = edit(Keys.SORT_DIR_BOOKS, d.storage)
    suspend fun setAudioSortDirection(d: SortDirection) = edit(Keys.SORT_DIR_AUDIO, d.storage)

    private suspend inline fun <reified T : Any> edit(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val LIBRARY_VIEW = stringPreferencesKey("library_view")
        val DARK_MODE = intPreferencesKey("dark_mode")
        val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")

        val READER_FONT_SIZE = intPreferencesKey("reader_font_size_sp")
        val READER_LINE_HEIGHT_PCT = intPreferencesKey("reader_line_height_pct")
        val READER_THEME = stringPreferencesKey("reader_theme")

        val AUDIO_SPEED_MILLIS = intPreferencesKey("audio_speed_ratio_x1000")
        val AUDIO_SKIP_BACK = intPreferencesKey("audio_skip_back_sec")
        val AUDIO_SKIP_FWD = intPreferencesKey("audio_skip_fwd_sec")
        val AUDIO_FADE_OUT = booleanPreferencesKey("audio_fade_out")
        val AUDIO_PLAY_NEXT = booleanPreferencesKey("audio_play_next_series")

        val LIBRARY_FOLDER_URI = stringPreferencesKey("library_folder_uri")
        val WATCH_LIBRARY_FOLDER = booleanPreferencesKey("watch_library_folder")
        
        val FTP_SYNC_ENABLED = booleanPreferencesKey("ftp_sync_enabled")
        val FTP_WIFI_ONLY = booleanPreferencesKey("ftp_sync_wifi_only")
        val FTP_CHARGING_ONLY = booleanPreferencesKey("ftp_sync_charging_only")
        val FTP_INTERVAL_MINUTES = intPreferencesKey("ftp_sync_interval_min")
        val FTP_MAX_CONCURRENCY = intPreferencesKey("ftp_max_concurrency")

        val SMB_SYNC_ENABLED = booleanPreferencesKey("smb_sync_enabled")
        val SMB_WIFI_ONLY = booleanPreferencesKey("smb_sync_wifi_only")
        val SMB_CHARGING_ONLY = booleanPreferencesKey("smb_sync_charging_only")
        val SMB_INTERVAL_MINUTES = intPreferencesKey("smb_sync_interval_min")

        val WEBDAV_SYNC_ENABLED = booleanPreferencesKey("webdav_sync_enabled")
        val WEBDAV_WIFI_ONLY = booleanPreferencesKey("webdav_sync_wifi_only")
        val WEBDAV_CHARGING_ONLY = booleanPreferencesKey("webdav_sync_charging_only")
        val WEBDAV_INTERVAL_MINUTES = intPreferencesKey("webdav_sync_interval_min")

        val TORRENT_BG_ENABLED = booleanPreferencesKey("torrent_bg_enabled")
        val TORRENT_WIFI_ONLY = booleanPreferencesKey("torrent_wifi_only")
        val TORRENT_CHARGING_ONLY = booleanPreferencesKey("torrent_charging_only")
        val TORRENT_MIN_BATTERY = intPreferencesKey("torrent_min_battery_pct")

        val LIB_FORMAT_FILTER = booleanPreferencesKey("lib_format_filter")
        val LIB_TAB_COUNTS = booleanPreferencesKey("lib_tab_counts")

        val HANDOFF_PRECISION = stringPreferencesKey("handoff_precision")
        val HANDOFF_TOAST = booleanPreferencesKey("handoff_toast")

        val ONLINE_COVER_LOOKUP = booleanPreferencesKey("online_cover_lookup")
        val SEEN_ONBOARDING = booleanPreferencesKey("seen_onboarding_v1")
        val PODCAST_COUNTRY = stringPreferencesKey("podcast_country")
        val PODCAST_SPEED_MILLIS = intPreferencesKey("podcast_speed_ratio_x1000")
        val USER_NAME = stringPreferencesKey("user_name")

        val SORT_MODE_BOOKS = stringPreferencesKey("sort_mode_books")
        val SORT_MODE_AUDIO = stringPreferencesKey("sort_mode_audio")
        val SORT_DIR_BOOKS = stringPreferencesKey("sort_dir_books")
        val SORT_DIR_AUDIO = stringPreferencesKey("sort_dir_audio")
    }

    val userName: Flow<String> = store.map { it[Keys.USER_NAME] ?: "Karoline" }

    suspend fun setUserName(name: String) = edit(Keys.USER_NAME, name)
    suspend fun setHasSeenOnboarding(seen: Boolean) = edit(Keys.SEEN_ONBOARDING, seen)
}
