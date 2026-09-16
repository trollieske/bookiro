package com.shelf.reader.app

import com.shelf.reader.data.local.entity.BookEntity
import com.shelf.reader.data.local.entity.FormatEntity
import com.shelf.reader.data.local.entity.BookTypeEntity

sealed class ShelfDestinations(val route: String) {
    object Library : ShelfDestinations("library")
    object Books : ShelfDestinations("books")
    object Audiobooks : ShelfDestinations("audiobooks")
    object Player : ShelfDestinations("player/{bookId}") {
        fun routeFor(bookId: Long) = "player/$bookId"
    }
    object Reader : ShelfDestinations("reader/{bookId}?positionPercent={positionPercent}") {
        fun routeFor(bookId: Long, positionPercent: Float? = null): String {
            val base = "reader/$bookId"
            return if (positionPercent != null && positionPercent > 0f) {
                "$base?positionPercent=${"%.4f".format(positionPercent)}"
            } else base
        }
    }
    object Sources : ShelfDestinations("sources")
    object Ftp : ShelfDestinations("ftp")
    object FtpAdd : ShelfDestinations("ftp/add")
    object FtpEdit : ShelfDestinations("ftp/edit/{serverId}") {
        fun routeFor(serverId: Long) = "ftp/edit/$serverId"
    }
    object FtpServer : ShelfDestinations("ftp/server/{serverId}") {
        fun routeFor(serverId: Long) = "ftp/server/$serverId"
    }
    object FtpBrowse : ShelfDestinations("ftp/browser/{serverId}") {
        fun routeFor(serverId: Long) = "ftp/browser/$serverId"
    }
    object Transfers : ShelfDestinations("transfers")
    object Smb : ShelfDestinations("smb")
    object SmbServer : ShelfDestinations("smb/server/{serverId}") {
        fun routeFor(serverId: Long) = "smb/server/$serverId"
    }
    object Webdav : ShelfDestinations("webdav")
    object WebdavServer : ShelfDestinations("webdav/server/{serverId}") {
        fun routeFor(serverId: Long) = "webdav/server/$serverId"
    }
    object Torrent : ShelfDestinations("torrent")
    object Podcasts : ShelfDestinations("podcasts")
    object PodcastDiscover : ShelfDestinations("podcasts/discover")
    object PodcastDetail : ShelfDestinations("podcasts/feed/{feedId}") {
        fun routeFor(feedId: Long) = "podcasts/feed/$feedId"
    }
    object PodcastPlayer : ShelfDestinations("podcasts/player/{episodeId}") {
        fun routeFor(episodeId: Long) = "podcasts/player/$episodeId"
    }
    object ImportProgress : ShelfDestinations("import-progress")
    object Settings : ShelfDestinations("settings")
    object Onboarding : ShelfDestinations("onboarding")
    object BookDetails : ShelfDestinations("book/{bookId}") {
        fun routeFor(bookId: Long) = "book/$bookId"
    }
    object Import : ShelfDestinations("import")
}
