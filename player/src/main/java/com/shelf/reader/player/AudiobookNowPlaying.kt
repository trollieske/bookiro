package com.shelf.reader.player

import com.shelf.reader.player.engine.AudiobookChapter

/**
 * Immutable snapshot of the audiobook engine, owned and published by
 * [com.shelf.reader.player.service.AudiobookPlaybackService].
 *
 * This is the single source of truth the player UI and the app-wide mini-player
 * read from. The service pushes updates (player callbacks + a 500 ms ticker while
 * playing); the view model no longer has to poll the binder.
 */
data class AudiobookNowPlaying(
    val bookId: Long = 0L,
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val chapterIndex: Int = 0,
    val chapters: List<AudiobookChapter> = emptyList(),
    val sleepTimerRemainingMs: Long = 0L
)
