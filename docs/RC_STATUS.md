# Bookiro Android — Release Candidate status (handoff)

Branch: `release/bookiro-android-rc`
Base: `d6bf7da` (`overnight-review` tip) — a strict descendant of `bookiro/main`
and `feat/home-onboarding`.
Integrated RC commits (on top of the base):

| commit | scope |
|---|---|
| `edd315a` | build: production release signing scaffolding (fail-closed, no secrets) |
| `657827d` | fix(player): resolve browsed `book_<id>` into a complete playable timeline |
| `f9d9844` | feat(reader): complete Readium in-book experience |
| `cca9230` | docs: release/privacy handoff |
| `924ba94` | test(audit): JVM regression/data-safety harness |

**Verdict: NO-GO for production / Play.** The code/config gates pass; the missing
gates are a real-device acceptance pass, a production signing key, and owner
elections (Play variant, F2 disclosure). "Green unit tests" alone is not GO.

---

## 1. Build & test evidence (2026-10-04, JDK 17 / Gradle 8.11.1)

| Check | Result |
|---|---|
| `./gradlew testDebugUnitTest` (all modules) | **410 tests, 0 failures, 0 errors** |
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew lintDebug` (all modules) | BUILD SUCCESSFUL |
| `:app:bundleRelease` + `:app:assembleRelease` (test signing) | BUILD SUCCESSFUL |
| `tools/validate_localization.py` + `tools/i18n_check.py` | all green |
| merged release manifest | targetSdk 36, minimal permissions |

Per-module test counts: reader 57, player 65, library 73, podcast 70, core 42, ftp 34,
calibre 21, torrent 20, data 16, webdav 9, app 3.

## 2. RC artifact provenance (`BOOKIRO_ALLOW_DEBUG_SIGNING=true`, TEST/RC only)

- source commit `924ba94` (tree of the RC tip)
- `versionCode = 11`, `versionName = 1.0.0-readium9`
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64` + universal
- signing identity: **`CN=Android Debug`** (SHA-256 `b3ef7270…55e1`) — **TEST ONLY**
- `app-release.aab` 50.8 MB — SHA-256 `8fe0baf22cd238e061f70eea9acad62f3f9481ccf1de6c61b2a3650d6322c87e`
- `app-arm64-v8a-release.apk` — SHA-256 `7fe4e1389164f7ef429baeee31ab14397118a24537be2b0d718e9adbab35d5ad`
- `app-armeabi-v7a-release.apk` — SHA-256 `5c7c5e4df2a487f81d5619ec9f8a894ce38bbc9a201c5de3be7cd50a6022365e`
- `app-x86_64-release.apk` — SHA-256 `8e4ef9fca39d32c084a7403e781e15462f2e6e221acb22970842923968a663ad`
- `app-universal-release.apk` — SHA-256 `9d0a2ac63c55e7e85dd01782de2b84f79ad2b295e6697d900641d0d2daf84292`
- 16 KB page size: `zipalign -c -P 16` → **Verification succesful**; all `.so` LOAD
  segments `0x4000`.

A production-signed artifact does **not** exist yet: the release signing gate fails
until the four `BOOKIRO_*` secrets are provided (see `docs/RELEASE_SIGNING.md`).

## 3. Readium / Bookiro reader parity

| Feature | Status | Evidence |
|---|---|---|
| Readium-first EPUB, real text | done (previous on-device fix retained) | `ReadiumPublicationOpener` + `EpubNavigatorFragment`; SAF `content://` path |
| TOC (hierarchical) + spine fallback | done | `buildBookChapters`, sheet |
| Active chapter from Locator | done | `chapterIndexForLocator`, `currentLocator` collect |
| Progress from Locator progression | done | `totalProgression` |
| Bookmarks add/list/remove/jump | **done** | `BookmarkDao` + `anchor_cfi`; `toggleBookmark`/`jumpToBookmark` |
| Legacy position migration | **done (best-effort)** | `ReadiumResume`; Locator → chapter+percent → honest unresolved |
| Font size / theme / margins / line height / paged-scroll | **done** | `ReadiumReaderPreferences.toEpubPreferences()` (only 3.0.3-supported fields) |
| Preferences persist/restore without losing position | done | reader-private store + `applyPreferences` re-anchor |
| In-book search | **done** | `publication.search()` (StringSearchService installed by the parser) |
| Selection → persistent highlights + list + jump | **done** | `SelectableNavigator` + `Decoration.Style.Highlight` ↔ `HighlightEntity.start_cfi` |
| Share with location context | done | `sharePosition()` |
| Full-surface tap toggle, overlay chrome | done | Readium `InputListener`; overlay Box |
| TTS | **not present** | old chapter-model TTS was retired with the old chrome; no dead button. Not re-integrated. |
| Bookmark near chapter boundary / after font change | not device-tested | logic unit-tested only |
| Non-EPUB (PDF/CBZ/MOBI/FB2/TXT) | unchanged | routed to `BookLoaderEngine` |

