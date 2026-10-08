# Bookiro — applicationId correction & data-safety changes (APPLIED)

Status: **APPLIED on branch `release/playstore-variant-rc`** (working tree, not yet
committed). Base `eec1c7c` plus the uncommitted changes described here.

Branding: **Bookrio was the original name; Bookiro is the current brand.** `com.bookrio.*`
namespaces/source packages are intentionally retained (no cosmetic package rename).

---

## 1. What was applied

### 1.1 applicationId (`app/build.gradle.kts`)

```kotlin
applicationId = "com.bookiro"   // was "com.bookrio"
```

Flavor/build suffixes are unchanged, so the built ids are now:

| Variant | applicationId (verified with `aapt2 dump badging`) |
|---|---|
| `full` release | `com.bookiro` |
| `playstore` release | `com.bookiro.play` |
| `full` debug | `com.bookiro.debug` |
| `playstore` debug | `com.bookiro.play.debug` |

versionCode `11`; versionName `1.0.0-readium9` / `1.0.0-readium9-play`; minSdk `26`;
targetSdk `36`; compileSdk `36`.

### 1.2 Namespaces / packages / storage names — unchanged

`namespace = "com.bookrio"`, all `com.bookrio.*` packages, Room DB `shelf.db`, DataStore
`shelf_prefs`, Keystore aliases `shelf_*`, EncryptedSharedPreferences `shelf_*_enc.xml`,
and all class names remain as they were. Only the applicationId changed.

### 1.3 applicationId-dependent behaviour — inspected and handled

| Behaviour | How it resolves | Action |
|---|---|---|
| FileProvider authority | `${applicationId}.fileprovider` (manifest) and `${ctx.packageName}.fileprovider` (`Screens.kt`) | consistent at runtime; no change needed |
| Notification launch intents | `getLaunchIntentForPackage(packageName)` (metadata/torrent workers) | follows the applicationId at runtime |
| Runtime receiver permission | `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, generated from applicationId | automatic |
| Package-derived prefs | `FtpServerStore` uses `${packageName}_ftp_plain` | backup/data-extraction rules updated (§1.4) |
| Hardcoded internal actions | `com.bookrio.ftp.action.*`, `com.bookrio.podcast.*` | internal broadcasts, not package-derived; left unchanged |

### 1.4 Backup / transfer exclusions (`backup_rules.xml`, `data_extraction_rules.xml`)

Plaintext FTP prefs are named from the applicationId. All four current names are now
excluded (old `com.bookrio_ftp_plain.xml` kept too):

- `com.bookiro_ftp_plain.xml`
- `com.bookiro.play_ftp_plain.xml`
- `com.bookiro.debug_ftp_plain.xml`
- `com.bookiro.play.debug_ftp_plain.xml`
- (legacy) `com.bookrio_ftp_plain.xml`

Covered for `full-backup-content`, `cloud-backup` and `device-transfer`.

### 1.5 Visible branding — confirmed

`app_name` = **Bookiro** in all 10 locales; onboarding/settings/about/share all say
Bookiro. `grep "Bookrio"` over user-facing resources = 0 hits. No user-facing fix needed.

### 1.6 Other data-safety changes applied (see §3)

- Removed `fallbackToDestructiveMigration()` and the automatic `deleteDatabase()` fallback.
- Dedup no longer collapses by title/author; annotations preserved.
- Audiobook repair soft-deletes; split false-positive gated.
- Metadata refresh rejects mismatched results.
- `VIBRATE` removed.

---

## 2. Installation & data-migration implications (unchanged from the plan)

Changing the applicationId makes Android treat the app as a **separate app**:

- New sandbox `/data/data/com.bookiro/`; the old `com.bookrio` install and **all its data
  remain untouched** (nothing is uninstalled, cleared or deleted).
- **This is a new installation, not an in-place upgrade.** Library progress, bookmarks,
  highlights and server credentials do **not** transfer automatically; app-private books
  and SAF grants do not move; Keystore-encrypted credentials are unusable in the new app.
- Google auto-backup is keyed by package name, so `com.bookiro` will not restore a
  `com.bookrio` backup set.

### 2.1 The raw DB export/import is NOT a verified migration path

The existing "Export database" / "Import" copies `shelf.db` verbatim. It is **not**
presented as a supported migration:

- **WAL/consistency:** the export copies only `shelf.db`, not `shelf.db-wal`/`shelf.db-shm`;
  a live WAL could make the snapshot stale or inconsistent.
- **Stored paths:** rows keep absolute/`content://` paths and SAF URIs that the new app has
  no grant for.
- **Credentials:** `password_encrypted` columns and EncryptedSharedPreferences are bound to
  the old app's Keystore and cannot be decrypted by `com.bookiro`.
- **Result:** a copied DB may open but with missing files, broken credentials and possibly
  stale progress. Treat it as best-effort, not a migration.

---

## 3. Data-loss fixes applied in this change

| Area | Before | After |
|---|---|---|
| DB open failure | `.fallbackToDestructiveMigration()` + `deleteDatabase` on failure | no destructive migration, no auto-delete; `databaseError` StateFlow + error/retry UI; reset only via explicit confirm |
| Dedup pass 2 | collapsed any same title+author (and cross-format) | only proven identity: same file URI/path, or same non-blank ISBN + type + format |
| Dedup annotations | only progress moved | progress (never overwriting) + bookmarks + highlights re-pointed to the survivor |
| Audiobook consolidate | `bookDao.delete(dup)` (hard) | `softDelete(dup)` + annotations preserved |
| Audiobook split | split any 2..12 same-folder chapter-name files | only when track files span ≥2 parent folders; one-folder/SAF groups untouched |
| Metadata refresh | any scored result could win | minimum-match gate (title containment or ISBN); mismatched → no update |
| VIBRATE | declared, unused | removed |

---

## 4. Verification (see `docs/PLAY_GO_NO_GO.md` and `docs/INTERNAL_TEST_ACCEPTANCE.md`)

- Both flavors build (debug + R8 release) with the new ids.
- Playstore AAB is torrent-free; full AAB still contains `libtorrent4j.so`.
- 406 JVM unit tests pass; Playstore lint has 0 errors.
- 16 KB: zip-aligned APK and `0x4000` ELF LOAD alignment for every `.so`.
- R8 mapping is embedded in the AAB metadata (`BUNDLE-METADATA/.../proguard.map`).

---

## 5. Approval checklist (owner)

- [ ] Confirm no Play app exists for `com.bookrio.play`/`com.bookiro.play` (Play Console → All apps).
- [ ] Accept that existing `com.bookrio` installs require a fresh import (new id = new app).
- [ ] Approve committing/pushing this change (not done yet).