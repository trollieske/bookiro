# Bookiro Android — Privacy & Outbound Data-Flow Inventory (RC `9aa0f8e`)

Auditor: Subagent C (release/Play readiness), 2026-10-04, branch `rc/play`.
Method: source inspection of every network client + manifest/permission scan.
No production code was changed. **Nothing here is modelled away — every risky
flow is listed, including the torrent client and the automatic Audible lookup.**

---

## 0. TL;DR

- The app has **no accounts, no ads, no analytics/attribution/crash SDKs**.
  It does not embed the Firebase, Crashlytics, Sentry, Amplitude, Mixpanel,
  AppsFlyer, Adjust or Google Analytics clients (grep over all modules was
  empty; the only "telemetry" hits are comments noting that a former LAN debug
  reporter was removed and is now an explicit no-op,
  `app/src/main/java/com/bookrio/app/Screens.kt:134`).
- It **does** talk to third parties for book/podcast metadata and audio
  chapter lookup, and to the user's own servers (FTP/SMB/WebDAV/Calibre/RSS).
  Those flows are enumerated in §3.
- The **torrent client** (full/sideload build) joins DHT/LSD/PEX, contacts
  trackers and peers, and enables UPnP/NAT-PMP (§3, T1). This is the single
  biggest Play-policy and privacy risk. `docs/PLAY_VARIANT_DECISION.md`
  proposes the Play variant that removes it.
- No Libgen link, preset or entry point exists in the code; "libgen" survives
  only as a *filename cleanup* regex for imported files
  (`MetadataCleaner`, `MetadataFetcher`, `BookComponents`). See §6.
- **No credentials are logged** and credentials at rest are Keystore-encrypted
  (§5). No hardcoded LAN debug endpoint remains (the former reporter is a no-op).

---

## 1. Permissions (from the merged release manifest)

`app/build/intermediates/merged_manifests/release/processReleaseManifest/universal/AndroidManifest.xml`:

