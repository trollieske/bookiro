# Bookiro — applicationId correction & safety/release plan

Status: **PROPOSED — NOT APPLIED.** This plan must be approved by the owner before
any identifier is changed. No code or build file was modified to produce it.

Branding clarification (owner, 2026-10-07): **Bookrio was the original name; the app has
been rebranded to Bookiro. "Bookiro" is the intended current brand, not a typo.**

---

## 1. Inspection report (measured, not assumed)

### 1.1 User-facing app name and branding — already "Bookiro"

| What | Where | Value |
|---|---|---|
| Launcher/app name | `app/src/main/res/values*/strings.xml` `app_name` | **`Bookiro`** (all 10 locales) |
| Onboarding copy | `values/strings.xml` | "Welcome to **Bookiro**", "Enter **Bookiro**", "Choose where **Bookiro** finds…" |
| Settings copy | `values/strings.xml` | "Where **Bookiro** reads and watches for books", "**Bookiro** will query Open Library…" |
| About copy | `values/strings.xml` | "**Bookiro** — a private, ad-free reader and audiobook player" |
| Share text | `values/strings.xml` | "(via **Bookiro**)" |

`grep -rn "Bookrio"` over `app/src/main/res`, `reader`, `player`, `podcast`, `library`,
`ftp`, `smb`, `webdav`, `calibre` = **no hits**. Visible branding is already correct.

Residual lowercase `bookrio` is **internal only** (drawable name `bookrio_splash`, a
`com.bookrio_ftp_plain.xml` prefs filename, the `com.bookrio.*` code package). Not
user-visible.

### 1.2 Actual applicationId per flavor and build type (from built APKs, `aapt2 dump badging`)

| Variant | applicationId | status |
|---|---|---|
| `full` **release** | **`com.bookrio`** | built |
| `playstore` **release** | **`com.bookrio.play`** | built |
| `full` **debug** | **`com.bookrio.debug`** | built |
| `playstore` **debug** | **`com.bookrio.play.debug`** | built |

Configured in `app/build.gradle.kts`: `applicationId = "com.bookrio"`, flavor
`playstore` adds `applicationIdSuffix = ".play"`, build type `debug` adds
`applicationIdSuffix = ".debug"`.

### 1.3 Kotlin/Java namespaces and package names — all `com.bookrio`

All modules use namespace `com.bookrio.*` (unchanged by this plan):

```
app          namespace = com.bookrio     (applicationId = com.bookrio)
core         com.bookrio.core
data         com.bookrio.data
designsystem com.bookrio.designsystem
library      com.bookrio.library
reader       com.bookrio.reader
player       com.bookrio.player
podcast      com.bookrio.podcast
ftp          com.bookrio.ftp
smb          com.bookrio.smb
webdav       com.bookrio.webdav
calibre      com.bookrio.calibre
torrent      com.bookrio.torrent
pagecurl     com.bookrio.pagecurl  (unwired)
```

ApplicationId-dependent things *inside* the app:

| Thing | Current value | Derived from |
|---|---|---|
| FileProvider authority | `com.bookrio.fileprovider` | `${applicationId}.fileprovider` (manifest) and `${ctx.packageName}.fileprovider` (`Screens.kt`) |
| Runtime receiver permission | `com.bookrio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx core, from applicationId |
| FTP legacy plain prefs | `com.bookrio_ftp_plain.xml` | `${ctx.packageName}_ftp_plain` (`FtpServerStore.kt`), excluded from backup |
| Launch intents | `getLaunchIntentForPackage(packageName)` | runtime, follows applicationId |

ApplicationId-**independent** (fixed names): Room DB `shelf.db`, DataStore `shelf_prefs`,
EncryptedSharedPreferences `shelf_ftp_servers_enc.xml` / `shelf_smb_servers_enc.xml` /
`shelf_webdav_servers_enc.xml`, `shelf_ftp_hostkeys`, Keystore aliases `shelf_*`.

Hardcoded `com.bookrio.*` strings that are **not** applicationId-derived (cosmetic only):
FTP broadcast actions `com.bookrio.ftp.action.*`, podcast actions
`com.bookrio.podcast.*`, manifest service class names (`com.bookrio.player...` — these
are the namespace, unchanged).

### 1.4 Has an app been uploaded to Play Console?

| Evidence | Result |
|---|---|
| Play Publisher plugin / Gradle config | none (`grep play-publisher/triplet/fastlane` = no hits) |
| Service-account key in repo | none |
| Public Play Store listing (`https://play.google.com/store/apps/details?id=…`) | **HTTP 404** for `com.bookrio`, `com.bookiro`, `com.bookrio.play`, `com.bookiro.play` |
| Production signing configured | no (`:app:bundlePlaystoreRelease` is debug-signed, `CN=Android Debug`) |

