package com.bookrio.core.time

import kotlinx.datetime.Clock

/** Multiplatform wall-clock millis (replaces java.lang.System.currentTimeMillis()). */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()