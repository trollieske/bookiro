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
- **iOS**: **verified by actually running on a macOS Actions runner** — the iOS
  Simulator smoke test boots the app, imports `tools/ci/sample.pdf`, opens the native
  page-curl reader, and uploads a screenshot. `Shared.framework` (iosSimulatorArm64 +
  iosArm64) and the `iosApp` Xcode project also link for the **simulator** and a
  **real device (arm64)**, for iPhone + iPad.
- **Reader slice done (PDF + EPUB)**: the iOS app shows a Compose-Multiplatform
  library list, imports via `UIDocumentPicker`, opens PDFs with Apple's built-in
  `UIPageViewController(.pageCurl)` + PDFKit, and opens EPUBs with the same native
  `.pageCurl` host over a shared-Kotlin EPUB parser + paginator. Progress is
  persisted in the shared Room DB (`ReadingProgressDao`). FB2/MOBI/CBZ are still
  "coming later" on iOS; the Android-only `:library`/`:reader` UI is unchanged.
- **iOS audio done**: one AVPlayer for the whole app (`AudioPlayers.shared`), so an
  audiobook and a podcast can never play at the same time. Local m4b/mp3/m4a/… play
  with play/pause/seek/chapters and persist position through `ReadingProgressDao`.
  AVAudioSession `.playback`, `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`
  are wired; `UIBackgroundModes = audio` is set.
- **iOS podcasts done**: subscribe by RSS URL, feed parsed in common Kotlin, stored
  in the existing `:data` `podcast_feeds`/`podcast_episodes` tables, episodes listed
  in the Compose shell and streamed through the same single `AudioPlayer`
  (`AudioOwner.PODCAST`); position is marked in `podcast_playback`. Pull-to-refresh
  is foreground-only. No downloads, directory or search.

## How to build / verify

Android (works on Linux):
```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

iOS (verified on a **real macOS toolchain** via GitHub Actions — this is how iOS
link/run is verified):
```bash
git push bookrio kmp-ios                       # triggers .github/workflows/ios.yml
gh -R trollieske/bookrio run watch --exit-status   # or: gh -R trollieske/bookrio run list
gh -R trollieske/bookrio run view <id> --log-failed
```

### Local iOS compilation (Linux!) — use this before every push
You cannot **link** iOS on Linux (no Xcode/Apple SDK), but since Kotlin 2.1 the
apple klibs **can be cross-compiled** here. The Kotlin/Native prebuilt + platform
klibs are already downloaded under `.konan/`. Run:
```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64 \
  -Pkotlin.native.enableKlibsCrossCompilation=true --console=plain
```
This catches unresolved cinterop symbols, wrong enum constant names, expect/actual
mismatches, and commonMain errors in seconds. To check a generated binding name
(e.g. whether PDFKit exposes `kPDFDisplayBoxMediaBox` or `PDFDisplayBoxMediaBox`):
```bash
bin/klib dump-metadata \
  .konan/kotlin-native-prebuilt-linux-x86_64-2.1.20/klib/platform/ios_simulator_arm64/org.jetbrains.kotlin.native.platform.PDFKit \
  | grep -i displaybox
