# Bookiro — handoff for the next agent

Read this first. It tells you where everything is, what is verified, and how to
keep going **without re-discovering the KMP/iOS pitfalls**.

## Brand rename (Bookrio → Bookiro)

The public product name is **Bookiro**. `Bookrio` and `Shelf` are legacy names and
must only survive as internal technical identifiers or historical references.

- **iOS / shared / this branch** (`ios-parity`): Android string resources (all 10
  locales), shared podcast/help text, `Info.plist` `CFBundleDisplayName`, iOS CI
  artifact + smoke-fixture titles, README, `settings.gradle.kts`
  `rootProject.name`, Xcode `ORGANIZATIONNAME`, and the localization validator
  path are rebranded here.
- **Android release source**: worktree `/home/ck3k/Work/shelf-rebrand-android`, branch
  `rebrand/bookiro-android`, **one commit on top of `feat/production-source-overhaul`
  (`9efd9b0`)**: the same Android-facing strings/share text plus the README,
  `rootProject.name` and the validator fix. It deliberately **excludes** the two
  unpushed `fix/audiobook-fragmentation` commits and is ready to merge/cherry-pick
  into the release line.
- **Intentionally kept** (stable identifiers): `applicationId`/namespace
  `com.bookrio`, iOS `com.bookrio.ios`, DB `shelf.db`, preference keys,
  notification channel IDs, `BOOKRIO_AUTO_*`, resource names like
  `bookrio_mark.png`, `Shelf*` class names, GitHub repo/remote names.
- **Remaining legacy names**: `lib_sort_shelf` (= the bookshelf sort word),
  `Theme.Shelf`, HTTP User-Agent strings, fixture credentials, and the
  historical “(formerly Bookrio, formerly Shelf)” README line.

## Repos & branches

- **`github.com/trollieske/bookrio`** — the KMP/iOS port (the active repo).
  - `kmp-ios` — previous active branch (reader + audio + podcast slices), fully pushed.
  - `ios-parity` — **current active branch**: the Android-parity UI, created from `kmp-ios`.
  - `main` — baseline Bookiro (Android-only) checkout.
- **`github.com/trollieske/shelf`** — the original Android repo.
  - `feat/production-source-overhaul` — Bookiro Android app + bugfixes.
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
- **iOS Android parity (`ios-parity`, CI-verified)**: the stub `App.kt` is replaced by
  a Material 3 shell that mirrors the Android app — 4 bottom tabs, badges, and one
  now-playing bar. Screens now shared: library (grid/list, sort rail, resume strip,
  search, tab counts), audiobook player chrome, reader chrome + real HTML pages, and
  the podcast root/detail + settings. Apple `.pageCurl` and the one audio owner are
  preserved. `designsystem` `BookVisual`/`BookFormat` now live in `commonMain`.

## iOS parity (`ios-parity`) — what matches Android

`ios-parity` (from `kmp-ios`) replaces the Compose stub with the Android look:

| iOS (shared) | Android source |
|---|---|
| `com.bookrio.library.BookrioLibraryScreen` | `library/ui/LibraryScreen.kt` |
| `com.bookrio.player.ui.BookrioPlayerScreen` | `player/ui/PlayerScreen.kt` |
| `EpubPageCurlReader` (WKWebView HTML pages) | `reader/ui/ReaderScreen.kt` + `engine/HtmlPageRenderer.kt` |
| `com.bookrio.podcast.ui.BookrioPodcastRootScreen`/`DetailScreen` | `podcast/ui/PodcastRootScreen.kt`/`PodcastDetailScreen.kt` |
| `com.bookrio.settings.BookrioSettingsScreen` | `app/.../app/ui/SettingsScreen.kt` |
| `App.kt` shell (tabs, badges, now-playing bar) | `MainActivity.kt` `ShelfRoot` + `NowPlayingBar` |

The diagrams in `App.kt` (`BookrioScreen` nav host) are the integrator's; agents only
expose entry composables.

### Shelf diff result (checked before porting)

