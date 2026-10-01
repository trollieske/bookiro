# Bookiro Android — complete Readium reader rebuild

Pi/DeepSeek: execute this on the VERIFIED Android recovery branch/worktree. This decision SUPERSEDES the reader portions of `docs/ANDROID_RELEASE_RECOVERY_BOOKIRO.md` and `docs/BOOKIRO_TEST_APK_HANDOFF.md`. The installed `bookiro-android-test-952d873` build displays entirely blank ebooks; it is broken. Do not spend more time patching custom `ReaderWebEngine`, CSS columns, JS page counts, bitmap pages or curl. The user wants a COMPLETE Android in-book reading experience based on Readium Kotlin Toolkit — not just a new renderer behind the old menus. iOS owns its separate native page-curl reader and is OUT OF SCOPE.

## 0. Safety and source

Inspect all local worktrees, diffs, remotes and ancestry first. Continue from the newest Android recovery work, preserving torrent, playback, Android Auto and Bookiro branding changes. Do not reset, clean, force-push, merge to `main`, edit `ios-parity`, delete user data or reimport user books. Make a dedicated Readium branch/worktree if parallel agents are running. Single integrator owns shared Gradle/app/DB files and integration. Capture the currently failing reader behavior with a real EPUB before editing; keep logs privacy-safe. Test1 stays marked broken.

## 1. Choose a supported Readium integration

Use Readium Kotlin Toolkit for EPUB publication opening, resources and its supported EPUB Navigator, navigation, progression/Locators and preferences. Evaluate released versions against the repo's pinned Kotlin/Gradle/Android setup; reproduce the prior Kotlin metadata incompatibility. Resolve it with a compatible released version or a controlled, tested toolchain upgrade. Never disable metadata validation, blindly replace the whole build, or use alpha Compose navigators merely to avoid hosting a stable Android Fragment. Readium may use web rendering INTERNALLY; Bookiro must not own EPUB layout/pagination in its own WebView. Do not overlay the old custom paginator on Readium.

## 2. Replace the ENTIRE in-book UI

Readium does not supply a finished Bookiro toolbar. Rebuild the reader chrome/menus as NEW Bookiro UI backed by Readium's Publication/Navigator APIs; retain Bookiro's OLED-black/lime visual identity, but retire the old reader controls, menu state and page-index assumptions. Inventory every existing in-book action before deleting it. Implement and verify:

- Full-screen reading and tap to show/hide controls without resizing or blanking the book.
- Actual TOC/spine links and chapter navigation.
- Bookmarks: add/list/remove/jump using Locators; restore old bookmarks as accurately as possible.
- Book and chapter progress/seek without pretending page N remains stable after font/viewport reflow.
- Font, size, theme, margins and paged/scroll mode via supported Readium preferences; hide unsupported controls.
- In-book search and result navigation using supported Readium APIs.
- Text selection, highlights and highlight navigation where the selected Readium version supports them; no fake bitmap/text-range mapping.
- Existing TTS/share/accessibility actions: integrate them correctly with Readium or flag any lost essential feature as a release blocker. No dead buttons or silent feature removals.

Map old feature → new API/implementation → test evidence in a feature-parity table. Remove obsolete custom CSS/JS/bitmap/curl/menu code only after replacement works. Keep PDF, CBZ and non-EPUB routes working: explicitly route unsupported formats to their existing renderer rather than forcing them through the EPUB navigator.

## 3. Data compatibility

Existing installations have reading progress/bookmarks based on chapter-local pageIndex/percent. New positions must use Readium Locators. A prior page number is NOT a stable text position after reflow. Keep old stored data intact, add an additive migration or lazy per-book conversion to a defensible nearest chapter/progression/anchor, and record ambiguity honestly. No destructive schema change or reset to the beginning of a partially read book. Test resume on a previously started EPUB before/after upgrade, font/theme change, rotation, process death, TOC and bookmark jumps. If exact translation is impossible, choose nearest chapter/text and document the limitation.

## 4. Delegation and proof

Integrator delegates with non-overlapping ownership: A compatibility/open-publication/navigator; B Bookiro in-book UI/menus; C position/bookmark migration and persistence; D independent test harness/feature parity. Subagents use isolated worktrees; only integrator edits cross-cutting Gradle/DB/app files and integrates after focused tests. Do not let four agents rewrite ReaderScreen simultaneously. First milestone is ONE real EPUB visibly displaying text on Android emulator/device, then navigate across 3+ chapters; do not polish menus over a blank navigator. Use fixture EPUB with cover, front matter, images and real TOC, plus the user's failing books when available.

Run module tests, `:app:assembleDebug`, broader tests and localization checks. Create instrumented/emulator tests that inspect VISIBLE text and actual Locator changes; parser-only/JVM tests are not sufficient. On device test first page, 20+ forward/back turns, both chapter boundaries, TOC, existing/new bookmarks, search, highlight, reader preferences, rotation, background/restart and progress migration; compare Bookiro's look with existing branding. Preserve audio/Auto functionality. If no phone is available until evening, continue static/emulator work and STOP SHORT of claiming device verification.

## Delivery gate

Separate commits for dependency integration, reader UI and data migration. Build a NEW clearly labelled test APK with a new version and SHA only when visible text works on emulator; never overwrite test1, merge to `main`, modify iOS or ship to Play automatically. Report Readium version/toolchain choice, feature-parity table, before/after reading location, tests, APK provenance, what ran on emulator vs physical phone, and unresolved blockers. If books remain blank or essential in-book menus are missing, status is NOT READY.
