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

```yaml
jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - name: Materialize upload keystore from secret
        run: echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/bookiro-release.jks"
        env:
          KEYSTORE_B64: ${{ secrets.BOOKIRO_KEYSTORE_BASE64 }}
      - name: Build signed AAB
        run: ./gradlew :app:bundleRelease
        env:
          BOOKIRO_KEYSTORE_PATH: ${{ runner.temp }}/bookiro-release.jks
          BOOKIRO_KEYSTORE_PASSWORD: ${{ secrets.BOOKIRO_KEYSTORE_PASSWORD }}
          BOOKIRO_KEY_ALIAS: ${{ secrets.BOOKIRO_KEY_ALIAS }}
          BOOKIRO_KEY_PASSWORD: ${{ secrets.BOOKIRO_KEY_PASSWORD }}
```

Rules: secrets only from the platform secret store; never echo a password (the Gradle
failure message prints variable **names**, not values); never upload the `.jks` as an
artifact — only the AAB/APK.

## Creating the upload key (documentation — do NOT run in this repo)

```bash
# Generate once, offline, with a strong passphrase. Never commit the .jks.
keytool -genkeypair -v \
  -keystore bookiro-release.jks \
  -alias bookiro-upload \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype PKCS12
```

Store the keystore + passwords in the team secret manager and register the key with
**Play App Signing** (Google holds the app signing key; this is the *upload* key).
Back it up: a lost upload key needs a Play Console upload-key reset (subject to Google
review).

## Verification commands

```bash
# 1. Without credentials -> must fail with the clear message
./gradlew :app:bundleRelease
./gradlew :app:assembleRelease

# 2. With credentials -> signed artifacts
BOOKIRO_KEYSTORE_PATH=/secure/bookiro-release.jks \
BOOKIRO_KEYSTORE_PASSWORD=... BOOKIRO_KEY_ALIAS=bookiro-upload \
BOOKIRO_KEY_PASSWORD=... ./gradlew :app:bundleRelease :app:assembleRelease

# 3. Certificate check (must NOT say "Android Debug")
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs --verbose \
  app/build/outputs/apk/release/app-universal-release.apk

# 4. AAB signature
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab | tail -5

# 5. Debug build stays debug-signed + .debug-suffixed
./gradlew :app:assembleDebug
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/debug/app-universal-debug.apk
```

## Verified interaction with the Play variant

The signing gate is flavor-agnostic: `playstoreRelease`/`fullRelease` both require the
four variables. See `docs/PLAY_VARIANT_DECISION.md`.