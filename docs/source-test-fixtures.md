# Running the source integration fixtures

This document describes how to run the SMB, WebDAV, Calibre and torrent
fixtures and which checks they are expected to prove. It also states honestly
which parts were executed during this overhaul and which still require a
developer machine or a physical device.

## 1. Unit tests (always runnable, no servers)

These run entirely on the JVM and are part of the normal Gradle test task:

```bash
./gradlew :data:testDebugUnitTest \
          :calibre:testDebugUnitTest \
          :webdav:testDebugUnitTest \
          :torrent:testDebugUnitTest \
          :ftp:testDebugUnitTest
```

They cover, among other things:

* persistence + schema for the shared transfer queue (`source_kind`, `source_ref`),
* the v8→v9 and v9→v10 migrations and the `calibre_servers` table,
* Calibre OPDS parsing, acquisition selection and Basic/Digest authentication,
* WebDAV `multistatus` parsing, collection detection and UTF-8/space decoding,
* torrent tracker URL masking, error classification and reason sanitization.

## 2. SMB fixture

```bash
fixtures/generate-media.sh
docker compose -f fixtures/docker-compose.yml up -d smb
```

Expected checks (SMB2/SMB3, authenticated):

| Check | How |
|-------|-----|
| Authenticated listing | Add source `smb://localhost/Books` user `shelf` |
| Nested browsing | `Series One/Book 01` … |
| EPUB/PDF/M4B/MP3 download | select files → Download |
| Multi-file audiobook folder | `Audiobooks/Demo Book` |
| Large file / atomic move | `misc/Large Sample.m4b` (256 MB) |
| Wrong password | add with a bad password → distinct auth error |
| Unknown host/share | distinct not-found error |
| SMB1 disabled | server min protocol is SMB2; the source must still work |

## 3. WebDAV fixture

```bash
docker compose -f fixtures/docker-compose.yml up -d webdav
```

| Check | How |
|-------|-----|
| Auth success/fail | user `shelf` / bad password |
| Nested collection browsing | `Books`, `Books/Nordic` |
| UTF-8 / space / apostrophe names | `Nordic/æøå O'Brien.epub` |
| Download + import | EPUB and M4B |
| Range resume | interrupt a large download; the `.part` file resumes |
| Server without Range | point at a server that ignores `Range`; restart honestly |

## 4. Calibre fixture

```bash
docker compose -f fixtures/docker-compose.yml up -d calibre
# import at least one EPUB and one audiobook into the library once
```

| Check | How |
|-------|-----|
| Unauthenticated server | `/opds` reachable without credentials |
| Authenticated server | enable Content Server auth → Digest challenge is answered |
| Library / author / series / tag navigation | OPDS navigation feeds |
| Search | the search field |
| Download + import | EPUB and audiobook format |
| Bad URL/auth error | distinct URL vs auth error in the form |

## 5. Torrent tracker fixtures

```bash
npx bittorrent-tracker --port 8000          # public
npx bittorrent-tracker --port 8001 --http   # private-flag fixture
```

| Check | Expected |
|-------|----------|
| Public magnet/.torrent | downloads, imports; DHT stays enabled |
| Private-flag torrent | DHT/PEX/LSD **off**, only the test tracker is announced to |
| Auth rejection (401/403 or bencoded failure) | UI shows "announce rejected / passkey", not "no seeds" |
| Invalid TLS certificate | not silently accepted in release; TLS diagnosis shown |
| Regression | public torrents keep working; private settings are per-torrent |

## 6. What was executed during this overhaul

* JVM unit tests: executed and green (see `docs/source-release-checklist.md`).
* Android build + localization validator: executed and green.
* SMB / WebDAV / Calibre Docker fixtures: **defined here but not executed in
  this environment** (the Docker daemon was not reachable). They must be run on
  a developer machine before a release.
* Torrent JNI/ABI behaviour: not exercised here (no device/emulator). The
  private-flag and TLS settings changed in code and are covered by unit tests
  for everything that is JVM-testable; the native session still needs a device
  smoke test.