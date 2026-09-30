package com.bookrio.player.ui

/**
 * Pure formatting helpers for [BookrioPlayerScreen] — the common-Kotlin twin of
 * the Android `player/ui/PlayerFormatting.kt`, but without `String.format`
 * (JVM-only) so it works on Kotlin/Native.
 *
 * Names are prefixed `bookrio…` on purpose: the Android `:player` module has
 * top-level functions in the same package, and identical top-level names in the
 * same package across modules would be ambiguous if the Android app ever puts
 * both modules on one classpath.
 */

/**
 * «12:34» / «1:02:03», never negative. Mirrors Android `formatDuration`
 * (`%d:%02d:%02d` with the hour part omitted below one hour).
 */
internal fun bookrioFormatDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = (safe % 3_600L) / 60L
    val seconds = safe % 60L
    return if (hours > 0L) {
        "$hours:${bookrioPad2(minutes)}:${bookrioPad2(seconds)}"
    } else {
        "$minutes:${bookrioPad2(seconds)}"
    }
}

/**
 * «0.5×», «0.75×», «1×», «1.25×», «1.5×», «2×», «3×».
 * Never a trailing «.0», never spaces. Mirrors Android `formatPlaybackSpeed`.
 */
internal fun bookrioFormatSpeed(speed: Float): String {
    val safe = if (speed.isNaN() || speed.isInfinite()) 1f else speed
    val body = if (safe % 1f == 0f) safe.toInt().toString() else safe.toString()
    return body + "×"
}

/**
 * Sleep-timer countdown: «45:00», «09:05», never negative. Mirrors Android
 * `formatSleepCountdown` (`%02d:%02d`, so 90 minutes reads «90:00»).
 */
internal fun bookrioFormatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    return "${bookrioPad2(seconds / 60L)}:${bookrioPad2(seconds % 60L)}"
}

private fun bookrioPad2(value: Long): String = if (value < 10L) "0$value" else value.toString()