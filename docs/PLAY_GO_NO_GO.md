# Bookiro Android — GO/NO-GO (Bookiro-id, data-safety RC)

**Base:** branch `release/playstore-variant-rc`, commit `eec1c7c` **plus uncommitted
working-tree changes** (applicationId correction + data-safety fixes). The candidate was
not built from a clean commit, so `HEAD` alone does not identify its source.
**Dato:** 2026-10-08. **Miljø:** JDK 17, Gradle 8.11.1, macOS/arm64, build-tools 36.0.0.

## Verdikter (separate)

| Gate | Verdikt | Grunnlag |
|---|---|---|
| **Lokal / enhets-test** | **READY TO BEGIN** | begge flavors bygger; **406 JVM-tester, 0 feil**; Playstore-lint 0 errors; 16 KB OK; data-tap-fikser inne. Ingen device-pass utført ennå. |
| **Play internal testing-upload** | **NOT READY** | ingen produksjonssignert AAB (kun debug/**TEST**); upload-nøkkel + Play App Signing ikke satt opp. |
| **Produksjon** | **NOT READY** | device-aksept, data-safety-eierbeslutninger, privacy-URL og F2-disclosure gjenstår. |

**Hvorfor ikke GO:** ingen produksjonssignert artefakt, og device-/data-safety-portene er
fortsatt **UKJENT**. Grønne tester er ikke GO.

---

## 1. Faktiske bygg-verdier (målt med `aapt2 dump badging`)

| Felt | Full | Playstore |
|---|---|---|
| `applicationId` | **`com.bookiro`** | **`com.bookiro.play`** |
| `versionCode` | `11` | `11` |
| `versionName` | `1.0.0-readium9` | `1.0.0-readium9-play` |
| `minSdk` / `targetSdk` / `compileSdk` | `26` / `36` / `36` | `26` / `36` / `36` |

Debug: `com.bookiro.debug` / `com.bookiro.play.debug`. Namespace forblir `com.bookrio.*`
(ingen kosmetisk pakke-rename). Ingen `debuggable`/`testOnly` i release.

---

## 2. Torrent-ekskludering (verifisert)

- `tools/verify_playstore_variant.sh` på Playstore-AAB: **OK** (ingen `libtorrent4j`, ingen
  `com.bookrio.torrent`-klasser, ingen torrent-ressurser).
- Full-AAB inneholder `libtorrent4j.so` for arm64/armeabi-v7a/x86_64 (3 treff).
- AAB-størrelse: Playstore ~? vs full ~? (bygg målt i denne kjøringen).

---

## 3. Permissions

Identisk sett for begge flavors, **uten `VIBRATE`** (fjernet — ingen kode satte et
vibrasjonsmønster og ingen kanal aktiverte vibrasjon):
`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` + auto-generert
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
Ingen `MANAGE_EXTERNAL_STORAGE`/storage/lokasjon/kamera. FGS-typer: `mediaPlayback` +
`dataSync` med matchende permissions.

---

## 4. Signering

- Debug/TEST-signert (`CN=Android Debug`) for både full og playstore release i denne
  kjøringen (eksplisitt `BOOKIRO_ALLOW_DEBUG_SIGNING=true`, kun for lokal TEST).
- Produksjonssignering forblir fail-closed: `:app:bundlePlaystoreRelease` uten creds feiler.
- Ingen nøkler/passord i git.

---

## 5. R8 / mapping

- `isMinifyEnabled = true`, `isShrinkResources = true`.
- **R8-mappingen er innebygd i AAB-en**: `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`.
  Tidligere antakelse om at manuell opplasting kreves er **feil** — AAB-en bærer mappingen.
- Lokale mapping-filer finnes også under `app/build/outputs/mapping/<variant>Release/`.

---

## 6. Data-safety-fikser anvendt (Phase 3)

| Område | Før | Nå | Bevis |
|---|---|---|---|
| DB open | `fallbackToDestructiveMigration()` + auto `deleteDatabase` | ingen destruktiv migrasjon/auto-sletting; `databaseError`-state + error/retry-UI; reset kun ved eksplisitt bekreftelse | `ShelfDatabase.kt`, `ShelfApplication.kt`, `DatabaseRecoveryPolicy(+Test)` |
| Dedup | kollapset alt med lik tittel+forfatter (og på tvers av format) | kun bevist identitet (samme fil-URI/sti, eller samme ISBN + type + format) | `DuplicateRepairTest` |
| Dedup-annotasjoner | kun progress flyttet | progress (overskrives ikke) + bokmerker + highlights flyttes til survivor | `DuplicateRepairTest` |
| Audiobook-consolidate | hard-slettet duplikat (`delete`) | `softDelete` + annotasjoner bevart | `AudiobookRepairTest` |
| Audiobook-split | splittet alle kapittelnavn i én mappe | kun når sporene spenner ≥2 foreldremapper; én-mappe/SAF urørt | `AudiobookRepairTest` |
| Metadata/cover | enhver scoret treff kunne vinne | minimums-match (tittel-innhold eller ISBN); mismatch → ingen oppdatering | `MetadataResultSafetyTest` |
| VIBRATE | deklarert, ubrukt | fjernet | manifest |
| Sample-bøker | «Load sample books» importerte sub-1 KB stub-er som ikke kunne leses | **fjernet** (UI, worker-gren, `importAssetsSamples`, `SampleBooks`/`SampleData`, død seed-data, strenger, assets) | verifisert på emulator: Import-skjermen viser ingen sample-rad |
| In-book søk | viste «No matches found» før et søk var kjørt | viser resultatet først etter et fullført søk (`hasSearched`-flagg) | verifisert på emulator: 5 treff etter Search, ingen feilmelding før |

**Ikke gjort / utsatt:** full transaksjonell omslutning av audiobook-merge (Room
`withTransaction` er ikke testbar i JVM-harnesset); full regresjon for manuelle
cover-overrides (ingen egen «manual override»-flagg funnet — dokumentert som UKJENT).

---

## 7. Kjørte kommandoer / resultater

| Kommando | Resultat |
|---|---|
| `:app:assembleFullDebug :app:assemblePlaystoreDebug` | **SUCCESS** |
| `:app:bundleFullRelease :app:bundlePlaystoreRelease` (TEST-signering) | **SUCCESS** |
| full regresjonssuite (12 tasks, alle moduler) | **406 tester, 0 feil, 0 errors** |
| `:app:lintPlaystoreRelease` | **0 errors**, 94 warnings, 1 info |
| `tools/verify_playstore_variant.sh` (Playstore AAB) | **OK** |
| full AAB `libtorrent4j` | 3 treff |
| `zipalign -c -P 16` (Playstore APK) | **successful** |
| ELF PT_LOAD-align (alle `.so`) | `0x4000` (16 KB-safe) |
| AAB mapping-metadata | `proguard.map` innebygd |

---

## 8. Krav-tabell

| # | Krav | Status | Bevis |
|---|---|---|---|
| 1 | applicationId/version/sdk riktige | **OK** | `aapt2`: `com.bookiro` / `com.bookiro.play`, vc11, 26/36/36 |
| 2 | Play-variant finnes og er torrent-fri | **OK** | verify-script OK; full har `libtorrent4j` |
| 3 | Ingen `MANAGE_EXTERNAL_STORAGE` | **OK** | manifest grep = 0 |
| 4 | `VIBRATE` fjernet | **OK** | aapt2 permissions = 0 treff |
| 5 | FGS-typer/permissions konsistente | **OK** | manifest |
| 6 | Produksjonssignering | **FEIL** | debug/TEST-signert |
| 7 | Ingen nøkler i git | **OK** | `git ls-files` = 0 |
| 8 | R8 + mapping bevart/innebygd | **OK** | AAB `proguard.map` |
| 9 | Enhetstester | **OK** | 406/406 |
| 10 | Lint (Playstore) | **OK** | 0 errors |
| 11 | 16 KB | **OK** | zipalign + ELF 0x4000 |
| 12 | DB-tap-fallback fjernet + test | **OK** | `DatabaseRecoveryPolicyTest` |
| 13 | Dedup kollapser ikke distinkte bøker | **OK** | `DuplicateRepairTest` |
| 14 | Audiobook hard-sletting fjernet | **OK** | `AudiobookRepairTest` |
| 15 | Metadata-mismatch avvises | **OK** | `MetadataResultSafetyTest` |
| 16 | Device-aksept (15 scenarier) | **DELVIS (emulator)** | launcher/onboarding/Home, torrent-tile, EPUB-render + ≥20 sidevendinger + TOC + bokmerke-save, rotasjon, resume, offline, gjentatt import: PASS; søk INCONCLUSIVE; audiobook/kilder/Auto/cert/FileProvider: NOT TESTED. Se `docs/INTERNAL_TEST_ACCEPTANCE.md` |
| 17 | Data-safety-eierbeslutninger | **UKJENT** | — |
| 18 | Privacy policy-URL + F2-disclosure | **UKJENT/FEIL** | ingen URL i repo/kode |
| 19 | Transaksjonell audiobook-merge | **DELVIS** | softDelete + annotasjoner; ikke Room-transaksjon |
| 20 | Manuell cover-override-beskyttelse | **UKJENT** | ingen egen override-flagg funnet |

---

## 9. Gjenstående blokkere

1. **KRITISK — produksjonssignert AAB.** Registrer upload-keystore + Play App Signing, bygg
   `:app:bundlePlaystoreRelease` med `BOOKIRO_*`-secrets, verifiser at cert ≠ "Android Debug".
2. **HØY (DELVIS) — device-aksept.** Emulator-pass (2026-10-08) dekket launcher/onboarding/Home, torrent-tile per flavor, EPUB-render + ≥20 sidevendinger + TOC + bokmerke-save + in-book søk, rotasjon, resume, offline og gjentatt import (PASS). Gjenstår: audiobook-avspilling (kun stub-er i samples → sample-funksjonen er nå fjernet), podkast↔lyd, kilder/avbrutt overføring, Android Auto/DHU, FileProvider-deling, self-signed cert, og same-id-oppgradering.
3. **HØY (UKJENT) — data-safety-eierbeslutninger** (privacy-URL, F2-disclosure).
4. **MIDDELS — transaksjonell audiobook-merge** ikke fullt ut; soft-delete + annotasjoner er
   på plass, men en krasj midt i en merge kan fortsatt etterlate delvis tilstand.

---

## 10. Neste steg for intern testing (Play Console)

Se `docs/APPLICATION_ID_PLAN.md` §5 og Phase 6-guiden i oppgaven. Kort: opprett Play-app
under `com.bookiro.play`, lag upload-nøkkel i Android Studio, konfigurer `BOOKIRO_*`,
bygg signert `:app:bundlePlaystoreRelease`, enrol i Play App Signing, last opp til internal
testing. Intern testing tilfredsstiller **ikke** kravet om closed testing for nye personlige
utviklerkontoer. Ikke promoter til produksjon før blokkere 1–3 er lukket.