=> **No public Play listing exists for any of these ids.** Whether a *draft/internal*
app exists in Play Console cannot be determined from this environment — **UNKNOWN**.
The owner should confirm in Play Console → "All apps" before the id is locked in
(a published Play package name is permanent).

---

## 2. Current vs. desired

| | Current (built artifacts) | Desired for first Play publication |
|---|---|---|
| `full` release | `com.bookrio` | **`com.bookiro`** |
| `playstore` release | `com.bookrio.play` | **`com.bookiro.play`** |
| `full` debug | `com.bookrio.debug` | `com.bookiro.debug` |
| `playstore` debug | `com.bookrio.play.debug` | `com.bookiro.play.debug` |

Because no Play upload exists and the current ids still use `com.bookrio`, the
correction **is in scope** — but only as a plan until approved.

---

## 3. Proposed change (NOT applied)

One identifier changes; nothing else. In `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.bookiro"   // was "com.bookrio"
    // namespace stays com.bookrio
}
```

Flavors/build types are unchanged and inherit the new base:

- `full` → `com.bookiro`
- `playstore` (`+".play"`) → `com.bookiro.play`
- `debug` (`+".debug"`) → `com.bookiro.debug`, `com.bookiro.play.debug`

**Deliberately NOT part of this change** (per owner: no cosmetic source rename):
namespace, packages, class names, drawable names, DB/DataStore/prefs names, icons.
These stay `com.bookrio.*` / `shelf*`.

A **companion fix is required in the same change** (see §5): the backup/data-extraction
rules must exclude the *new* plaintext FTP prefs filename.

---

## 4. Installation & data-migration implications (must be understood before applying)

Changing `applicationId` makes Android treat the app as a **different app**:

1. **New sandbox.** The new app uses `/data/data/com.bookiro/` (app-private DB, prefs,
   files, Keystore keys under the new UID). The old `com.bookrio` sandbox is **not
   accessible** to it.
2. **Old install is not removed.** `com.bookrio` stays installed as a separate app with
   its data intact. This plan **does not uninstall, clear data, or delete any
   database** — consistent with the owner's requirement.
3. **No automatic migration.** App-private books (`filesDir/books`), the `shelf.db`
   library/progress/bookmarks/highlights, FTP/SMB/WebDAV/Calibre credentials and
   preferences do **not** move. SAF-imported books whose URIs are still granted the old
   app are not automatically granted to the new app.
4. **Keystore-bound data is unreadable across apps.** Room `password_encrypted`
   ciphertext and EncryptedSharedPreferences are bound to the old app's Keystore, so even
   a copied DB cannot be decrypted by the new app.
5. **Google auto-backup is keyed by package name.** `com.bookiro` will not restore the
   `com.bookrio` backup set (and vice-versa).
