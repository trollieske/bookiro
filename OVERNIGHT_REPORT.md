# OVERNIGHT_REPORT.md — Bookiro autonomous review

Branch: `overnight-review` (based on `feat/home-onboarding`, commit `da45a9e`, vc11)
Worktree: `/home/ck3k/Work/wt-polish`
Date: 2026-10-03

---

## 0. Final build status (top of report)

| Check | Result |
|---|---|
| `assembleDebug` (all modules) | **BUILD SUCCESSFUL** |
| On-device clean install (OnePlus 13 / CPH2653, Android 16) | **Installed, launched, onboarding + folder picker verified, no crash** |
| `testDebugUnitTest` (all modules) | **339 tests, 0 failures, 0 errors** (baseline was also green) |
| `lintDebug` (every module, incl. `:app`) | **BUILD SUCCESSFUL** — baseline had **26 lint errors** in `:core` (2), `:player` (20), `:ftp` (4) |
| `assembleRelease` / `bundleRelease` | **BUILD SUCCESSFUL** (see §0.1) |
| `tools/validate_localization.py` | **All checks passed** (was failing 1 hardcoded-log false positive) |
| `tools/i18n_check.py` | **0 hard problems** (missing keys / placeholders / plurals) |

Baseline (before any change), for the record:
- `./gradlew assembleDebug` OK.
- 339 unit tests, 0 failures.
- `lintDebug`: **FAILED** — `:core:lintDebug` aborted with 2 `MissingPermission` errors; `:player:lintDebug` also had 20 errors and `:ftp:lintDebug` 4 (not reached because lint aborted at `:core` first).
- `tools/validate_localization.py` reported 1 problem: a Norwegian string inside the `chapterDiag` developer log in `:player`.

Environment note: the committed `gradle.properties` contains a Windows JDK path
(`org.gradle.java.home=C:/Users/tlarsen/.jdks/jbr-21.0.11`). On this Linux machine the
build was run with `-Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk`. **This will
break any Linux/macOS CI clone** and should be made machine-local (e.g. remove it from the
committed `gradle.properties` and use an env var / local file). See Needs human review.

