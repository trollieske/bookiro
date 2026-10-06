# Bookiro Android — Play GO/NO-GO

**Verdikt: NO-GO for Google Play (inkl. intern testing-track).**

- Branch: `release/bookiro-android-rc`
- Commit: `4af538c35c27aaa6a391be217a5e7b6fcf222ef5` (`4af538c`)
- Dato: 2026-10-06
- Miljø: JDK 17 (openjdk 17.0.20.1), Gradle 8.11.1 (wrapper), macOS/arm64
- Arbeidstre: ren (`git status --short` tom). Ingen kode endret, ingenting committet.
- Metode: `app/build.gradle.kts` + `app/src/main/AndroidManifest.xml` lest direkte;
  merged release-manifest, release-AAB, R8-mapping og lint-resultater inspisert;
  10 dokumenter i `docs/` lest. Alle verdier under er målt, ikke antatt.

**Hvorfor NO-GO:** det finnes ingen produksjonssignert Play-artefakt
(`:app:bundleRelease` feiler fail-closed), og flere påkrevde porter er dokumentert
**UKJENT/uverifisert** (device-aksept, eier-valg, data-safety-aksept). Grønne
enhetstester alene er ikke GO.

> **Oppdatering 2026-10-06 (etter `4af538c`):** `full`/`playstore`-splitten er nå
> implementert (se `docs/BUILD_VARIANTS.md`). Krav #2 og #3 under er nå **OK**:
> playstore-flavoren finnes, og den R8-minifiserte AAB-en er verifisert torrent-fri av
> `tools/verify_playstore_variant.sh`. Verdiktet er fortsatt **NO-GO** fordi
> produksjonssignering, device-aksept og data-safety/eier-portene gjenstår.

---

## 1. Faktiske bygg-verdier (målt)

| Felt | Verdi | Kilde |
|---|---|---|
| `applicationId` | `com.bookrio` | `app/build.gradle.kts` linje 49 |
| debug-suffiks | `.debug` → `com.bookrio.debug` | `app/build.gradle.kts` linje 88 |
| `versionCode` | `11` | `app/build.gradle.kts` linje 52 |
| `versionName` | `1.0.0-readium9` | `app/build.gradle.kts` linje 53 |
| `minSdk` | `26` | `app/build.gradle.kts` linje 50 |
| `targetSdk` | `36` | `app/build.gradle.kts` linje 51 |
| `compileSdk` | `36` | `app/build.gradle.kts` linje 47 |
| `namespace` | `com.bookiro` | `app/build.gradle.kts` linje 46 |

Bekreftet i **merged release-manifest**
(`app/build/intermediates/merged_manifests/release/processReleaseManifest/universal/AndroidManifest.xml`):

```
package="com.bookrio"
android:minSdkVersion="26"
android:targetSdkVersion="36"
android:versionCode="11"
android:versionName="1.0.0-readium9"
```

`targetSdk 36` oppfyller Plays krav (35+). Ingen `debuggable`/`testOnly` i
release-manifestet.

---

## 2. Play-variant og ekskludering av torrent/FTP/SMB/WebDAV

**Det finnes ingen Play-variant i bygget.** `grep -riE 'productFlavors|flavorDimensions|playstore|play-store|"full"'`
over `app/build.gradle.kts`, `build.gradle.kts` og `settings.gradle.kts` gir **ingen treff**.
`docs/PLAY_VARIANT_DECISION.md` beskriver en *foreslått* `playstore`-flavor, men
sier eksplisitt: **"PROPOSAL — NOT APPLIED to `release/bookiro-android-rc`"**.
RC-en er full/private-bygget.

| Modul | I RC-artefaktet i dag | I den (ikke-anvendte) Play-varianten |
|---|---|---|
| `:torrent` | **inkludert** | fjernet |
| `:ftp` | inkludert | inkludert (uendret) |
| `:smb` | inkludert | inkludert (uendret) |
| `:webdav` | inkludert | inkludert (uendret) |
| `:calibre`, `:podcast` | inkludert | inkludert |

Bevis for at torrent faktisk er i release-artefaktet:

- `app/build.gradle.kts:167` → `implementation(project(":torrent"))`
- `unzip -l app/build/outputs/bundle/release/app-release.aab` viser
  `base/lib/arm64-v8a/libtorrent4j.so` (15.9 MB),
  `base/lib/armeabi-v7a/libtorrent4j.so` (13.7 MB),
  `base/lib/x86_64/libtorrent4j.so` (16.4 MB).

