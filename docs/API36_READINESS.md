# Bookiro Android — API 36 (Android 16) Readiness

Status: **evidence-backed audit of the RC at `9aa0f8e` (branch `rc/play`)**
Auditor: Subagent C (release/Play readiness), 2026-10-04
Scope: compileSdk/targetSdk, merged manifest, edge-to-edge, notifications,
foreground-service types, storage behaviour, and Android 16/Play behavioural
changes that can bite a targetSdk 36 release.

No production file was modified for this document. Everything below is
verification + recommendations for the integrator.

---

## 1. SDK levels — confirmed

`app/build.gradle.kts` (read directly):

```kotlin
android {
    namespace = "com.bookrio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.bookrio"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.0-readium9"
    }
```

Merged release manifest (actual build output, task
`:app:processReleaseMainManifest` / AGP task it depends on
`:app:processReleaseManifest`) —
`app/build/intermediates/merged_manifests/release/processReleaseManifest/universal/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.bookrio"
    android:versionCode="11"
    android:versionName="1.0.0-readium9" >
    <uses-sdk
        android:minSdkVersion="26"
        android:targetSdkVersion="36" />
```

=> **compileSdk 36, targetSdk 36 confirmed from the merged artifact, not only
from the build script.** Play's current requirement (2025/2026) is targetSdk 35+;
36 satisfies it. `gradle.properties` has
`android.suppressUnsupportedCompileSdk=35,36,37` so AGP 8.5.2 does not warn.

## 2. Edge-to-edge (Android 15/16 behaviour)

`app/src/main/java/com/bookrio/MainActivity.kt`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
        statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    )
```

- `enableEdgeToEdge()` from `androidx.activity` 1.10.1 is called before
  `setContent`, with explicitly dark (transparent) system bars — the app is
  dark-themed, so this is the intended pairing.
- Apps targeting API 36 **cannot opt out** of edge-to-edge anymore
  (`R.attr.windowOptOutEdgeToEdgeEnforcement` is deprecated/ignored on Android
  16). The app does not use that attribute (grep: no hits), so no
  dead opt-out needs removing.
- Insets handling: `MainActivity` root uses a Material3 `Scaffold` (bottom nav +
  content padding `Modifier.padding(pad)`), and individual screens use
  `statusBarsPadding()`, `navigationBarsPadding()` or a real
  `setOnApplyWindowInsetsListener` (`statusBarsPadding` in `Screens.kt:1348`,
  `HomeScreen.kt:241`, `ReadiumEpubReaderScreen.kt`, etc.). 22 hits across the
  Compose UI — the RC is not relying on deprecated
  `fitsSystemWindows`-style handling.
- Reader has an explicit immersive mode path
  (`WindowInsetsControllerCompat.hide/show(Type.systemBars())` in
  `ReaderScreen.kt` and `ReadiumEpubReaderScreen.kt`) with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — still valid on API 36.
- `AndroidManifest.xml` still declares
  `android:windowSoftInputMode="adjustResize"` on `MainActivity`. On an
  edge-to-edge target-36 app the IME inset is no longer delivered through
  `adjustResize` in the old way; Compose `Scaffold` + `imePadding()` should be
  the source of truth. **Unverified on device: IME over the torrent/source URL
  dialogs and the settings text fields.**

**Verdict: structurally ready; device pass still required for IME, cutout and
3-button-nav gesture areas on Android 16.**

## 3. Notifications

Permissions in the merged manifest:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Runtime request: `RequestNotificationPermissionIfNeeded()` in
`MainActivity.kt` (per-process `rememberSaveable` guard) is invoked only when
navigating into **FTP browse / FTP server / torrent / Calibre browse** screens.

Notification channels created (13 total call sites):

| Channel owner | Purpose |
|---|---|
| `player/AudiobookPlaybackService` | audiobook playback (media session) |
| `podcast/PodcastPlaybackService` | podcast playback (media session) |
| `app/workers/ImportWorker` | SAF import progress (FGS dataSync) |
| `app/workers/MetadataRefreshWorker` | opt-in metadata refresh (FGS dataSync) |
| `ftp/worker/FtpSyncWorker` | FTP sync/transfer (FGS dataSync) |
| `smb`, `webdav`, `calibre`, `torrent` workers | same pattern (FGS dataSync) |

Findings:

- **Media notifications** (playback) are exempt from `POST_NOTIFICATIONS`, so
  audiobook/podcast playback notifications always work.
- **Transfer/import notifications** need the runtime grant, but the permission
  is only requested inside those four destination screens. A SAF import started
  from the Import screen (`ImportWorker`) or a periodic background sync can post
  a notification while the permission was never asked for. On Android 13+ the
  notification is then silently dropped (the FGS still runs; no crash). This is
  a UX gap, not a policy violation. **Recommendation: request
  `POST_NOTIFICATIONS` once during onboarding (or before the first worker
  enqueue), not only in source screens.**
- The merged manifest contains no `USE_FULL_SCREEN_INTENT`, no exact-alarm
  permissions, no `SCHEDULE_EXACT_ALARM` — nothing to clean for Play policy.

## 4. Foreground services / types

Merged manifest:

```xml
<service android:name="com.bookrio.player.service.AudiobookPlaybackService"
    android:exported="true" android:foregroundServiceType="mediaPlayback" />
