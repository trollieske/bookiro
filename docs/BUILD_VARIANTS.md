# Bookiro Android — build variants (`full` vs `playstore`)

Bookiro ships **two product flavors** from the same branch. They are the *same app*
for everything except the torrent client, which Google Play treats as a policy risk.

| | `full` (side-load / private) | `playstore` (Google Play) |
|---|---|---|
| Application id (actual) | `com.bookrio` | `com.bookrio.play` |
| versionName suffix | — | `-play` |
| Torrent client (`:torrent`, `libtorrent4j`) | **included** | **removed** |
| Torrent UI / routes / strings | included | removed |
| FTP / SMB / WebDAV / Calibre / podcasts | included | included |
| Everything else | identical | identical |

> `debug` builds get the usual `.debug` id suffix on top: full debug is
> `com.bookrio.debug`, playstore debug is `com.bookrio.play.debug`.
>
> **applicationId status:** the built artifacts currently use `com.bookrio` /
> `com.bookrio.play`. A correction to `com.bookiro` / `com.bookiro.play` is proposed in
> [`docs/APPLICATION_ID_PLAN.md`](APPLICATION_ID_PLAN.md) and is **not applied** until
> the owner approves. The code namespace stays `com.bookrio.*` either way.

## How the split works

### 1. Gradle flavors (`app/build.gradle.kts`)

```kotlin
flavorDimensions += "store"
productFlavors {
    create("full")      { dimension = "store" }
    create("playstore") { dimension = "store"; applicationIdSuffix = ".play"; versionNameSuffix = "-play" }
}

dependencies {
    // Torrent is full-only; playstore never links the module.
    "fullImplementation"(project(":torrent"))
}
```

### 2. `TorrentFeature` seam (shared code stays torrent-agnostic)

- `app/src/main/java/com/bookrio/app/torrent/TorrentFeature.kt` — the interface.
- `app/src/full/java/.../TorrentFeatureProvider.kt` — real implementation backed by
  `:torrent` (`TorrentScreen`, `TorrentDownloadWorker`, torrent strings).
- `app/src/playstore/java/.../TorrentFeatureProvider.kt` — no-op implementation.

Both source sets define an object with the same name (`TorrentFeatureProvider`), so
shared `main` code calls `TorrentFeatureProvider.feature.*` and each variant compiles
exactly one implementation. Shared code never imports `com.bookrio.torrent.*`.

The seam covers every entry point:

| Surface | Where | Playstore behaviour |
|---|---|---|
| Home service tile | `HomeScreen` → `feature.HomeTile` | nothing rendered |
| Sources overview row | `MainActivity` → `feature.SourceCard` | nothing rendered |
| Settings block | `SettingsScreen` → `feature.SettingsSection` | nothing rendered |
| Nav route | `MainActivity` → `if (feature.isAvailable) composable(feature.route)` | route not registered |
| Worker wiring | `ShelfApplication` → `feature.applyBackgroundSettings` | no-op |

### 3. Resources

- Torrent-only strings (`torrent_title`, `torrent_subtitle`, `home_service_torrent`,
  `settings_torrent_bg`, `settings_torrent_bg_sub`, `settings_torrent_min_battery`) live
  only in `app/src/full/res/values*/` and every locale.
- The combined strings that mention torrent (`onboarding_services`,
  `settings_sources_row_sub`) keep the torrent wording in `main` and are **overridden
  without torrent** in `app/src/playstore/res/values*/` for every locale.
- The reader's old `rdr_error_garbage_torrent` message was removed; the generic
  "doesn't look like a book" message is used in both variants.

## Build & test

```bash
# Debug
./gradlew :app:assembleFullDebug
./gradlew :app:assemblePlaystoreDebug

# Unit tests (app has one task per flavor; reader has no flavor)
./gradlew :app:testFullDebugUnitTest :app:testPlaystoreDebugUnitTest :reader:testDebugUnitTest

# Release AABs (need the four BOOKIRO_* signing values — see RELEASE_SIGNING.md)
./gradlew :app:bundleFullRelease
./gradlew :app:bundlePlaystoreRelease

# Lint
./gradlew :app:lintFullRelease
./gradlew :app:lintPlaystoreRelease
```

Output paths:

```
app/build/outputs/apk/full/debug/app-full-universal-debug.apk
app/build/outputs/apk/playstore/debug/app-playstore-universal-debug.apk
app/build/outputs/bundle/fullRelease/app-full-release.aab
app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab
```

## Verify the Play artifact is torrent-free

```bash
tools/verify_playstore_variant.sh \
  app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab
```

The script fails the build if the artifact contains `libtorrent4j`, any
`com/bookrio/torrent/` class, or a torrent resource/string.

## Known inert residual (not torrent functionality)

The shared `data` module keeps the Room entity/DAO for torrent download *rows*
(`torrent_downloads` table, `TorrentDownloadEntity`, `TorrentDownloadDao`,
`SourceEntities.kt`/`SourceDaos.kt`). Room requires all entities in one database, and
the shared `BookImportRepository` reads the (always empty) table. **No torrent client
code runs in the playstore build** — there is no way to create a row, no P2P engine, no
DHT/tracker/peer code and no native library. These identifiers are internal only and
never user-visible. Removing them would require flavor-specific Room schemas across the
`data`/`library` modules and is intentionally out of scope.

## CI

`.github/workflows/android.yml`:

- **On push / PR**: unit tests for both flavors, both debug APKs, then asserts the
  playstore APK is torrent-free and the full APK still ships torrent.
- **Manual (`workflow_dispatch`)**: builds both signed release AABs from the
  `BOOKIRO_*` secrets, verifies the playstore AAB, and uploads both AABs as artifacts.

`.github/workflows/sideload-apks.yml`:

- **Manual dispatch or `v*` tag**: builds release **APKs** for both flavors, verifies
  the playstore APK is torrent-free, uploads them as workflow artifacts and attaches
  them to a GitHub Release. Production-signed when the `BOOKIRO_*` secrets exist,
  otherwise debug/**TEST**-signed.

This is how the side-load (`full`) build is handed out: install
`Bookiro-full-universal.apk`; `Bookiro-playstore-universal.apk` mirrors what Google
Play would receive.
