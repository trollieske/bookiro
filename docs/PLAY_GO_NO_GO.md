# Bookiro Android — Play GO/NO-GO (playstore-flavor, re-verifisert)

**Verdikt: NO-GO for Google Play (inkl. intern testing-track).**

- Branch: `release/playstore-variant-rc`
- Commit: `accb368b00628be2c4092f1cac22ffedce036bac` (`accb368`)
- Dato: 2026-10-07
- Miljø: JDK 17, Gradle 8.11.1 (wrapper), macOS/arm64, Android build-tools 36.0.0
- Arbeidstre: ren. **Ingen kode endret, ingenting committet** i denne re-verifiseringen
  (kun dette dokumentet er skrevet).
- Metode: playstore-artefaktene (release-AAB + release-APK) inspisert med
  `tools/verify_playstore_variant.sh`, `unzip -l`, `aapt2 dump badging`, merged
  release-manifest, `zipalign`, `jarsigner`/`apksigner`; tester og lint kjørt for
  playstore-flavoren. Alle verdier under er målt, ikke antatt.

**Hvorfor NO-GO:** selv om Play-varianten nå er torrent-fri, gjenstår
**produksjonssignering** (artefaktene er debug-signert), og **device-aksept**,
**data-safety-portene** og **privacy policy-URL** er fortsatt **UKJENT**. Grønne
tester alene er ikke GO.

---

## 0. Endringer siden forrige rapport (`4af538c`)

| Tidligere funn | Nå |
|---|---|
| «Ingen Play-variant finnes» | **Løst** — `full`/`playstore`-flavors implementert |
| Torrent i release-artefaktet | **Løst** — playstore AAB/APK har ingen `libtorrent4j`/torrent-klasser/ressurser |
| `rdr_error_garbage_torrent`-melding | **Løst** — fjernet, generisk melding i begge varianter |
| Lint krasjet (`UseTomlInstead`) med to flavors | **Løst** — kun den sjekken er deaktivert; lint grønn for begge |
| Produksjonssignering | **Fortsatt FEIL** |
| Device-aksept / data-safety / privacy-URL / F2-disclosure | **Fortsatt UKJENT** |

---

## 1. Faktiske bygg-verdier — playstore (målt)

Fra `aapt2 dump badging` på `app-playstore-universal-release.apk` og merged
release-manifest
(`app/build/intermediates/merged_manifests/playstoreRelease/processPlaystoreReleaseManifest/universal/AndroidManifest.xml`):

| Felt | Full (`com.bookiro`) | **Playstore** |
|---|---|---|
| `applicationId` | `com.bookrio` | **`com.bookrio.play`** |
| `versionCode` | `11` | **`11`** |
| `versionName` | `1.0.0-readium9` | **`1.0.0-readium9-play`** |
| `minSdk` | `26` | **`26`** |
| `targetSdk` | `36` | **`36`** |
| `compileSdk` | `36` | **`36`** |

Merged playstore release-manifest:

```
package="com.bookrio.play"
android:minSdkVersion="26"
android:targetSdkVersion="36"
android:versionCode="11"
android:versionName="1.0.0-readium9-play"
```

Ingen `android:debuggable`/`android:testOnly` i release-manifestet.

---

## 2. Torrent-ekskludering — verifisert på playstore-artefaktene

| Sjekk | Playstore | Full |
|---|---|---|
| `tools/verify_playstore_variant.sh` (AAB) | **OK** (exit 0) | **FAIL** — `libtorrent4j` finnes (exit 1) |
| `tools/verify_playstore_variant.sh` (APK) | **OK** (exit 0) | (ikke kjørt) |
| `libtorrent4j.so` i AAB (`unzip -l`) | **0 treff** | 3 treff: arm64 15 883 152 B, v7a 13 734 884 B, x86_64 16 358 808 B |
| `libtorrent4j.so` i APK (`unzip -l`) | **ingen** | 3 treff |
| Native `.so` i playstore AAB | kun `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so` (arm64-v8a, armeabi-v7a, x86, x86_64) | + `libtorrent4j.so` |
| `com/bookrio/torrent/`-klasser (DEX) | **ingen** | ja |
| torrent-ressurser/strenger (`aapt2 dump resources`) | **ingen** | ja |

AAB-størrelse: playstore **32 607 475 B** vs. full **50 802 399 B** (torrent utgjør
~18 MB). Universal release-APK: playstore **16 346 366 B** vs. full **62 755 780 B**.

