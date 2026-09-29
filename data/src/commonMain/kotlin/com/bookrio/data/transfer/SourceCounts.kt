package com.bookrio.data.transfer

/** Per-source aggregate used by the source cards. Shared (no platform APIs). */
data class SourceCounts(
    val sourceRef: String,
    val total: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val paused: Int = 0,
    val retrying: Int = 0
)