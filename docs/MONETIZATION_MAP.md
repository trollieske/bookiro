# Bookiro Android — monetization map (Free + one-time "Bookiro Pro")

Status: **PROPOSAL / owner decision.** No billing code exists today
(`grep -i billing gradle/libs.versions.toml` → none). This document is the map the
owner needs to approve before any `ProEntitlement` code is written.

Model: **free app + one-time in-app purchase "Bookiro Pro" at $4.99** (Google Play
`INAPP` product, not a subscription). One purchase, kept forever.

Scope: user-facing features per module on the current `release/playstore-variant-rc`
(`accb368`). `torrent` exists only in the `full` flavor. `store/` is **not** a feature
module — it contains only `play_icon_512.png`; the "store" surface is the Play listing.

Tier legend: **FREE** = available without purchase · **PRO** = requires Bookiro Pro ·
**UNKNOWN** = not yet decided / not verified.

Gating-cost legend: **Lav** = hide a button/screen/section · **Middels** = several entry
points and/or a worker + settings · **Høy** = background workers, MediaSession/Auto,
multi-module or migration.

---

## 1. What "Pro feels like the real app at $4.99" means here

Free Bookiro must be a genuinely good local reader/player/listener. Pro then layers on
the three things that make it feel like a *complete* app:

1. **Annotations** — bookmarks, persistent highlights, in-book search.
2. **Your own servers** — FTP / SMB / WebDAV / Calibre (+ transfers, sync, LAN discovery).
3. **Convenience & integration** — sleep timer, autoplay next, Android Auto, reader↔audio
   handoff, online metadata refresh, watch-folder auto-import.

That is a coherent, defensible $4.99 bundle: acquisition is easy (free local
reading/listening/podcasts), and the paid features are the ones power users actually
want.

---

## 2. Feature map

### 2.1 Reader (`reader`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Open/read local EPUB, PDF, MOBI, FB2, TXT, CBZ/CBR | reader | FREE | the base promise: read your files | Lav (already free) |
| TOC + chapter navigation (hierarchical, spine fallback) | reader | FREE | basic reading | Lav |
| Reading position + resume, cross-open | reader | FREE | basic reading | Lav |
| Reader display settings: font size, theme, margins, line-height, paged/scroll | reader | FREE | "basic reader settings" per hypothesis; they are display, not power features | Lav |
| Image zoom in book | reader | FREE | part of reading | Lav |
| Share position / share quote | reader | FREE | growth/virality — do not paywall sharing | Lav |
| Legacy position migration | reader | FREE | correctness, not a feature | n/a |
| **Bookmarks (add/list/remove/jump)** | reader | **PRO** (see challenge §3.1) | annotation pack | Lav (DAO + UI already exist) |
| **Persistent highlights + highlight list + jump** | reader | **PRO** | annotation pack; signature Pro value | Lav–Middels |
| **In-book search** | reader | **PRO** | annotation/advanced; Readium search is a differentiator | Middels |
| Text-to-speech | reader | **UNKNOWN** | `TtsPlaybackEngine.kt` exists but is never referenced (not wired). If wired later → PRO | Høy if wired |

### 2.2 Player / audiobooks (`player`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Play/pause/seek audiobook | player | FREE | the base promise: play your files | Lav |
| Chapter list + chapter navigation | player | FREE | basic playback | Lav |
| Playback speed / skip back / skip forward | player | FREE | basic audiobook controls; gating these feels punitive (challenge to hypothesis) | Lav |
| Sleep timer | player | PRO | convenience, classic Pro anchor | Lav |
| Fade-out | player | FREE | minor polish | Lav |
| Autoplay next in series | player | PRO | convenience | Lav |
| **Android Auto browse → direct playback** | player/service | **PRO** (see challenge §3.2) | strong integration anchor; but gating platform integration carries UX/policy risk | Høy (MediaSession/Auto + entitlement) |
| Automatic chapter lookup (Audible / audnex, F2) | player | **FREE** (owner decision; see §7) | keeps free audiobooks usable; F2 disclosure is required regardless of tier | Middels (also privacy) |
| Reader↔audio position handoff (precision + toast) | app/data | PRO | signature convenience | Middels |