Reader tests: `reader/src/test/.../ReadiumReaderLogicTest.kt` (17 tests) plus the
existing chapter-mapping tests — 57 total.

**Not verified:** on-device/emulator reading of a real EPUB (20+ turns, cross-chapter,
TOC/search/highlight/bookmark/rotation/force-stop/resume). The subagent that owned the
reader was terminated before the device pass; this session could not seed a book into
the emulator library headlessly (SAF-only onboarding). This is the biggest reader gap.

## 4. Android Auto browse → direct playback

Root cause (independently confirmed from Media3 1.4.1 bytecode):

- `MediaSessionLegacyStub.onPlayFromMediaId` builds an ID-only `MediaItem`
  (`book_<id>`, no URI) and calls `onSetMediaItems(..., -1, C.TIME_END_OF_SOURCE)`.
- Default `onSetMediaItems` → `onAddMediaItems`; the default `onAddMediaItems` returns
  `immediateFailedFuture(UnsupportedOperationException)` for any item without a
  `LocalConfiguration`, and the legacy path swallows the failure → nothing plays.

Fix (`657827d`): override `onAddMediaItems`/`onSetMediaItems` to resolve every
`book_<id>` asynchronously into the book's **complete** chapter timeline (same
`AudiobookPlaybackPlan` used by the in-app load), with URI/title/subtitle/artwork/extras,
stored-position resume, ownership hand-off, and a hard error for unknown/missing items.

Evidence: 12 new JVM tests (`AudiobookPlaybackPlanTest`) covering id resolution, clipping,
one-file-per-chapter, missing file, resume target; 65 player tests green. The `book_<id>`
vs `<id>_<index>` id round-trip is asserted.

**Not verified:** DHU and a real parked car (DHU not installed; no device attached).
This is a harness/JVM proof only.

## 5. API 36 / signing / privacy / torrent

- **API 36:** compileSdk/targetSdk 36 confirmed in the merged release manifest.
  16 KB page size passes. Device behavioural pass on Android 16 outstanding.
- **Signing:** fail-closed scaffolding implemented; release fails clearly without the
  four `BOOKIRO_*` credentials; explicit `BOOKIRO_ALLOW_DEBUG_SIGNING=true` only for
  test artifacts. No key committed. See `docs/RELEASE_SIGNING.md`.
- **Privacy:** full outbound-flow register in `docs/PRIVACY_DATA_FLOW.md`
  (F1 opt-in metadata, **F2 automatic Audible/audnex chapter lookup — owner elected to
  keep+disclose**, F3 podcasts, F6–F8 user servers, F10 torrent, F13 backup). No ads,
  no analytics, no crash SDKs; debug LAN telemetry removed; no hardcoded endpoint.
- **Torrent / Libgen:** no Libgen link/preset/entry point exists (only filename-cleanup
  regexes). The RC is the **full/private** build including the torrent client. A
  `play-store` flavor removing `:torrent` + all entry points is specified in
  `docs/PLAY_VARIANT_DECISION.md` but **not applied** pending owner election.

## 6. Data-safety / regression audit (`docs/REGRESSION_AUDIT.md`)

JVM guarantees: SAF dedup idempotent + soft-delete + progress move only when the
survivor has none; audiobook fragments not consolidated for scoped storage; duplicate
tracks deduped/renumbered; FTP re-plan does not duplicate; single-owner hand-off
invariants hold.

**Known gates (not hidden):**
- two genuinely different EPUBs with identical title+author can be collapsed silently;
- audiobook fragment consolidation hard-deletes fragments (no soft-delete);
- chapter-name file titles in one folder can be wrongly split;
- the online metadata refresh can replace a good embedded cover with a mismatched result
  (opt-in only, no per-book preview);
- `ShelfApplication` can `deleteDatabase()` on a transient open failure.

**Unverified on device:** cold start, process death, rotation, offline, and upgrade from
the published `bookiro-overnight-review-54947a3` build.

## 7. Branch retention / PR plan (no deletion)

- Source of truth: `release/bookiro-android-rc`.
- Proposed PR: `release/bookiro-android-rc` → `bookiro/main`, because the RC is a clean
  descendant of `main` (`71cc98b`) and this is the least ambiguous review target.
  Do **not** retarget PR #4 to `main`, and do not merge `feat/home-onboarding` into
  `main` (or vice-versa).
- Retain all existing branches; archive only after the RC merges and is tagged.

## 8. Required before GO

1. Real-device / emulator reader acceptance (EMU + parked car for Auto).
2. Production upload keystore + secrets; rebuild AAB and verify the certificate.
3. Owner election on the Play variant and confirmation of the F2 disclosure.
4. Device pass for cold start / process death / offline / upgrade.
5. Resolve or explicitly accept the data-safety gates in §6.