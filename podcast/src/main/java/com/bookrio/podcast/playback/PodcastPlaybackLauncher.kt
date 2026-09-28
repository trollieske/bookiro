package com.bookrio.podcast.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts podcast playback through its dedicated foreground service. */
object PodcastPlaybackLauncher {

    fun play(context: Context, episodeId: Long) {
        val app = context.applicationContext
        val intent = Intent(app, PodcastPlaybackService::class.java).apply {
            action = PodcastPlaybackService.ACTION_LOAD_EPISODE
            putExtra(PodcastPlaybackService.EXTRA_EPISODE_ID, episodeId)
        }
        runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { runCatching { app.startService(intent) } }
    }
}