### 2.3 Podcasts (`podcast`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Follow/subscribe to RSS feeds | podcast | FREE | acquisition; broad appeal | Lav |
| Feed discovery (iTunes search, country storefront) | podcast | FREE | acquisition | Lav |
| Stream episodes | podcast | FREE | basic listening | Lav |
| Download episodes (offline) | podcast | FREE | offline is a basic expectation (challenge §3.4) | Lav |
| Podcast playback (background + media session) | podcast | FREE | basic listening | Lav |
| Per-feed refresh / unfollow / remove download | podcast | FREE | basic feed management | Lav |
| Auto-download new episodes / playback speed | podcast | **UNKNOWN** | candidate Pro levers if conversion is low; default keep FREE | Middels |

### 2.4 Library & local import (`library`, app-level `Import`/`BookDetails`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Import files (SAF picker, folder, share/view intent, drag-drop) | library/app | FREE | core | Lav |
| Shelf: books / audiobooks tabs, sort, filter | library | FREE | core | Lav |
| Reading progress / continue-reading | library | FREE | core | Lav |
| Book details (chapters, formats, size, actions) | app | FREE | core | Lav |
| Typographic fallback covers | library | FREE | core | Lav |
| Duplicate / merged-audiobook repair | library | FREE | correctness | n/a |
| Reading streak / gamification | app/data | FREE | engagement (acquisition) | Lav |
| **Online metadata & cover refresh (opt-in)** | library | **PRO** | convenience/advanced; also keeps free-tier network egress smaller (privacy-positive) | Middels (worker + settings) |
| **Watch library folder (auto-import on new files)** | app | **PRO** | advanced automation | Middels (worker) |
| Change/override a book cover | library | **UNKNOWN** | small convenience; could be FREE or PRO | Lav |
| Export / import database backup | app | **FREE** (challenge §3.3) | data portability — never hold user data hostage | Lav |

### 2.5 FTP (`ftp`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Add / edit / delete FTP(S)/SFTP sources | ftp | PRO | own-server feature; power user | Høy |
| Browse remote folders | ftp | PRO | own-server | Høy |
| Download/import from FTP, transfer screen | ftp | PRO | own-server | Høy |
| Periodic sync (interval, Wi-Fi only, charging only) | ftp/app | PRO | advanced automation | Middels |
| FTP notifications / cancel | ftp | PRO | part of sync | Middels |

### 2.6 SMB (`smb`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Add/authenticate SMB share (NTLM/NTLMv2), browse, import | smb | PRO | own-server | Høy |
| SMB sync (interval, constraints) | smb/app | PRO | advanced | Middels |

### 2.7 WebDAV (`webdav`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| WebDAV source (Basic/Digest), browse, download/upload | webdav | PRO | own-server | Høy |
| WebDAV sync (interval, constraints) | webdav/app | PRO | advanced | Middels |

### 2.8 Calibre Content Server (`calibre`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Calibre / OPDS source, browse, search, download | calibre | PRO | own-server | Høy |
| Calibre sync (interval, constraints) | calibre/app | PRO | advanced | Middels |

### 2.9 App-level: Home, Sources, Transfers, Settings (`app`)

| Feature | Module | Tier | Rationale | Gating cost |
|---|---|---|---|---|
| Onboarding | app | FREE | core | n/a |
| Home dashboard (continue reading/listening, tiles, streak) | app | FREE | core | n/a |
| Sources overview (FTP/SMB/WebDAV/Calibre rows) | app | PRO | gated because the sources are Pro | Lav (hide rows) |
| Transfers screen | app | PRO | only meaningful with remote sources | Lav |
| LAN source discovery ("Scan now") | core/app | PRO | power feature | Middels |
| Basic settings: theme, language, reader/audio defaults, clear cache | app | FREE | core | Lav |
| Advanced settings: sync intervals, handoff precision, watch folder, metadata refresh | app | PRO | advanced | Lav–Middels |
| Database export/import | app | FREE | data portability (challenge §3.3) | Lav |
| Torrent (magnet/`.torrent`, DHT, seeding) | torrent | **PRO / always-on in `full`** | not in the playstore variant; see §5 | Høy |

---