FTP/SMB/WebDAV/Calibre/podkast er med i begge varianter, som bestemt
(`docs/PLAY_VARIANT_DECISION.md` §1).

**Kjent inert rest:** den delte `data`-modulen beholder Room-entiteten/DAO-en
`torrent_downloads` (tabellen kan aldri få rader uten `:torrent`; ingen P2P/DHT/
tracker-kode, ingen native lib). Internal-only, ikke brukervendt. Se
`docs/BUILD_VARIANTS.md`.

---

## 3. Permissions i merged playstore release-manifest

Fra `aapt2 dump badging` + merged manifest — **identisk sett** med full (11 totalt,
10 app + 1 auto):

| Permission | Playstore | Begrunnet? | Grunnlag |
|---|---|---|---|
| `INTERNET` | ja | Ja | F1–F9 (metadata, podkast, bruker-servere) |
| `ACCESS_NETWORK_STATE` | ja | Ja | sync/torrent(–)/podkast-constraints |
| `ACCESS_WIFI_STATE` | ja | Ja | LAN-discovery leser `WifiManager.connectionInfo` |
| `FOREGROUND_SERVICE` | ja | Ja | media + WorkManager |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ja | Ja | player/podcast |
| `FOREGROUND_SERVICE_DATA_SYNC` | ja | Ja | WorkManager-arbeidere |
| `POST_NOTIFICATIONS` | ja | Ja | media-/overføringsvarsler |
| `VIBRATE` | **ja — fortsatt der** | **Nei — ubegrunnet/ubrukt** | `grep -rn "VIBRATE\|vibrat"` i `app/core/data/library`-koden: **0 kode-treff** |
| `WAKE_LOCK` | ja | Ja | `setWakeMode` + WorkManager |
| `RECEIVE_BOOT_COMPLETED` | ja | Ja | WorkManager reschedule |
| `com.bookrio.play.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | ja | Ja (auto) | signature-level, androidx core |

- **`MANAGE_EXTERNAL_STORAGE`: fraværende** (`grep -c` = 0). Ingen storage/lokasjon/
  kamera/mikrofon/kontakter/`QUERY_ALL_PACKAGES`/eksakte alarmer.
- **Foreground service-typer:** `mediaPlayback` (player + podkast) og `dataSync`
  (WorkManager), med matchende permissions.
- **`POST_NOTIFICATIONS`:** deklarert; runtime-forespørsel kun i enkelte
  kilde-skjermer (UX-gap, ikke policy-brudd).

---

## 4. Signering

- `hasProductionSigning` = **false** i dette miljøet (ingen `BOOKIRO_*`-verdier /
  `keystore.properties`). Release-bygget bruker derfor **debug-nøkkelen**.
- **Målt identitet (playstore release):**
  - APK: `Signer #1 certificate DN: CN=Android Debug, O=Android, C=US`,
    SHA-256 `c5269dcee05b8dbbfef756b90f1e9c9fef3030666516f7997fe5a8686ff6f88a`
  - AAB: `jarsigner` → `Signed by "CN=Android Debug, O=Android, C=US"`, `jar verified.`
  - => **ikke Play-opplastbar.**
- **Ingen nøkler/passord i git** (re-verifisert):
  `git ls-files | grep -iE '\.(jks|keystore|p12|pepk)$|keystore\.properties'` → **ingen treff**.
- `:app:bundlePlaystoreRelease` uten creds feiler fail-closed (krever
  `BOOKIRO_ALLOW_DEBUG_SIGNING=true` for et TEST-artefakt).

---

## 5. R8 / minifisering

- `release`: `isMinifyEnabled = true`, `isShrinkResources = true`. Playstore-AAB og
  -APK er bygget med R8 (32.6 MB AAB vs. 16.3 MB universal APK).
- Mapping genereres per buildtype/flavor:
  `app/build/outputs/mapping/playstoreRelease/mapping.txt` (bevart av AGP).
- **Gap:** ingen automatisert mapping-opplasting til Play (må lastes opp manuelt).

---

## 6. Kjørte kommandoer — faktiske resultater (playstore)

