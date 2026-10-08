# Bookiro Android — Release Baseline (engineering handoff)

Status: **RC in progress — NOT RELEASE READY**
Date: 2026-10-04
Branch: `release/bookiro-android-rc`
Base commit: `d6bf7da260e8818e36dbf748d1e4b2d18a0a16a6`
("docs: record the opt-in metadata refresh feature")

iOS (`ios-parity`, `kmp-ios`) is **out of scope**. This document is an engineering
fact sheet, not marketing.

---

## 1. Why this base

`overnight-review` @ `d6bf7da` is the single Android source of truth because it is a
**strict superset** of every other Android branch in this repository:

| Branch | Relation to `d6bf7da` | Unique commits |
|---|---|---|
| `bookiro/main` (== `feat/play-store-qol`) `71cc98b` | ancestor | 0 |
| `feat/home-onboarding` `da45a9e` | ancestor | 0 |
| `recovery/readium-reader` `3ab6d40` | ancestor | 0 |
| `recovery/android-auto` `a3c27f9` | ancestor | 0 |
| `recovery/bookiro-android-release` `952d873` | ancestor | 0 |
| `recovery/reader-webview` `25da95b` | ancestor | 0 |
| `fix/audiobook-fragmentation` `5e99ec2` | ancestor | 0 |
| `origin/feat/production-source-overhaul` `9efd9b0` | ancestor | 0 |
| `fix/production-ftp-sync-performance` `3b96ed2` | ancestor | 0 |
| `ios-parity` `b951590` | divergent | 50 (iOS only — excluded) |
| `kmp-ios` `8a430cd` | divergent | 39 (iOS only — excluded) |
| local `main` (shelf remote) `f180989` | divergent histories | excluded (see below) |

Verified with `git merge-base` / `git rev-list --count <branch>..d6bf7da`:
every Android/recovery branch has **0 commits not already in `d6bf7da`**. No Android
work is lost by basing the RC here. PR #4 already targets `feat/home-onboarding`,
which is itself an ancestor of this base; the clean release PR target is therefore
`main` (see §6).

The two GitHub repositories are different projects:
- `bookiro` (remote `bookiro`) — Android app; `main` == `71cc98b` (QOL).
- `shelf` (remote `origin`) — the older superproject with KMP/iOS work; local `main`
  and `ios-parity` track it. **Not** merged into the Android RC.

## 2. Included feature groups (all already on the base)

- **Reader (Readium 3.0.3)**: `ReadiumPublicationOpener`, `ReadiumEpubReaderScreen`,
  SAF `content://` EPUB opening, image centring + tap-to-zoom, theme-coloured
  letterbox, TOC + chapter label, Locator persistence. Non-EPUB formats still use
  the legacy `ReaderWebEngine`/`BookLoaderEngine` route.
- **Library safety**: SAF duplicate-import fix, app-start duplicate repair
  (soft-delete), title/author cleanup, conservative audiobook split/merge + track
  renumber, `BookTitleCleaner`.
- **Metadata**: opt-in "Refresh metadata & covers" (`MetadataRefreshWorker`,
  suspect/all, requires online-cover toggle).
- **FTP**: live "scanning remote folder" phase / sync feedback.
- **Play hardening**: targetSdk/compileSdk 36, LAN debug telemetry removed, Libgen
  preset removed, unused Bluetooth permissions removed, cleartext network policy,
  R8 log stripping, all-module lint green (as reported).
- **Torrent**: real pause/resume, retry cap, safe delete, notification intent,
  background/network/charging/battery settings wired, rights notice.
- **i18n**: 10 locales, key/placeholder parity, `tools/i18n_check.py`.
- **Home/onboarding**: main-menu Home, folder-first onboarding, folder label
  resolution for MediaStore trees.

## 3. Excluded / not merged

- iOS branches (`ios-parity`, `kmp-ios`) — out of scope, divergent histories.
- local `main` / `fix/production-ftp-sync-performance` / `rebrand/bookiro-android`
  — old `shelf` history; rebrand work already present on the base.
- No historical branch is merged "to get everything"; the base already contains all
  Android commits. Branches are **retained**, not deleted (see §6).

## 4. Versioned / installed APK state

- `app/build.gradle.kts`: `versionCode = 11`, `versionName = "1.0.0-readium9"`,
  `applicationId = com.bookrio`, `minSdk 26`, `targetSdk/compileSdk 36`.
- ABI splits: `arm64-v8a`, `armeabi-v7a`, `x86_64` + universal.
- Release build type: `isMinifyEnabled = true`, `isShrinkResources = true`,
  **signed with the debug keystore** (`signingConfig = debug`). ← blocker.
- Published test artifacts (GitHub releases) — **TEST ONLY, not production**:
  - `bookiro-home-onboarding-{arm64,universal}.apk` (local, vc11, `54947a3`-era).
  - `bookiro-overnight-review-54947a3`, `bookiro-overnight-review-3371988`.
  - `bookiro-android-test-952d873` — **broken blank-reader artifact**, do not
    relabel as production.
- No production keystore, AAB, or Play upload exists. Do not overwrite or label any
  existing test artifact as production-ready.

## 5. Known release blockers (entry state)

1. **Release signing** uses the debug keystore; production signing config + docs were
   missing. (Being added on this branch; secrets never committed.)
2. **Readium in-book parity incomplete**: bookmarks, search, highlights, TTS/share,
   margins/line-height/paged-scroll preferences, and old `pageIndex` → Locator
   migration are missing/partial. See `reader/README-READIUM.md` §3.
3. **Android Auto browse → direct play**: list renders but selecting an item does not
   start playback (no `onAddMediaItems` resolution of `book_<id>`).
4. **Privacy/data-flow docs** and Play Data Safety checklist not yet written.
5. **Torrent / Libgen decision**: Libgen preset already removed; a Play-safe product
   flavor that strips torrent UI/deps is not yet defined.
6. **Committed Windows JDK path** in `gradle.properties`
   (`org.gradle.java.home=C:/Users/tlarsen/...`) breaks Linux/macOS/CI — removed on
   this branch.
7. Regression/data-safety of the overnight repairs not independently re-verified
   (candidate collapses, metadata refresh, FTP restart).

## 6. Branch-retention / PR plan (proposal, no deletion)

- Source of truth: `release/bookiro-android-rc`.
- Open **one new PR** from `release/bookiro-android-rc` → `bookiro/main` once the GO
  criteria are met. Rationale: `bookiro/main` is the current default branch and the
  RC is a strict descendant, so this is a fast-forwardable, reviewable integration.
  Do **not** retarget PR #4 to `main` and do not merge `feat/home-onboarding` or
  `main` into each other.
- Retain `overnight-review`, all `recovery/*`, `feat/*`, `fix/*` and iOS branches as
  history. Archive/cleanup only after the RC merges and is tagged.

## 7. Build environment (verified locally)

- JDK 17 (`/usr/lib/jvm/java-17-openjdk`), Gradle 8.11.1 wrapper, AGP 8.5.2,
  Kotlin 2.0.21, Android SDK platforms 35 + 36, build-tools 34/35/36.
- `:app:assembleDebug` — **BUILD SUCCESSFUL** on this branch (2026-10-04).
- Emulator AVD `bookiro35` (Android 35 x86_64) available; no physical device
  attached at baseline time.