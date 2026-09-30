# Library chrome contract — Agent 1 (iOS parity)

Status: **no blocking contract requests.** Everything the shared library screen
needs already exists (`appDatabase()`, `rememberLocalCover()`, `AppPrefs` +
`PrefKeys`, `BookVisual`/`BookFormat`, `LibrarySortMode`/`SortDirection`,
`ShelfTheme`/`OmarchyColors`/`ShelfTypography`).

## Entry composable (only public screen entry, module `:shared`)

```kotlin
package com.bookrio.library

enum class BookrioLibraryMode { BOOKS, AUDIOBOOKS }

@Composable
fun BookrioLibraryScreen(
    mode: BookrioLibraryMode,
    onBookClick: (Long) -> Unit,          // Books tab: open/play book
    onAudiobookClick: (Long) -> Unit,     // Audiobooks tab: play audiobook
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onBookLongClick: (Long) -> Unit = {},          // long-press → book details
    onNavVisibilityChange: (Boolean) -> Unit = {}, // scroll: false=hide shell nav
)
```

The screen dispatches clicks by `mode`; long-press is wired on grid cards and list
rows. The shell only needs to host it inside a `Box`/`Column` that gives it the
full remaining height (it fills the size it is given).

## Notes for the integrator (not blockers)

1. **PrefKeys vs Android DataStore keys.** iOS reads/writes `PrefKeys.LIBRARY_VIEW`,
   `PrefKeys.{BOOKS,AUDIO}_SORT_MODE`, `PrefKeys.{BOOKS,AUDIO}_SORT_DIR`,
   `PrefKeys.LIB_TAB_COUNTS`. The iOS values are `NSUserDefaults`-local, so the
   Android DataStore key strings (`sort_mode_books`, …) never collide; but the
    names are not identical, so a later cross-platform settings sync would need a
   mapping decision (`PrefKeys.kt` is outside this agent's allowlist).
2. **Bookrio mark.** `designsystem` only ships `bookrio_mark.png` in `androidMain`.
   The common screen draws the lime "B" mark in Compose instead. If the real PNG
   mark is required on iOS, `:designsystem` needs a common/iOS asset.
3. **Sort/view persistence is synchronous** (`AppPrefs`) — no ViewModel needed;
   the screen recomputes its state from Room flows + prefs on every change.