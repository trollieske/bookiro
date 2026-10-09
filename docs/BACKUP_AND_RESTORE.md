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
media/@EXT@/<rel>        external app files dir (e.g. unpacked imports)
media/@RESTORE@/<hash>/  files that lived outside app storage (SAF `content://`
                         or shared paths) and were relocated into the app
```

The database carries everything the library knows: books, audiobooks,
podcast feeds/episodes, reading progress (`position_ms`, `chapter_index`,
`progress_percent`, timestamps), bookmarks, highlights, reading rhythm and
server source rows. Credentials stay encrypted with the Android Keystore; after
a reinstall the old key is gone, so restored sources fall back to
"needs sign-in" instead of crashing.

## Why paths are tokenized

The install's data directory contains the application id
(`/data/user/0/com.bookiro/files`), which changes between the `full` and
`playstore` flavors. Before the database is archived, every TEXT column is
rewritten so app roots become `@FILES@`, `@EXT@`, `@EXT1@`, … and files copied
from outside app storage become `@RESTORE@/<hash>/<name>`. Import resolves the
tokens against the *current* roots, so the same backup restores correctly into
either flavor. `chapters_json` media URIs are covered automatically because the
rewrite touches every TEXT column.

## Speed

- The database is snapshotted with `VACUUM INTO` on API 30+ (or a WAL
  checkpoint + copy below that) and deflated normally.
- Media is streamed with a 64 KB buffer. Already-compressed files (audio,
  epub, pdf, images, archives) are deflated at level 0, so the engine never
  wastes CPU recompressing them.
- Only the SAF stream is held open; a multi-GB library never lands in memory.
- "Include media files" can be turned off for a fast metadata-only archive.

## Restore semantics

1. The archive is streamed to a staging directory in `cacheDir`.
2. Media entries are resolved token-by-token to their final locations and
   written there.
3. The staged database has its tokens resolved and then atomically replaces the
   live `shelf.db`. `ShelfDatabase.resetInstance()` closes the Room singleton
   first.
4. The process restarts (`ShelfDatabase` is rebuilt on the next launch), so no
   ViewModel or playback service keeps a closed database handle.

## Tests

- `app/src/test/.../backup/BackupFormatTest.kt` — token round-trip, external
  ref classification and manifest JSON.
- `app/src/androidTest/.../BackupRoundTripInstrumentedTest.kt` — real
  export → wipe → import of a book, its progress and its file, plus relocation
  of a file outside app storage into `filesDir/restored`.
