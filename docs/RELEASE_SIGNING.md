# Bookiro Android — release signing

The release build **never** uses the debug keystore unless you explicitly opt in for a
non-production test artifact. No keystore or secret is committed to this repository.

## Required production credentials

Provide all four values as **environment variables** (preferred; CI secrets) or in an
ignored `keystore.properties` at the repo root (`keystore.properties` is in
`.gitignore`, along with `*.jks`, `*.keystore`, `*.p12`, `*.pepk`).

| Environment variable | `keystore.properties` key | Meaning |
|---|---|---|
| `BOOKIRO_KEYSTORE_PATH` | `storeFile` | Path to the `.jks`/`.keystore` (repo-relative or absolute) |
| `BOOKIRO_KEYSTORE_PASSWORD` | `storePassword` | Keystore password |
| `BOOKIRO_KEY_ALIAS` | `keyAlias` | Release key alias |
| `BOOKIRO_KEY_PASSWORD` | `keyPassword` | Key password |

Example `keystore.properties` (do **not** commit):

```properties
storeFile=/secure/path/bookiro-release.jks
storePassword=...
keyAlias=bookiro-release
keyPassword=...
```

## Behaviour

- `./gradlew :app:assembleDebug` — debug keystore, `applicationId` suffix `.debug`.
- `./gradlew :app:assembleRelease` / `:app:bundleRelease` — uses the production signing
  config when the four credentials are present. V1 + V2 signing enabled.
- If the credentials are **absent**, any release task (`assembleRelease`,
  `bundleRelease`, `assemble`, `bundle`, `build`) **fails at configuration time** with a
  clear message. A debug-signed artifact can never be produced silently.
- For a **non-production test/RC artifact only**, set
  `BOOKIRO_ALLOW_DEBUG_SIGNING=true`. The build then uses the debug keystore and the
  artifact must be labelled TEST/RC — never "Play-ready".

## Signing identity classification (for artifact provenance)

| Artifact | Command | Signing identity |
|---|---|---|
| Debug APK | `:app:assembleDebug` | Debug keystore (`CN=Android Debug`) |
| RC test APK/AAB | `BOOKIRO_ALLOW_DEBUG_SIGNING=true :app:assembleRelease` | Debug keystore — **TEST ONLY** |
| Production AAB | credentials set, `:app:bundleRelease` | Upload key (`BOOKIRO_KEY_ALIAS`) — Play-ready |

Play App Signing: the upload key above is the *upload* key; Google re-signs with the
app signing key. Keep the upload keystore backed up outside the repo.

## CI

Expose the four values as protected CI secrets; do not print them. `bundleRelease`
should be the only release packaging command in CI, and it must fail if the secrets are
not injected.