```
Then still **push and read the Actions log** — only Xcode proves it links/launches.
Each run is ~8–12 min (usually queued).

The workflow is more than a build: after linking it **boots an iOS Simulator, installs
the app, copies `tools/ci/sample.pdf` into the app container, launches with
`BOOKRIO_AUTO_OPEN_PDF`, screenshots the page-curl reader, and fails on
`Uncaught Kotlin exception` or when the reader was not presented**. Download the
proof with:
```bash
gh -R trollieske/bookrio run download <run-id> -n Bookrio-ios-simulator-screenshot -D /tmp/shots
```

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

### iOS reader slice

`:shared` has a working, compiling iOS vertical slice: a Compose-Multiplatform
library list (`shared/.../App.kt`) over the shared Room DB, `UIDocumentPicker`
import, and native page-curl readers.

- `shared/.../platform/ReaderPlatform.kt` (`expect`) + `ReaderPlatform.ios.kt`
  (`actual`): `appDatabase()`, `presentPdfReader(...)`, `presentEpubReader(...)`,
  `importBookWithPicker(...)`, and the `BOOKRIO_AUTO_OPEN_PDF` /
  `BOOKRIO_AUTO_OPEN_EPUB` CI hooks.
- `shared/.../reader/PdfPageCurlReader.kt`: `UIPageViewController` with
  `UIPageViewControllerTransitionStylePageCurl` + PDFKit-rendered pages.
- `shared/.../reader/Epub*.kt`: a defensive EPUB 2/3 parser (hand-rolled ZIP reader
  + `platform.zlib` raw inflate) and a pure-common paginator; `EpubPageCurlReader`
  feeds those pages into the same `.pageCurl` host (one page view per page, `.min`
  spine on phone, `.mid` for regular-width landscape iPad).

**Decision (do not regress):** the iOS page turn uses Apple's built-in
`UIPageViewController` page-curl transition. Do **not** port the Android
`:pagecurl`/`ReaderScreen` curl canvas to iOS. `:pagecurl` stays Android-only.

### iOS audio + podcasts

- `shared/.../player/AudioPlayback.kt`: `AudioPlayer` interface + `AudioRequest`/
  `AudioPlayerState`, and `AudioPlayers.shared` — **exactly one** audio owner per
  process. `shared/.../player/AudiobookPlayback.kt` adds track-ordered audiobook
  playback, chapter parsing (`chaptersJson`, no network lookup), resume and ~2 s
  progress persistence. `shared/src/iosMain/.../player/IosAudioPlayer.kt` is the
  actual: AVPlayer + `AVAudioSession.playback` + `MPNowPlayingInfoCenter` +
  `MPRemoteCommandCenter`.
- `shared/.../podcast/`: pure-common RSS 2.0/Atom parser + identity, a
  `PodcastRepository` over the existing `:data` podcast DAOs, `httpGetText`
  (`NSData` GET off the main thread) and `PodcastPlayback` (streams the enclosure
  through `AudioPlayers.shared`, owner `PODCAST`, marks `podcast_playback`).
- `App.kt` now has `BØKER`/`PODKASTER` tabs, one bottom now-playing bar shared by
  audiobook and podcast, an RSS field, episode lists and pull-to-refresh.

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
- **Room on iOS needs an absolute path**: `Room.databaseBuilder(name = "shelf.db")`
  fails with `Unable to open database 'shelf.db'`; use
  `"${NSHomeDirectory()}/Documents/shelf.db"` (see `ShelfDatabaseIos.kt`).
- **Compose iOS requires** `<key>CADisableMinimumFrameDurationOnPhone</key><true/>`
  in `iosApp/Info.plist`, otherwise Compose throws on launch (the smoke test caught this).
- The Compose Gradle plugin's iOS resource task fails in Xcode → keep `:shared` on plain CMP artifacts.
- `project.pbxproj` is hand-written; `xlint`/parse errors surface as "Unable to read project". Frameworks for CI are staged into `shared/build/ci-frameworks/$(CONFIGURATION)/$(PLATFORM_NAME)` and `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` skips the Xcode Gradle phase.
- **`UIPageViewController` must have a view controller before it appears.** Deferring
  `setViewControllers` to `viewDidLayoutSubviews` makes `viewWillAppear` throw
  `NSInvalidArgumentException: The number of provided view controllers (0) does not
  match the number required (1) for the requested spine location`. Paginate once in
  `init` (screen bounds), then refine in `viewDidLayoutSubviews`.
- **CI podcast smoke uses a file, not HTTP.** `python3 -m http.server` on the
  `macos-15` runner reported as still running, yet loopback connections were refused
  (`curl: (28) Failed to connect to 127.0.0.1 port 8765 … Couldn't connect to
  server`) and the app logged `podcastSubscribe=fail … err=http_failed`. ATS
  exceptions (`NSExceptionDomains` for `localhost`/`127.0.0.1` +
  `NSAllowsLocalNetworking`) did not help because the server itself was unreachable.
  The smoke now writes the RSS fixture into the app container and drives
  `PodcastRepository.subscribeXml(feedUrl, xml)` via `BOOKRIO_AUTO_SUBSCRIBE_RSS_FILE`,
  which tests the parser + Room persistence deterministically. The real HTTP path
  (`httpGetText` = `NSData.dataWithContentsOfURL`) is unchanged for production and is
  covered by the parser's offline `kotlinc-native` harness.
- **The single audio owner is `AudioPlayers.shared`.** Never construct a second
  AVPlayer/engine for podcasts; start a new item by calling `play()` (it replaces the
  previous one).

## Next steps (in order)

Playback, EPUB, audio and podcast playback are all done and CI-verified. The next
agent should start at **discovery/downloads**, not playback:

1. **Podcast discovery/search** (iTunes/`PodcastIndex` search UI) and optional
   episode downloads (local file wins over the enclosure URL, using the existing
   `podcast_downloads` table + a foreground/download manager — no WorkManager on iOS).
2. **Shared importer / metadata**: extract `BookVisual`/`BookFormat` from
   `:designsystem` `BookComponents` into `commonMain`, then replace the minimal
   `App.kt` library rows with the shared `:library` UI. Add security-scoped
   bookmarks so imports are read in place instead of copied.
3. **Other formats on iOS**: FB2/MOBI/CBZ readers behind the same native host.
4. **Podcast extras**: artwork in the now-playing bar / lock screen
   (`MPMediaItemArtwork`), playback speed UI, mark-as-played, per-episode context menu.
5. **Audiobook multi-track polish**: chapters across separate files, sleep timer.
6. Update `KMP_PORT_STATUS.md` as phases land.

## Current branches/commits

`kmp-ios` head after this run: EPUB, audio and podcast phases are all committed and
pushed. Order: `c08ceaf` (EPUB reader) → `3e48b71` (EPUB viewWillAppear fix) →
`5a44d96` (single audio owner) → `9c9fdb4` (podcasts) → `2993cee` + `ad826d2` +
`3b4147f` (podcast CI smoke fixes).

**CI (GitHub Actions `iOS Build`, `macos-15`):**
- `36633471628` ✅ — audio commit; PDF + EPUB smoke tests pass.
- `36641917158` ✅ — final: PDF + EPUB + **podcast RSS (file) smoke** all pass.
- `36630606663`, `36638298509`, `36640121145` ❌ — earlier podcast-smoke attempts;
  the networking failure and its fix are written up in the pitfalls section above.