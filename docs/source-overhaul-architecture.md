# Production source overhaul — architecture, current state and target design

Branch: `feat/production-source-overhaul`
Modules touched: `:app`, `:core`, `:data`, `:ftp`, `:smb`, `:webdav`, `:calibre`, `:torrent`, `:library`

This document was written **before** the implementation, as required by the
overhaul brief. It is updated as modules land. It is intentionally honest about
what is finished and what is not (see §10).

---

## 1. Non-negotiable product rules

A visible button / source card is never a dead end. If something is not
implemented, tested and usable, it is either implemented properly, hidden, or
clearly marked experimental with a working flow. No toast-only placeholders.

All remote file sources (FTP/FTPS/SFTP, SMB, WebDAV, Calibre) obey one
philosophy:

```
source configuration = persistent Room data
transfer queue/status/errors = persistent Room data
long-running I/O = WorkManager / foreground work
UI = observes data, never owns a transfer or a socket
credentials = Android Keystore-encrypted at rest
```

Navigation, ViewModel recreation, rotation, backgrounding and process death must
never make a source or an active transfer disappear.

Security baseline: no secrets in logs, Room, JSON, preferences, backups or
`WorkManager` inputData; no TLS/certificate bypass in release; no bypass of
tracker policy, passkeys, ratio rules or whitelists.

---

## 2. Current state per source

### 2.1 FTP / FTPS / SFTP — the reference implementation

The `:ftp` module was overhauled on `fix/production-ftp-sync-performance` and is
the durable architecture the other sources must converge on.

* Durable sources: `ftp_servers` (Room) via `FtpServerRepository`.
* Durable transfers: `download_tasks` (Room) via `FtpTransferRepository`.
* Execution: `FtpSyncWorker` (CoroutineWorker, foreground), unique work
  `ftp-sync-server-<id>`; periodic `ftp-periodic-sync` only enqueues manual work.
* Credentials: `FtpCredentialCipher` (Keystore AES-256-GCM), only `iv‖ciphertext`
  in Room, decrypted per connection, excluded from backups.
* Atomic downloads: `<final>.part` → validate size → atomic rename → import
  off-lane → `COMPLETED`. Progress write is rate-limited (≤500 ms / ≥1 MiB).
* UI: `Sources → Source details → full-screen browser → global Transfers`.

Files: `ftp/data/Ftp{Source,Transfer}Repository.kt`, `ftp/client/*`,
`ftp/transfer/FtpTransferEngine.kt`, `ftp/worker/FtpSyncWorker.kt`,
`ftp/ui/*`, plus `data/local/entity/FtpEntities.kt`, `data/local/dao/FtpDaos.kt`.

### 2.2 SMB — real client, but not a durable source

`SmbClientEngine.kt` uses **jcifs-ng** (`jcifs.*`) with `PropertyConfiguration`.

* Version negotiation: `AUTO` maps to min `SMB202` / max `SMB311`, so SMB2/3 is
  the direction, but the string literal `SMB1` is accepted for min/max.
* Domain/workgroup is passed to `NtlmPasswordAuthenticator`, if provided.
* `listDirectory` returns `SmbEntry`; `downloadFile` streams to a `File`.
* **Root problem:** the `SmbViewModel` owns both the socket and the transfer in
  `viewModelScope` (`downloadAndImportSelected` / `downloadAndImportCurrentFolder`).
  `onCleared()` disconnects. Leaving the screen kills the transfer. Exactly the
  FTP root cause (R1–R3 in `ftp-sync-architecture.md`), unfixed for SMB.
* `downloadFile` writes straight to the **final** path — no `.part` staging, no
  verification, no atomic move. A crash leaves a truncated file that is then
  imported.
* Cancellation/resume: none. `SmbSyncWorker` runs serially and re-downloads
  anything whose length differs.
* SMB "save server" uses `SmbServerStore` — an `EncryptedSharedPreferences` JSON
  blob with a **plaintext `SharedPreferences` fallback** if Keystore init fails.
  Room tables `smb_servers` already exist but are unused for storage.
* `SmbViewModel.downloadAndImportSelected` inserts `download_tasks` rows with
  `server_id = NULL`, bypassing the FTP FK and the `(server_id, remote_path)`
  unique index (SQLite treats NULLs as distinct → no dedup, no ownership).
