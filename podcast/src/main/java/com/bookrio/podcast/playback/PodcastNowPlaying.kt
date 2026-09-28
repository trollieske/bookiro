package com.bookrio.podcast.playback

/**
 * Immutable snapshot of the podcast engine, owned and published by
 * [PodcastPlaybackService]. The player UI and the resume card read from this
 * instead of polling the binder.
 */
data class PodcastNowPlaying(
    val episodeId: Long = 0L,
    val feedId: Long = 0L,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val sleepTimerRemainingMs: Long = 0L
)
