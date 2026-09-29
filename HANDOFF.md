# Bookrio — handoff for the next agent

Read this first. It tells you where everything is, what is verified, and how to
keep going **without re-discovering the KMP/iOS pitfalls**.

## Repos & branches

- **`github.com/trollieske/bookrio`** — the KMP/iOS port (the active repo).
  - `kmp-ios` — **active branch**, everything below is here, fully pushed, tree clean.
  - `main` — baseline Bookrio (Android-only) checkout.
- **`github.com/trollieske/shelf`** — the original Android repo.
  - `feat/production-source-overhaul` — Bookrio Android app + bugfixes.
  - Release **`v1.0.0-rc1`** — signed APKs for phone testing.

Local clone: `/home/ck3k/Work/shelf` (remote `bookrio` → the new repo, `origin` → shelf).
A stale local `main` (shelf's) exists; ignore it — use `bookrio/*`.

## TL;DR status

- **Android**: `:app:assembleDebug` and `:app:assembleRelease` (R8) pass; unit tests pass.
- **iOS**: **verified on macOS GitHub Actions runners** — `Shared.framework`
  (iosSimulatorArm64 + iosArm64) and the `iosApp` Xcode project link for both the
  **simulator** and a **real device (arm64)**, for iPhone + iPad.
- **Not done**: the `:library`/`:reader`/`:player` UI still compiles Android-only, so
  the iOS app currently shows the Compose stub in `shared/.../App.kt`, not the real
  library/reader/player. The iOS reader/audio actuals are not written yet.

## How to build / verify

Android (works on Linux):
```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

iOS (from this Linux box, via the macOS runner — this is how every iOS fix was verified):
```bash
git push bookrio kmp-ios                       # triggers .github/workflows/ios.yml
gh -R trollieske/bookrio run watch --exit-status   # or: gh -R trollieske/bookrio run list
gh -R trollieske/bookrio run view <id> --log-failed
```
You cannot compile iOS locally on Linux (no Xcode/Apple SDK). **Always push and
read the Actions log** — it is the compiler for iOS here. Each run is ~8–12 min
(usually queued).

On a real Mac: `open iosApp/iosApp.xcodeproj`, scheme `iosApp`, Run (it calls
`:shared:embedAndSignAppleFrameworkForXcode`). Set your team in Signing to run on a
physical iPhone/iPad.

## Architecture (what is KMP today)

| module | state | notes |
|---|---|---|
| `:core` | KMP | pure code in `commonMain`; `android.*`/`java.*` in `androidMain`. `PlatformDispatchers` (`expect`) provides IO. `nowMillis()` in `com.bookrio.core.time`. |
| `:data` | KMP + Room KMP | entities/DAOs/pure repos in `commonMain`; migrations use `SQLiteConnection`; `@ConstructedBy(ShelfDatabaseConstructor::class)` + `expect object`; Android builder = `ShelfDatabase.getInstance(context)` extension (keeps `shelf.db`), iOS builder = `getShelfDatabase()` with `BundledSQLiteDriver`. |
| `:designsystem` | KMP + CMP | theme in `commonMain`; `ApplySystemBarAppearance` is `expect/actual`; Android renderers (Coil/`android.graphics`) in `androidMain`. |
| `:shared` | KMP + CMP | iOS framework `Shared`; exports `:core`+`:data`; `MainViewController()`. **Does NOT apply the `org.jetbrains.compose` Gradle plugin** (its `syncComposeResourcesForIos` breaks the Xcode script) — it depends on CMP artifacts directly. |
| `:library` `:reader` `:player` `:podcast` `:ftp` `:smb` `:webdav` `:calibre` `:torrent` `:pagecurl` | Android-only | no iOS targets; excluded from iOS by construction. |

`iosApp/` is the SwiftUI shell (`ContentView` hosts the Compose UIViewController).
`store/play_icon_512.png` is the Android/Play icon.

## Version set (bumped together, do not mix)

Kotlin **2.1.20**, KSP **2.1.20-1.0.32**, Compose Multiplatform **1.8.2**,
AGP **8.5.2**, Room **2.7.1**, `androidx.sqlite` **2.5.0**, kotlinx-datetime **0.6.2**,
coroutines 1.9.0, Gradle 8.11.1. Room 2.7.1's klib needs Kotlin ≥2.1.10; its sqlite
must be 2.5.0 (2.6+/2.7 klibs need Kotlin 2.3.x).

## Pitfalls already hit (don't reintroduce)

- Never put `android.*`, `java.*`, okhttp, Coil 2, Media3 or WorkManager in a `commonMain`.
- `Dispatchers.IO` is internal on Native → use `platformIoDispatcher`.
- `System.currentTimeMillis`/`java.time`/`String.format`/`synchronized`/`@Volatile`/`runInTransaction` are JVM-only. Use `nowMillis()`, `kotlinx.datetime`, `roundToInt()`, `run {}`, `kotlin.concurrent.Volatile`, sequential DAO calls.
- Room KMP: `@ConstructedBy` + `expect object … : RoomDatabaseConstructor<T>`.
- The Compose Gradle plugin's iOS resource task fails in Xcode → keep `:shared` on plain CMP artifacts.
- `project.pbxproj` is hand-written; `xlint`/parse errors surface as "Unable to read project". Frameworks for CI are staged into `shared/build/ci-frameworks/$(CONFIGURATION)/$(PLATFORM_NAME)` and `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` skips the Xcode Gradle phase.

## Next steps (in order)

1. Extract `BookVisual`/`BookFormat` from `:designsystem` `BookComponents` (androidMain)
   to `commonMain`, so `:library`'s mapper can be shared.
2. Convert `:library` to KMP+CMP: move `mapper/DomainMappers`, `sort/ResumeSelector` to
   `commonMain`; keep `LibraryScreen`/`LibraryViewModel`/`BookImportRepository`/`CoverRepository`
   in `androidMain` until you replace AndroidX lifecycle/navigation + Coil 2 + SAF.
3. Convert `:player` (AudioPlayer interface + AVPlayer actual) and `:reader` (shared
   pagination model + `UIPageViewController`/PDFKit host) to KMP.
4. Add `:library`/`:reader`/`:player` to `:shared` and build the real UI entry point.
5. Update `KMP_PORT_STATUS.md` as phases land.

## Current branches/commits

`kmp-ios` head: `8f6d0f6` (docs: macOS CI-verified iOS build). Full history there.