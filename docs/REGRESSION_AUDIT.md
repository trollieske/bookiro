# Bookiro Android — Regression & data-safety audit (overnight-review fixes)

Scope: the `overnight-review` library/audiobook/metadata/FTP/handoff repairs, audited on
the release candidate base. Method: read the real production paths and execute the
**real** `BookImportRepository` / FTP planner / ownership code through a JVM harness
(`library/src/test/java/com/bookrio/library/testutil/JvmHarness.kt`, shadow
`android.util.Log` + `org.json`) — not copies of the logic. No production code was
changed to make a test pass.

Test evidence (all green on the audit branch):

| module | tests | failures | errors |
|---|---|---|---|
| `:library:testDebugUnitTest` | 73 | 0 | 0 |
| `:ftp:testDebugUnitTest` | 34 | 0 | 0 |
| `:player:testDebugUnitTest` | 54 | 0 | 0 |

This is **JVM evidence only**. Android-runtime behaviours (process death, rotation,
offline, upgrade, lockscreen/Bluetooth/Auto) are listed as unverified at the end.

---

## 1. SAF duplicate prevention and repair — `SAFE-WITH-GATES`

Executed against the real `BookImportRepository.deduplicateLibrary()`:

- The same SAF `file_uri` (twice), the same local `file_path`, and the same book in
  several formats collapse to one row. EPUB wins over PDF/MOBI/etc.
- Repair is **idempotent**: the second run changes nothing.
- Duplicates are **soft-deleted** (`is_deleted = 1`), never hard-deleted by the repair.
- Progress of a dropped copy is moved to the survivor **only when the survivor has no
  progress**; if both copies have progress, the dropped position is not migrated (the
  survivor wins). This avoids mixing two positions, but bookmarks/highlights of the
  dropped copy are not re-pointed — they cascade away with the soft-delete.

**Gates / risks (must be surfaced, not hidden):**

1. **Two genuinely different EPUB files with identical title + author are collapsed
   silently** (`two different EPUB files with identical title and author are collapsed
   silently`). Different editions/translations can share title+author. Collapse keys on
   file identity first, then title+author, but there is **no edition/publisher/ISBN
   disambiguation**. Mitigation is the soft-delete + the start-up repair log, but there
   is no user-visible confirmation.
2. Same title + author in *different formats* is intentionally treated as the same work
   (one survivor). That is a product decision, not a bug, but it is destructive to the
   user's per-format state.
3. The repair runs on **every app start** (background warm-up thread). It is idempotent,
   so repeated runs are cheap, but it means a wrong collapse is applied automatically.

## 2. Audiobook split / merge / track repair — `SAFE-WITH-GATES`

Executed against the real `consolidateFragmentedAudiobooks()`,
`repairDuplicateAudioTracks()` and `splitMergedAudiobooks()`:

- Scoped-storage audiobooks with no local `filePath` are **never** consolidated (this is
  the fix for the previous "every scoped audiobook collapses into one" bug).
- Local fragments merge into one book in **natural track order**, and merging once does
  not re-add tracks on the next run.
- Duplicate tracks are deduped, **renumbered** and the totals recomputed.
- Single-track rows, non-audiobooks, and a real multi-file audiobook with proper chapter
  labels are left untouched.
- A wrongly-merged book whose parts have unrelated standalone titles is split back out,
  **idempotently** (reusing an existing row per file so re-runs cannot duplicate).

**Gates / risks:**

1. **`consolidateFragmentedAudiobooks` hard-deletes duplicate fragments** (the audit test
   documents this). Unlike the library dedup, there is no soft-delete/undo for the
   fragment rows. Track/progress is carried to the survivor, but this is irreversible.
2. **KNOWN RISK — chapter-name file titles in one folder are wrongly split**
   (`KNOWN RISK - chapter-name file titles in one folder are wrongly split`). If files are
   named like `Chapter 01.mp3` without per-file title metadata, the heuristic can treat
   one book as several. This is conservative-split territory and needs a user-visible
   gate or a stronger "same folder + monotonic numbering" rule.
