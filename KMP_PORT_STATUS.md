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
- **`iosApp`** — complete Xcode project: SwiftUI shell hosting the Compose UI,
  bundle id `com.bookrio.ios`, display name Bookrio, iOS 15+, iPhone **and** iPad
  (`TARGETED_DEVICE_FAMILY = 1,2`). Xcode runs
  `:shared:embedAndSignAppleFrameworkForXcode`.

## Remaining (needs a Mac)

1. Migrate `:library`, `:reader`, `:player` chrome to Compose Multiplatform
   `commonMain`. They currently mix AndroidX Lifecycle/Navigation, Coil 2,
   SAF/`Context` and `android.graphics`, which must be replaced with the JetBrains
   multiplatform artifacts + a KMP image loader. (Extract `BookVisual`/`BookFormat`
   from `:designsystem` `BookComponents` to `commonMain` first.)
2. iOS reader host: `UIPageViewController(transitionStyle: .pageCurl)` fed by a
   shared pagination model; PDF via `PDFKit`; delegate writes the settled index
   back to shared state. Scroll mode = plain `UIScrollView`.
3. iOS audio actual: `AVPlayer` + `AVAudioSession.playback` +
   `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`, feeding the same progress
   repository as the Android Media3 service.
4. iOS import: `UIDocumentPickerViewController`, copy into the app documents dir,
   persist a security-scoped bookmark only when not copied.
5. Verify on the Mac: `open iosApp/iosApp.xcodeproj`, run `:shared` framework
   build, then simulate on iPhone + iPad.

## Excluded on iOS (by construction)

Only `:core`, `:data`, `:designsystem`, `:shared` have iOS targets. `torrent`,
`smb`, `ftp`, `webdav`, `calibre`, `pagecurl`, WorkManager and Media3 are not in
any iOS compilation.

## Android

`:app:assembleDebug` and `:app:assembleRelease` (R8 + resource shrink) pass on the
new KMP toolchain, and `:core`/`:data` unit tests pass.