| Kommando | Resultat | Exit |
|---|---|---|
| `tools/verify_playstore_variant.sh <playstore AAB>` | **OK: no torrent functionality found** | 0 |
| `tools/verify_playstore_variant.sh <playstore APK>` | **OK** | 0 |
| `tools/verify_playstore_variant.sh <full AAB>` | **FAIL: libtorrent4j present** (beviser at sjekken virker) | 1 |
| `./gradlew :app:testPlaystoreDebugUnitTest` | **BUILD SUCCESSFUL** — 3 tester, 0 feil, 0 errors, 0 skipped | 0 |
| `./gradlew :app:lintPlaystoreRelease` | **BUILD SUCCESSFUL** — 0 errors, **93 warnings**, 1 info (95 issues) | 0 |
| `zipalign -c -P 16 -v 4 <playstore APK>` | **Verification successful** | 0 |

Lint-funn (playstore, alle warnings): `GradleDependency` 53, `PluralsCandidate` 26,
`UnusedResources` 5, `TrustAllX509TrustManager` 3, `Typos` 2, `ExportedService` 2,
`InsecureBaseConfiguration` 1, `ObsoleteSdkInt` 1, `AutoboxingStateCreation` 1.
(`UseTomlInstead` er deaktivert fordi AGP-sjekken krasjer med to flavors.)

**Merk:** app-modulen har kun **3 JVM-tester** (`HomeLogicTest`). Det er tynn dekning
for en release-port; den brede testmassen ligger i bibliotek-modulene
(`reader/player/library/...`) og kjøres ikke av `:app:testPlaystoreDebugUnitTest`.

---

## 7. Krav-tabell (playstore)

| # | Krav | Status | Bevis |
|---|---|---|---|
| 1 | applicationId/versionCode/versionName/minSdk/targetSdk/compileSdk riktige | **OK** | `aapt2 dump badging` + merged manifest: `com.bookrio.play`, 11, `1.0.0-readium9-play`, 26/36/36 |
| 2 | Play-variant valgt og anvendt | **OK** | `app/build.gradle.kts` flavors; `docs/BUILD_VARIANTS.md` |
| 3 | Torrent ekskludert fra Play-artefakt | **OK** | verify-script OK; `unzip -l` = 0 `libtorrent4j`; 0 torrent-klasser/ressurser |
| 4 | FTP/SMB/WebDAV med (per design) | **OK** | i begge varianter |
| 5 | Ingen `MANAGE_EXTERNAL_STORAGE` | **OK** | merged manifest grep = 0 |
| 6 | Alle permissions begrunnet | **FEIL (mindre)** | `VIBRATE` fortsatt der, 0 kode-treff |
| 7 | FGS-typer + permissions konsistente | **OK** | `mediaPlayback` + `dataSync` med matchende permissions |
| 8 | `POST_NOTIFICATIONS` håndtert | **OK (UX-gap)** | deklarert; runtime kun i noen skjermer |
| 9 | Produksjonssignering konfigurert | **FEIL** | release-artefaktene er `CN=Android Debug` |
| 10 | Ingen nøkler/passord i git | **OK** | `git ls-files` = 0 treff |
| 11 | Release minifisert med R8 | **OK** | playstore-AAB produsert (32.6 MB) |
| 12 | Mapping-fil bevart | **OK** | `mapping/playstoreRelease/mapping.txt` |
| 13 | Automatisk mapping-opplasting til Play | **FEIL (mindre)** | ingen Play Publisher/upload-konfig |
| 14 | Playstore-enhetstester grønne | **OK** | `:app:testPlaystoreDebugUnitTest` 3/3 |
| 15 | Lint uten errors (playstore) | **OK** | `:app:lintPlaystoreRelease` 0 errors (93 warnings) |
| 16 | 16 KB page-size | **OK** | `zipalign -c -P 16 -v 4` → Verification successful |
| 17 | Device-aksept: leser (EPUB), Auto/DHU, cold start, process death, rotation, offline, upgrade | **UKJENT** | ikke utført |
| 18 | Data-safety-porter løst/akseptert | **UKJENT** | `docs/REGRESSION_AUDIT.md` §1–§3, §7 |
| 19 | Privacy policy-URL finnes | **UKJENT/FEIL** | ingen policy-URL i repo/kode/strings |
| 20 | F2 (automatisk Audible/audnex) disclosert i Play-skjema | **UKJENT** | eier-valg dokumentert i docs, ikke bekreftet i Play |
| 21 | 16 KB / minSdk / andre Play-formkrav | **OK** | se #16 |

