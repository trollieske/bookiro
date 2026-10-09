# Backup & Restore

Bookiro can export the **entire library** to a single portable archive and
restore it after an uninstall, a reinstall, or a package-name change
(`com.bookiro` ↔ `com.bookiro.play`).

Reachable from **Settings → Storage & sync → Backup & restore**.

## What is in an archive

A backup is one ZIP stream (`.zip`, named `bookiro-backup-YYYYMMDD-HHMMSS.zip`):

```
manifest.json            metadata (first entry, so import can plan)
db/shelf.db              consistent SQLite snapshot, app paths tokenized
media/@FILES@/<rel>      app-private files: covers, converted MOBI, remote-source
                         downloads, torrents, unpacked imports, restored files
media/@EXT@/<rel>        external app files dir (e.g. podcast downloads)
media/@RESTORE@/<hash>/  files that lived outside app storage (SAF `content://`
                         or shared paths) and were relocated into the app
```

The database carries everything the library knows: books, audiobooks,
podcast feeds/episodes, reading progress (`position_ms`, `chapter_index`,
`progress_percent`, timestamps), bookmarks, highlights, reading rhythm and
server source rows. Credentials stay encrypted with the Android Keystore; after
a reinstall the old key is gone, so restored sources fall back to
"needs sign-in" instead of crashing.

## Fine-grained options

`BackupOptions` selects what is packed; three presets cover the common cases:

| Preset | Includes |
| --- | --- |
| **Everything** | every media category + sources + history + annotations |
| **Library only** | database, covers, converted files and annotations (no bulk media, no sources, no history) |
| **Custom** | individual toggles |

Individual toggles: covers & artwork, converted files, remote downloads
(FTP/SMB/WebDAV/Calibre), torrents, podcast audio, external/SAF media, server
sources, reading history, bookmarks & highlights. Excluded DB tables are pruned
from the staged snapshot, so the restored database is self-consistent.

## Background, pause & cancel

Export and restore run as **foreground WorkManager work** (`BackupWorker`).
The user can leave the screen; a notification shows live progress with
**Pause/Resume** and **Cancel**. Pause stops at a file boundary (a paused
archive always resumes where it stopped); cancel stops the coroutine and removes
the partial document. The app observes `WorkManager` progress so re-opening the
screen shows the running operation.

A restore swaps `shelf.db` and sets a persisted *restore-pending* flag. The live
UI restarts the process, and a cold start consumes the flag (the database is
already restored) so a background restore is never missed and never loops.

## Scheduled backups

Enable **Scheduled backups**, pick a **backup folder** (SAF tree, persisted
grant), a frequency (daily / every 3 days / weekly), optional *Wi-Fi only* and
*while charging* constraints, and how many copies to keep (1/3/5). Each run
creates a new `bookiro-backup-*.zip` in the folder and prunes older ones.
"Back up now" runs the same path immediately.

## Speed

- The database is snapshotted with `VACUUM INTO` on API 30+ (or a WAL
  checkpoint + copy below that) and deflated normally.
- Media is streamed with a 64 KB buffer. Already-compressed files (audio,
  epub, pdf, images, archives) are deflated at level 0, so the engine never
  wastes CPU recompressing them.
- Only the SAF stream is held open; a multi-GB library never lands in memory.

## Quality of life

- **Last backup** card with date, size and file name.
- **Copy to…** moves the finished archive to a new location.
- **Share** sends the archive to another app.
- Progress and pause/cancel are available from the notification.

## Tests

- `app/src/test/.../backup/BackupFormatTest.kt` — token round-trip, external
  ref classification and manifest JSON.
- `app/src/test/.../backup/BackupOptionsTest.kt` — options JSON, presets.
- `app/src/androidTest/.../BackupRoundTripInstrumentedTest.kt` — real
  export → wipe → import of a book, progress and file; relocation of an
  external file; and a WorkManager `BackupWorker` background export.
