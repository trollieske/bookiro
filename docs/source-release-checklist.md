# Source release checklist (physical device)

Run every line before shipping a build that touches sources, transfers or the
torrent client. Items marked **(live)** need a real server/account and are not
covered by the automated fixtures.

## Build and static checks

- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew :data:testDebugUnitTest :calibre:testDebugUnitTest :webdav:testDebugUnitTest :torrent:testDebugUnitTest :ftp:testDebugUnitTest` is green.
- [ ] `python3 tools/validate_localization.py` passes.
- [ ] No plaintext credentials in `adb logcat` while browsing/downloading.
- [ ] No `passkey=`/`key=`/`token=` value appears unmasked in logs or UI.

## Shared source / transfer behaviour

- [ ] Add an SMB, a WebDAV and a Calibre source; each survives a full app
      restart from the recents screen.
- [ ] Start a large download, navigate back to the book list, lock the phone,
      change Wi-Fi → mobile, unlock. The transfer continues and Transfers shows
      the correct state.
- [ ] Force-stop the app mid-download; on relaunch the file resumes or restarts
      and is never imported truncated.
- [ ] Rotate the device mid-download; the source and queue are unchanged.
- [ ] A failed listing (server off) does not delete the source or clear its
      credentials.
- [ ] Global Transfers shows SMB/WebDAV/Calibre/FTP work from anywhere in the app.

## SMB

- [ ] SMB2/SMB3 share with a domain account and with a local account.
- [ ] Wrong password shows an auth error, not an empty list.
- [ ] Unknown share shows a distinct "share not found" error.
- [ ] Download EPUB + a multi-file M4B audiobook folder and verify one library
      entry each.

## WebDAV

- [ ] Nextcloud/ownCloud base URL and a plain WebDAV server both connect.
- [ ] Files with spaces, nordic characters and apostrophes list and download.
- [ ] A server that ignores `Range` restarts honestly with a clear status.
- [ ] A cross-origin redirect never sends the Authorization header (verify with
      a proxy log).

## Calibre

- [ ] Content Server without auth and with `--enable-auth` (Digest).
- [ ] Navigate library/author/series/tag, search, and download EPUB + M4B.
- [ ] A wrong password shows an auth error, not a parse error.

## Torrent **(live, user's own authorized torrent only)**

- [ ] Public magnet and `.torrent` still download and import.
- [ ] A `.torrent` the user is authorized to use on a private tracker imports,
      and the Connection details view shows `Private`, DHT/PEX/LSD `Off`, and
      the tracker status.
- [ ] A rejected tracker shows a precise, masked reason (passkey/client policy),
      not "no seeds".
- [ ] `Reannounce now` is rate-limited (a second tap within a minute does not
      reannounce).
- [ ] The user is never asked for a tracker passkey by the app.
- [ ] HTTPS tracker certificate validation is on (a bad certificate is reported,
      not silently accepted).
- [ ] Seeding policy is respected: `Stop when downloaded` stops, the other
      policies keep seeding while allowed.

## Known external limits

- A private tracker may still refuse the honest `Vierel` client if its
  whitelist does not include it. The app reports this instead of spoofing.
- Trackers with untrusted HTTPS certificates cannot be used in release; the app
  reports the TLS failure rather than bypassing validation.
- Background seeding cannot be guaranteed by Android without a foreground
  service; seeding stops when the process ends.
- SMB resume over the current jcifs input-stream API is not available; a
  restarted download is used instead of a false resume.