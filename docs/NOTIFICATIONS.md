# Notifications

Android notifications are the app's only out-of-app surface. The rules below are
enforced across every module.

## Every notification is tappable

A notification that does nothing when tapped is a dead end, and users read it as
a bug. Each notification sets a content intent built by
`com.bookrio.core.notifications.AppNotifications.contentIntent(context, route)`.
Tapping opens the app; the `target_route` extra navigates to the relevant screen:

| Source | Route |
| --- | --- |
| Audiobook loading placeholder | `player/<bookId>` |
| Podcast loading placeholder | `podcasts/player/<episodeId>` |
| Import / metadata refresh | `library` |
| FTP / SMB / WebDAV / Calibre transfers | `transfers` |
| Torrent | `torrent` |
| Backup / restore | `backup` |

`MainActivity.consumeNotificationRoute()` turns a tap into a one-shot navigation
event, so it works both on cold start and while the app is already running.

## Foreground placeholders are short-lived and never dead ends

Media playback must call `startForeground()` quickly after
`startForegroundService()`. Bookiro uses a small, **tappable** "Loading…"
placeholder on the media notification id, which Media3 replaces with the real
transport notification as soon as metadata is published. The placeholder is only
posted for an actual load (`ACTION_LOAD_EPISODE` / `ACTION_LOAD_BOOK`); unknown
or system-restart intents never post one, and a load watchdog removes it if
playback never starts. This fixes the "Loading podcast…" notification that could
linger and could not be tapped.

## What deserves a notification

| Operation | Notification |
| --- | --- |
| Media playback | Media3 transport notification (play/pause/skip, opens the player) |
| FTP / SMB / WebDAV / Calibre / torrent transfers | Progress, tap opens Transfers, FTP has Pause/Cancel |
| Podcast episode downloads | System `DownloadManager` notification (live progress) |
| Import | Foreground progress + completion |
| Backup / restore | Progress + Pause/Resume/Cancel |
| Metadata refresh | Foreground progress |
| **Podcast RSS refresh / periodic sync** | **None** — it is silent background work |

Silent background syncs do not notify. If an operation has no user-visible
progress and cannot be cancelled, it should not post at all.

## Android best practices applied

- Channels are declared once with the lowest sufficient importance
  (`IMPORTANCE_LOW` for progress), names and descriptions localized.
- Ongoing progress notifications use `setOnlyAlertOnce(true)`, `setOngoing(true)`
  and a determinate/indeterminate `setProgress`.
- Foreground service notifications use
  `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` and the correct
  `ForegroundInfo` service type (`dataSync` / `mediaPlayback`), so Android 14+
  does not hide or reject them.
- Completion notifications use `setAutoCancel(true)`.
- No OEM-specific workarounds: everything goes through `NotificationCompat` and
  the platform `PendingIntent`/channel APIs.

## Related files

- `core/.../notifications/AppNotifications.kt` — content intents + route names.
- Playback placeholders: `AudiobookPlaybackService`, `PodcastPlaybackService`.
- Workers: `ImportWorker`, `MetadataRefreshWorker`, `FtpNotifications`,
  `SmbSyncWorker`, `WebdavSyncWorker`, `CalibreSyncWorker`,
  `TorrentDownloadWorker`, `BackupWorker`.
