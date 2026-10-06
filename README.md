# Bookiro — local-first ebooks, audiobooks and podcasts

**Bookiro** (formerly Bookrio, formerly Shelf) is a calm, local-first reader and
player. It keeps ebooks, audiobooks and podcasts in one library on your own
device: no account, no tracking, no ads.

The Android app is the shipping product. An iOS app is being built as a Kotlin
Multiplatform port on the `kmp-ios` / `ios-parity` branches and implements the
same library, reader, player, podcast and settings flows; source sync and some
polish are still outstanding (see **Platform status**).

## Platform status

| Platform | State | Notes |
|---|---|---|
| **Android** | Shipping | Full ebook reader, audiobook player, podcasts, sources, torrent. |
| **iOS** | In progress | Library, reader, audiobook player, podcasts and settings are ported (Compose Multiplatform + UIKit readers). FTP/SMB/WebDAV/Calibre, torrent, Android Auto/CarPlay and podcast downloads are **not** implemented on iOS yet. |

Both platforms keep persistent identifiers (`com.bookrio`, `com.bookrio.ios`,
`shelf.db`, preference keys, notification channel IDs) so existing installs and
data are never reset by branding changes.

## What it does

### Ebooks
- Multi-format reader: **EPUB 2/3**, **PDF**, **MOBI / AZW / AZW3** (KF8),
  **FB2**, **CBZ / CBR**, **TXT**, **Markdown**, **HTML**.
- Reliable single-surface reader on Android with instant/slide page turns, the
  same Apple-Books-style overlay UI, tap zones, highlights, bookmarks, themes
  (light / sepia / dark), font size and line height. (The legacy 3D page-curl
  fork is kept in the unwired `pagecurl/` directory for rollback only.)
- Durable reading position (page + offset) and tolerant multi-charset text
  parsing.
- On iOS the page turn uses Apple's built-in `UIPageViewController(.pageCurl)`
  (PDFKit for PDFs, one `WKWebView` page per EPUB page); Android uses the simpler
  single-surface reader described above.

### Audiobooks
- **M4B**, **MP3 / M4A / AAC**, **FLAC / OGG / OPUS** via AndroidX Media3 on
  Android and a single `AVPlayer` on iOS.
- Embedded chapter atoms / ID3 `CHAP` frames, folder-based multi-track
  auto-stitching, variable speed, configurable skip, sleep timer, lock-screen
  and notification controls, Android Auto browsing.
- Position is restored across restarts; one audio owner per app means an
  audiobook and a podcast can never play at the same time.

### Podcasts
- Subscribe by RSS/Atom URL, episodes parsed and stored locally, streamed
  without downloads.
- Foreground refresh; per-episode resume and played state.
- No podcast directory/search and no background worker on iOS.

### Library, sources and torrent (Android)
- Four tabs: **Bøker**, **Lydbøker**, **Podkaster**, **Innstillinger**, with sort,
  search, grid/list views and a persistent mini-player.
- Sources: **FTP / FTPS / SFTP**, **SMB**, **WebDAV** and **Calibre Content
  Server**, with LAN discovery and a durable Room-backed transfer queue.
- An embedded **libtorrent4j** torrent engine (magnet links, auto-import).
- Online cover lookup is **opt-in and off by default**; iOS currently uses local
  covers only.

## Project layout

```
app/           Android entry point: MainActivity, bottom-nav shell, screens, settings
core/          Domain models, parsers, metadata cleaning, dispatchers
data/          Room database (ShelfDatabase), DAOs, repositories, preferences
designsystem/  Material 3 theme, color tokens, shared components
library/       Library dashboard, import pipeline, cover repository
reader/        Ebook reader (page renderer, reader screen, layout engine)
pagecurl/      UNWIRED legacy Android page-curl fork (rollback only, not built)
player/        Audiobook engine, Media3 playback service, player screen
podcast/       RSS parsing, podcast repository, playback service, screens
ftp/ smb/ webdav/ calibre/   Remote source clients and sync workers
torrent/       libtorrent4j engine and download worker
```

The iOS port adds a `:shared` Kotlin Multiplatform module (Compose Multiplatform
UI compiled into `Shared.framework`, hosted by a SwiftUI shell in `iosApp/`) on
the KMP branches.

- **Android application id**: `com.bookrio`
- **iOS bundle id**: `com.bookrio.ios`
- **SDK**: `minSdk 26` (Android 8.0) · `targetSdk 36` (Android 16) · iOS 15+
- **Build**: Kotlin + Jetpack Compose (Material 3), Room, Media3 and WorkManager on
  Android; Kotlin Multiplatform + Compose Multiplatform + UIKit for the iOS port.

## Build & run

### Android
Requires JDK 17 and Android SDK API 36.

There are **two store flavors** (see [`docs/BUILD_VARIANTS.md`](docs/BUILD_VARIANTS.md)):

| Flavor | App id | Torrent client |
|---|---|---|
| `full` | `com.bookiro` | **yes** (side-load / private) |
| `playstore` | `com.bookiro.play` | **no** (Google Play) |

```bash
./gradlew :app:assembleFullDebug       # full debug APK (with torrent)
./gradlew :app:assemblePlaystoreDebug  # Play-safe debug APK (no torrent)
./gradlew :app:installFullDebug        # install full on a connected device
./gradlew :app:testFullDebugUnitTest :app:testPlaystoreDebugUnitTest   # unit tests
```

A release build requires the four `BOOKIRO_*` signing values (see
[`docs/RELEASE_SIGNING.md`](docs/RELEASE_SIGNING.md)):

```bash
./gradlew :app:bundleFullRelease       # signed full AAB
./gradlew :app:bundlePlaystoreRelease  # signed Play AAB
./gradlew :app:lintPlaystoreRelease    # lint the Play variant
```

### iOS (macOS only)
```bash
cd iosApp
open iosApp.xcodeproj            # scheme “iosApp”, Run
```
Xcode invokes `:shared:embedAndSignAppleFrameworkForXcode` to build
`Shared.framework`, then links it. On Linux the Kotlin/Native side can still be
cross-compiled:

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 \
  -Pkotlin.native.enableKlibsCrossCompilation=true
```

## Privacy & security
- **Local-first**: library data and progress live on the device
  (`/data/data/com.bookrio/databases/shelf.db` on Android,
  `${NSHomeDirectory()}/Documents/shelf.db` on iOS).
- **No accounts, no telemetry, no ad SDKs.**
- Remote credentials are encrypted with `EncryptedSharedPreferences` backed by
  the Android Keystore; SSH keys and passphrases never leave the device.
- Scoped Storage / SAF on Android; security-scoped imports on iOS.

## Acknowledgements
Bookiro stands on excellent open source, including
[AndroidX / Jetpack](https://developer.android.com/jetpack) (Compose, Room,
Media3, WorkManager), [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html)
and [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
(JetBrains), [KotlinX Coroutines](https://github.com/Kotlin/kotlinx.coroutines),
[jsoup](https://jsoup.org/), [Coil](https://coil-kt.github.io/coil/),
[OkHttp](https://square.github.io/okhttp/),
[Apache Commons Net](https://commons.apache.org/proper/commons-net/),
[SSHJ](https://github.com/hierynomus/sshj),
[jcifs-ng](https://github.com/AgNO3/jcifs-ng),
[libtorrent4j](https://github.com/aldenml/libtorrent4j). (The vendored
[pagecurl](https://github.com/oleksandrbalan/pagecurl) fork is no longer built.)

*Built with care. Keep it calm. Keep it yours.*