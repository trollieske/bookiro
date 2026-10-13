package com.bookrio.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Shared notification plumbing.
 *
 * Bookiro's rule: **every notification is tappable**. A notification that cannot
 * be tapped is a dead end, so each one gets a content intent that opens the app,
 * optionally on the screen that matters (transfers, the running podcast, …).
 *
 * The route is read by `MainActivity` and dispatched to the Compose navigator, so
 * feature modules never need to depend on the app module's navigation types.
 */
object AppNotifications {

    /** Intent extra consumed by `MainActivity` to navigate on launch/tap. */
    const val EXTRA_TARGET_ROUTE = "target_route"

    /**
     * Route strings understood by the app shell. Kept in sync with
     * `com.bookrio.app.ShelfDestinations`.
     */
    object Routes {
        const val HOME = "home"
        const val LIBRARY = "library"
        const val PODCASTS = "podcasts"
        const val TRANSFERS = "transfers"
        const val SOURCES = "sources"
        const val BACKUP = "backup"
        const val FTP = "ftp"
        const val CALIBRE = "calibre"
        const val SMB = "smb"
        const val WEBDAV = "webdav"
        const val TORRENT = "torrent"

        fun podcastPlayer(episodeId: Long) = "podcasts/player/$episodeId"
        fun audiobookPlayer(bookId: Long) = "player/$bookId"
    }

    /**
     * Content intent that opens the app's launcher activity, optionally on [route].
     * Returns null only if the platform has no launch intent (should not happen).
     */
    fun contentIntent(
        context: Context,
        route: String? = null,
        requestCode: Int = 0,
    ): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!route.isNullOrBlank()) launch.putExtra(EXTRA_TARGET_ROUTE, route)
        return PendingIntent.getActivity(
            context,
            requestCode,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
