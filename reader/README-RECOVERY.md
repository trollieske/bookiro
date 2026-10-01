# Reader recovery — direct WebView pagination (Android)

This document records the recovery of the Android EPUB reader after the previous
"simplified" reader was still broken on device. It also records why the Readium
Kotlin Toolkit was evaluated and **not** adopted, and what still has to be
verified on a physical device.

## Root cause that was replaced

`HtmlPageRenderer` rendered every page by driving an **off-screen**
`android.webkit.WebView`, capturing it into an `android.graphics.Bitmap`
(`postVisualStateCallback` + `draw(Canvas)`), caching the bitmaps in
`PageBitmapCache` and drawing them in `ReaderScreen` with
`drawIntoCanvas`/`drawBitmap`. That screenshot pipeline (plus its cache,
`RenderCoordinator` and generation gates) is what the recovery prompt forbids
continuing. It is now deleted:

- `engine/HtmlPageRenderer.kt`, `engine/PageBitmapCache.kt`, `engine/RenderCoordinator.kt`
- `test/.../RenderCoordinatorTest.kt`, `test/.../FixBPendingRenderRegressionTest.kt`,
  `test/.../ReaderContentBoxTest.kt` and the bitmap/generation parts of
  `ReaderRenderQualityTest.kt`

## Readium Kotlin Toolkit evaluation (decision: not adopted)

`org.readium.kotlin-toolkit:readium-navigator` (+ `readium-shared`, `readium-streamer`)
was evaluated first, as required. It was rejected for four concrete reasons:

1. **Toolchain incompatibility with the latest stable.** The newest release
   (3.4.0, Sept 2026) is built against `kotlin-stdlib 2.4.20`; 3.2.0/3.3.0 use
   2.3.20. This repository pins Kotlin **2.0.21** (AGP 8.5.2, Compose BOM
   2024.09.02, coroutines 1.9.0) in `gradle/libs.versions.toml`, and a 2.0
   compiler cannot consume 2.3/2.4 metadata. Only `3.1.2` (Kotlin 2.1.21) is
   even plausibly readable, i.e. integrating Readium would either force a
   project-wide Kotlin/AGP/Compose upgrade on a release-recovery branch, or pin
   an old, less-maintained release. (Versions verified from the published POMs
   on Maven Central.)
2. **It is a Fragment-owned reading UI, not a renderer.** `readium-navigator`
   depends on `androidx.fragment:fragment-ktx` and exposes `EpubNavigatorFragment`
   (its own WebView + its own Compose decoration overlay + `EpubPreferences`).
   The app is single-activity Compose Navigation with a custom HUD (tap zones,
   immersive controls, bottom sheets). Adopting it means either two overlapping
   reader UIs or rewriting `ReaderScreen` around Readium's API.
3. **Position/persistence model mismatch.** Bookiro persists chapter-local page
   indices (`ReadingProgressEntity.chapterIndex/pageIndex/progressPercent`) and
   page-indexed bookmarks/highlights. Readium positions are `Locator` JSON
   (href + progression + CSS selector) with a decoration API; adopting it means
   new columns/tables, a data migration for existing rows and a rewrite of
   bookmarks/highlights. Readium also parses publications itself
   (`readium-streamer`), replacing `BookLoaderEngine`'s EPUB/MOBI→EPUB pipeline
   and its data-URI image embedding.
4. **Risk.** No Android device is available in this environment, so a large new
   engine + migration could not be verified; the recovery prompt explicitly
   allows an equally robust direct WebView/HTML pagination approach. The
   fallback reuses the already-tuned `buildReaderHtml`/`STABLE_IMAGE_CSS`
   typography and keeps every ViewModel/database contract byte-identical.

## Replacement: direct, on-screen WebView pagination

- `engine/ReaderWebEngine.kt` owns one **visible** `WebView` (hosted by Compose
  `AndroidView` in `ui/ReaderScreen.kt`). No off-screen WebView, no bitmap, no
  capture, no shader, no overlay page, no timer patch.
- The chapter HTML (`ReaderChapter.htmlContent` from `BookLoaderEngine`) is
  wrapped by `buildReaderHtml` — the same CSS/typography as before, with
  `#content-wrapper` becoming the horizontal column scroller:
  `column-width: 100vw`, `column-gap: 0`, `column-fill: auto`, `overflow-y: hidden`.
- Page count = `ceil(scrollWidth / viewportWidth)`; current page = `round(scrollX / viewportWidth)`
  (JS reads both from the live layout; `ReaderPagination` mirrors the math on the
  JVM so it is unit-tested).
- Page turns are **programmatic horizontal scroll** (`scrollLeft = page × viewportWidth`),
  driven by the ViewModel's page index. Tap zones (left 28 % / right 28 % /
  centre) are detected in the WebView's JS and reported over the
  `AndroidReader` bridge; the padding margins around the reading area use the
  same zones in Compose. A user drag is snapped to the nearest column and
  reported back as a page change, so `scrollX` stays the source of truth.
- Chapter/font/theme changes reload the WebView with a new generation; stale
  JS callbacks are dropped. A viewport change (rotation, multi-window) triggers
  a re-measure and the ViewModel's page is restored.
- Backward chapter crossing no longer needs a pre-rendered neighbour bitmap:
  `vm.previousChapter()` sets `pendingRepositionPct = 1f`, and the existing
  `PageIndexMath.pageForPercent(1f, count)` resolves to the previous chapter's
  **last** page once it is measured (never page 0 of the wrong place).

### Preserved contracts

- `ReaderViewModel` chapter-local page model (`onPageCountKnown`, `onPageTurned`,
  `percent`, `pageIndex`) and `ReadingProgressEntity` persistence.
- **No position migration:** stored `pageIndex` stays chapter-local exactly as
  before; there is no old-page-N → new-page-N mapping because the model is
  unchanged (only the renderer behind it changed).
- Font size / theme prefs (`UserPreferencesRepository.readerFontSizeSp`,
  `readerTheme`; `readerLineHeight` was already unused and remains so), TOC and
  chapter jump, bookmarks, highlights/selection bridge, forward/back across
  chapter boundaries, orientation, process-death resume, offline operation.
- PDF/CBZ/MOBI/FB2/TXT: they all reach the reader as `ReaderChapter` HTML and
  are rendered by the same component — the format paths were not changed.
- TTS (`TtsPlaybackEngine`) consumes `ReaderChapter`/HTML and is untouched.

## Tests

- `SyntheticEpubReaderTest` (Robolectric, JVM): builds a synthetic EPUB
  (frontmatter + EPUB3 nav/NCX TOC + 3 chapters) and loads it through
  `BookLoaderEngine` + Room in-memory; asserts chapter HTML, `inToc`, titles and
  the pagination math over the loaded chapter.
- `ReaderPaginationTest` (JVM): page count / page-from-scrollX / scrollX-per-page,
  incl. rounding slop and clamping.
- `ReaderRenderQualityTest` (JVM): reader CSS (images, link colours, column
  pagination) and the scroll/tap/highlight bridge contract.
- `PageNavigationTest` (JVM): unchanged intra-chapter/boundary navigation math.

## Device verification still required (NOT AVAILABLE here)

No Android device is attached in this environment; nothing below is proven:
first page, 20+ successive forward turns, backward turns, chapter-boundary
crossings in both directions, TOC jump, font/theme change (position preserved),
rotation, reopen/resume, bookmarks, highlight selection, and the user's real
EPUBs. Also verify that `column-width: 100vw` + `scrollLeft` behaves identically
on the target WebView build, that text selection still shows the highlight
palette, and that PDF/CBZ pages still render.