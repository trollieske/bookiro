# Podcast + settings chrome — integration contract (iOS parity, agent 4)

Everything in this task is implemented as **new files only**:

- `shared/src/commonMain/kotlin/com/bookrio/podcast/ui/BookrioPodcastComponents.kt`
- `shared/src/commonMain/kotlin/com/bookrio/podcast/ui/BookrioPodcastRootScreen.kt`
- `shared/src/commonMain/kotlin/com/bookrio/podcast/ui/BookrioPodcastDetailScreen.kt`
- `shared/src/commonMain/kotlin/com/bookrio/podcast/ui/BookrioPodcastAddFeedDialog.kt`
- `shared/src/commonMain/kotlin/com/bookrio/settings/BookrioSettingsScreen.kt`

No file outside the allowlist was edited (App.kt / nav host / build files untouched).

## Exact entry signatures

```kotlin
package com.bookrio.podcast.ui

@Composable fun BookrioPodcastRootScreen(onOpenDetail: (Long) -> Unit, onOpenEpisode: (Long) -> Unit)

@Composable fun BookrioPodcastDetailScreen(feedId: Long, onBack: () -> Unit, onOpenEpisode: (Long) -> Unit)
```

```kotlin
package com.bookrio.settings

@Composable fun BookrioSettingsScreen(onBack: () -> Unit)
```

Each entry wraps itself in `ShelfTheme { }`, opens the shared Room DB through the
existing `internal expect fun appDatabase()` and is safe to drop into the current
Compose shell. The host (App.kt / nav host — not edited here) must:

1. Call the root as the podcast tab content; `onOpenDetail(feedId)` opens the detail.
2. Call the detail with `onBack`; `onOpenEpisode(episodeId)` is **informational only**.
   Both the detail row tap **and** the root resume strip already start playback via
   `PodcastPlayback.play(db, scope, episode, PodcastPlayback.resumePosition(db, id))`
   (single shared owner, `AudioOwner.PODCAST`). `onOpenEpisode` must NOT construct a
   second player or another AVPlayer; wire it to the existing now-playing bar or to
   nothing.
3. Settings: wire `onBack`.

## Android rows / screens matched

Podcast root (`PodcastRootScreen` list chrome): header (refresh / add feed), resume
strip (`PodcastResumePolicy` filter: followed + started + unfinished), `FOLLOWING`
label with new count, feed rows (title, author, episode + unplayed counts, latest
episode title + "d MMM yyyy" date, accent unplayed dot), empty state (+ RSS help),
foreground pull-to-refresh through `PodcastRepository.refreshAll()`.

Podcast detail (`PodcastDetailScreen`): back/refresh header, feed header (96 dp
artwork, title, author, language · Explicit, "Updated now"/"Could not update",
Follow/Unfollow), episode rows (play icon, title, description, date · duration,
accent progress bar + "… left" for started, check mark for completed), play on tap,
foreground pull-to-refresh through `PodcastRepository.refresh(feedId)`.

Settings (`SettingsScreen` section grammar — uppercase LabelMedium dim header,
`Panel` card + 4 dp radius, `BodyLarge` rows, `BodySmall` subtitles, 18 dp section
gap, 14 dp inner gap): sections `LIBRARY`, `READER`, `AUDIOBOOK PLAYBACK`,
`PODCAST PLAYBACK`. Ported rows: library view (Android library top-bar toggle),
tab counts (Android MainActivity pref), font size + theme (Android Reader section),
line height (Android pref, no Android UI), playback speed + skip back/forward +
fade out + auto-play next (Android Audiobook playback section), podcast speed
(Android podcast player "Playback speed"). Header is a plain HUD row (back arrow +
"Settings") instead of Android's `Scaffold`/`TopAppBar`.

## Prefs written (all through `AppPrefs` / `PrefKeys`)

| key | format | default | range written |
|---|---|---|---|
| `LIBRARY_VIEW` | `"grid"` / `"list"` | treated as grid when unset | — |
| `LIB_TAB_COUNTS` | bool | true | — |
| `READER_FONT_SIZE` | int sp | 16 | 10..32 |
| `READER_LINE_HEIGHT` | int % | 140 | 100..220 |
| `READER_THEME` | `light`/`sepia`/`dark` | light | — |
| `AUDIO_SPEED_MILLIS` | int (1000 = 1×) | 1000 | 500..3000 |
| `AUDIO_SKIP_BACK` | int s | 10 | 5..60 |
| `AUDIO_SKIP_FWD` | int s | 30 | 10..120 |
| `AUDIO_FADE_OUT` | bool | true | — |
| `AUDIO_PLAY_NEXT` | bool | false | — |
| `PODCAST_SPEED_MILLIS` | int (1000 = 1×) | 1000 | 500..3000 |

Writers are synchronous write-through (NSUserDefaults); consumers elsewhere should
re-read `AppPrefs` (there is no preference Flow in commonMain).

## Rows refused (and why)

Android-only effect → left out, no stub row, and a contract line here:

- Sources row (FTP / SMB / WebDAV / Torrent / Calibre), all `SyncSourceRow`s,
  torrent background/battery, watch-library-folder/SAF, DB export/import,
  clear cache — WorkManager / SAF / Android file layout; no iOS implementation.
- Immersion handoff (precision, toast, scan) — no iOS handoff code in shared scope.
- Language picker — no `PrefKeys` language key; iOS locale switching not wired.
- Dark mode / dynamic colors / true black — no `PrefKeys` entries; `ShelfTheme` is
  locked to the HUD dark scheme.
- About/version — not a pref and not requested.
- `ONLINE_COVER_LOOKUP` — **stays off and has no row** (iOS covers are local-only).
  It is also not shown as a disabled stub.

## Known deviations (deliberate)

- Strings are hard-coded Android **English** default resources (`:shared` does not
  apply the Compose-resources Gradle plugin, so no string resources/localization).
- The podcast root header omits Android's small brand bitmap (`bookrio_mark`);
  `:shared` has no Compose resources.
- Feed/episode artwork always uses the Android fallback tile: iOS has no remote
  image loader and online cover lookup stays off (RSS artwork URLs are not fetched).
- Android's AssistChips are rendered as HUD pills with the same row slots
  (`Accent`-tinted when selected, `Panel` otherwise).
- Numeric-entry dialogs for font size / skip values were not ported; the sliders
  (same ranges/steps) are the control and a pill shows the current value.
- Feed meta shows language + Explicit only: the shared RSS parser does not parse
  `categories_json`, so there is no category in the Android header meta.
- Dates use the UTC civil date (no `java.time` on Kotlin/Native).

## Verification

`kotlinc-native` 2.1.20 cross-compile (`-target ios_simulator_arm64 -p library`,
Compose compiler plugin) of these exact files against the cached Compose
Multiplatform 1.8.2 uikitSimArm64 klibs + the real `PodcastDaos`/`PodcastEntities`/
`PodcastRepository`/`PodcastPlayback` sources, with only `Http`/`AppPrefs`/
`ShelfDatabase`/theme stubs: **EXIT=0, no warnings in the new files**. Gradle was
not run (task constraint).