3. Chapter timelines are only built from real embedded markers or real per-file
   boundaries; the tests confirm no invented timed chapters, but a chapterless single
   file intentionally yields one chapter (not an error).

## 3. Opt-in metadata & cover refresh — `SAFE-WITH-GATES`

- The refresh path is reachable only through Settings → "Refresh metadata & covers" and
  requires the existing online-cover toggle (`F1`, default OFF). It is not automatic.
- "Suspect only" vs "all" selection, cancellation, retry, and network-off are handled by
  the worker/UI; the pure result picker is unit-tested.
- A result with no matches produces **no** metadata update; an exact title match is
  preferred.

**Gates / risks:**

1. **A completely mismatched title can still win when it carries more cover/ISBN points**
   (`a completely mismatched title still wins when it has cover and isbn points`).
   `CoverRepository.refreshOnline()` intentionally lets the online cover override an
   embedded one, so a bad match **can replace a good embedded cover without a separate
   confirmation**. The user must already have opted in, but there is no per-book preview.
2. "Unknown author" detection treats blank/placeholder values as unknown; genuinely
   different authors can still match on title alone.

## 4. FTP sync large folder — `SAFE`

Executed against the real FTP planner/fake remote:

- A large folder scan reports **live per-folder progress** (the "Scanning remote folder…
  N files" phase) and queues only book files.
- Re-planning the same folder **never queues a duplicate**.
- Cancelling the scan propagates and still closes the connection (no leaked client).
- The preparing state starts at zero files, tracks the live count, ignores progress for a
  server that is not preparing, and is dropped when cleared.

**Unverified on device:** cancellation under real network latency, restart mid-scan with
a real server, and "no duplicate import after app restart" with the real Room DB.

## 5. Audiobook/podcast hand-off — `SAFE` (invariants) / device metadata unverified

Executed against the real `ActivePlaybackState` / `PodcastPlaybackState` /
`NowPlayingOwnership`:

- Rapid A→B→A leaves exactly **one** published snapshot.
- A paused owner can be taken over by an explicit play; a **playing** owner cannot be
  stolen (the other engine must be stopped by the arbiter first).
- A paused audiobook's progress ticks cannot repaint over a playing podcast.
- Podcast artwork keys are episode-scoped even with no artwork (`episode:<id>:`), so a
  stale audiobook cover cannot be attributed to a podcast.

**Unverified on device:** lockscreen/notification/Bluetooth/Android Auto metadata after a
rapid A→B→A switch (the Media3 session layer is not covered by these JVM tests).

## 6. Cold start / process death / rotation / offline / upgrade — `UNVERIFIED`

Not exercised here. The start-up repair runs on a background thread and is idempotent,
but the following need a real device pass:

- cold start with a clean and with a populated library;
- process death during the reader / player and resume;
- rotation on reader, player, sheets;
- fully offline operation (metadata, cover, chapter lookup, podcast sync);
- upgrade from the latest published test build (`bookiro-overnight-review-54947a3`)
  with real user data, including the position/bookmark migration.

## 7. Additional code-level risks found while auditing

- `ShelfApplication`'s DB fallback calls `deleteDatabase("shelf.db")` if the database
  cannot be opened — a last-resort **destructive** path. It is wrapped in `runCatching`,
  but a transient open failure would wipe data. Should be gated/loud.
- The start-up repair is fire-and-forget: if it throws, the app continues with partially
  repaired data and no user-visible signal.

## 8. Verdict per area

| Area | Verdict |
|---|---|
| SAF duplicate prevention/repair | SAFE-WITH-GATES (silent edition collapse, no per-copy bookmark migration) |
| Audiobook split/merge/track repair | SAFE-WITH-GATES (hard-delete, chapter-name wrong-split risk) |
| Metadata/cover refresh | SAFE-WITH-GATES (mismatched cover can override embedded; opt-in only) |
| FTP large-folder sync | SAFE (JVM); device restart/cancel unverified |
| Audio/podcast hand-off invariants | SAFE (JVM); device metadata unverified |
| Cold start / process death / rotation / offline / upgrade | UNVERIFIED — device pass required |

No production fix was applied by this audit; each gate above needs either an owner
decision or a device-verified follow-up before a GO.