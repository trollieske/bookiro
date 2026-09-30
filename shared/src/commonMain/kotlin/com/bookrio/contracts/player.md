# Player chrome (iOS parity, agent 2) — integration contract

Everything here is **new files only** (nothing outside the allowlist was edited):

- `shared/src/commonMain/kotlin/com/bookrio/player/ui/BookrioPlayerScreen.kt`
- `shared/src/commonMain/kotlin/com/bookrio/player/ui/BookrioPlayerSheets.kt`
- `shared/src/commonMain/kotlin/com/bookrio/player/ui/BookrioPlayerFormatting.kt`

## Exact entry signature

```kotlin
package com.bookrio.player.ui

@Composable fun BookrioPlayerScreen(bookId: Long, onBack: () -> Unit)
```

The screen wraps itself in `ShelfTheme` and opens `appDatabase()` on its own, so
the host just needs to provide the book id + a back action.

## Host requirements (App.kt / nav host — not edited here)

1. Call `BookrioPlayerScreen(bookId, onBack)` for audiobook books.
2. **Do not start playback in the host before/after navigating to it.** The screen
   itself loads the book, tracks and resume position from the shared Room DB and
   starts `AudiobookPlayback.play(db, scope, book, tracks, AudiobookPlayback.resumePosition(db, bookId))`.
   (The host must also keep its existing single-owner rule: nothing else may build
   an AVPlayer/engine — this screen only ever calls `AudioPlayers.shared`.)
3. `onBack` only leaves the screen; playback keeps running through the shared owner
   and continues to be visible in the shell's now-playing bar.

## What matches the Android player

Cover (`rememberLocalCover` + HUD fallback with title/author), chapter counter
(`CHAPTER x OF y` / `AUDIOBOOK` / `AUDIO PLAYER`), chapter title, author, whole-book
scratch scrub with live counters, `IN CHAPTER` scrub (`Chapter x of y`, in-chapter
elapsed/left, chapter title), transport row (skip back / prev chapter / play-pause /
next chapter / skip forward — 5/10/30 s icons from `PrefKeys.AUDIO_SKIP_BACK`,
`PrefKeys.AUDIO_SKIP_FWD`), `−5 min` / `+5 min` quick row, speed selector
(0.5×–3.0×, `AudioPlayers.shared.setSpeed` + persisted `PrefKeys.AUDIO_SPEED_MILLIS`
as int millis), UI-layer sleep timer (countdown chip in the header that pauses the
audiobook via `AudiobookPlayback.pause()` at zero; sheet with custom minutes + the
5/10/15/30/45/60/90 presets), chapter list sheet
(`AudiobookPlayback.chapters` + `currentChapterIndex` +
`AudiobookPlayback.seekToChapter(db, bookId, chapter, tracks)`).

Times use a local helper (`mm:ss` / `h:mm:ss`); no `String.format` (JVM-only).

## Missing owner APIs — written here, owner NOT edited (STOP lines)

The frozen plan pins the whole-book scrub to `AudioPlayerState.positionMs /
durationMs`. That state is **AVPlayer-item relative**, so a true *global* scrub for
a book spread over several track files needs a global mapping the shared owner does
not expose. Exact signatures needed (in `com.bookrio.shared.player.AudiobookPlayback`,
`commonMain`) — the corresponding cross-file seek UI is therefore left as
per-item/jump-only in this slice:

```kotlin
fun seekToGlobal(db: ShelfDatabase, bookId: Long, positionMs: Long)

/** Book-global position: cumulative track durations before the active track + in-track offset. */
val globalPositionMs: StateFlow<Long>
```

Consequences with today's API (documented, deliberate):

- Whole-book line and counters are exact for single-file audiobooks (m4b/mp3, the
  common case). For multi-track books they cover the current item; dragging them
  seeks only within the item (`AudioPlayers.shared.seekTo`).
- Chapter jumps (`seekToChapter`) still switch files correctly, also from the
  chapter list.
- In-chapter scrub is disabled (slider `enabled = false`) when the current chapter
  declares a `mediaUri` that is a different track file than the one loaded.

## Notes

- Playback scope: `AudiobookPlayback.play` receives a process-wide
  `CoroutineScope(SupervisorJob() + platformIoDispatcher)` (private to the screen
  file), not a composition scope — popping the screen must not cancel the track
  watcher / progress ticker. The host scope is never used for this.
- Sleep timer is UI-layer (screen state, not persisted); it counts down while the
  screen is composed and pauses at zero.
- `AudioPlayerState.speed` is the displayed speed value; the persisted
  `AUDIO_SPEED_MILLIS` (default 1000) is re-applied right after `play(...)`.
- No `ONLINE_COVER_LOOKUP`; covers are local files only.