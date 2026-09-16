package com.shelf.reader.ftp.transfer

import android.content.Context
import android.os.BatteryManager

/** Charging state, used only to pick a conservative or aggressive lane count. */
object PowerState {
    fun isCharging(context: Context): Boolean {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        return runCatching { manager.isCharging }.getOrDefault(false)
    }
}