* Errors are swallowed: connection and listing return `emptyList()` / `false`,
  so the UI cannot distinguish bad credentials from host-not-found from
  share-not-found.

Files: `smb/client/SmbClientEngine.kt`, `smb/data/SmbServerStore.kt`,
`smb/viewmodel/SmbViewModel.kt`, `smb/worker/SmbSyncWorker.kt`, `smb/ui/SmbScreen.kt`.

### 2.3 WebDAV — real client, but not durable and not safe

`WebdavClientEngine.kt` uses **OkHttp**.

* `connect()` does a `PROPFIND Depth: 0`; success includes `404`.
* `listDirectory()` does `PROPFIND Depth: 1` and parses with a namespace-unaware
  `XmlPullParser` (`isNamespaceAware = false`), matching `name.substringAfterLast(':')`.
  This works for `d:`/`D:`/`lp1:` prefixes but does not verify that the response
  is in the `DAV:` namespace.
* Collection detection: `<collection>` sets `FOLDER`. A resource with no
  `<resourcetype>` is classified `FILE`, and content length/etag are read.
* **TLS bypass is user-exposed** (`trustAllCertificates`) and implemented with an
  all-trusting `X509TrustManager` + `HostnameVerifier { _, _ -> true }`.
* `OkHttpClient.Builder().followRedirects(true)` **without** an interceptor that
  strips `Authorization` on cross-origin redirects → potential credential leak.
  (OkHttp's default retry/follow-up does forward most headers.)
* `downloadFile` writes straight to the final path, full re-download only, no
  `Range`/`206` handling.
* Same ViewModel-owned-transfer problem as SMB: `WebdavViewModel` owns the
  socket/transfer via `viewModelScope`; `WebdavSyncWorker` is serial and rebuilds
  a broken URL (`baseUrl + "/" + path + username` — a bug) when it runs.
* `WebdavServerStore` is `EncryptedSharedPreferences` with a plaintext fallback.
* Errors swallowed and returned as empty lists.

Files: `webdav/client/WebdavClientEngine.kt`, `webdav/data/WebdavServerStore.kt`,
`webdav/viewmodel/WebdavViewModel.kt`, `webdav/worker/WebdavSyncWorker.kt`,
`webdav/ui/WebdavScreen.kt`.

### 2.4 Calibre Content Server — not implemented

There is no Calibre module. What exists:

* `core/net/LanSourceDiscovery.kt` contains a partial `CalibreContentServerClient`
  that does an unauthenticated-ish `GET /opds` and greps the body for `OPDS`.
  It is only reachable from the LAN scan section and is never used for browsing
  or download.
* The Sources overview has a `Calibre` card whose only action is a Toast
  (`sources_calibre_toast`). This is a dead button.

Files: `core/net/LanSourceDiscovery.kt`, `app/MainActivity.kt` (`SourcesOverviewScreen`).

### 2.5 Generic OPDS catalog / Known OPDS catalogs — must be removed

What exists:

* `SourcesOverviewScreen`: a `sources_opds_title` / `sources_opds_sub` card whose
  action is a Toast — the generic OPDS dead button the brief asks to delete.
* `WellKnownCatalogsSection` (MainActivity) with Standard Ebooks, Feedbooks,
  Project Gutenberg and LibriVox, all with Toast-only actions.
* `LanSourceDiscovery.WELL_KNOWN_OPDS_CATALOGS` constant (unused candidates).
* `ImportSourceEntity.OPDS_CATALOG` enum value (keep for read-compat).
* Strings `sources_opds_*`, `wkc_*` in `app/src/main/res/values*/strings.xml`.

Calibre Content Server may use OPDS/HTTP internally; that is a private technical
detail and must not be shown as a user-facing generic OPDS feature.

### 2.6 Torrent — functional, but spoofs a client and disables TLS validation

`TorrentEngine.kt` uses **libtorrent4j**.

* **Client identity spoofing:** `user_agent = "qBittorrent/4.6.3"`,
  `peer_fingerprint = "-qB4630-"`, `handshake_client_version = "qBittorrent/4.6.3"`.
  This is a tracker-policy violation and makes diagnostics misleading.
* **TLS bypass:** `validate_https_trackers = false` globally, described in a
  comment as "required by private HTTPS trackers".
* **Anonymous mode off**, `announce_to_all_trackers/tiers = true`.
* Global DHT/LSD/UPnP/NAT-PMP enabled (`enable_dht/lsd/upnp/natpmp = true`).
* Private-torrent handling: `applyPrivateTorrentFlags` disables DHT/PEX/LSD for
  the torrent and strips a hardcoded list of *public fallback tracker hostnames*.
  It runs from `AddTorrentAlert`/`MetadataReceivedAlert` **if** the native flag
  constants are reflectively found. `appendFallbackTrackersIfNeeded` **appends
  public trackers to magnets that have none**, which directly leaks private
  infohashes before the private flag is known.
* Tracker diagnostics exist only in Logcat; the UI shows a coarse
  `trackerStatus: String`. Announce URLs are masked only for `passkey=`; the
  full URL (which may contain other auth tokens) is otherwise logged.
* Seeding runs forever after completion ("Keep seeding — do NOT remove handle")
  with no user-facing policy and no foreground-service guarantee.
* `TorrentDownloadWorker.kt` exists but the engine keeps a process-wide
  singleton session; lifecycle/foreground policy is unclear.

Files: `torrent/engine/TorrentEngine.kt`, `torrent/worker/TorrentDownloadWorker.kt`,
`torrent/viewmodel/TorrentViewModel.kt`, `torrent/ui/TorrentScreen.kt`.

---

## 3. Dead buttons and unfinished flows (verified)

| # | Location | Symptom |
|---|----------|---------|
| D1 | `SourcesOverviewScreen` Calibre card | Toast only, no browse/download |
| D2 | `SourcesOverviewScreen` OPDS card | Toast only |
| D3 | `WellKnownCatalogsSection` (4 catalogs) | Toast only |
| D4 | `LanDiscoverySection` result row | Toast preview only; never loads the source into the add form |
| D5 | `LanSourceDiscovery.WELL_KNOWN_OPDS_CATALOGS` | Unused constant |
| D6 | `WebdavSyncWorker` URL construction | Broken URL; never actually syncs correctly |
| D7 | SMB/WebDAV transfer status | Not in the global Transfers screen; disappears on navigation |
| D8 | `SmbScreen`/`WebdavScreen` back navigation | Kills in-flight transfer (`onCleared` disconnect) |
| D9 | Torrent tracker status | Logcat only; UI has no per-tracker diagnosis, no reannounce |

---

## 4. Before / after architecture (ASCII)

### Before

```
SourcesOverviewScreen
 ├─ Calibre card ──► Toast (dead)
 ├─ OPDS card ─────► Toast (dead)
 ├─ WellKnownCatalogs ─► Toast (dead)
 ├─ LAN scan ──────► Toast preview (dead end)
 ├─ SMB  ─► SmbScreen ─► SmbViewModel (owns socket + transfer in viewModelScope)
 ├─ WebDAV ─► WebdavScreen ─► WebdavViewModel (owns socket + transfer)
 └─ FTP  ─► durable Room + WorkManager (reference)

SmbServerStore / WebdavServerStore = EncryptedSharedPreferences JSON (+plaintext fallback)
download_tasks used ad-hoc with server_id = NULL (no dedup, no owner)

TorrentEngine = singleton native session
  user_agent = qBittorrent/4.6.3, validate_https_trackers = false,
  appends public trackers to magnets, logs full tracker URLs
```

### After (target)

```
                       ┌───────────────────────────── app process ─────────────────────────────┐
                       │ Compose UI (app + per-source modules)                                │
                       │   Sources / Source details / Browser / global Transfers              │
                       │        │ observes Flow only (never owns a socket or transfer)          │
                       │        ▼                                                             │
                       │ ViewModels                                                           │
                       │        │ repository calls (save, enqueue, pause, cancel, list)       │
                       │        ▼                                                             │
                       │ Repositories (per protocol, same contract)                           │
                       │   SourceRepository  ── Keystore-decrypt credentials on demand        │
                       │   TransferRepository ── state machine + rate-limited progress        │
                       │        │                                                             │
                       │        ├──► Room (single source of truth)                            │
                       │        │      sources (per kind) + transfer_tasks (with kind/owner)  │
                       │        │                                                             │
                       │        └──► WorkManager (unique work per source, foreground)         │
                       │                 │                                                    │
                       │                 ▼                                                    │
                       │            RemoteSyncWorker (kind-dispatched)                        │
                       │              lane = own RemoteFileClient connection                  │
                       │              staging .part → verify → atomic move → import           │
                       └──────────────────────────────────────────────────────────────────────┘
```

The transfer queue and state machine are shared. Credentials and per-protocol
config live behind per-kind repositories. No protocol is forced into a
god-object; they share the contract, not the schema.

---

## 5. Consistent model for sources, credentials, sync and transfers

### Source (common shape)

```
id, kind (FTP|FTPS|SFTP|SMB|WEBDAV|CALIBRE), displayName, host/URL (safe),
port, basePath, encrypted credential payload, isEnabled, lastConnectedAt,
lastSyncAt, lastErrorCode (sanitized), lastErrorAt, sync settings, createdAt, updatedAt
```

FTP already has this in `ftp_servers`. SMB/WebDAV Room tables exist
(`smb_servers`, `webdav_servers`) and must become authoritative instead of the
preferences blobs. Calibre gets `calibre_servers`.

### Transfer states (shared)

```
QUEUED RUNNING PAUSED_BY_USER WAITING_FOR_NETWORK RETRYING
VERIFYING IMPORTING COMPLETED FAILED CANCELLED
```

`DownloadStatusEntity` already defines these. Legacy `PENDING`/`PAUSED` are
read-only aliases.

### Transfer task (common shape)

`download_tasks` already carries `server_id`, `remote_path`, `remote_name`,
`size_bytes`, `remote_mtime`, `staging_path`, `local_path`, `downloaded_bytes`,
`status`, `retry_count`, `error_kind/message`, `priority`, `auto_import`,
`created/started/completed_at`, `bytes_per_sec`, `next_attempt_at`.

To host non-FTP sources, the queue gains a `source_kind` column and a stable
`source_ref` string used for dedup when `server_id` is null (SQLite unique
indices treat NULLs as distinct). A new unique index
`(source_kind, source_ref, remote_path)` is added; the existing
`(server_id, remote_path)` index is preserved for FTP.

### Credentials

`FtpCredentialCipher` is generalized to `SourceCredentialCipher` (Keystore
AES-256-GCM). SMB, WebDAV and Calibre store only `iv‖ciphertext` in Room. The
`EncryptedSharedPreferences` fallbacks are removed. Credentials are never
`WorkManager` input.

### Resume / atomic files (all protocols)

```
remote file → <final>.part → download with persistent downloadedBytes
→ resume if protocol supports range/offset (FTP REST, SFTP offset, WebDAV Range)
→ verify size → atomic move → import off-lane → COMPLETED
```

If resume is unsupported, the part file is discarded and the UI says the file
starts over. Incomplete files are never imported.

### WorkManager

Unique work per source. Manual sync appends to the Room queue and never cancels
or duplicates it. Periodic sync only enqueues manual work. At worker start and
app start, any `RUNNING` row without live work is rehydrated to `QUEUED`. Network
failure becomes `RETRYING`/`WAITING_FOR_NETWORK`, never data loss.

---

## 6. Integration test fixtures

Reproducible, local, and independent of the user's own servers. All fixtures
live under `fixtures/` and are started with `docker compose`. See
`docs/source-test-fixtures.md` for exact commands and credentials.

```
fixtures/
  smb/      Samba container (SMB2/SMB3, test user, nested dirs, EPUB/PDF/M4B/MP3)
  webdav/   WebDAV container (auth, PROPFIND, Range, UTF-8/space/apostrophe names)
  calibre/  Calibre Content Server with a minimal library (EPUB + M4B)
  torrent/  local open tracker + private-flag tracker + auth-fail + TLS-fail
```

Fixture credentials are non-sensitive and committed; they are never referenced
by production code.

Tests:

* JVM unit tests (no network): path normalization, state machine, dedup,
  masking, private-flag policy, XML parsing, feed parsing.
* Instrumentation/host tests against the fixtures (opt-in, tagged) verify
  authenticated listing, download, resume, import and error mapping.
* Torrent policy tests run against the tracker fixtures where the JNI/ABI is
  available.

---

## 7. Private torrent behaviour (policy-compatible)

No bypass, no spoofing, no credential harvesting. What the app does:

1. **Honest identity:** `user_agent = "Vierel/<version> libtorrent/<version>"`,
   neutral peer fingerprint/handshake version. No qBittorrent imitation.
2. **TLS on:** `validate_https_trackers = true`. A bad/private-CA tracker shows
   a sanitized "secure tracker connection could not be verified" diagnosis.
   No "ignore certificate errors" button.
3. **Per-torrent privacy:** when metainfo has `private=1`, the torrent gets
   `disable_dht` + `disable_lsd` + `disable_pex` **per torrent**; public torrents
   keep DHT/LSD. No fallback trackers are ever appended to a private torrent.
4. **Input:** `.torrent` files via SAF (primary for private trackers, since the
   announce URL/passkey travels in the metainfo) and magnets.
5. **Diagnostics:** a `Connection details` view shows per-tracker status, a
   **masked** URL, error class (auth/policy/TLS/timeout/DNS/announce), retry
   time, privacy flags and peer counts. `Reannounce now` is rate-limited.
6. **Seeding:** explicit user policy (`stop when downloaded`, `seed until
   stopped`, `seed while app active`) with Wi-Fi/charging/battery constraints.

The app never authenticates to a tracker website, never scrapes cookies, never
requests or stores a passkey itself, and never tries to defeat a client
whitelist.

---

## 8. UX

All sources share the card structure, status colours, actions and empty states.
FTP, SMB, WebDAV and Calibre each have a full-screen browser with breadcrumbs
(Calibre uses library/author/series/tag navigation instead of fake paths),
multi-select, pull-to-refresh, loading/empty/error/retry states and content
descriptions. The global Transfers screen observes Room only and works even if a
source browser is never opened again.

Design follows `OmarchyColors` / `ShelfTypography`: dark, calm, lime as
accent/status only. No wood-shelf/Apple-Books styling on source surfaces.

---

## 9. Migration and cleanup

* Old OPDS rows in `books.import_source` remain readable (`OPDS_CATALOG` enum
  kept) but cannot be created.
* `ftp_servers`, `download_tasks` migrate forward additively.
* SMB/WebDAV preference blobs are migrated read-only into their Room tables,
  then ignored.
* Dead routes, callbacks, stubs, empty toasts, unused strings and misleading
  README claims are removed. Localization validator is run for every locale.

---

## 10. Status (updated as work lands)

See `CHANGELOG.md` and the commit history on this branch for the authoritative
list. This section is a summary and may lag slightly.

| Area | Status |
|------|--------|
| Architecture / root-cause analysis | done |
| Generic OPDS + known catalogs removed | done |
| Calibre Content Server module | done (JVM-tested; live-server smoke pending) |
| Torrent identity/TLS/private-flag diagnostics | done (JVM-tested; native smoke pending) |
| SMB durable source + WorkManager transfer | done (not integration-tested here) |
| WebDAV durable source + range resume | done (not integration-tested here) |
| SMB/WebDAV credentials in Room + Keystore | done |
| Unified Transfers UI for all sources | done |
| Docker fixtures + integration docs | defined; not executed in this environment |
| Release checklist | done |
| README update | done |

All four remote file sources (FTP, SMB, WebDAV, Calibre) now follow the same
contract: Room is the source of truth, credentials are Keystore ciphertext,
transfers run in a per-source foreground worker, and the UI only observes data.

Remaining work is verification rather than architecture:

* run `fixtures/docker-compose.yml` on a machine with Docker and execute the
  SMB/WebDAV/Calibre integration matrix in `docs/source-test-fixtures.md`;
* smoke-test the torrent native session and its private-flag behaviour on a
  physical device / emulator;
* optionally migrate the legacy FTP `FtpCredentialCipher` and the Calibre
  transfer repository onto the shared `RemoteTransferRepository` for one
  implementation instead of two.

Known external limits are listed in `docs/source-release-checklist.md`.