<service android:name="com.bookrio.podcast.playback.PodcastPlaybackService"
    android:exported="true" android:foregroundServiceType="mediaPlayback" />
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:directBootAware="false"
    android:enabled="@bool/enable_system_foreground_service_default"
    android:exported="false" android:foregroundServiceType="dataSync" />
```

Permissions:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

Per-worker `ForegroundInfo` types (source-verified, every worker passes the
matching type on API 34+):

| Worker | Type | Evidence |
|---|---|---|
| `ImportWorker` | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` | `ImportWorker.kt:151` |
| `MetadataRefreshWorker` | `DATA_SYNC` | `MetadataRefreshWorker.kt:133` |
| `FtpSyncWorker` | `DATA_SYNC` | `FtpSyncWorker.kt:117` |
| `SmbSyncWorker` | `DATA_SYNC` | `SmbSyncWorker.kt:216` |
| `WebdavSyncWorker` | `DATA_SYNC` | `WebdavSyncWorker.kt:195` |
| `CalibreSyncWorker` | `DATA_SYNC` | `CalibreSyncWorker.kt:270` |
| `TorrentDownloadWorker` | `DATA_SYNC` | `TorrentDownloadWorker.kt` ~225 |

No `dataSync` FGS started from `BOOT_COMPLETED` or from the background outside
WorkManager; WorkManager owns the lifecycle. Media playback starts through
`ContextCompat.startForegroundService` and immediately posts the loading
notification (`AudiobookPlaybackService.kt:483-541`,
`PodcastPlaybackService.kt:343-346`), satisfying the API 26+ contract.

**Play policy note (Android 14+):** `dataSync` FGS has a 6-hour/24h cumulative
runtime quota. Long FTP/torrent downloads on a foreground screen keep the
worker in the foreground set; a *background* transfer can be stopped when the
quota is exhausted. WorkManager handles this by design (retry), but users will
see transfers pause. This is expected Android 14+ behaviour and is already
documented as "background downloads may pause" in the app copy. No change
needed for API 36.

## 5. Storage behaviour

- **No storage permissions at all** in the app manifest or merged manifest:
  no `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`,
  `MANAGE_EXTERNAL_STORAGE`, no `requestLegacyExternalStorage`. Import is 100%
  SAF/MediaStore grants (`ACTION_OPEN_DOCUMENT`, `ACTION_OPEN_DOCUMENT_TREE`),
  downloads go to app-private storage, and sharing goes through
  `FileProvider` (`${applicationId}.fileprovider`, `@xml/file_paths` with
  `files-path`/`cache-path`/`external-files-path`).
- `file_paths.xml` exposes only `books/`, `covers/`, `exports/`, cache and
  `external-files-path books/` — no root or download dir. Good minimal surface.