| Permission | Needed? | Justification |
|---|---|---|
| `INTERNET` | yes | user's servers, podcast RSS/streams, metadata lookups, torrent |
| `ACCESS_NETWORK_STATE` | yes | connectivity/constraints for sync + torrent + podcast |
| `ACCESS_WIFI_STATE` | yes | LAN discovery reads `WifiManager.connectionInfo` |
| `FOREGROUND_SERVICE` (+ `_MEDIA_PLAYBACK`, `_DATA_SYNC`) | yes | media sessions and WorkManager data-sync workers |
| `POST_NOTIFICATIONS` | yes | media + transfer notifications (runtime prompt) |
| `VIBRATE` | kept (low risk) | no direct vibration code; notification channels may use it. Can be dropped after a channel audit |
| `WAKE_LOCK` | yes | `setWakeMode` during playback, WorkManager |
| `RECEIVE_BOOT_COMPLETED` | yes | WorkManager reschedule after reboot |
| `com.bookrio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | auto | added by androidx core, signature level |

**Absent (deliberately, verified):** no storage permissions at all, no
`MANAGE_EXTERNAL_STORAGE`, no Bluetooth/Nearby, no location, no camera/mic, no
contacts, no `QUERY_ALL_PACKAGES`, no exact alarms.

## 2. Local-only data (never leaves the device by design)

| Data | Location | Notes |
|---|---|---|
| Books/audiobooks/PDFs etc. | app-private `filesDir/books`, SAF URIs, or user imports | read via `ContentResolver`/SAF grants |
| Covers | `filesDir/covers` (`book_<id>.webp`) | generated typographic covers when offline |
| Library DB | Room `shelf.db` (books, progress, bookmarks, sources, podcasts) | encrypted password columns |
| Podcast episodes downloaded | `DownloadManager` → app-private dir | user-initiated; may be large |
| Torrent payloads (full build) | `filesDir/shelf_torrents` | never shared back except by seeding |
| Prefs | DataStore + `EncryptedSharedPreferences` for credentials | keys in `UserPreferencesRepository` |

## 3. Outbound flow register (every network egress found)

Legend: **trigger** = what the user/app does first. All endpoints are HTTPS
unless stated.

### F1 — Online metadata & cover lookup (opt-in, default OFF)
- **Code:** `core/.../MetadataFetcher.kt`; called only from
  `CoverRepository.refreshOnline` / `renderAndPersist`, which check
  `prefs.onlineCoverLookup` (default `false`, `MetadataRefreshWorker` returns
  early when off). Also invoked by the Settings action "Refresh metadata &
  covers" (scope: suspects/all).
- **Endpoints:** `openlibrary.org/api/books`, `openlibrary.org/search.json`,
  `covers.openlibrary.org/b/{isbn,id}/…`, `www.googleapis.com/books/v1/volumes`,
  `itunes.apple.com/search?media=audiobook`.
- **Data sent:** title, author, ISBN (URL query), plus IP/UA
  (`ShelfEbookReader/1.1 (Android; +https://shelf.local)`).
- **Received:** title/author/publisher/date/description/cover URL; the cover
  bitmap is downloaded and stored locally.
- **Retention:** none by the app beyond the local cover file; third-party
  retention is governed by those services.

### F2 — Audiobook chapter lookup (AUTOMATIC, no opt-in)
- **Code:** `player/.../AudibleChapterLookup.kt` (UA
  `Shelf-Audiobook/1.0 (chapter metadata lookup)`), triggered from
  `AudiobookEngine.ensureFreshChapters` whenever an audiobook has a single
  generic/stub chapter or generic chapter titles. Result is stored in the local
  chapter list; one attempt per file identity per process, 24h failure cooldown.
- **Endpoints:** `api.audible.com/1.0/catalog/products?keywords=…`
  then `api.audnex.us/books/{asin}/chapters`.
- **Data sent:** audiobook **title and author** (and the ASIN once found), plus
  IP/UA. No account, no audio, no listening history.
- **Retention:** app stores only chapter titles/timings locally.
- **Owner decision recorded in `OVERNIGHT_REPORT.md` §6.2:** keep the feature
  and disclose it. This doc does the disclosure; the Play variant does not
  change it.

### F3 — Podcast discovery (user-initiated search)
- **Code:** `podcast/.../ItunesSearchClient.kt` + `PodcastHttp`.
- **Endpoints:** `itunes.apple.com/search`, `itunes.apple.com/lookup`
  (media=podcast; storefront country from user choice/locale).
- **Data sent:** the search text + storefront code + IP/UA
  (`Shelf/1.0 (Android; podcast RSS reader)`). 15-minute in-memory cache, no
  disk persistence of queries.

### F4 — Podcast feed sync (followed feeds)
- **Code:** `PodcastFeedSyncWorker` (daily periodic + manual refresh +
  per-feed fetch after subscribe), `PodcastHttp.fetchFeedBody`.
- **Endpoints:** the feed URLs the user subscribed to (arbitrary https/http;
  cleartext allowed by policy, §7).
- **Data sent:** HTTP GET with UA; gzip accepted; 8 MB cap. Episode enclosure
  URLs come back and are used for play/download.

### F5 — Podcast episode download / streaming
- **Code:** `podcast/.../PodcastDownloads.kt` (Android `DownloadManager`, user
  taps download) and media3 `ExoPlayer` for streaming (user taps play).
- **Endpoints:** episode enclosure URLs from the feed (often third-party CDNs).
- **Data sent:** HTTP range/GET requests + IP; no app identifiers.

### F6 — User-configured file sources (FTP / FTPS / SFTP)
- **Code:** `ftp/...` (Commons Net + sshj), credentials from
  `shelf_ftp_servers_enc.xml` / Room ciphertext.
- **Trigger:** connect/browse/sync (manual, periodic sync or "sync all").
- **Data sent:** credentials (FTP USER/PASS, SSH password/key), path names,
  file requests. Plain FTP and SFTP/FTP(S) are supported; cleartext FTP is
  allowed because home servers use it (justified in the network config, §7).

### F7 — SMB (jcifs-ng)
- **Code:** `smb/...`; NTLM/NTLMv2 auth to the user's share.
- **Data sent:** domain/user/password hash protocol exchanges, share+path
  listings, file bytes. No SMB discovery outside the LAN-scan feature (F9).

### F8 — WebDAV and Calibre Content Server
- **Code:** `webdav/...`, `calibre/...` (OkHttp). Basic/Digest auth; OPDS
  browse/search/download. Cross-origin redirects strip `Authorization` (see
  `docs/source-release-checklist.md`).
- **Data sent:** credentials, paths, queries, file bytes to the user's server.

### F9 — LAN discovery (user-initiated "Scan now")
- **Code:** `core/.../LanSourceDiscovery.kt`.
- **Behaviour:** after the tap, the app enumerates the **local RFC1918 subnet**
  (from `LinkProperties`, fallback `WifiManager`, fallback the 3 fixed /24s
  `192.168.1.0/24`, `192.168.0.0/24`, `10.0.0.0/24`) and opens TCP connections
  to ports 21, 445, 80, 8080, 8081 on each host to fingerprint FTP/SMB/WebDAV/
  Calibre. Only a TCP handshake is attempted; no credentials or payloads.
- **Privacy note:** this is a network scan of the user's own LAN. It is only
  started by a visible button, never on boot/background. Keep it that way for
  the Play variant.

### F10 — Torrent (full/sideload build only) — **highest risk**
- **Code:** `torrent/...` (libtorrent4j). Magnet/`.torrent` input by the user.
- **Egress:** tracker announce URLs embedded in the torrent/magnet (HTTP/UDP),
  **DHT** (public bootstrap nodes), **LSD** (multicast on the local network),
  **PEX**, and **peer connections** (TCP/uTP), `0.0.0.0:62473,[::]:62473`.
  UPnP and NAT-PMP are enabled, so the engine asks the router to open that port.
  Private torrents get DHT/PEX/LSD disabled per-torrent.
- **Data exposed:** the user's public IP, the swarm-visible client identity
  (`Vierel/1.0`, fingerprint `-VR1000-`), infohash, downloaded/uploaded data.
  Seeding continues while the process lives (foreground worker/engine).
- **App-side retention:** download state in Room; payload in app-private
  storage. Trackers/passkeys are masked in the UI diagnostics.
- **Policy:** Google Play has repeatedly removed apps seen as facilitating
  copyright infringement. A torrent client plus an ebook library is a high-risk
  combination even with the "download only authorized content" notice
  (`toru_rights_notice`). See `PLAY_VARIANT_DECISION.md` for the split.

### F11 — External links
- Torrent funnel links (full build): `archive.org/search`,
  `standardebooks.org`, `gutenberg.org` — opened in the user's browser via
  `ACTION_VIEW` (`TorrentScreen.kt:174`).
- "Show in folder" uses `ACTION_VIEW` with a SAF document/tree URI and a
  read grant (`LibraryFolderLauncher.kt`).
- No analytics/referral parameters are appended in either case.

### F12 — Reader content rendering (WebView / Readium)
- Legacy engine loads chapter HTML with
  `loadDataWithBaseURL("https://bookiro.app/r/", …)`, `javaScriptEnabled`,
  `allowFileAccess`, `allowContentAccess`, navigation blocked by
  `shouldOverrideUrlLoading = true`. Sub-resource loads are **not** blocked:
  an EPUB whose chapter HTML references absolute `http(s)` images/fonts/trackers
  can cause direct requests while that chapter is rendered. This is
  content-author-controlled, not app telemetry.
- Readium navigator: publication resources are served locally; remote
  resources referenced by a malicious EPUB can likewise be fetched by the
  WebView it owns. No app data is attached.
- **Recommendation (not applied):** for the Play release consider
  `settings.blockNetworkLoads = true` (legacy) / a Readium resource policy that
  denies remote loads; at minimum document that third-party EPUB content can
  beacon out.

### F13 — Android system cloud backup (user/OS controlled)
- `allowBackup=true`, `data_extraction_rules` + `backup_rules` exclude all
  credential pref files. Room DB + files may be uploaded to the user's Google
  Drive backup; the Keystore key does not leave the device, so encrypted
  passwords restored on another device cannot be decrypted.

### F14 — WorkManager periodic traffic (background)
Scheduled at app start: media scanner (local), FTP periodic sync, FTP
coordinator, torrent settings (no-op unless enabled), podcast daily sync,
Calibre/SMB/WebDAV sync if their toggle is on. Everything except the media
scanner only runs for sources the user configured and honours
Wi‑Fi/charging constraints. Metadata refresh stays off until the user enables
"Online cover lookup" **and** taps refresh.

## 4. Data types — quick view

| Category | Leaves device? | Where |
|---|---|---|
| Library files / audio content | only to the user's own servers when uploading; to peers only in torrent build | F6–F10 |
| Book title/author/ISBN | yes | F1 (opt-in), F2 (automatic) |
| Podcast search terms | yes | F3 |
| Podcast/feed URLs | yes | F4/F5 |
| FTP/SMB/WebDAV/Calibre credentials | yes, to the user's own server only | F6–F8 |
| IP address / user agent | inevitably to all of the above | F1–F10 |
| Contacts, location, ads ID, analytics events | **no** | — |
| Crash logs | **no SDK** (nothing uploaded) | — |

## 5. Credential handling at rest

- FTP/SMB/WebDAV legacy store: `EncryptedSharedPreferences` with
  `MasterKey(AES256_GCM)` (`FtpServerStore`, `SmbServerStore`,
  `WebdavServerStore`), files excluded from backup.
- Room `password_encrypted` columns (FTP/Calibre/SMB/WebDAV sources) are
  AES/GCM ciphertext from `KeystoreSecretCipher` / `FtpCredentialCipher`
  (Android Keystore, per-device key).
- Passwords are never written to logs; tracker URLs are masked
  (`TrackerDiagnostics.maskUrl`) including `user:pass@` and `passkey=`.
- Release R8 rules strip `Log.v/d/i` entirely (`proguard-rules.pro`
  `-assumenosideeffects`), so debug paths/URIs cannot leak from release logs
  (only `w/e` remain).

## 6. Torrent UI & Libgen — explicit status

- Torrent screen (`torrent/src/main/java/com/bookrio/torrent/ui/TorrentScreen.kt`)
  offers: add `.torrent` file, add **magnet**, filter list, pause/resume,
  delete, connection diagnostics, settings for background Wi‑Fi/charging/battery,
  and a rights notice string `toru_rights_notice` = "Only download content you
  have the rights to."
- Preset sources are only `archive.org` (texts), `standardebooks.org`,
  `gutenberg.org` (`TorrentScreen.kt:127-131`).
- **Libgen:** `grep -rni libgen` finds **no link, URL or preset** anywhere.
  Remaining hits are filename-scrubbing regexes in `MetadataCleaner`,
  `MetadataFetcher`, `BookComponents` that *remove* libgen-style tags from
  imported titles, plus historical notes in `OVERNIGHT_REPORT.md`.
- No hidden/obfuscated torrent entry point was found: the route is registered in
  `MainActivity.kt` (`ShelfDestinations.Torrent`), surfaced as a Sources card
  (`MainActivity.kt:795`), a Home tile (`HomeScreen.kt:509`) and Settings
  toggles (`SettingsScreen.kt:1241-1319`). All of those are the entry points the
  Play variant must remove (see the proposal doc).

## 7. Network security config (cleartext)

`network_security_config.xml` sets `cleartextTrafficPermitted="true"` for
**all hosts**, because LAN servers are arbitrary private IPs (FTP/SMB/WebDAV/
Calibre/podcast http feeds). HTTPS is still used for every internet endpoint
(F1–F5) and system trust anchors are kept; per-server self-signed trust is
opt-in. This is intentional and must be disclosed in the privacy policy. For the
Play variant, an optional hardening is to scope cleartext to RFC1918 ranges
(`<domain includeSubdomains="false">` cannot wildcard CIDR, so this requires a
custom `NetworkSecurityPolicy`/`isCleartextTrafficPermitted` check in the
source clients instead — **recommendation only, not applied**).

## 8. Google Play Data Safety — recommended answers

Google Play's definitions: "collected" = transmitted off device; "shared" =
sent to a third party. "Processed ephemerally" (not stored by the developer)
can be declared as not collected, but we take the conservative,
policy-safe position below because third-party APIs can log requests.

| Data Safety section | Question | Recommended answer | Why |
|---|---|---|---|
| Data collection | Does your app collect or share required user data? | **Yes** | metadata/query flows |
| Data types | Personal info → Name? | **No** | author names are content metadata, not the user |
| | Personal info → Email/Address/Phone? | **No** | no accounts |
| | Financial info | **No** | no purchases |
| | Location | **No** | LAN discovery is not location collection; no GPS permission |
| | Files & docs | **No** (not collected) | files stay on device/user servers; may be *transferred* to user's own server by design |
| | App activity → In-app search history | **Yes — App interactions / "Other in-app actions"** | podcast search, metadata refresh, chapter lookup send queries |
| | Web browsing | **Yes — "Other in-app actions"** (feed/URL fetches) | fetching user-entered RSS/OPDS/feed URLs; no browser history |
| | App info & performance → Crash logs/Diagnostics | **No** | no crash SDK |
| | Device or other IDs | **No** | no advertising ID, no device ID sent |
| Purpose | Why collected? | **App functionality** | only reason |
| Sharing | Is data shared with third parties? | **Yes** for F1/F2/F3 (Open Library, Google, Apple, Audible/audnex) and **Yes** for F4/F5/F6–F9 (the user's own servers — Data Safety treats these as service providers/user-directed) | |
| Optional | Is this data optional? | F1 = **yes (opt-in)**, F2 = **no (automatic)**, F3–F9 = **yes (user-initiated)** | must match the policy text |
| Security | Encrypted in transit? | **Yes** for internet endpoints (HTTPS); LAN servers may be plain HTTP/cleartext per user config | state it in the policy |
| Deletion | Can users request deletion? | **No account — nothing to delete server-side**; local data is deleted by uninstall / in-app delete | |
| Ads/analytics | Contains ads? | **No** | |

Additional mandatory statements (Play, and GDPR "data recipients"):

- Processor/recipient list: Open Library (Internet Archive), Google Books
  API, Apple iTunes Search API, Amazon Audible catalog API, audnex.us, plus
  the user's own configured servers/trackers/peers.
- Broadcast/manifest declarations: no ad ID (`AD_ID` permission absent), no
  `ACCESS_ADSERVICES_*`, no `AD_SERVICES_CONFIG`.
- **The torrent/DHT/tracker/peer flow must NOT be declared on the Play listing
  if the Play variant removes it; if the full build were uploaded, the Data
  Safety answers would additionally need "Device or other IDs" (peer-visible IP
  is treated as personal data) and a policy review that a P2P client is
  acceptable. This is the core reason for the variant split.**
- Privacy policy URL must cover: local-first design, the F1/F2 endpoints with
  example payloads, the fact that F2 runs automatically, user-server traffic,
  cleartext possibility on LAN, LAN scanning, torrent (only for the sideload
  build), retention (local only), and contact for privacy requests.

## 9. Residual risks (not hidden)

1. **F2 is automatic and third-party** (Audible/audnex). Owner accepted the
   disclosure route; if the legal position changes, gating F2 behind the same
   opt-in switch as F1 is a one-line change in `AudiobookEngine.ensureFreshChapters`.
2. **Torrent client** = Play policy risk even with legitimate uses. Mitigated by
   the `play-store` variant (removes module + UI + all entry points).
3. **ISBN/title queries over cleartext-free HTTPS but to third parties** leak
   reading interests (title+author in URLs). No mitigation short of opt-in.
4. **Reader content beacons** (F12) — a malicious EPUB can call out; recommend
   blocking network loads in the reader.
5. **LAN discovery footprint** — repeated TCP opens can trip IDS on corporate
   Wi‑Fi; user-initiated only.
6. **Cleartext allowed globally** — required for LAN sources, but it also
   permits a plain-http metadata/podcast URL if one were ever introduced.
   Client code currently hardcodes https for F1–F3; F4 tolerates http feeds by
   design.
7. **No `dataSync` FGS quota exemption** — background sync may pause after the
   Android 14+ quota; not a privacy issue, but users will notice.

## 10. Verification commands used (reproducible)

```bash
grep -rniE "firebase|crashlytics|sentry|amplitude|mixpanel|appsflyer|adjust\.com|google-analytics" \
    --include=*.kt --include=*.kts --include=*.xml --exclude-dir=build .
grep -rn "Log\.[dviwe]" --include=*.kt --exclude-dir=build */src/main | wc -l
grep -rni "libgen" --exclude-dir=build --exclude-dir=.git .
grep -rn "ACTION_VIEW\|openConnection\|OkHttp" --include=*.kt --exclude-dir=build */src/main
sed -n '1,120p' app/build/intermediates/merged_manifests/release/processReleaseManifest/universal/AndroidManifest.xml
```