### 0.1 Release build (final)
`assembleRelease` + `bundleRelease` **BUILD SUCCESSFUL** (with the R8 `Log`-stripping rule).
Produced `app-release.aab` and split APKs (arm64 ≈ 32 MB, armeabi-v7a ≈ 30 MB).
Release is already R8-minified + resource-shrunk, so the arm64 download is much smaller
than the 60 MB debug APK. The release build is still signed with the **debug keystore**
(see blocker #2).

---

## 1. Summary and verdict

**Verdict: ready for closed testing (internal/closed track)** — the app builds, all 339 unit tests pass, every module lint task is green, and release assembles. Remaining items are owner decisions (release signing key) and Data Safety paperwork, not code blockers.

Status of the previous blockers:
1. ~~`targetSdk` below Play requirement~~ — **FIXED**, now API 36.
2. **Release signing uses the debug keystore** — owner decision: stay on debug for now; required before a public upload.
3. ~~Torrent UI links to Libgen~~ — **FIXED**, removed.
4. **Audible/audnex chapter lookup** — owner decision: **keep and disclose** in Data Safety + privacy policy.
5. Leftover LAN debug telemetry — **FIXED** (removed).

---

## 2. Fixed bugs

All commits are on `overnight-review`.

| # | Commit | Module / file | Problem | Fix |
|---|---|---|---|---|
| B1 | `c440b35` | `core/src/main/AndroidManifest.xml` | Lint `MissingPermission`: `LanSourceDiscovery` reads `getActiveNetwork`/`getLinkProperties` but `:core` declared no permission, aborting the whole lint build. | Declared `ACCESS_NETWORK_STATE` + `ACCESS_WIFI_STATE` in the `:core` manifest. |
| B2 | `099004a` | `app` + `core` (`LanSourceDiscovery.label`, `MainActivity.kt`) | `DiscoveredSourceCandidate.label` was hardcoded Norwegian and rendered verbatim in Settings → LAN discovery. | Added `lan_type_*` keys to all 10 locales; UI now resolves the label from the enum. |
| B3 | `449da6b` | `player/engine/ChapterRefresh.kt` | `CHAPTER_REFRESH_DIAG = true` left on: verbose book-id/chapter logging in release. | Defaulted to `false`. |
| B4 | `cad1d59` | `player/engine/AudiobookEngine.kt` | Three `chapterDiag` log strings in Norwegian. | Translated to English (developer logs only). |
| B5 | `f51d35b` | `app/Screens.kt`, `app/workers/ImportWorker.kt`, `player/ui/PlayerScreen.kt`, `player/viewmodel/PlayerViewModel.kt` | **Privacy/Play:** four debug helpers POSTed JSON diagnostics — including `fileUri`/`filePath` — to a hardcoded LAN IP `192.168.1.10:7777` on every screen/action, in release builds. | Neutralised to no-ops, removed the `java.net` imports. |
| B6 | `1ef184d` | `ftp`, `torrent` manifests; `player/engine/AudiobookEngine.kt`; `player/service/AudiobookPlaybackService.kt`; `ftp/worker/FtpSyncWorker.kt` | Pre-existing lint errors that had never been reached (lint aborted at `:core`): missing `ACCESS_NETWORK_STATE` in `:ftp`/`:torrent`; `MediaStore.Downloads.getContentUri` used on minSdk 26 (**NoClassDefFoundError** on API 26–28 when looking up `.cue` sidecars); Media3 `UnstableApi` opt-in missing; deprecated `SessionResult.RESULT_ERROR_*` constants; WorkManager `await` `RestrictedApi`. | Added permissions; guarded the API-29 call behind `Build.VERSION_CODES.Q`; `@OptIn(UnstableApi)` + `SessionError` codes; suppressed the RestrictedApi false positive. All module lint now passes. |
| B7 | `85ff8f4` | `app/src/main/AndroidManifest.xml`, backup rules | Unused `BLUETOOTH` / `BLUETOOTH_CONNECT` permissions; SMB/WebDAV encrypted prefs were not excluded from cloud backup / device transfer. | Removed the Bluetooth permissions; added the SMB/WebDAV `EncryptedSharedPreferences` files to both backup rule files. |
| B8 | `d20e06f` | `torrent` engine/worker/viewmodel/UI | Several real torrent bugs (see below). | Fixed. |

### 2.1 Verification of the three debug-*.md crash areas

- **`debug-shelf-ebook-audiobook-torrent-crashes.md` B1 (EPUB "format not supported")** — the fixes are present in `core/.../BookFormatParsers.kt` (case-insensitive `container.xml` lookup via `zip.getEntry` fallback + `equals(..., ignoreCase = true)`, attribute-order-tolerant `<rootfile … full-path>` regex, deep `.opf` fallback). `ReaderViewModel.load()` wraps the whole load in `runCatching`, merges a localized `rdr_error_no_readable_chapters` / `rdr_error_cannot_open_book*` message, and never throws to the UI. No device repro was possible here; code path inspected.
- **B2 (audiobook start crash, OnePlus 13 / Android 15)** — `AudiobookPlaybackService.onStartCommand` calls `startForegroundLoading()` immediately (wrapped in try/catch) to satisfy the FGS contract; the Media3 `MediaLibrarySession` callback now compiles with the required `UnstableApi` opt-in and valid `SessionError` codes (the previous deprecated `SessionResult.RESULT_ERROR_*` usage was one of the pre-existing lint errors). Playback start still needs a device pass.
- **B3 (torrent `UnknownFormatConversionException`)** — verified no `String.format(url, …)` remains; the preset sources use `url.replace("%s", URLEncoder.encode(...))`. Only numeric `%.1f`/`%.2f` formatting remains (safe).
- **`debug-ebook-audio-crash.md` H5 (process-death null bookId)** — `ReaderViewModel` reads the book id from navigation args and restores progress defensively (`coerceIn`, `runCatching`); no unguarded `!!` on `bookId` was found in the reader/player VMs. Device process-death test still required (§9 step 9).

Details of B8 (torrent):
- **Pause was fake.** `TorrentDownloadWorker.pauseActiveDownloads` used a dead reflection call (`getDeclaredMethod("sessionManager")` — the method does not exist), so on low battery / metered network only the DB changed and libtorrent kept downloading. Now calls `TorrentEngine.pauseDownload(id)`.
- **`pauseAll()` / `resumeAll()` only changed the DB.** Now also pause/resume the native handles.
- **Crash risk.** `addMagnet` / `addInfoHash` / `reimportTorrent` / `importCustomTorrentFolder` called into the engine/importer with no `runCatching`; a malformed magnet or unreadable folder crashed the app. Now wrapped with a localized error toast.
- **Data-loss risk.** Re-import deleted any torrent-imported book whose *title contained the first 15 chars of the torrent display name* (`fileSize <= 1 KB OR title match`), which could delete an unrelated real book. Restricted to genuinely tiny (≤1 KB) rows.
- **Wasted work on every launch.** The worker started the native session + a foreground notification on every app start and `runNow` used non-unique `enqueue` (workers could pile up). The worker now returns early when there is nothing pending, and `runNow` uses `enqueueUniqueWork(..., KEEP)`.
- **Missing notification permission** on the Torrent route; now requests `POST_NOTIFICATIONS`.
- Added a localized "Only download content you have the rights to" notice next to the preset sources.

### 2.2 Round 2 additions

| Commit | Area | Change |
|---|---|---|
| `616a0e3` | Play | **Removed the Libgen preset source.** Only Internet Archive, Standard Ebooks and Project Gutenberg remain. |
| `b5dd0a7` | Functional (high) | **Added a network security config.** There was none, so on targetSdk 28+ Android blocked every `http://` connection — WebDAV, Calibre Content Server and plain-HTTP podcast feeds on a LAN failed with *CLEARTEXT communication not permitted*. Cleartext is now permitted (LAN IPs cannot be domain-allow-listed) with system TLS anchors kept. |
| `da4e037` | Torrent | Cap failed session adds at 10 → `FAILED` (no more 2-second retry loop); convert 32-char base32 magnet infohashes to hex + case-insensitive `xt` match; `deleteDownload(withFiles=true)` only deletes paths strictly inside the private torrent dir; torrent notification now opens the app. |
| `d8f6044` | Torrent / Settings | **The torrent background / Wi-Fi-only / charging-only / min-battery settings now actually work.** They were written to DataStore but never read; the worker hardcoded the values and was scheduled regardless of the master toggle. New `applyUserSettings()` is called on app start and after every toggle. |
| `ac261ff` | UI | Torrent settings section used a hardcoded purple that clashed with the single lime HUD accent; Home empty-state Sources link now has a 48 dp touch target. |
| `ebe7d52` | Perf / privacy | R8 `-assumenosideeffects` strips `Log.v/d/i` from release builds (smaller APK, fewer path/URI strings in logs); `Log.w/e` kept. |
| `ab8a46f` | i18n | Reader bookmark save/remove HUD and stored bookmark title/snippet were hardcoded Norwegian/English (new `rdr_bookmark_saved`, `rdr_bookmark_removed`, `rdr_page_n` keys, all 10 locales). The torrent summary showed the raw `TrackerState` enum and picked the error colour via `contains("feil")/"error"`; now carries the enum and uses the localized `toru_tr_*` labels. |
| `e042b3c` | i18n | Removed the last hardcoded Norwegian in `:core`/`:designsystem` (PDF/CBZ parse-failure HTML, MOBI magic-byte exception, not-downloaded cover `contentDescription`) — now English base locale; full resource localization needs `Context` plumbed into the parsers. |
| `54c3b62` | UI (found on device) | Onboarding and Settings showed the raw MediaStore tree id (e.g. `11365`) instead of the folder name when the library folder is a Downloads/MediaStore tree (`msf:11365`). Now resolves the provider `DISPLAY_NAME` first and falls back to the path parser. Verified on the OnePlus 13: label now reads `BOOKIRO`. |
| `0efac71` | **Reader (major, found on device)** | **"File not found." on every SAF-imported EPUB.** EPUBs render through Readium, but `ReadiumEpubReaderScreen` required a local `book.filePath` and bailed out immediately for books imported from a library folder, which only have a `content://` `fileUri` (`filePath == null`). `ReadiumPublicationOpener.open()` now takes both path and uri and reads the SAF URI through Readium's ContentResolver factory. Verified on the OnePlus 13: a scoped-storage EPUB now renders real content. |
| `5f78090` | UI (found on device) | Readium page was inset from the cutout/rounded corners, so the app's black background showed as bars around light/sepia pages. The container is now painted with Readium's own theme background (light `#FFFFFF`, sepia `#FAF4E8`, night `#000000`). Verified in dark and sepia. |
| `a0954cb` | Reader feature (on device) | Book images could sit off-centre because of the EPUB's own `text-align`/float; a small injected stylesheet centres block images. Tapping an illustration now opens a full-screen zoom viewer (pinch / double-tap / close). Verified on the Shannara map in the OnePlus 13. |
| `460c1db` | FTP UX (reported) | "Sync now" on a large library looked dead because the worker lists the whole remote tree before creating any download row, and the UI only showed transfer counts. `FtpTransferRuntime` now tracks a per-server **preparing** state with a live file count (auto-expiring), reported per folder by `listDirectoryRecursive`/`FtpQueuePlanner`; both the sources list and details screen show a spinner + "Scanning remote folder… N files found" (all 10 locales). |
| `24b6668` | **Library duplicates (major, reported)** | The scheduled media scan re-imported scoped-storage books every run: the ebook upsert only de-duplicated by `file_path`, which is `null` for SAF imports, so a new row was inserted each scan. On device the library had grown to **1938 rows for ~500 books** (titles repeated 6–26×). Import now also de-duplicates by `file_uri`; a new `deduplicateLibrary()` collapses exact-file rows and same title+author rows (EPUB > PDF > MOBI > … > TXT, progress moved to the survivor, duplicates soft-deleted, idempotent). Audiobooks had no title+author duplicates. |
| `81ab22f` | Library | Re-imported titles were cleaned (`06 Red Country` → `Red Country`); series indexes parsed as authors (`the 01`) blanked via `BookTitleCleaner` (core, unit-tested) and an author guard. Repairs now run at app start (background warm-up thread), dedup before title cleanup. **On-device: 1938 → 503 active rows, 0 duplicate file uris, 0 `the 01` authors.** |
| `3371988` | **Audiobooks (major, reported)** | `consolidateFragmentedAudiobooks` grouped by a path hash that is **constant when `filePath` is blank**, so every scoped-storage audiobook with a generic/blank title collapsed into one (taking the first book's title/author) — and it re-inserted the canonical's tracks on each run, multiplying them (e.g. 28 rows for 4 files). Consolidation now only considers audiobooks with a real local `filePath`; it wipes and rebuilds the group's tracks once. New repairs: `repairDuplicateAudioTracks()` (dedup + renumber) and `splitMergedAudiobooks()` (conservative split of clearly-merged books, reusing an existing row per file so re-runs can't duplicate). **On-device: 0 duplicate tracks/books; The Martian 28 rows/51 h → 1 track/10.9 h; Galaphile, His and Hers, And Then There Were None split back out.** |

---

## 3. Performance improvements

- **Torrent background work** no longer spins up the native `SessionManager` and a foreground notification on every cold start; it bails out before `engine.start()` when no download is pending/running/paused. Expected effect: lower cold-start cost and no spurious notification for the majority of users who never use torrents. (`d20e06f`).
- **Torrent worker de-duplication** (`enqueueUniqueWork KEEP`) prevents a backlog of `OneTimeWork` jobs accumulating across launches.
- **Cover file existence checks** in `HomeViewModel` run on `Dispatchers.Default` and only for the ≤12 rows actually shown — no change needed.
- Recommendations not applied (need device verification): the ProGuard keep rules are very broad (`-keep class androidx.compose.**`, `kotlinx.coroutines.**`, `com.bookrio.data.**`). No serialization/reflection library needs them; trimming them is the obvious lever for the ~60 MB arm64 / ~90 MB universal APK, but it must be validated with a release smoke test. See §8.
- `:torrent` `TorrentEngine` still has dead code (`extractAnnounceUrlsFromBencode`, `computeTorrentInfoHash`) and no `getFreeSpace` low-storage handling; not changed (low risk, low value).

---

## 4. i18n findings

Tooling added: `tools/i18n_check.py` (key/placeholder/plural parity + untranslated heuristic).
The repo already had `tools/validate_localization.py`; it now reports **All localization
validation checks passed** (previously 1 false positive from a Norwegian developer log).

- **Key parity:** every module has an identical key set across all 9 locales (`en, nb, da, sv, fr, de, es, ru, uk`) + the default `values/` (English). No missing keys, no extra keys.
- **Placeholders:** no `%1$s` / `%1$d` / bare `%s` mismatches. Plurals: no mismatches.
- **Hardcoded user-facing text:** fixed the only real instance (LAN discovered-source labels). `validate_localization.py` now passes its hardcoded-string scan.
- **Untranslated heuristic:** the checker flags ~1300 "identical to English" strings, but after inspection these are almost all legitimate: protocol/brand words (`FTP`, `SMB`, `WebDAV`, `Torrent`, `Calibre`), the format list (`EPUB, PDF, MOBI…`), language endonyms (`Norsk`, `Deutsch`), `Pause`/`Format`, etc. No action taken.
- **Text-overflow:** labels use `maxLines` + `TextOverflow.Ellipsis` on Home tiles, rows, onboarding folder path and the reader; German/Finnish-length strings (e.g. `Hörbücher`, `Quellen`) fit the 3-across tiles. No truncation bug found by inspection; needs the device pass in §9.
- **Locale config** (`xml/locale_config.xml`) lists exactly the 9 supported tags.

---

## 5. QoL / UI improvements

- Home screen was already in good shape from the previous release (stable LazyColumn keys, loading state, empty state with a primary action, 48 dp-ish tiles, dark/light tokens from `:designsystem`). No change was needed beyond the torrent notice.
- Torrent: added the rights notice; notification permission now requested.
- No new components were invented; everything uses `:designsystem`.
- Not done (papercuts, see §7): the Settings → Torrent "background / Wi-Fi only / charging / min battery" controls are **dead UI** — they write preferences that no code reads. The worker hardcodes `wifiOnly=true`, `chargingOnly=false`, `minBattery=15`.

---

## 6. Play Store readiness checklist

| Item | Status | Notes |
|---|---|---|
| `targetSdk` meets current requirement | **OK** | `targetSdk = 36` (Android 16) — current Play requirement. |
| `compileSdk` | **OK** | `compileSdk = 36`. |
| R8 minify + shrinkResources | **OK** | `isMinifyEnabled = true`, `isShrinkResources = true` in release. |
| ProGuard keeps cover native/reflection | **OK (conservative)** | libtorrent4j JNI, SMB/FTP/SSH/SLF4J/BouncyCastle covered; broad Compose/coroutines keeps are safe but bloat the APK. |
| ABI splits / 64-bit | **OK** | arm64-v8a, armeabi-v7a, x86_64, universal; 64-bit present. |
| Version sanity | **OK / review** | versionCode 11, versionName `1.0.0-readium9` — the `-readium9` suffix will show to users; consider `1.0.0`. |
| Adaptive icon / round icon | **OK** | `ic_launcher` + `ic_launcher_round`. |
| `allowBackup` + data extraction rules | **OK (improved)** | Intentional; credentials excluded (FTP plain+enc, now SMB/WebDAV). |
| No debug code / verbose release logging | **FIXED** | Removed LAN telemetry, disabled chapter diag. |
| Release signing injectable without secrets | **DEFERRED (owner decision)** | Release currently uses the debug keystore; owner chose to stay on debug for now. Must be replaced before a public upload. |
| `bundleRelease` configures/builds | see §Release result | |
| Permissions minimised | **OK (improved)** | Removed Bluetooth. No `MANAGE_EXTERNAL_STORAGE`, no storage permissions at all (SAF). |
| Foreground service types | **OK** | `mediaPlayback` (audio+podcast), `dataSync` (WorkManager host for torrent/FTP). |
| Privacy / Data Safety data | **NEEDS ACTION** | See §6.1. |
| Torrent policy risk | **NEEDS DECISION** | Libgen link + torrent client. See §8. |
| Secrets / keystores committed | **OK** | `local.properties` is gitignored; no keystores or secrets in the diff (a secret scan is item in §9). |

### 6.1 Permissions table (merged manifest)

| Permission | Source | Needed? | Justification |
|---|---|---|---|
| `INTERNET` | app | **Yes** | remote sources, podcast streaming, torrent, metadata. |
| `ACCESS_NETWORK_STATE` | app + WorkManager + `:core`/`:ftp`/`:torrent` | **Yes** | connectivity checks for sync/torrent/podcast. |
| `ACCESS_WIFI_STATE` | app + `:core` | **Yes** | LAN source discovery (`WifiManager.connectionInfo`). |
| `FOREGROUND_SERVICE` | app + WorkManager | **Yes** | media + data-sync services. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | app | **Yes** | audiobook/podcast playback. |
| `FOREGROUND_SERVICE_DATA_SYNC` | app | **Yes** | torrent/FTP download workers. |
| `POST_NOTIFICATIONS` | app | **Yes (runtime)** | media/transfer notifications; requested at runtime. |
| `WAKE_LOCK` | app + WorkManager + setWakeMode | **Yes** | network wake lock during playback/downloads. |
| `RECEIVE_BOOT_COMPLETED` | app + WorkManager | **Yes** | reschedule WorkManager after reboot. |
| `VIBRATE` | app | Review | no vibration code found, but media notification channels may rely on it. Kept (normal permission, low risk). |
| `BLUETOOTH`, `BLUETOOTH_CONNECT` | app | **Removed** | unused; app never touches `BluetoothAdapter`. |
| `com.bookrio.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx (debug only) | Auto | debug manifest only. |

### 6.2 Data Safety notes (what actually leaves the device)

- **No accounts, no analytics, no ads, no crash reporting SDK** (verified by dependency scan).
- **Book metadata enrichment** (only when `onlineCoverLookup` is enabled, default **off**): title/author/ISBN sent to `openlibrary.org`, `covers.openlibrary.org`, `www.googleapis.com` (Books), `itunes.apple.com`.
- **Audiobook chapter lookup** (runs automatically when an audiobook has a single/generic chapter): book **title + author** sent to `api.audible.com` and `api.audnex.us`. Applies to a third party (Amazon/Audible + audnex). **Owner decision (2026-10-03): keep the feature and disclose it** in the Data Safety form and privacy policy. No code change.
- **User-configured servers:** FTP/FTPS/SFTP, SMB, WebDAV, Calibre — credentials stored encrypted (`EncryptedSharedPreferences` / Keystore ciphertext in Room); never logged.
- **Torrent:** DHT/tracker/peer traffic; download path is app-private storage (`filesDir/shelf_torrents`).
- **Podcasts:** subscriber RSS URLs and episode streams are fetched directly.
- **TanStack/HTTP client:** no telemetry endpoints.

Privacy policy must state: local-first; the metadata/chapter endpoints above; that
credentials never leave the device; and (for the EU) the legal basis for those lookups.

---

## 7. Skipped or reverted changes and why

- **targetSdk/compileSdk bump to 36** — **DONE** in `fbbadb7`. Android 16 behavior changes (edge-to-edge, large-screen resizability) still need the device pass.
- **Release signing config** — skipped per hard rule #3 (do not change signing configs). Reported instead.
- **Trimming the broad ProGuard keeps** — skipped: size optimisation without a release smoke test risks reflection breakage in SMB/WebDAV/torrent.
- **`HandoffRepository.runBlocking` inside `db.runInTransaction`** — identified as a deadlock/ANR risk in the ebook↔audio handoff path; left as-is because there are no unit/instrumented tests around it and a wrong refactor could break handoff. Flagged in §8.
- **Settings → Torrent toggles are dead UI** — not wired (would need worker/scheduler plumbing). Flagged in §8.
- **`TorrentEngine.stop()` runs native `SessionManager.stop()` on the main thread** (called from `onCleared`) and `running` is a non-volatile flag; start/stop race possible. Not changed (touches native lifecycle; needs device testing). Flagged in §8.

---

## 8. Needs human review (ranked)

1. ~~**API 36 target** (blocker)~~ — **DONE** in `fbbadb7`. Only remaining question is whether to go further to API 37, which needs an AGP/Gradle upgrade first.
2. **Release signing** — owner decision: stay on debug for now. Still required before a public upload: add a release signing config sourced from env/`local.properties` (never committed).
3. **Libgen preset source** — **FIXED** in `616a0e3` (removed). Only legitimate public-domain sources remain.
4. **Audible/audnex chapter lookup** (privacy) — owner decision: **keep and disclose** (the feature is valued). Ensure it is covered in the Data Safety form and privacy policy (title + author are sent to `api.audible.com` / `api.audnex.us`).
5. **`gradle.properties` Windows JDK path** — remove from the committed file so CI on Linux/macOS works.
6. **Handoff repository** — ~~replace `runBlocking` inside `db.runInTransaction`~~ **FIXED** in `45a31bd` (`db.withTransaction`). Still worth a device pass of the ebook↔audio handoff; no unit test covers it.
7. **Torrent Settings toggles** — **FIXED** in `d8f6044` (`applyUserSettings()` now reads the prefs on start and on change).
8. **Torrent engine lifecycle** — `stop()` on the main thread, non-volatile `running`, `sessionManager` not cleared on stop, repeated `start()` can create a second `SessionManager`.
9. **ProGuard keeps / APK size** — validate trimming with a release smoke test before shipping.
10. **`TorrentEngine.pauseAll`** now pauses native handles, but the UI "Pause all"/"Resume all" should be manually verified on device.
11. **Stuck torrents** — **FIXED** in `da4e037` (retry cap → `FAILED`).
12. **Base32 magnets** — **FIXED** in `da4e037` (converted to hex).
13. **`deleteDownload(withFiles = true)`** — **FIXED** in `da4e037` (root guard).
14. **Torrent notification has no content intent** — **FIXED** in `da4e037`.
15. **Low storage** — only the WorkManager `setRequiresStorageNotLow` constraint applies; the running engine does not react to the device filling up. (scout F14.)

---

## 9. Manual test checklist for a real device (OnePlus 13, arm64)

1. **Fresh install** (uninstall first) → onboarding appears, no Home flash.
2. **Folder picker:** choose a folder → confirm the path is shown and a first scan starts; test **Cancel** (stays on onboarding); test a **revoked/deleted** folder later (Settings) → friendly error, no crash.
3. **Process death during onboarding:** pick a folder, force-stop, relaunch → onboarding state sane.
4. **Complete onboarding** → Home; force-stop + relaunch → onboarding not shown again; back button exits.
5. **Home states:** empty (no books), only ebooks, only audiobooks, only podcasts, and a mixed library — check "Continue" rows, tiles, long titles, missing covers, and each tile navigates correctly.
6. **Dark/light** toggle + **font scale 200%**: Home, onboarding, sleep timer, reader — no clipped buttons or overlapping text.
7. **Rotation** on Home, reader, player, sleep-timer sheet, torrent screen.
8. **Audiobook in background:** play, lock screen, force app to background, return; notification controls work; kill process → reopen → position restored.
9. **Ebook↔audio switching:** read an EPUB, switch to its audiobook via the handoff, then back; repeat several times; trigger process death in between. (This is the historical crash path.)
10. **Sleep timer:** open sheet (fully expanded, "Set/Start" visible), 5-min timer, watch countdown, extend, cancel; verify fade-out (if on), correct stop, no volume left ducked, survives rotation.
11. **EPUB reading:** open a large book and a malformed/DRM file; verify a friendly error, special chars (æ ø å), RTL sample, position save/restore.
12. **FTP / SMB / WebDAV / Calibre:** connect to a real unreachable host (friendly error, no ANR), then a real host; browse a large directory; cancel a transfer.
13. **Torrent:** add a magnet and a `.torrent`; cancel; pause-all/resume-all (verify actual pause); simulate low battery / mobile network; delete.
14. **Language switch:** Norwegian, German, Russian, Ukrainian — check Home/torrent/reader-error strings and that the language persists after restart.
15. **Release check:** install the **release** APK/AAB (`assembleRelease`), repeat steps 5, 8, 10, 13 — this validates R8/ProGuard and the release-only paths.

---

## 10. Repo hygiene

- Large/untracked root-level folders to review (reported, not deleted): `.artifacts/`, `.dbg/`, `.pi-subagents/`, `.trae-html-share-packages/`.
- No keystores or secrets in the working diff or tracked files (verified manually; a dedicated secret scan is recommended in CI).
- `local.properties` is gitignored and was not committed.

---

## 11. Release build result

- `assembleRelease` / `bundleRelease`: **BUILD SUCCESSFUL**.
- `app-release.aab`: 49 MB. Split release APKs: arm64-v8a 31 MB, armeabi-v7a 29 MB, x86_64 32 MB, universal 60 MB.
- R8 minify + `shrinkResources` confirmed working (debug arm64 was ~60 MB).
- Release is signed with the **debug keystore** — must be replaced before upload.

---

## 12. Commit list on `overnight-review`

```
c440b35 fix(core): declare ACCESS_NETWORK_STATE/WIFI_STATE for LAN discovery
3d753cd chore(tools): add locale key/placeholder parity checker
099004a i18n: localize discovered-source type labels
449da6b chore(player): disable verbose chapter-refresh diagnostics in release
cad1d59 chore(player): English developer diagnostics
f51d35b fix(privacy): remove debug telemetry that POSTed to a LAN server
1ef184d fix(lint): clear all-module lint errors before Play submission
85ff8f4 fix(play): drop unused Bluetooth permissions and harden backup rules
d20e06f fix(torrent): actually pause downloads, guard crashes, neutral rights notice
45a31bd perf(data): use Room withTransaction in the handoff path
616a0e3 fix(play): remove Libgen from the torrent preset sources
b5dd0a7 fix(network): permit cleartext HTTP for user LAN sources
da4e037 fix(torrent): cap start retries, base32 magnets, safe delete, notif intent
d8f6044 fix(torrent): make the background/network/charging/battery settings work
ac261ff ui: torrent settings use the lime accent; 48dp Home empty-state link
ebe7d52 perf(release): strip verbose/info logging with R8
ab8a46f i18n: localize reader bookmark HUD and torrent tracker status
e042b3c i18n: remove hardcoded Norwegian fallbacks in core/designsystem
fbbadb7 chore(play): target Android 16 (API 36)
0efac71 fix(reader): open SAF content:// EPUBs in the Readium reader
5f78090 ui(reader): fill the letterbox around the Readium page with the theme colour
a0954cb feat(reader): centre book images and tap an illustration to zoom
460c1db ui(ftp): show a live 'scanning remote folder' phase after Sync now
24b6668 fix(library): stop duplicate imports and repair existing duplicates
81ab22f fix(library): run duplicate repair at app start, dedup before title cleanup
3371988 fix(library): stop merging unrelated audiobooks; repair merged/duplicated tracks
```

### 12.1 Artifact
A debug build of `3371988` is published as a GitHub release:
`https://github.com/trollieske/bookiro/releases/tag/bookiro-overnight-review-3371988`
(arm64-v8a + universal debug APKs).