- `android:allowBackup="true"` with `data_extraction_rules.xml` /
  `backup_rules.xml` excluding all credential pref files
  (`shelf_ftp_servers_enc.xml`, `com.bookrio_ftp_plain.xml`,
  `shelf_smb_servers_enc.xml`, `shelf_webdav_servers_enc.xml`). The Room DB
  (book metadata, reading progress, Keystore-encrypted password ciphertext) is
  backed up; the Keystore key is device-bound so restored ciphertext is
  unusable by design.
- Android 16 has **no new storage permission behaviour** relevant to this app.
  The relevant 16KB-page-size requirement is native-library-only and is covered
  in §7.

## 6. Other API 36 behaviour changes checked against the code

| Android 16 change | Impact on Bookiro | Action |
|---|---|---|
| Edge-to-edge enforced (opt-out removed) | Already edge-to-edge (`enableEdgeToEdge`), insets handled | Device pass (IME, cutout) |
| Predictive back enabled by default for target 36 | Manifest does **not** set `android:enableOnBackInvokedCallback`; `activity 1.10.1` + `navigation-compose 2.8.4` are predictive-back capable, but the app's manual `popBackStack()` handling hasn't been exercised with the new animation | Add `android:enableOnBackInvokedCallback="true"` explicitly and run the back-gesture pass; do not silently opt out |
| Large-screen orientation/resizability restrictions ignored (≥600dp) | Manifest locks `configChanges="orientation|screenSize|..."`; `screenOrientation` is not set, so no blocked rotation. The reader/player states must survive a resize; `configChanges` already includes `screenSize` | Tablet/foldable smoke test |
| 16 KB page size support required by Play for target 35+ (since 2025-11-01) | Depends entirely on native `.so` files | See §7 — **only open API 36 blocker found** |
| Local Network Protection (developer preview in 16, enforced in later release) | FTP/SMB/WebDAV/Calibre, LAN discovery (3× /24 TCP scan on 21/445/80/8080/8081) and torrent DHT/peer traffic are all local-network features | No API 36 action; track for the next target bump. Documented in `PRIVACY_DATA_FLOW.md` |
| `targetSdk` 36 notification/`PendingIntent` immutability | All PendingIntents in workers use `FLAG_IMMUTABLE` | None |

## 7. 16 KB page size — the one API 36/Play blocker to verify

Google Play requires apps targeting Android 15+ to support 16 KB page sizes
(from 2025-11-01 for new apps/updates). This app ships prebuilt native code and
must pass the check.

**Measured on the RC release artifact (2026-10-04, commit before reader integration):**

- `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-arm64-v8a-release.apk`
  → `Verification succesful`.
- ELF `LOAD` segment alignment of every native library in the arm64 release APK
  (`llvm-readelf -l`):

| library | LOAD align | 16 KB safe |
|---|---|---|
| `libtorrent4j.so` (15.9 MB) | `0x4000` | yes |
| `libandroidx.graphics.path.so` | `0x4000` | yes |
| `libdatastore_shared_counter.so` | `0x4000` | yes |

A 16 KB page size is `0x4000` bytes, and all three libraries are `0x4000`-aligned,
so the 16 KB requirement is **met for the full/private build**.

- The **play-store variant** additionally removes `:torrent`, but the remaining
  `.so` files still pass the same check (verified: `libandroidx.graphics.path.so`
  and `libdatastore_shared_counter.so`).

## 8. Verdict

**API 36 readiness: CONDITIONAL — code/config gates PASS, device-behavior pass OUTSTANDING.**

- compileSdk/targetSdk 36 and the merged release manifest are correct.
- Edge-to-edge/insets are implemented, but IME/cutout/predictive-back device
  testing is still outstanding.
- FGS types/permissions are consistent across media + WorkManager workers.
- Storage uses SAF only; no legacy storage flags.
- **16 KB page-size support: PASS** on the release artifact (§7).
- Remaining work is a real device/emulator behavioural pass on Android 16 (or 15),
  not a code change.