---

## 8. Blokkere — løst vs. gjenstår

**Løst (siden `4af538c`):**
- Play-varianten finnes og er torrent-fri (ingen `libtorrent4j`, ingen
  `com.bookrio.torrent`-klasser, ingen torrent-ressurser/-strenger).
- Readerens torrent-spesifikke feilmelding fjernet.
- Lint grønn for begge flavors (AGP `UseTomlInstead`-krasj workaround).
- 16 KB page-size verifisert på playstore-APK.

**Gjenstår (blokkerende for GO):**
1. **KRITISK — ingen produksjonssignert artefakt.** Release-artefaktene er
   debug-signert (`CN=Android Debug`). *Løsning: registrer upload-keystore, injiser de
   fire `BOOKIRO_*`-secretene, bygg `:app:bundlePlaystoreRelease`, verifiser at
   cert ≠ "Android Debug".*
2. **HØY (UKJENT) — device-aksept.** Reader på ekte EPUB, Android Auto/DHU, cold
   start, process death, rotation, offline, oppgradering fra forrige publiserte bygg.
3. **HØY (UKJENT) — data-safety-porter** ikke avgjort: stille kollaps av to ulike
   EPUB-er med lik tittel+forfatter; hard-sletting av audiobook-fragmenter; feil-splitt
   av kapittelnavn-filer; online-cover kan overskrive embedded cover; `deleteDatabase()`
   -fallback. Krever eier-aksept eller fiks.
4. **MIDDELS (UKJENT/FEIL) — privacy policy-URL** mangler, og F2-disclosure er ikke
   bekreftet i Play-skjemaet. Play krever URL når appen behandler data (F1/F2/F3/F4).
5. **LAV — `VIBRATE`** permission er ubegrunnet/ubrukt; fjern eller dokumenter.
6. **LAV — lint-advarsler av sikkerhetsrelevans** (`TrustAllX509TrustManager`,
   global `cleartextTrafficPermitted="true"`).
7. **LAV — mapping-opplasting til Play** er ikke automatisert.

**Ingen av disse er UKJENT-avklart, derfor fortsatt NO-GO.**

---

## 9. Neste steg for intern testing-track i Play Console (playstore-flavor)

1. **Eier-beslutninger:** bekreft pakke-id `com.bookiro.play`, `versionCode`-policy, og
   F2-disclosure (automatisk Audible/audnex-oppslag).
2. **Upload-keystore** opprettes offline, legges i secret manager.
3. **Registrer Play App Signing** når appen opprettes i Play Console.
4. **Bygg produksjonssignert Play-AAB:**
   ```
   BOOKIRO_KEYSTORE_PATH=… BOOKIRO_KEYSTORE_PASSWORD=… BOOKIRO_KEY_ALIAS=… \
   BOOKIRO_KEY_PASSWORD=… ./gradlew :app:bundlePlaystoreRelease
   ```
   Verifiser at `jarsigner -verify --certs` **ikke** sier `Android Debug`.
5. **Verifiser artefaktet:** `tools/verify_playstore_variant.sh <AAB>` → OK; last opp
   `mapping/playstoreRelease/mapping.txt`.
6. **App content:** privacy policy-URL, Data Safety-skjema
   (`docs/PRIVACY_DATA_FLOW.md` §8), innholdsrating, målgruppe, «No ads», ingen konto.
7. **Internal testing-track:** last opp AAB, legg til testere, rull ut.
8. **Ikke promoter** til production før blokkere 1–4 er lukket.

---

### Reproduksjon (playstore)

```bash
tools/verify_playstore_variant.sh app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab   # OK
tools/verify_playstore_variant.sh app/build/outputs/apk/playstore/release/app-playstore-universal-release.apk  # OK
tools/verify_playstore_variant.sh app/build/outputs/bundle/fullRelease/app-full-release.aab             # FAIL (torrent present)
unzip -l app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab | grep '\.so$'
unzip -l app/build/outputs/bundle/fullRelease/app-full-release.aab       | grep libtorrent4j
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/playstore/release/app-playstore-universal-release.apk
./gradlew :app:testPlaystoreDebugUnitTest :app:lintPlaystoreRelease
$ANDROID_HOME/build-tools/36.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/playstore/release/app-playstore-universal-release.apk
jarsigner -verify -verbose -certs app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab
```