6. **Migration options for existing sideload testers:**
   - Preferred: re-import the original files (the source books are usually still in the
     user's SAF folder / SD card) and re-add server sources.
   - Secondary: in-app **Export database** (`SettingsScreen.exportDb` writes
     `shelf.db` → `getExternalFilesDir/shelf_backup.db`) from the old app, then **Import**
     in the new app. Caveats: the old export path is under
     `Android/data/com.bookrio/files/`, which is hard to reach with the SAF picker on
     Android 11+; credentials stay unusable across apps (§4.4); and DB schema must match.
   - The old app must be left installed until the user has migrated, then can be removed
     by the user (not by us).
7. **Debug/dev installs** (`com.bookrio.debug`) are likewise orphaned; developers get a
   fresh install.

**Bottom line:** the correction is safe to apply *before the first Play upload*, but any
existing `com.bookrio` installation (GitHub-release sideloads) will need a manual
re-import. That trade-off must be accepted by the owner.

---

## 5. Safety / privacy side-effect to fix in the same change

`FtpServerStore` builds its legacy plaintext prefs file as
`"${appContext.packageName}_ftp_plain"` → currently `com.bookrio_ftp_plain.xml`, which is
**excluded from cloud backup and device transfer** in
`app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` (hardcoded
`com.bookrio_ftp_plain.xml`).

After the applicationId change, that file becomes `com.bookiro_ftp_plain.xml`, so the
hardcoded excludes would **no longer match** and the plaintext FTP credentials fallback
could be included in backup/transfer. Required companion edits (do **not** apply yet):

- `app/src/main/res/xml/backup_rules.xml`: add
  `<exclude domain="sharedpref" path="com.bookiro_ftp_plain.xml" />`
- `app/src/main/res/xml/data_extraction_rules.xml`: add the same exclude under both
  `cloud-backup` and `device-transfer`.
- (Optional hardening) make the exclusion package-independent, or drop the plaintext
  fallback in favour of a fail-closed error.

---

## 6. Documentation updates (after approval and application)

Currently several docs already say `com.bookiro*` (the desired id) while the built
artifacts say `com.bookrio*`. Until this plan is approved, the docs are corrected to the
**actual** artifacts (`com.bookrio*`) and point here. After approval + rebuild they must
be updated to `com.bookiro*`:

- `docs/BUILD_VARIANTS.md` (applicationId table + debug-id note)
- `docs/PLAY_VARIANT_DECISION.md` (§1 table, §3, §4)
- `docs/PLAY_GO_NO_GO.md` (build-values table + Play Console steps)
- `docs/MONETIZATION_MAP.md` (§4.1 interface package stays `com.bookrio.*`; §6 app-id note)
- `docs/RELEASE_SIGNING.md` (flavor packaging notes — no id change needed, verify)
- `README.md` (flavor table)

---

## 7. CI / release implications

- `tools/verify_playstore_variant.sh` is applicationId-independent — no change.
- `.github/workflows/*` do not hardcode the id — no change.
- GitHub release assets (`Bookiro-*.apk`) built from the new commit will have the new id.
- The old `v1.0.0-test` release keeps the old id; do not overwrite it silently — cut a new
  tag (e.g. `v1.0.0-test2`) and note the id change in the release notes.

---

## 8. Verification after applying (do not run yet)

```bash
BOOKIRO_ALLOW_DEBUG_SIGNING=true ./gradlew :app:assembleFullRelease :app:assemblePlaystoreRelease
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/full/release/app-full-universal-release.apk      | grep '^package:'
#   expect com.bookiro
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/playstore/release/app-playstore-universal-release.apk | grep '^package:'
#   expect com.bookiro.play
tools/verify_playstore_variant.sh app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab   # still OK
# confirm backup excludes now cover com.bookiro_ftp_plain.xml
grep -R 'ftp_plain' app/src/main/res/xml/
```

---

## 9. Rollback

The change is a single line; revert `applicationId` to `com.bookrio` and restore the
backup-rule edit. Because the new id would be a separate install, rolling back does not
recover data created under the new id — another reason to lock the id before first
upload.

---

## 10. Approval checklist (owner)

- [ ] Confirm there is **no** draft/internal Play app for `com.bookrio`/`com.bookrio.play`
      (Play Console → All apps).
- [ ] Approve changing the applicationId to **`com.bookiro` / `com.bookiro.play`**.
- [ ] Approve that existing `com.bookrio` sideload installs will need a manual re-import
      (no automatic migration), and that we **will not** uninstall/clear/delete anything.
- [ ] Approve the companion backup-rule fix (§5).
- [ ] Confirm `versionCode` policy for the first Play upload (current value is `11`).
- [ ] Confirm the package name is locked **before** the first Play upload (it is permanent
      afterwards).
