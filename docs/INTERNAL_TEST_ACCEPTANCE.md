# Bookiro — INTERNAL TEST acceptance (Bookiro id, data-safety RC)

Base commit: `eec1c7c` **plus uncommitted working-tree changes** (applicationId correction
+ data-safety fixes). This candidate was **not** built from a clean commit — HEAD alone
does not identify its complete source.

Target ids: `full` = `com.bookiro`, `playstore` = `com.bookiro.play`.

## Verdicts

| Gate | Verdict | Basis |
|---|---|---|
| **Local / device test readiness** | **READY TO BEGIN** | both flavors build; 406 JVM tests pass; Playstore lint 0 errors; 16 KB checks pass; data-loss fixes in. Emulator pass done for launches/onboarding/reader (see below); audiobook, sources, Auto and certs still need manual testing. |
| **Play internal-track upload readiness** | **NOT READY** | no production-signed AAB (only debug/**TEST**-signed); upload key/Play App Signing not set up. |
| **Production readiness** | **NOT READY** | device acceptance, data-safety owner decisions, privacy-policy URL and F2 disclosure still pending. |

## What is automated vs manual

**Automated (run, observed):**
- `:app:assembleFullDebug :app:assemblePlaystoreDebug` — BUILD SUCCESSFUL.
- `:app:bundleFullRelease :app:bundlePlaystoreRelease` (debug/TEST signing) — BUILD SUCCESSFUL.
- 406 JVM unit tests across core/data/library/reader/player/podcast/ftp/smb/webdav/calibre/app — 0 failures.
- `:app:lintPlaystoreRelease` — 0 errors, 94 warnings, 1 info.
- `tools/verify_playstore_variant.sh` on the Playstore AAB — OK (torrent-free); full AAB contains `libtorrent4j` (3 ABIs).
- 16 KB: `zipalign -c -P 16` on the Playstore APK = successful; ELF PT_LOAD align `0x4000` for every `.so`.
- R8 mapping embedded in the AAB (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`).

**Manual (requires owner interaction; NOT TESTED here):** every item below.

`adb devices` was checked before planning device work. The owner started the
`Bookiro_API35_ARM64` emulator (`emulator-5554`, arm64-v8a). Installation was done by the
agent on that emulator only; **no uninstall / clear-data / device-wipe was performed**.

## Observed results on the emulator (2026-10-08)

Environment: `emulator-5554`, AVD `Bookiro_API35_ARM64` (API 35, arm64-v8a).
Installed simultaneously: old `com.bookrio.debug` (from an earlier snapshot), new
`com.bookiro.debug`, and new `com.bookiro.play.debug`. No crash (`FATAL EXCEPTION`) and no
"Database open failed" in logcat during the whole pass.

| Observation | Result |
|---|---|
| Old `com.bookrio.debug` and new `com.bookiro.debug` + `com.bookiro.play.debug` coexist; all three launch | **PASS** |
| Onboarding renders (both flavors): "BOOKRIO" mark, "Welcome to Bookiro", language + folder prompt | **PASS** |
| Full Home shows the **Torrent** tile; Playstore Home does **not** | **PASS** |
| Onboarding "Skip for now" → Home renders (media counts, tiles) | **PASS** |
| Bundled sample import (`Load sample books`): `ImportWorker` SUCCESS → 7 ebooks + 2 audiobooks | **PASS** |
| Valid EPUB imported via the SAF picker (`Bookiro_Test_EPUB.epub`, 25.6 kB) → `[CREATE_EBOOK] … format=EPUB path=null` | **PASS** |
| Reader opens the SAF EPUB and renders real text ("Chapter 1" heading + paragraphs) | **PASS** |
| ≥20 page turns (swipe) advance the content (screenshots differ) | **PASS** |
| TOC (`Contents`) lists Chapter 1–5, hierarchical | **PASS** |
| `Add bookmark` saves a bookmark ("Chapter 1", "16 % through the book") | **PASS** |
| Invalid/truncated EPUB (bundled 631-byte stub) → graceful "Cannot read the book" error, no crash | **PASS (error handling)** |
| Force-stop + relaunch | **PASS** |
| Rotation landscape↔portrait (no crash, same activity) | **PASS** |
| Offline (wifi+data off) launch + Home render | **PASS** |
| Repeated startup/import: counts stay consistent (7→8 ebooks), no crash | **PASS** |
| **Real M4B audiobook** (LibriVox *Gettysburg Address*, 1.36 MB, public domain): import, play, seek, speed, background | **PASS** | media_session `state=PLAYING`, speed 1.2×, still playing after Home |
| **Podcast** (local RSS over `10.0.2.2`): add feed, sync, stream episode | **PASS** | server served `/feed.xml` + `/episode.mp3`; `PodcastFeedSyncWorker` SUCCESS; session `shelf_podcast` PLAYING |
| In-book search for `mailbox` (present in the text) | **PASS after fix** — the empty-state message was shown before searching; fixed with a `hasSearched` flag. Verified: no message before pressing Search, then 5 hits |
| Bookmark re-open / list / navigate | **NOT VERIFIED** (bookmark save confirmed; list navigation not isolated) |

### Findings from the emulator pass (status after fixes)

1. **Bundled sample "books" were sub-1 KB stubs** (`assets/samples/*`, EPUB 631 B, M4B 615 B,
   MP3 514 B). They imported but could not be read/played. The **"Load sample books" option
   was removed** (UI card, `enqueueSamples`, `importAssetsSamples`, `SampleBooks`/`SampleData`,
   the dead `SeedCallback` demo data, the strings and the assets). Verified on-device: the
   Import screen offers only real sources.
2. **In-book search works** (5 hits for `mailbox`). The defect was a misleading empty state:
   `ReadiumSearchSheet` showed "No matches found" whenever the query was non-blank, even
   before a search ran. Fixed with a `hasSearched` flag that only shows the message after a
   search completes. Verified on-device.
3. No data-loss behaviour was observed: the DB opened cleanly, nothing was auto-deleted, and
   the old `com.bookrio` app kept its own separate data.
4. **Android Auto could not be completed** on `emulator-5554` despite a full setup attempt:
   the real **Android Auto 17.9.664004** was sideloaded (system stub removed via
   `-writable-system` + `remount`), developer mode was enabled, and the **head-unit server
   was running on 5277** (`GH.DHUService: Network server running on port 5277`). The
   Desktop Head Unit connects, the phone logs `Head unit connected` and handles the
   `com.google.android.gms.carsetup.START_DUPLEX` intent, but then the phone logs
   `Exception in DeveloperHeadUnitNetworkService.ProxyThreadHandler.run` and the DHU logs
   `Failed to read from transport - disconnect. Exiting` — the projection never streams
   (`Waiting for phone...`). Likely causes: the only available **DHU is v2.0 (build
   2022-03-30)** (confirmed: SDK Manager offers no newer version) and/or the
   `google_apis` image is not **Play-certified**. Structural Auto readiness is correct.

## Owner device checklist — PASS / FAIL / NOT TESTED

| # | Scenario | Status | Notes |
|---|---|---|---|
| 1 | Fresh install of `com.bookiro` (full) **alongside** the old `com.bookrio` app; both coexist and the old one is untouched | **PASS** | three packages installed together; old app launched with no FATAL |
| 2 | Onboarding + SAF folder selection, including cancelling the picker | **PASS / PARTIAL** | onboarding renders; folder picker **cancel** not exercised |
| 3 | EPUB: ≥20 page turns, chapter boundary, TOC jump, in-book search | **PASS** | page turns + TOC + in-book search all pass (after the empty-state fix) |
| 4 | Bookmarks/highlights: save, reopen the book, list and navigate | **PARTIAL** | save **PASS**; reopen/list/navigate **NOT VERIFIED** |
| 5 | Reader resume after force-stop / process death | **PASS** | force-stop + relaunch |
| 6 | Rotation and large font settings in the reader | **PASS / PARTIAL** | rotation **PASS**; large-font settings not exercised |
| 7 | Audiobook: play, seek, chapters, speed, background/lockscreen controls | **PASS** | real M4B: play, seek (+30s), speed 1.2×, background playback; chapter list present (1 chapter) |
| 8 | Podcast ↔ audiobook switching: exactly one playback owner and correct metadata | **PARTIAL** | podcast playback **PASS** (own `shelf_podcast` session); the live A↔B owner switch was not driven on device — covered by JVM hand-off invariants only |
| 9 | Fully offline local reading and listening | **PASS / PARTIAL** | offline launch+render **PASS**; offline audio not tested |
| 10 | Repeated import / scanning / startup: no duplicates and no disappearing books | **PASS** | samples + valid EPUB; consistent counts; no crash |
| 11 | Source browsing, transfer cancellation, unreachable-server handling | **NOT TESTED** | no server configured |
| 12 | Upgrade / data preservation with the **SAME** applicationId and a compatible signing key | **NOT TESTED** | no prior `com.bookiro` install |
| 13 | Android Auto/DHU browse → direct playback and correct metadata | **NOT TESTED (environment)** | real AA 17.9 sideloaded; dev mode + head-unit server on 5277 running; DHU connects but transport drops (`ProxyThreadHandler` exception / `Failed to read from transport`) → no projection. Only DHU v2.0 (2022) available; `google_apis` image not Play-certified. Structural readiness verified (manifest car metadata + `MediaLibraryService` + `MEDIA_PLAY_FROM_SEARCH`). Try the **Play-image AVD** (`Medium_Phone_API_37.0`) with Android Auto installed from Play |
| 14 | File sharing / FileProvider after the id change (`com.bookiro.fileprovider`) | **NOT TESTED** | |
| 15 | Self-signed certificate opt-in works; an invalid certificate is rejected by default | **NOT TESTED** | no TLS server; WebDAV `trustAllCertificates` defaults off |

**#12 must be distinguished from #1:** the Bookrio → Bookiro change is a *new installation*
with **no** automatic data migration. #12 tests the ordinary upgrade path within
`com.bookiro` (same id, compatible signing) and is the only one that should preserve data.

## Owner handoff — publishing to Play internal testing (ordered)

> The owner has **not** used Play Console yet: no app, no AAB, no release. None of the
> steps below have been performed. Do not assume any Console action is done.

1. **Developer account.** Complete Google Play developer-account setup and the required
   identity/verification. A **new personal** developer account must later run a closed
   test before production access.
2. **Create the app.** In Play Console create Bookiro under the package **`com.bookiro.play`**.
3. **Upload key.** Create an upload keystore via Android Studio
   (*Build → Generate Signed Bundle / APK → Android App Bundle* → *Create new…*), store it
   and its passwords in a password manager (never in the repo).
4. **Signing inputs.** Provide the four `BOOKIRO_*` values (env or ignored
   `keystore.properties`) without printing or committing them. Production signing is
   fail-closed: without them `:app:bundlePlaystoreRelease` fails.
5. **Build + verify.**
   ```
   ./gradlew :app:bundlePlaystoreRelease
   tools/verify_playstore_variant.sh app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab
   jarsigner -verify -verbose -certs app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab   # must NOT say Android Debug
   ```
6. **Play App Signing + internal test.** Enroll the upload key (Google holds the app
   signing key), then upload the AAB to **Internal testing**.
7. **Testers.** Add a few trusted tester emails and install through Play.
8. **Closed test (later).** Prepare the closed test required for the personal developer
   account. **Internal testing does not satisfy that requirement.**

Production privacy declarations, the privacy-policy URL, the Data Safety form and the
store listing are **separate work** and are neither done nor invented here.
