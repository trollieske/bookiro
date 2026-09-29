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
  library list over the shared Room DB, `UIDocumentPicker` import, and a native PDF
  reader using `UIPageViewController` with Apple's built-in `.pageCurl` transition +
  PDFKit (`shared/.../reader/PdfPageCurlReader.kt`). Reading progress is written back
  through the shared `ReadingProgressDao`. **No Android `:pagecurl` code is ported.**
  ✅ **CI-verified** on `macos-15`: `Shared.framework` links PDFKit/UIKit and the
  iosApp links it for simulator + device (run `36548654298`).
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

## Remaining (UI parity, needs iterative work)

1. Migrate `:library`, `:reader`, `:player` chrome to Compose Multiplatform
   `commonMain`. They currently mix AndroidX Lifecycle/Navigation, Coil 2,
   SAF/`Context` and `android.graphics`, which must be replaced with the JetBrains
   multiplatform artifacts + a KMP image loader. (Extract `BookVisual`/`BookFormat`
   from `:designsystem` `BookComponents` to `commonMain` first.)
2. **EPUB on iOS**: WKWebView + a shared pagination model (JS column pagination) feeding
   page images into the same native page-curl host used for PDF. PDF is already done.
3. iOS audio actual: `AVPlayer` + `AVAudioSession.playback` +
   `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`, feeding the same progress
   repository as the Android Media3 service.
4. iOS import polish: the current slice copies the picked file into
   `Documents/books` via `UIDocumentPickerModeImport`; later add security-scoped
   bookmarks for in-place access + a shared importer.
5. Verify on the Mac: `open iosApp/iosApp.xcodeproj`, run `:shared` framework
   build, then simulate on iPhone + iPad.

## Excluded on iOS (by construction)

Only `:core`, `:data`, `:designsystem`, `:shared` have iOS targets. `torrent`,
`smb`, `ftp`, `webdav`, `calibre`, `pagecurl`, WorkManager and Media3 are not in
any iOS compilation.

## Android

`:app:assembleDebug` and `:app:assembleRelease` (R8 + resource shrink) pass on the
new KMP toolchain, and `:core`/`:data` unit tests pass.