## 3. Challenges to the proposed hypothesis

The hypothesis was: **FREE** = read/play local files, library, podcast subscriptions,
basic reader settings; **PRO** = highlights, in-book search, bookmarks, Android Auto,
FTP/SMB/WebDAV, advanced settings. Where I disagree or where it needs an owner call:

### 3.1 Bookmarks are weak as Pro on their own
Bookmarks are a near-universal expectation and cheap to build; a user who loses
bookmarks may bounce before ever considering Pro. **Recommendation:** keep the Pro
"Annotations" *bundle* (bookmarks + highlights + search) but consider making
**bookmarks FREE** and highlights/search Pro. Owner decision (UNKNOWN).

### 3.2 Gating Android Auto is risky
Android Auto is a *platform integration*, and paywalling it can read as punitive and
invite bad reviews. At the same time it is one of the strongest reasons to buy audiobook
apps. **Recommendation:** keep it in Pro, but make the paywall honest ("Android Auto is
part of Bookiro Pro") and consider a limited trial. Owner decision (UNKNOWN).

### 3.3 Do NOT paywall export/backup
Exporting the database is data portability; gating it is anti-user and can be a policy
smell. **Keep export/import FREE** even though it sits in "advanced settings".

### 3.4 Podcasts as a fully free suite is a feature, not a mistake
Keeping subscriptions, downloads and playback free is the right acquisition play. If
conversion is too low later, the *least* harmful Pro lever is "auto-download new
episodes", not basic playback.

### 3.5 Basic playback speed / skip should stay FREE
The hypothesis lumped "advanced settings" into Pro. Speed and skip are core audiobook
controls; gate the *convenience* features (sleep timer, autoplay, handoff, Auto) instead.
Marked FREE above.

### 3.6 Online metadata refresh as Pro is a reasonable privacy trade
It is already opt-in and worker-driven; moving it to Pro reduces free-tier network
egress (good for Data Safety) and gives Pro tangible value. Fine as PRO.

### 3.7 Remote sources are the real Pro core
FTP/SMB/WebDAV/Calibre are the single biggest Pro value and the hardest to build. Correct
to put them in Pro.

---

## 4. Proposed architecture

### 4.1 `ProEntitlement` — one interface, queried everywhere

Every feature module already depends on `:core` (verified: reader, player, podcast,
library, ftp, smb, webdav, calibre, torrent — all `project(":core")`). Put the interface
there so any module can ask without new dependencies:

```kotlin
// :core  →  com.bookrio.core.entitlement   (existing namespace; no cosmetic rename)
interface ProEntitlement {
    /** Reactive: true when Bookiro Pro is owned (or forced on in debug). */
    val isPro: StateFlow<Boolean>
    /** Re-query the store and refresh the local cache. Safe to call on every resume. */
    fun refresh()
}
```

A `ProEntitlementProvider` object exposes the single instance, implemented per flavor
source set exactly like the existing `TorrentFeatureProvider` seam:

```
app/src/main/.../entitlement/ProEntitlementProvider.kt      // declared by flavor sets
app/src/playstore/.../ProEntitlementProvider.kt             // PlayBillingEntitlement
app/src/full/.../ProEntitlementProvider.kt                  // AlwaysProEntitlement
```

- `playstore` → **Play Billing 8.x** (`com.android.billingclient:billing-ktx`), one-time
  `INAPP` product `bookiro_pro`.
- `full` → **always Pro** (see §5).

### 4.2 Offline-capable local cache

```
DataStore (Preferences): pro_owned: Boolean, pro_last_verified_at: Long
```

- On app start and on `ON_RESUME`: `queryPurchasesAsync(INAPP)` → update
  `pro_owned` + timestamp, then emit to `isPro`.
- Offline: trust `pro_owned` from the cache (a one-time purchase is permanent, so there
  is no expiry/grace-window problem — simpler than subscriptions).
- Restore: the same `queryPurchasesAsync` is the restore path; expose a "Restore
  purchase" button on the Pro screen.

### 4.3 Debug flag to force Pro on/off

Three levels, in order:
1. Gradle property `-Pbookiro.pro=on|off` (or `BOOKIRO_FORCE_PRO` env) baked into a
   `BuildConfig` field only for debug builds.
2. A hidden developer toggle in Settings (debug builds only) stored in prefs, overriding
   the billing result at runtime.
3. `BuildConfig.DEBUG` default = off (so debug behaves like a real free user).

### 4.4 Gating mechanics

- **Compose:** a small `ProGate`/`ProBadge` in `:designsystem` that reads `isPro` and
  either shows the content or a lock + "Go Pro" sheet. Used at the entry points
  (bookmark button, search action, highlight sheet, Sources rows, Auto browse).
- **Non-UI (workers):** `FtpSyncWorker`, `SmbSyncWorker`, `WebdavSyncWorker`,
  `CalibreSyncWorker`, `MediaScannerWorker` (watch folder) and
  `MetadataRefreshWorker` must check `isPro.value` before enqueueing/running, so a
  free user cannot trigger Pro background work.
- **MediaSession/Auto:** gate `AudiobookLibraryTree`/`onGetChildren` + the Auto browse
  tree on `isPro`, while keeping playback notifications working for free playback.

### 4.5 Purchase flow

1. Pro screen shows product, price and a buy button.
2. `launchBillingFlow(activity, ProductDetailsParams(bookiro_pro, INAPP))`.
3. `PurchasesUpdatedListener` → on `OK`, `acknowledgePurchase` (required within 3 days
   for `INAPP`), persist `pro_owned=true`, emit `isPro=true`.
4. Handles `USER_CANCELED`, `ITEM_ALREADY_OWNED` (treat as owned), `SERVICE_UNAVAILABLE`
   (retry via `BillingClient` reconnection).

---

## 5. How the `full` (sideload) flavor gets its entitlement

Recommendation: **`full` is always Pro** (`AlwaysProEntitlement`, `isPro = true`),
because:

- It is distributed outside Google Play, so it cannot use Play Billing.
- It already ships the torrent client — a private/power build.
- It avoids a second paywall/UX surface in the sideload build.

Concretely: the `playstore` source set wires `PlayBillingEntitlement`; the `full` source
set wires `AlwaysProEntitlement` and does **not** depend on the billing library
(`"playstoreImplementation"(libs.play.billing)`), so the full artifact stays free of
Billing code — same pattern as `:torrent`.

UNKNOWN: if the owner ever wants to *sell* the full build separately, that needs a
different mechanism (license key / external store) — not designed here.

---

## 6. Edge cases & risks

- **Different application ids** (`com.bookrio.play` vs `com.bookrio` today; proposed
  `com.bookiro.play` vs `com.bookiro`, see `docs/APPLICATION_ID_PLAN.md`): a Play
  purchase is bound to the Play applicationId. The full build will never see it — by
  design (§5).
- **Refunds / chargebacks:** a one-time purchase can be refunded; `refresh()` on resume
  handles downgrade. With no server validation, a refunded user may keep Pro until the
  next Play query — acceptable for a $4.99 product (UNKNOWN whether owner wants server
  validation).
- **One-time product + Play policy:** allowed; must be a non-consumable `INAPP` and
  acknowledged. Not yet independently verified against current Play policy (UNKNOWN).
- **Downloaded podcast/Auto caching** must not leak Pro-only data to free users.
- **Analytics:** there is no analytics SDK (`PRIVACY_DATA_FLOW.md` §0). Purchase events
  will not be collected — conversion can only be seen in Play Console.

---

## 7. UNKNOWN / decisions needed

1. Are **bookmarks** FREE or PRO? (recommend FREE, §3.1)
2. Is **Android Auto** PRO or FREE? (recommend PRO with honest paywall, §3.2)
3. Is **TTS** ever going to be wired, and is it PRO? (engine is dead code today)
4. Is **F2 chapter lookup** FREE (current) or PRO?
5. Server-side purchase validation: yes/no? (recommend no for launch)
6. Exact **price localization** and whether $4.99 is the only tier.
7. Whether "**export/import DB**" stays FREE (recommended) — confirm.
8. Whether a free **trial** exists for Android Auto / annotations.
9. Play policy review of paywalling platform integrations — not verified here.
10. Product id and Play Console product setup (`bookiro_pro`) do not exist yet.
