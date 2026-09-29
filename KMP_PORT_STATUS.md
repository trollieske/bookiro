# Bookrio KMP / iOS port — status

Target from the spec in `trollieske/shelf@main` `Whatsnext.md`: a Kotlin
Multiplatform app that keeps building Android and adds an iOS (iPhone + iPad)
app that maximises shared Kotlin and Compose Multiplatform.

Branch: `kmp-ios`. iOS is **not buildable on Linux** — the Xcode project is
complete but must be compiled/verified on a Mac.

## Done

- **Toolchain**: Kotlin Multiplatform 2.0.21 + Compose Multiplatform 1.7.3;
  `androidTarget`, `iosArm64`, `iosSimulatorArm64`, static frameworks. The
  machine-specific `org.gradle.java.home` was removed.
- **`:core`** — KMP. Pure Kotlin (domain models, sort modes, SessionSource,
  ReadingTrackerFacade, CueParser, MetadataCleaner, DispatcherProvider, Result)
  is in `commonMain`; everything touching `android.*`/`java.*` stays in
  `androidMain`.
- **`:data`** — KMP with **Room KMP 2.7.1**. Entities, DAOs, Converters, pure
  repositories and `SeedCallback` are in `commonMain`; migrations use the Room
  2.7 `SQLiteConnection` API. Platform DB builders only: Android keeps
  `ShelfDatabase.getInstance(context)` + the existing `shelf.db` location; iOS
  uses `Room.databaseBuilder<ShelfDatabase>(name).setDriver(BundledSQLiteDriver())`
  in the app documents dir. Keystore/DataStore/lifecycle/file staging stay in
  `androidMain`.
- **`:designsystem`** — KMP + Compose Multiplatform. The HUD theme (Colors,
  Typography, Shapes, Theme, WoodBookshelfTheme) is in `commonMain`; the
  system-bar hook is an `expect`/`actual`; Android-only renderers + resources stay
  in `androidMain`.
- **`:shared`** — Compose Multiplatform iOS framework (`Shared.framework`) that
  exports `:core` + `:data` and exposes `MainViewController()`.
- **iOS reader slice** — a compiling vertical slice in `:shared`: Compose-Multiplatform
  library list over the shared Room DB, `UIDocumentPicker` import, and native readers
  using `UIPageViewController` with Apple's built-in `.pageCurl` transition: PDF via
  PDFKit (`shared/.../reader/PdfPageCurlReader.kt`) and EPUB via a shared Kotlin EPUB
  parser + paginator (`shared/.../reader/Epub*.kt`, `platform.zlib` raw inflate).
  Reading progress is written back through the shared `ReadingProgressDao`.
  **No Android `:pagecurl` code is ported.**
  ✅ **CI-verified** on `macos-15`: the PDF smoke test and the EPUB smoke test both
  open the native page-curl reader (runs `36633471628`, `36641917158`).
- **iOS audio** — one `AVPlayer` for the whole app (`AudioPlayers.shared`,
  `shared/.../player/`): audiobook and podcast can never overlap. Local m4b/mp3/…
  play with play/pause/seek/chapters, resume + ~2 s progress writes through
  `ReadingProgressDao`. `AVAudioSession.playback`, `MPNowPlayingInfoCenter` and
  `MPRemoteCommandCenter`, `UIBackgroundModes = audio`.
- **iOS podcasts** — subscribe by RSS URL, RSS 2.0/Atom parsed in common Kotlin,
  stored via the existing `:data` `podcast_feeds`/`podcast_episodes` DAOs, episodes
  listed in the Compose shell, streamed through the same audio owner and marked in
  `podcast_playback`. Foreground pull-to-refresh only. ✅ Final CI run `36641917158`
  parses an RSS fixture (filesystem, not HTTP — the runner refused loopback) and
  asserts the feed + 2 episodes are stored.
- **`iosApp`** — complete Xcode project: SwiftUI shell hosting the Compose UI,
  bundle id `com.bookrio.ios`, display name Bookrio, iOS 15+, iPhone **and** iPad
  (`TARGETED_DEVICE_FAMILY = 1,2`). Xcode runs
  `:shared:embedAndSignAppleFrameworkForXcode`.

## Verified on a real Mac (GitHub Actions `macos-15`, Xcode 16.4)
`.github/workflows/ios.yml` builds, on every push to `kmp-ios`:

- `:shared:linkDebugFrameworkIosSimulatorArm64` and `:shared:linkDebugFrameworkIosArm64` ✅
- `iosApp` for the **iOS Simulator** (arm64) with the Compose UI linked ✅
- `iosApp` for **iOS device** (arm64) ✅
- **Runtime smoke test** ✅ — boots a Simulator, installs the app, seeds
  `tools/ci/sample.pdf`, launches with `BOOKRIO_AUTO_OPEN_PDF`, opens the native
  page-curl reader, screenshots it, and fails on an uncaught Kotlin exception. This
  caught two real launch bugs that build-only CI missed:
  `CADisableMinimumFrameDurationOnPhone` missing from `Info.plist`, and Room's iOS
  builder needing an absolute DB path.

The framework therefore compiles/links with Kotlin/Native and the SwiftUI shell links
the Kotlin Compose UI on a real macOS toolchain for both iPhone and iPad.

## Remaining (next agent starts at discovery/downloads, not playback)

Playback, EPUB, audio and podcast playback are done. What is left is mostly
polish and the non-Apple-source work:

1. Podcast discovery/search (iTunes/PodcastIndex) and optional episode downloads
   using the existing `podcast_downloads` table; no WorkManager/BGTaskScheduler.
2. Migrate `:library`, `:reader`, `:player` chrome to Compose Multiplatform
   `commonMain` (extract `BookVisual`/`BookFormat` from `:designsystem` first).
3. iOS import polish: security-scoped bookmarks for in-place access + a shared
   importer; FB2/MOBI/CBZ readers behind the same native page-curl host.
4. Podcast/audiobook extras: lock-screen artwork, playback speed UI, mark-as-played,
   sleep timer, multi-file audiobook chapters.
5. Verify on a Mac (or trust CI): `open iosApp/iosApp.xcodeproj`, run the `:shared`
   framework build, then simulate on iPhone + iPad.

## Excluded on iOS (by construction)

Only `:core`, `:data`, `:designsystem`, `:shared` have iOS targets. `torrent`,
`smb`, `ftp`, `webdav`, `calibre`, `pagecurl`, WorkManager and Media3 are not in
any iOS compilation.

## Android

`:app:assembleDebug` and `:app:assembleRelease` (R8 + resource shrink) pass on the
new KMP toolchain, and `:core`/`:data` unit tests pass.