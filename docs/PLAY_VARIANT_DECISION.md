# Bookiro Android — Play-safe vs full/private variant (decision + config)

Status: **PROPOSAL — NOT APPLIED to `release/bookiro-android-rc`.**
The RC is the **full/private** build. Applying the Play-safe variant is an **owner
decision** and must not happen before the owner elects to publish through Google Play.

---

## 1. Decision summary

| | Full / private (RC) | Play-safe (`play-store`) |
|---|---|---|
| Torrent client (`:torrent`, libtorrent4j) | **included** | **removed** |
| Torrent UI, magnet/`.torrent` add, DHT trackers | included | removed |
| Libgen link/preset | **already absent** (only a filename-cleanup regex remains) | absent |
| Metadata / chapter lookup (F1/F2) | included (F1 opt-in, F2 automatic+disclosed) | included, **must still be disclosed** |
| FTP/SMB/WebDAV/Calibre/podcasts | included | included |
| Package / version identity | same app, private distribution | recommended **separate** app id, e.g. `com.bookiro.play` |
| Signing | production upload key | production upload key (Play App Signing) |

`grep -rni libgen` finds **no link, URL, preset or entry point**; the remaining hits
are `_libgen.` / `[libgen]` filename **cleanup** regexes in `MetadataCleaner`,
`MetadataFetcher` and `BookComponents`. There is nothing to hide — the only Play
policy risk is the torrent client itself.

## 2. Exactly what the Play variant must remove

All torrent entry points in the full build:

| Location | Reference |
|---|---|
| Module dependency | `app/build.gradle.kts` → `implementation(project(":torrent"))` |
| Navigation route | `MainActivity.kt` → `ShelfDestinations.Torrent` (`app/.../app/Navigation.kt:49`) |
| Screen composable | `MainActivity.kt` → `com.bookrio.torrent.ui.TorrentScreen` (~line 496) |
| Home tile | `app/.../app/ui/HomeScreen.kt:509` (`home_service_torrent`) |
| Sources card | `MainActivity.kt:~796` (`torrent_title` / `torrent_subtitle`) |
| Settings toggles | `app/.../app/ui/SettingsScreen.kt` (~lines 1241–1319) |
| App-start worker wiring | `ShelfApplication.kt:93` (`TorrentDownloadWorker.applyUserSettings`) |
| Torrent manifest permissions | merged from `:torrent` (no components in the app manifest) |

`torrent/` declares no Android components of its own, so removing the dependency and
the UI entry points removes the code and the native libraries from the artifact.

## 3. Proposed build configuration (exact shape)

Standard two-flavor dimension. **Impact:** every variant is renamed
(`debug` → `fullDebug`, `release` → `fullRelease`; `playstoreDebug`,
`playstoreRelease`). CI task names, the signing gate and any external scripts must be
updated. This is why it is not applied to the RC.

```kotlin
// app/build.gradle.kts
android {
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            isDefault = true          // keeps the full/private build the default
        }
        create("playstore") {
            dimension = "store"
            applicationIdSuffix = ".play"
            versionNameSuffix = "-play"
        }
    }
}

dependencies {
    "fullImplementation"(project(":torrent"))
    // :torrent is intentionally absent from the playstore variant.
}
```

Then neutralize the app-level torrent references in the `playstore` source set, either
by a small indirection interface (`TorrentEntryPoint`) with `full`/`playstore`
implementations, or by flavor source sets:

```
app/src/playstore/java/com/bookrio/app/ui/TorrentEntryStub.kt   // no-op / hidden tile
app/src/playstore/res/values/strings.xml                        // no torrent strings shown
```

`ShelfApplication.kt` must guard the worker call behind the indirection so the
`playstore` variant never references `com.bookrio.torrent.*`.

## 4. Package / version / signing implications

- **Application id:** a separate `com.bookiro.play` id is recommended if both variants
  are ever published, otherwise Play treats a Play build and a sideload build as the
  same app only when ids match. Separate ids allow side-by-side install but split
  reviews. Either choice must be made *before the first Play upload*.
- **versionCode:** Play requires a monotonically increasing `versionCode` per track;
  the full/private build may reuse codes only if it is never uploaded to the same track.
- **Signing:** both variants use the same production upload key from
  `docs/RELEASE_SIGNING.md`; secrets stay out of the repo. Play App Signing holds the
  app signing key.
- **RC artifact today** is debug-signed (`BOOKIRO_ALLOW_DEBUG_SIGNING=true`), labelled
  TEST only — not Play-ready.

## 5. Data Safety consequences

- Removing torrent removes the DHT/tracker/peer flow (F10), the single biggest risk.
- The Play build **still** needs the Data Safety answers for the metadata/chapter
  lookups (F1/F2), podcast RSS (F4), and user-configured servers (F6–F8). See
  `docs/PRIVACY_DATA_FLOW.md` §8.
- Owner decision on record: keep F2 (Audible/audnex title+author) and disclose it.

## 6. Build audit — 16 KB page size (measured on the RC release APK)

`zipalign -c -P 16 -v 4 app-arm64-v8a-release.apk` → **Verification succesful**.
Every native `LOAD` segment is `0x4000`-aligned: `libtorrent4j.so`,
`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`. The full build is
16 KB safe; the Play build (without `libtorrent4j.so`) remains safe with the two
remaining `.so` files.

## 7. Recommendation

1. Keep the RC as the **full/private** build.
2. Apply the `play-store` flavor only after the owner elects to publish via Play.
3. If Play is chosen: remove `:torrent`, hide every entry point in §2, add F1/F2
   disclosures to the Play listing, then build `playstoreRelease` with the production
   upload key and re-run the 16 KB + privacy checks on that exact artifact.
4. Do **not** publish either variant until explicit owner approval.