FTP/SMB/WebDAV er ikke ment å fjernes selv i Play-varianten, men de er per nå
ikke ekskludert fra noe bygg fordi det bare finnes ett bygg.

---

## 3. Permissions i manifestet og begrunnelse

Fra `app/src/main/AndroidManifest.xml`; **identisk** sett i merged release-manifest
(10 app-permissions + 1 auto-generert):

| Permission | Begrunnet? | Grunnlag / bevis |
|---|---|---|
| `INTERNET` | Ja | F1–F10 i `docs/PRIVACY_DATA_FLOW.md` (metadata, podkast, bruker-servere, torrent) |
| `ACCESS_NETWORK_STATE` | Ja | tilkoblings-constraints for sync/torrent/podkast |
| `ACCESS_WIFI_STATE` | Ja | LAN-discovery leser `WifiManager.connectionInfo` (F9) |
| `FOREGROUND_SERVICE` | Ja | media + WorkManager |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Ja | `mediaPlayback`-tjenester (player/podcast) har egen permission |
| `FOREGROUND_SERVICE_DATA_SYNC` | Ja | WorkManager `dataSync`-arbeidere har egen permission |
| `POST_NOTIFICATIONS` | Ja | media- og overføringsvarsler (runtime-prompt) |
| `VIBRATE` | **Nei — ubegrunnet/ubrukt** | `grep -rn "VIBRATE\|vibrat"` i `app/src/main`, `core/src/main` ga **ingen kode-treff**. `API36_READINESS.md` §1 sier selv "no direct vibration code … can be dropped" |
| `WAKE_LOCK` | Ja | `setWakeMode` under avspilling + WorkManager |
| `RECEIVE_BOOT_COMPLETED` | Ja | WorkManager reschedule etter reboot |
| `com.bookrio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Ja (auto) | signature-level, lagt til av androidx core |

**MANAGE_EXTERNAL_STORAGE: fraværende.** `grep -c MANAGE_EXTERNAL_STORAGE` på merged
release-manifest = `0`. Ingen `READ/WRITE_EXTERNAL_STORAGE`, ingen
`requestLegacyExternalStorage`, ingen Bluetooth/nærhet/lokasjon/kamera/mikrofon/
kontakter/`QUERY_ALL_PACKAGES`/eksakte alarmer. Import er SAF-basert
(`ACTION_OPEN_DOCUMENT`/`_TREE`), deling via `FileProvider`.

**Foreground service-typer:** `AudiobookPlaybackService` og
`PodcastPlaybackService` → `mediaPlayback` (begge med matching permission);
`androidx.work.impl.foreground.SystemForegroundService` → `dataSync`
(med matching permission). Alle WorkManager-arbeidere sender matchende type på API 34+
per `API36_READINESS.md` §4.

**Notifications:** `POST_NOTIFICATIONS` er deklarert. Runtime-forespørsel skjer kun
ved navigasjon til FTP-/torrent-/Calibre-skjermene (`API36_READINESS.md` §3), så en
import eller bakgrunnssync kan poste varsel uten at tillatelsen er spurt om
(Android 13+ dropper det stille). UX-gap, ikke policy-brudd.

---

## 4. Signering

- Release bruker **aldri** debug-nøkkel med mindre man eksplisitt setter
  `BOOKIRO_ALLOW_DEBUG_SIGNING=true` (`app/build.gradle.kts` linjer 37–84).
- Produksjonsnøkkel leses fra fire verdier: `BOOKIRO_KEYSTORE_PATH`,
  `BOOKIRO_KEYSTORE_PASSWORD`, `BOOKIRO_KEY_ALIAS`, `BOOKIRO_KEY_PASSWORD`
  (env eller ignorert `keystore.properties`). `hasProductionSigning` krever at
  alle fire finnes **og** at nøkkelfilen eksisterer.
- Uten nøkler: enhver release-pakking kaster `GradleException` ved konfigurasjon
  (linje 63–71). V1+V2-signering er slått på for release-configen.

**Ingen nøkler/passord i git (verifisert):**

- `git ls-files | grep -iE '\.(jks|keystore|p12|pepk)$|keystore\.properties'` → ingen treff.
- `keystore.properties` finnes ikke lokalt.
- `.gitignore` linjer 24–29 ekskluderer `keystore.properties`, `*.jks`, `*.keystore`,
  `*.p12`, `*.pepk`.
- `git grep` etter `storePassword`/`keyPassword`/`BOOKIRO_*PASSWORD` utenfor docs gir
  bare *variabelnavn* i `app/build.gradle.kts` og hardkodede **debug**-standarder
  (`"android"`), aldri reelle produksjonspassord.

**Men:** produksjonsnøkkelen er ikke konfigurert i dette miljøet, så ingen
Play-opplastbar artefakt finnes. AAB-en som ble bygget under test-opt-in er signert
`CN=Android Debug, O=Android, C=US` (`jarsigner -verify` → `jar verified.`) og er
**TEST ONLY**.

---

## 5. R8 / minifisering og mapping

- `release` buildtype: `isMinifyEnabled = true`, `isShrinkResources = true`
  (`app/build.gradle.kts` linjer 73–74). `debug`: `isMinifyEnabled = false`.
- R8-mapping produseres og bevares av AGP:
  `app/build/outputs/mapping/release/mapping.txt` = **261 MB, 1 846 426 linjer**,
  hvorav **1 684 443** `a -> b`-mappings. Også `seeds.txt`, `usage.txt`,
  `configuration.txt`, `resources.txt` finnes.
- `app/proguard-rules.pro` har `-assumenosideeffects class android.util.Log`
  (fjerner `v/d/i` fra release-logger).
- **Gap:** det er ingen automatisert opplasting av mapping til Play
  (ingen Play Publisher/Gradle-plugin eller `mappingFileUpload`-konfig).
  Mapping må lastes opp manuelt per release for at krasj skal deobfuseres i Play.

---

## 6. Kjørte kommandoer — faktiske resultater

| Kommando | Resultat | Exit |
|---|---|---|
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL** — 3 tester, 0 feil, 0 errors (skipped 0) | 0 |
| `./gradlew :app:bundleRelease --no-daemon` | **BUILD FAILED** — fail-closed: "Production release signing is not configured…" (`app/build.gradle.kts` linje 63) | 1 |
| `./gradlew :app:lintRelease` | **BUILD SUCCESSFUL** — 0 errors, **102 warnings**, 1 info | 0 |
| `BOOKIRO_ALLOW_DEBUG_SIGNING=true ./gradlew :app:bundleRelease --no-daemon` (tillegg, kun for R8/mapping-bevis) | BUILD SUCCESSFUL — AAB 50 788 482 B, debug-signert | 0 |

Lint-funn av sikkerhetsrelevans (alle *warnings*, ingen errors):

| id | antall | sted | relevans |
|---|---|---|---|
| `TrustAllX509TrustManager` | 3 | `bcpkix-jdk18on-1.75.jar`, `commons-net-3.10.0.jar` | tom `checkServerTrusted`/`checkClientTrusted` i transitiv avhengighet |
| `InsecureBaseConfiguration` | 1 | `app/src/main/res/xml/network_security_config.xml:15` | `cleartextTrafficPermitted="true"` for **alle** hoster |
| `ExportedService` | 2 | `AndroidManifest.xml:75` | eksportert mediatjeneste uten permission (bevisst for MediaBrowser) |
| `UnusedResources` | 5 | `colors.xml`, `strings.xml`, `themes.xml` | død ressurs |
| `Typos` | 2 | `values-nb`, `values-de` | skrivefeil |

Øvrige 89 warnings: `GradleDependency` (53), `PluralsCandidate` (26), `UseTomlInstead` (9), `ObsoleteSdkInt` (1), `AutoboxingStateCreation` (1).

---

## 7. Krav-tabell

| # | Krav | Status | Bevis |
|---|---|---|---|
| 1 | applicationId/versionCode/versionName/minSdk/targetSdk/compileSdk korrekte | **OK** | `app/build.gradle.kts:46-53`; merged release-manifest |
| 2 | Play-variant valgt og anvendt | **OK** (2026-10-06) | `app/build.gradle.kts` flavors `full`/`playstore`; `docs/BUILD_VARIANTS.md` |
| 3 | Torrent ekskludert fra Play-artefakt | **OK** (2026-10-06) | `tools/verify_playstore_variant.sh` mot `app-playstore-release.aab` → OK (ingen `libtorrent4j`/`com.bookrio.torrent`) |
| 4 | FTP/SMB/WebDAV ekskludert (der det kreves) | n/a (skal være med) | `PLAY_VARIANT_DECISION.md` §1 — beholdes |
| 5 | Ingen `MANAGE_EXTERNAL_STORAGE` | **OK** | merged manifest grep = 0 |
| 6 | Alle permissions begrunnet | **FEIL (mindre)** | `VIBRATE` ubegrunnet/ubrukt (ingen kode-treff) |
| 7 | Foreground service-typer + permissions konsistente | **OK** | merged manifest; `API36_READINESS.md` §4 |
| 8 | `POST_NOTIFICATIONS` håndtert | **OK (med UX-gap)** | deklarert i manifest; runtime kun i 4 skjermer |
| 9 | Produksjonssignering konfigurert | **FEIL** | `:app:bundleRelease` → BUILD FAILED (fail-closed) |
| 10 | Ingen nøkler/passord i git | **OK** | `git ls-files`/`git grep`/`.gitignore` |
| 11 | Release minifisert med R8 | **OK** | `isMinifyEnabled=true`; `mapping.txt` 1,68 M mappings |
| 12 | Mapping-fil bevart | **OK** | `app/build/outputs/mapping/release/mapping.txt` |
| 13 | Automatisk mapping-opplasting til Play | **FEIL (mindre)** | ingen Play Publisher/upload-konfig |
| 14 | Enhetstester grønne | **OK** | `:app:testDebugUnitTest` 3/3 |
| 15 | Lint uten errors | **OK** | `:app:lintRelease` 0 errors (102 warnings) |
| 16 | 16 KB page-size | **OK (per docs)** | `API36_READINESS.md` §7; ikke re-målt i denne sesjonen |
| 17 | Device-aksept: leser (EPUB), Auto/DHU, cold start, process death, rotation, offline, upgrade | **UKJENT** | `RC_STATUS.md` §3/§4/§6, `REGRESSION_AUDIT.md` §6 — ikke utført |
| 18 | Data-safety-porter løst eller eksplisitt akseptert av eier | **UKJENT** | `REGRESSION_AUDIT.md` §1–§3, §7 (eier-beslutning mangler) |
| 19 | Privacy policy-URL finnes | **UKJENT/FEIL** | ingen policy-URL i repo eller i `strings.xml`/kode; kun intern `PRIVACY_DATA_FLOW.md` |
| 20 | F2 (automatisk Audible/audnex-oppslag) besluttet + disclosert i Play-skjema | **UKJENT** | eier-valg "keep+disclose" dokumentert i docs, men ikke bekreftet i Play-skjema |

---

## 8. Blokkere sortert etter alvorlighet

1. **KRITISK — Ingen produksjonssignert artefakt.**
   `:app:bundleRelease` feiler fail-closed; eneste byggbare release er debug-signert
   (`CN=Android Debug`). Play avviser en AAB som ikke er signert med registrert
   opplastingsnøkkel. *Løsning: opprett/registrer upload-keystore, injiser de fire
   `BOOKIRO_*`-secretene, bygg på nytt, verifiser at cert ≠ "Android Debug".*

2. **LØST (2026-10-06) — Play-varianten er anvendt og torrent-fri.**
   `playstore`-flavoren lenker ikke `:torrent`; R8-AAB-en er verifisert uten
   `libtorrent4j`, uten `com.bookrio.torrent`-klasser og uten torrent-ressurser.
   Gjenstår kun eier-beslutning om å publisere og Data Safety-svarene.

3. **HØY — Uverifisert på enhet (UKJENT).**
   Reader-aksept på ekte EPUB, Android Auto/DHU, cold start, process death, rotation,
   offline og oppgradering fra forrige publiserte testbygg. Kreves før GO.

4. **HØY — Data-safety-porter ikke avgjort (UKJENT).**
   Stille kollaps av to ulike EPUB-er med lik tittel+forfatter; hard-sletting av
   audiobook-fragmenter (ingen soft-delete); feil-splitt av kapittelnavn-filer;
   online-cover kan overskrive god embedded cover; `deleteDatabase()`-fallback i
   `ShelfApplication`. Hver krever eier-aksept eller fiks.

5. **MIDDELS — Privacy policy-URL og Data Safety-svar mangler.**
   Ingen policy-URL funnet i repo/kode. Play krever URL når appen samler data
   (metadata/podkast/F2). F2 (automatisk) må oppgis i skjemaet.

6. **LAV — Lint-advarsler av sikkerhetsrelevans.**
   `TrustAllX509TrustManager` (transitive avhengigheter) og global
   `cleartextTrafficPermitted="true"`. Warnings, men bør gjennomgås før produksjon.

7. **LAV — `VIBRATE`-permission ubegrunnet/ubrukt.** Fjern eller dokumenter bruk.

8. **LAV — Repo-hygiene.** `.dbg/ebook-audio-crash.env` og
   `.pi-subagents/runs/*.jsonl` er sporet i git. Ingen hemmeligheter (env-filen har kun
   en `127.0.0.1`-debug-URL), men bør ryddes.

---

## 9. Eksakte neste steg for intern testing-track i Play Console

> Intern testing kan i prinsippet ta en debug-signert AAB, men **denne** AAB-en kan
> ikke lastes opp til Play uten en opplastingsnøkkel, og torrent-innholdet er en
> policy-risiko selv internt. Gjør derfor steg 1–6 før opplasting.

1. **Eier-beslutninger (blokkerende):**
   - Velg Play-pakke-id: behold `com.bookiro` **eller** bruk `com.bookiro.play`
     (se `PLAY_VARIANT_DECISION.md` §4). Bestem før første opplasting.
   - Velg `versionCode`-policy per track.
   - Bekreft fjern av `:torrent` (anvend `playstore`-flavor eller fjern permanent).
   - Bekreft F2-disclosure (automatisk Audible/audnex-oppslag).
2. **Opprett upload-keystore offline** (ikke i repo), f.eks.:
   `keytool -genkeypair -v -keystore bookiro-release.jks -alias bookiro-upload -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12`.
   Legg nøkkel + passord i teamets secret manager.
3. **Registrer Play App Signing** når appen er opprettet i Play Console (Google holder
   app-signeringsnøkkelen; dette er *upload*-nøkkelen).
4. **Anvend Play-varianten** (`app/build.gradle.kts` + `app/src/playstore/…` per
   `PLAY_VARIANT_DECISION.md` §3) og fjern alle torrent-entry points (§2 i samme doc).
5. **Injiser secrets og bygg Play-AAB:**
   ```
   BOOKIRO_KEYSTORE_PATH=/secure/bookiro-release.jks \
   BOOKIRO_KEYSTORE_PASSWORD=… BOOKIRO_KEY_ALIAS=bookiro-upload \
   BOOKIRO_KEY_PASSWORD=… ./gradlew :app:bundleRelease
   ```
   Verifiser: `jarsigner -verify --certs app/build/outputs/bundle/release/app-release.aab`
   → skal **ikke** si `Android Debug`.
6. **Last opp `mapping.txt`** (`app/build/outputs/mapping/release/mapping.txt`) sammen
   med AAB-en for deobfuskering (eller sett opp Play Publisher-plugin for automatikk).
7. **I Play Console — opprett app:** navn "Bookiro", standard språk, App (ikke spill),
   Gratis. Bruk den valgte pakke-id-en.
8. **Fyll ut "App content" (alt må være grønt):**
   - Personvernerklæring-URL (må dekke F1–F10, F2 automatisk, cleartext LAN, torrent
     kun for sideload, retention, kontakt — `PRIVACY_DATA_FLOW.md` §8).
   - **Data Safety-skjema** med svarene i `PRIVACY_DATA_FLOW.md` §8 (Yes til innsamling;
     App interactions/Other in-app actions; deling med Open Library/Google/Apple/
     Audible/audnex + brukerens egne servere; F1 opt-in, F2 automatisk, F3–F9
     brukerinitiert; ingen ads/krasj-ID).
   - Innholdsrating-skjema, målgruppe/alder, annonseerklæring = "No ads",
     nyhetsapp = Nei, statlig app = Nei, data-sletting = ingen konto.
9. **Opprett "Internal testing"-track:** last opp AAB, legg til tester-e-poster/liste,
   skriv release notes, rull ut. Intern testing krever ikke full produksjonsgjennomgang,
   men AAB-en må være opplastingssignert.
10. **Ikke promoter** til closed/open/production før blokkere 3–5 (device-aksept,
    data-safety-aksept, privacy policy) er lukket og bekreftet.

---

### Verktøy/bevis (reproduserbart)

```bash
git rev-parse HEAD                                   # 4af538c…
./gradlew :app:testDebugUnitTest                      # SUCCESS (3 tester)
./gradlew :app:bundleRelease --no-daemon              # FAILED (fail-closed signing)
./gradlew :app:lintRelease                            # SUCCESS (0 errors / 102 warnings)
BOOKIRO_ALLOW_DEBUG_SIGNING=true ./gradlew :app:bundleRelease --no-daemon  # TEST-artefakt
unzip -l app/build/outputs/bundle/release/app-release.aab | grep '\.so$'
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
ls -la app/build/outputs/mapping/release/
git ls-files | grep -iE '\.(jks|keystore|p12|pepk)$|keystore\.properties'
grep -c MANAGE_EXTERNAL_STORAGE \
  app/build/intermediates/merged_manifests/release/processReleaseManifest/universal/AndroidManifest.xml
```
