package com.bookrio.podcast.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts podcast playback through its dedicated foreground service. */
object PodcastPlaybackLauncher {

    /**
     * @param force explicit user play intent (episode tap / resume / mini-player
     *   tap): may autoplay and take playback ownership back from the audiobook.
     *   A passive call defers while the other engine owns playback.
     */
    fun play(context: Context, episodeId: Long, force: Boolean = false) {
        val app = context.applicationContext
        val intent = Intent(app, PodcastPlaybackService::class.java).apply {
            action = PodcastPlaybackService.ACTION_LOAD_EPISODE
            putExtra(PodcastPlaybackService.EXTRA_EPISODE_ID, episodeId)
            putExtra(PodcastPlaybackService.EXTRA_FORCE_PLAY, force)
        }
        runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { runCatching { app.startService(intent) } }
    }
}
