package com.bookrio.shared.platform

/**
 * Tiny cross-platform key/value preferences for the iOS parity screens.
 *
 * Values are stored as strings; the typed helpers parse them back. On iOS this is
 * `NSUserDefaults.standardUserDefaults`, which is exactly the persistence the
 * Android side gets from DataStore. It is intentionally synchronous and
 * unobtrusive so common UI can read/write settings without a lifecycle-aware
 * ViewModel.
 */
internal expect fun prefsGetString(key: String): String?
internal expect fun prefsPutString(key: String, value: String?)
internal expect fun prefsRemove(key: String)

object AppPrefs {
    fun getString(key: String): String? = prefsGetString(key)

    fun putString(key: String, value: String?) {
        if (value == null) prefsRemove(key) else prefsPutString(key, value)
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        getString(key)?.toBooleanStrictOrNull() ?: default

    fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())

    fun getInt(key: String, default: Int): Int = getString(key)?.toIntOrNull() ?: default

    fun putInt(key: String, value: Int) = putString(key, value.toString())

    fun getFloat(key: String, default: Float): Float = getString(key)?.toFloatOrNull() ?: default

    fun putFloat(key: String, value: Float) = putString(key, value.toString())

    fun getLong(key: String, default: Long): Long = getString(key)?.toLongOrNull() ?: default

    fun putLong(key: String, value: Long) = putString(key, value.toString())
}