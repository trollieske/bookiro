package com.bookrio.player.ui

/**
 * Rene formathjelpere for lydbokspiller-UI (enhetstestes i PlayerFormattingTest).
 */

/**
 * «0.5×», «0.75×», «1×», «1.25×», «1.5×», «2×», «3×».
 * Aldri etterfølgende «.0», aldri mellomrom/linjeskift, aldri literal ${...}-tekst.
 */
internal fun formatPlaybackSpeed(speed: Float): String {
    val safe = if (speed.isNaN() || speed.isInfinite()) 1f else speed
    val body = if (safe % 1f == 0f) safe.toInt().toString() else safe.toString()
    return body + "×"
}

/**
 * Nedtelling for søvntimer: «45:00», «09:05».
 * Null/negativ → «00:00» (aldri negative verdier, aldri «0:0»).
 */
internal fun formatSleepCountdown(remainingSeconds: Long): String {
    val s = remainingSeconds.coerceAtLeast(0L)
    val m = s / 60L
    val sec = s % 60L
    return "%02d:%02d".format(m, sec)
}
