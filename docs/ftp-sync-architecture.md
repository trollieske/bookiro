# FTP / FTPS / SFTP sync — architecture, root cause and production design

Branch: `fix/production-ftp-sync-performance`
Modules: `:ftp`, `:data`, `:app`

This document describes (1) why the current FTP flow loses state when the user
navigates away, and (2) the production architecture that replaces it. It is
written before the implementation and updated with measured results at the end
(see [§9 Benchmarks](#9-benchmarks-and-measured-results)).

---

## 1. Current behaviour and root cause

### 1.1 What the user sees

```
enter IP / user / password → test connection OK → browse folders
→ select many audiobooks → Sync → download starts
→ go back to the library
→ the FTP screen is empty / disconnected when returning
→ server info has to be entered again
→ no trustworthy way to tell whether the transfer continues, stopped or failed
```

### 1.2 Actual data flow (before)

```
FtpScreen (Compose)
  │  collectAsState()
  ▼
FtpViewModel  ────────────── viewModelScope  ← lifetime = NavBackStackEntry
  │  engine = FtpClientEngine()              (ONE socket, mutable fields)
  │  store  = FtpServerStore()               (EncryptedSharedPreferences JSON)
  │
  ├─ connect()/listDirectory()               (UI-owned short-lived connection)
  ├─ syncCurrentFolderNow() / syncServerNow()
  │     performParallelSync()                ← runs ENTIRE download inside
  │        coroutineScope { bookFiles.map { launch { semaphore.withPermit {
  │            FtpClientEngine() per file, download, import } } } }
  │
  └─ onCleared() { viewModelScope.launch { engine.disconnect() } }

FtpSyncWorker  ← scheduled ONLY as a 4-hour periodic worker.
                 It is never enqueued by the user's "Sync" action.
                 It reads FtpServerStore and downloads serially.
```

### 1.3 Where state, credentials and the job context are lost

| # | Location | Problem |
|---|----------|---------|
| R1 | `FtpViewModel.performParallelSync` | The entire transfer runs in `viewModelScope`. When the FTP destination leaves the back stack, the ViewModel is cleared and the scope is cancelled → **the download dies**. |
| R2 | `FtpViewModel.onCleared` | Explicitly closes the shared `FtpClientEngine` socket. Even if a worker existed, the screen tears the transport down. |
| R3 | `FtpViewModel` progress fields | Progress/queue/bytes/errors live in `combine(formState, …)` — a **UI-only `StateFlow`**. Nothing is persisted; recreation yields an empty screen. |
| R4 | No download-task persistence | `download_tasks` and `ftp_servers` exist in Room but **no production code writes to them**. Queue = `List<FtpEntry>` in RAM. |
| R5 | `FtpServerStore` | The *only* server persistence is an EncryptedSharedPreferences JSON blob owned by the ViewModel. `loadServer()` fills the *form*, so a cold start always shows the empty add-server form. Credentials are stored plaintext-in-dekrypted-form and are never migrated. |
| R6 | Download target | `downloadDir.listFiles()?.forEach { it.delete() }` wipes the whole per-server folder at the start of every sync. No `.part` staging, no resume, no verification. |
| R7 | Atomicity | `retrieveFile` writes straight to the final path. A crash/interrupt leaves a truncated file that later "exists with the right name"; import is done inline, so partial files can be imported. |
| R8 | `FtpSyncWorker.schedule` | Schedules a periodic worker only; `ftpSyncEnabled` defaults to `false`, so in practice the worker never transfers anything. |
| R9 | Reconnect | Each file creates a brand-new connection (`workerEngine.connect(...)`), but they are created inside the same ViewModel scope and are never resumed. |
| R10 | Restart | A process death leaves no record of what was running; on restart there is nothing to rehydrate. |

### 1.4 Root cause (one sentence)

**Transfer ownership is wrong.** The active transfer is owned by a
Compose/ViewModel-scoped coroutine that also owns the socket, while the only
durable store (`Room`) is unused — so navigation, recreation and process death
destroy the job, the queue and the status simultaneously.

The fix is **not** "keep the socket alive". A connection is a short-lived I/O
resource; the *source* and the *tasks* must be durable data.

---

## 2. Target architecture (after)

```
┌───────────────────────────── app process ─────────────────────────────┐
│ Compose UI (ftp module)                                               │
│   SourcesScreen / SourceDetailsScreen / FtpBrowserScreen / Transfers  │
│        │ observes Flow only (never owns a socket)                     │
│        ▼                                                              │
│ ViewModels (ftp module)                                               │
│   SourcesViewModel / BrowserViewModel / TransfersViewModel            │
│        │ repository calls (enqueue, pause, cancel, list)              │
│        ▼                                                              │
│ Repositories (ftp module)                                             │
│   FtpSourceRepository  ── decrypts credentials on demand              │
│   FtpTransferRepository ─ state machine + rate-limited progress       │
│        │                                                              │
│        ├──────────────► Room (data module)  ◄── SINGLE SOURCE OF TRUTH│
│        │                 ftp_servers                                 │
│        │                 download_tasks                              │
│        │                                                              │
│        └──────────────► WorkManager                                   │
│                          unique work "ftp-sync-server-<id>"          │
│                              │                                        │
│                              ▼                                        │
│                       FtpSyncWorker (CoroutineWorker, foreground)     │
│                         lanes = Semaphore(policy.concurrency)         │
│                         lane → own RemoteFileClient connection        │
│                         lane: QUEUED → RUNNING → VERIFYING →          │
│                               IMPORTING → COMPLETED                   │
│                         writes bytes back to Room (rate-limited)      │
└───────────────────────────────────────────────────────────────────────┘
```

Persistence contract:

```
Room ftp_servers      = only authoritative source metadata
Room download_tasks   = only authoritative transfer state (queue, bytes, errors)
WorkManager           = only scheduling/execution mechanism
FtpServerStore        = legacy read-only input for a one-shot migration, then dead
```

---

## 3. State machines

### 3.1 Source state (`FtpSourceStateEntity`)

```
ACTIVE ──user disables──────────────► DISABLED
ACTIVE ──auth failure───────────────► NEEDS_AUTH ──successful auth──► ACTIVE
ACTIVE ──connect/transport failure──► CONNECTION_ERROR ──success────► ACTIVE
DISABLED ──user enables─────────────► ACTIVE
```

A failing connection never deletes or resets a source. `last_error` is stored
(masked) but credentials are kept.

### 3.2 Transfer state (`DownloadStatusEntity`)

Valid transitions (all others are rejected by `TransferStateMachine`):

```
QUEUED        → RUNNING
RUNNING       → VERIFYING | RETRYING | PAUSED_BY_USER | FAILED | CANCELLED | WAITING_FOR_NETWORK
RETRYING      → QUEUED | FAILED | CANCELLED
WAITING_FOR_NETWORK → QUEUED | CANCELLED
PAUSED_BY_USER→ QUEUED | CANCELLED
VERIFYING     → IMPORTING | RETRYING | FAILED
IMPORTING     → COMPLETED | FAILED
COMPLETED     → (terminal)
FAILED        → QUEUED (retry) | CANCELLED
CANCELLED     → QUEUED (re-queue) | (terminal)
```

Legacy `PENDING`/`PAUSED` rows are still tolerated on read (mapped to
`QUEUED`/`PAUSED_BY_USER` semantics) but never written.

`isDownloading: Boolean` is not used as the truth anywhere.

---

## 4. Connection & credential model

```
User opens browser
  → repository creates short-lived connection
  → list directory
  → close in finally

Worker needs work
  → source from Room + decrypt credentials
  → one connection per transfer lane (Semaphore-bounded)
  → reuse that lane's connection while the queue has work
  → reconnect with bounded retry if the transport dies
  → close cleanly in finally
```

* Credentials are encrypted with an **Android Keystore AES-256-GCM** key
  (`FtpCredentialCipher`). Only base64 `iv‖ciphertext` is stored in Room.
* `password_encrypted` / `private_key_passphrase_encrypted` really contain
  ciphertext.
* Credentials never appear in `WorkManager` input (only `serverId`), never in
  logs, never in crash reports, never in the UI after saving, and are excluded
  from Android Auto Backup / device transfer.
* FTP is kept for compatibility but the UI shows a localized warning and
  recommends SFTP first, then FTPS.

---

## 5. Fast downloads — choices

### 5.1 Parallelism

Parallelise **different files**, never segments of one file. One resumable
stream per file.

```
slot 1 → Book A.m4b
slot 2 → Book B.m4b
slot 3 → Book C.m4b
```

`TransferPolicy`:

| Transport | default | max user |
|-----------|---------|----------|
| Wi-Fi     | 2       | 4        |
| Mobile    | 1       | 2        |
| Same file | 1       | 1        |

`Auto` = the default. User can pin 1–4. Concurrency is **reduced** (never
raised) after repeated connect failures / TCP resets / auth errors.

### 5.2 Buffers

Transfer buffer is configurable internally with candidates 64 KiB / 256 KiB /
512 KiB; **256 KiB** is the default unless the benchmark (below) shows
otherwise. Streams write directly to staging; whole audiobooks are never held
in RAM.

### 5.3 Resume / atomic writes

```
remote file
 → download to <final>.part (append, offset = validated part length)
 → persist downloadedBytes (≤ every 500 ms or ≥ every 1 MiB)
 → verify part length == remote size
 → atomic rename .part → final
 → import
 → COMPLETED
```

* FTP: `REST` offset via `FTPClient.setRestartOffset`.
* SFTP: `RemoteFile.read(offset, …)`.
* If the server cannot resume, the part file is discarded and the UI says so
  honestly ("this file will restart from the beginning").
* Incomplete files are never imported; a crash never leaves a `.part` in the
  library or a truncated file with the final name.

### 5.4 Database / UI write rate

Progress is written to Room at most every 500 ms **or** every 1 MiB of new
bytes, whichever comes first, plus an exact flush on pause / error / cancel /
completion. Currently-active bytes are held in memory for a live UI.

---

## 6. WorkManager model

* Manual sync: unique work `ftp-sync-server-<serverId>`
  (`ExistingWorkPolicy.KEEP` + `APPEND_OR_REPLACE` for new items).
* Periodic sync: separate unique periodic work `ftp-periodic-sync` that only
  *enqueues* manual work for sync-enabled sources. It can never cancel manual
  work.
* Constraints: `NetworkType.CONNECTED` (or `UNMETERED` when wifi-only),
  `requiresStorageNotLow`, `requiresCharging` when configured.
* Long transfers run as **foreground** work with an accurate, rate-limited
  notification (source, current file, `n of m`, %, speed, ETA, Pause, Cancel).
* Pressing Sync again never creates a competing worker and never deletes an
  active queue — it appends to the same Room queue.
* Restart/process death: at worker start (and app start) any `RUNNING` task
  without live work is rehydrated deterministically to `QUEUED`
  (if resumable) or `RETRYING`. A false `RUNNING` can never remain forever.

---

## 7. Navigation & UX

```
ftp                                   → Sources (FTP roots, from Room)
ftp/add                               → add/edit connection form
ftp/server/{serverId}                 → Source details
ftp/server/{serverId}/browser         → full-screen browser
transfers                             → global transfers
```

* Server list and transfer status come from Room / WorkManager flows.
* The browser's current path is *screen* state; the server id and transfer
  status survive recreation.
* Nothing sensitive or connection-like is put in navigation arguments.
* Back from the browser returns to the same source detail, never cancelling a
  transfer.

---

## 8. Test strategy

Unit (JVM):
* migration idempotency,
* credential encrypt/decrypt + never in WorkManager input,
* no duplicate source / no duplicate task per remote path,
* state machine accept/reject,
* unique work naming,
* resume offset from `.part` length,
* incomplete file is never imported,
* atomic move only after verified completion,
* rate-limited progress,
* concurrency policy defaults (Wi-Fi vs mobile),
* queue survives repository/ViewModel recreation.

Instrumentation:
* abstracted `RemoteFileClient` + fake transport exercises the end-to-end flow
  in §9 of the prompt.

Physical (EVO Seedbox): see §9.

---

## 9. Benchmarks and measured results

**Physical EVO Seedbox benchmarking could not be executed from this environment**
(no Android device attached and no network access to the seedbox). The engine is
built so the measurement is a configuration change, not a code change:

* `FtpTransferEngine` reads the lane count from `TransferPolicy`
  (`concurrencyOverride` / transport default).
* `TransferPolicy.CANDIDATE_BUFFERS` exposes 64 / 256 / 512 KiB and the buffer is
  passed per transfer, so a benchmark harness can iterate them.
* `FtpTransferRepository.observeForServer` + `FtpTransferRuntime` give per-file
  and aggregate speed without extra instrumentation.

Default shipped: **256 KiB buffer**, **2 lanes on Wi-Fi**, **1 on mobile**,
user-selectable 1–4 (clamped to 4 on Wi-Fi / 2 on mobile), always one stream per
file. These are conservative starting values chosen to avoid seedbox
rate-limiting; they are overridable per source.

The physical test matrix to run on the target device is listed in the prompt
(single 1 GB M4B, several 100–500 MB files, an EPUB, 1/2/3/4 lanes, 64/256/512 KiB
buffers, network switch, screen off, process death). The expected procedure is
unchanged; results are pending.

## 10. Test results

JVM unit tests (`:ftp:testDebugUnitTest`, `:data:testDebugUnitTest`):

* state-machine accept/reject and legacy mapping,
* concurrency policy defaults and clamping,
* resume-offset validation, path-escape prevention, atomic move,
* rate-limited progress persistence,
* work-name/input-key contract (only `serverId`),
* credential encryption at rest, blank-password edit keeps ciphertext,
* natural-key de-duplication and idempotent legacy migration,
* decrypt-failure → `NEEDS_AUTH`,
* no duplicate enqueue per remote path,
* illegal transition rejection,
* engine end-to-end: complete download → verify → move → import exactly once,
* truncated download → no import, `.part` kept,
* resume from an existing `.part`,
* queue survival across repository recreation,
* exported Room schema 8 contains the durability columns and the unique
  `(server_id, remote_path)` index.

Localization validator (`tools/validate_localization.py`) passes for all 9 locales.

## 11. Known limitations

* Physical throughput numbers against the EVO Seedbox are not measured here.
* FTPS uses an accept-all trust manager (seedboxes commonly present self-signed
  certificates). Certificate pinning is a follow-up hardening step.
* SFTP uses trust-on-first-use host-key verification; a changed host key is
  surfaced as an error and requires removing/re-adding the source to trust again.
* A dedicated in-app benchmark/diagnostics screen is not implemented; the
  policy/buffer knobs and runtime snapshot it would use are in place.
* Periodic sync uses `KEEP` and enqueues manual work; very aggressive seeds with
  many thousands of files have not been load-tested.
