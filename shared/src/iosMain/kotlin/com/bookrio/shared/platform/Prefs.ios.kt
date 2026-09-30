package com.bookrio.shared.platform

import platform.Foundation.NSUserDefaults

private val defaults = NSUserDefaults.standardUserDefaults

internal actual fun prefsGetString(key: String): String? = defaults.stringForKey(key)

internal actual fun prefsPutString(key: String, value: String?) {
    if (value == null) {
        defaults.removeObjectForKey(key)
    } else {
        defaults.setObject(value, forKey = key)
    }
}

internal actual fun prefsRemove(key: String) {
    defaults.removeObjectForKey(key)
}