`bookrio/main == origin/feat/production-source-overhaul == 9efd9b0` (Bookiro rebrand).
`shelf` `main` (`8fc2520`) is **not** a descendant of `9efd9b0`; its only extra commit
is `Add Whatsnext.md DeepSeek prompt for the iOS port` (docs only) and it lacks the
Bookiro code, so **no Android code commits were newer and nothing was cherry-picked**.
Canonical Android spec for this work is `9efd9b0`.

### Fixed Android bugs deliberately NOT ported

- Two now-playing engines / one engine clearing the other → iOS keeps the single
  `AudioPlayers.shared` owner; the bar never constructs a second AVPlayer.
- Non-unique MediaSession / binder polling / a stuck "Loading podcast…" → no iOS
  equivalent exists; the shared player is one instance.
- Persisting "Kapittel N"/"Chapter N" as a real title → generic titles stay
  render-time only (`localizedChapterTitle` pattern).
- Online cover lookup by default → `ONLINE_COVER_LOOKUP` stays `false`; iOS renders
  only local covers (`rememberLocalCover`) and never fetches RSS artwork.
- Screen-scoped FTP/SMB transfers, qBittorrent UA spoofing / public trackers, OPDS →
  not ported at all (see "Android-only" below).

### Still Android-only (not shown as fake iOS cards)

FTP / SFTP / SMB / WebDAV / Calibre / torrent, Android Auto + CarPlay, WorkManager,
SAF folder watching, DB export/import, sync scheduling, handoff, language picker.
The library header omits the Sources/CloudSync icon and the settings screen omits
those rows rather than showing disabled stubs.

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
gh -R trollieske/bookrio run download <run-id> -n Bookiro-ios-simulator-screenshot -D /tmp/shots
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

Android parity for the main flow is done and CI-verified on `ios-parity`. Left to do:

1. **Sources (later integrator phase)**: FTP/SMB/WebDAV/Calibre. Port the *current*
   Room transfer queue (never the old screen-scoped ViewModel socket), and only then
   show a working source card/settings row. No torrent, no OPDS.
2. **Book details + import progress/transfers** screens (long-press target is currently
   a no-op) and security-scoped bookmarks so imports are read in place.
3. **Podcast player screen** (Android has a full-screen player; iOS currently uses the
   bar + detail) and podcast discovery/optional downloads (`podcast_downloads`, no
   WorkManager/BGTaskScheduler).
4. **Other formats on iOS**: FB2/MOBI/CBZ behind the same native `.pageCurl` host.
5. **Player contract gaps** (`contracts/player.md`): cross-track whole-book seek and a
   global position API on the audio owner; also lock-screen artwork (`MPMediaItemArtwork`).
6. **UI/i18n**: `:shared` does not use Compose resources, so shared screens are
   hard-coded English; wire a common string surface if localization is wanted.

## Current branches/commits

- `kmp-ios` (previous run): `c08ceaf` EPUB reader → `3e48b71` viewWillAppear fix →
  `5a44d96` single audio owner → `9c9fdb4` podcasts → `2993cee`/`ad826d2`/`3b4147f`
  podcast CI smoke fixes → `8a430cd` docs.
- `ios-parity` (this run): `b73d2d1` BookVisual/BookFormat commonMain → `3e5eb4e`
  designsystem dep + icons + cover loader → `9d6f82e` AppPrefs → `0293ad4` PrefKeys →
  `4ee5415` parity screens + nav host → `9f707fc` CI on ios-parity → `f70a943`
  smoke polling + app-start → `a37009c` EPUB measuring-view ghost fix.

**CI (GitHub Actions `iOS Build`, `macos-15`):**
- `36696696262` ✅ — parity shell; PDF + EPUB + podcast RSS smoke pass.
- `36700991801` ✅ — current head; same three smokes pass (ghost column fixed).
- `36692735019` ❌ — first parity run: PDF smoke failed only because the larger
  framework needed more than a fixed 20 s; replaced with a log-line poll.