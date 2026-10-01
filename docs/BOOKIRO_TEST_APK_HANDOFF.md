# Bookiro Android test APK — Pi/DeepSeek handoff

Execute this in the local Android recovery worktree. The user cannot connect a phone until tonight and specifically wants a DOWNLOADABLE APK on GitHub now, with the app named and visibly branded **Bookiro**. This is a test build, NOT a production-ready release. Continue the existing recovery work; do not restart the reader/Auto implementation or redo completed tests. Read `docs/ANDROID_RELEASE_RECOVERY_BOOKIRO.md` for the full acceptance gates.

## 1. Verify source and preserve work

- Inspect all local worktree statuses and confirm `recovery/bookiro-android-release` is the integrated Android branch at or after `4e629e8`, based on `bookrio/main`. Confirm it includes reader `recovery/reader-webview` and Android Auto `recovery/android-auto` fixes. If local SHA differs, report actual ancestry before proceeding. Do not reset, clean, force-push, touch `ios-parity`, or merge into `main`.
- Verify the build inputs contain the intended Bookiro branding in all 10 locales, app label, onboarding/About, notifications and Android Auto. Keep `com.bookrio` as the package and preserve the logo symbol/colors; fix visible old Bookrio/Shelf text if found. Inspect merged manifest and packaged resources, not just Kotlin/source strings.

## 2. Validate what can be validated WITHOUT A PHONE

- Run `:app:assembleDebug`, `:app:assembleRelease`, relevant reader/Auto tests, broader unit tests and localization validator. Record actual counts and pre-existing translation gaps. Do not call Robolectric a real-device EPUB test or a pure browse-tree test a real Android Auto test.
- Inspect the final APK with Android SDK packaging/signing tools: exact package (`com.bookrio` for installable release-variant APK), versionCode, versionName, default and Norwegian app labels **Bookiro**, launcher icon, signing-certificate SHA-256 and APK SHA-256. If code or packaging is wrong, fix and rebuild before upload.
- If an Android emulator is available, install the EXACT candidate APK there without wiping user data, capture launcher/About/reader screenshots, and verify the Bookiro label and existing visual identity. Otherwise state that visual/device verification remains pending. Do not claim to have tested the user's failing EPUBs without access to them.
- Do not make a speculative visual redesign: preserve the OLED-black/lime brand, layout and logo. The goal is consistent **Bookiro** branding and an actually usable reader, not screenshots that merely look polished. The current direct-WebView reader still requires real-book validation tonight.

## 3. Publish a clearly labelled TEST APK on GitHub

- Push ONLY the integrated recovery branch to `trollieske/bookrio`. Verify the remote tip matches the build source SHA and contains no stray subagent commits. Do not merge to `main`.
- Produce one downloadable, non-expiring GitHub APK asset tied to that exact SHA. Prefer a NEW GitHub prerelease tagged uniquely for this recovery build (for example `bookiro-android-test-<shortsha>`), clearly named `Bookiro Android — TEST / NOT READY`, with the APK attached; if release asset upload is unavailable, use a durable downloadable Actions artifact on the pushed recovery branch and explain access/expiry. Do not overwrite `v1.0.0`, publish to Google Play, or mislabel a debug-signed release-variant APK as production-signed. Do not commit the binary APK into source or Git LFS.
- Give the download page link, APK filename, FULL source commit SHA, APK SHA-256, signing certificate fingerprint, package ID, versionCode/versionName, and exact limitations in release notes. The old `trollieske/shelf` RC1 APK says Bookrio and must NOT be offered as the new test build.
- Check update compatibility: same package AND same signing key as the user's installed APK, and a permissible versionCode. Because the installed APK is unavailable now, mark compatibility as UNCONFIRMED; include a short evening procedure to check it before installing. Never suggest uninstalling or clearing app data. If a safe versionCode bump is needed, do it deliberately in the recovery branch and rebuild the exact asset.

## 4. Evening device gate — do not mark done prematurely

Prepare a concise checklist for when ADB/phone returns: inspect the currently installed APK identity first; install the test build in place if compatible; verify Bookiro launcher/Android Auto label, onboarding/About and notification branding; test resume on an already-started problematic EPUB BEFORE/AFTER update, 20+ turns, chapter edges both ways, TOC, bookmarks, font/theme, rotation and reopen; test audiobook/podcast artwork handoff; test MediaBrowser/DHU and, only while parked, real Android Auto browse and play. Capture failures/logcat and fix on recovery branch.

FINAL REPORT: remote recovery branch link, downloadable APK link, provenance hashes/version/signature, build/test results, Bookiro branding verification, what was and was NOT visually verified, and a clear `TEST BUILD — NOT READY FOR RELEASE` status until reader and Android Auto pass on device. Do not stop after merely pushing source code; the user asked for an APK they can download tonight.
