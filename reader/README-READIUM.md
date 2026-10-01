# Android reader — Readium rebuild

This module now reads **EPUB** with the Readium Kotlin Toolkit. Bookiro does **not**
own EPUB layout or pagination any more; Readium parses the publication and its
`EpubNavigatorFragment` renders it. The old custom `ReaderWebEngine` (CSS columns,
JS page counts) remains only for non-EPUB formats.

## 1. Toolchain / Readium version decision

The repo pins **Kotlin 2.0.21** (AGP 8.5.2, Gradle 8.11.1, Compose BOM 2024.09.02,
Room 2.6.1, Media3 1.4.1). Released Readium versions and the Kotlin metadata they
are built with:

| Readium | kotlin-stdlib | readable by Kotlin 2.0.21 |
|---|---|---|
| 3.0.3 | 1.9.24 | **yes** |
| 3.1.2 | 2.1.21 | no |
| 3.2.0 / 3.3.0 | 2.3.20 | no |
| 3.4.0 | 2.4.20 | no |

**Decision: Readium 3.0.3.** It is the newest released line whose metadata the
pinned compiler can consume, so **no project-wide toolchain upgrade is needed**
and metadata validation is not disabled. This was proven with a compile probe
(`ReadiumCompatProbe.kt`) before any UI work. Artifacts used:
`readium-shared`, `readium-streamer`, `readium-navigator` `3.0.3`; the stable
fragment is hosted with `androidx.fragment:fragment-compose:1.8.7`
(`AndroidFragment`) — no alpha Compose navigator.

## 2. Architecture

- `ReadiumPublicationOpener` — opens a local file with `PublicationOpener`
  (`DefaultPublicationParser`, no PDF factory). Returns `Result<Publication>`.
- `ReadiumEpubReaderScreen` — Compose screen. Builds an
  `EpubNavigatorFactory(publication).createFragmentFactory(initialLocator, prefs)`,
  installs it on the host `FragmentManager`, and hosts the stable
  `EpubNavigatorFragment` via `AndroidFragment`. Bookiro owns the chrome only.
- `ReaderScreen` routes `FormatEntity.EPUB` here and keeps the existing engine for
  PDF/CBZ/MOBI/FB2/TXT/… (`BookLoaderEngine` + `ReaderWebEngine`).

## 3. Feature-parity table

| Bookiro in-book feature | Status | Implementation / gap |
|---|---|---|
| Open EPUB, visible text | ✅ emulator | Readium `PublicationOpener` + `EpubNavigatorFragment` |
| Paged reflow, reflow on font/viewport | ✅ | Readium navigator owns layout |
| Swipe / page turn | ✅ emulator | Readium gesture |
| Chapter boundaries (forward/back) | ✅ emulator | Readium reading order |
| TOC list + jump | ✅ emulator | `publication.tableOfContents` + `navigator.go(link)` |
| Progress read/seek | Partial | `currentLocator` persisted; no scrub UI yet |
| Reading position restore on reopen | ⚠️ not verified | initialLocator from stored Locator (additive) |
| Font size / theme (dark·light·sepia) | Partial | `A-`/`A+`/`Tema` → `submitPreferences`; margins/line-height/columns missing |
| Paged vs scroll mode | ❌ | `EpubPreferences(scroll=…)` not wired to UI |
| Bookmarks add/list/remove/jump | ❌ | needs Locator-based bookmarks UI |
| In-book search | ❌ | Readium `SearchService` not wired |
| Text selection / highlights | ❌ | not wired; `selectionActionModeCallback` unused |
| TTS | ❌ | old `TtsPlaybackEngine` is tied to the old chapter model |
| Share | ❌ | not re-wired in the new chrome |
| Accessibility | Partial | Readium default web a11y; chrome needs content descriptions |
| Tap anywhere to toggle chrome | Partial | margins toggle; full-surface gesture needs a Readium gesture hook |
| PDF / CBZ / MOBI / FB2 / TXT | ✅ (unchanged) | routed to the existing Bookiro engine |
| Data safety | ✅ | **no schema change**; old rows untouched |
| Position migration | Partial | Locator stored in existing `anchor_cfi`; **old `pageIndex` is not mapped to a page number** (pagination is not stable after reflow). Books with only old progress open at the start. |

## 4. Position model

- Persisted: `reading_progress.anchor_cfi = Locator.toJSON()`, `anchor_href`, and
  `progress_percent = locations.totalProgression`. This is **additive** — no
  destructive migration, no reset.
- Restored: `Locator.fromJSON(anchor_cfi)` is passed as `initialLocator`.
- **Honest limitation:** existing installs that only have `pageIndex` cannot be
  translated to a stable text position (font/viewport reflow changes pagination).
  They open at the book start rather than at a guessed page. A best-effort
  chapter+progression conversion is still to be implemented.

## 5. Emulator evidence (Android 35 x86_64)

- A real fixture EPUB (cover, front matter, SVG image, EPUB3 nav, 3 chapters) was
  pushed into the app and opened through `reader/1`:
  - cover + front matter rendered; chrome shows **Innhold / A- / A+ / Tema**.
  - swipe → `OEBPS/ch1.xhtml`, progress **25 %**, chapter text visible.
  - TOC → `OEBPS/ch3.xhtml`, progress **75 %**, chapter text visible.
  - `reading_progress.anchor_cfi` updated with Readium Locator JSON.
- **Not verified:** the user's real/failing EPUBs, process-death restore,
  font/theme reflow position stability, search/highlight, TTS, Android Auto
  regression, and non-EPUB regression on device.

## 6. Status

**NOT READY.** The navigator shows real text (the previous blank-book failure is
resolved), but essential in-book menus (bookmarks, search, highlights, TTS, share),
full preference mapping and old-position migration are still missing.