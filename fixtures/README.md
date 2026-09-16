# Source test fixtures

Reproducible, non-sensitive test servers for the remote-file sources. They live
here so no developer or reviewer needs a NAS, Nextcloud tenant, Calibre library
or private tracker account to validate the browse/download/import flows.

```
fixtures/
  docker-compose.yml     Samba (SMB2/3), WebDAV, Calibre Content Server
  generate-media.sh      synthetic EPUB/PDF/M4B/MP3 + a 256 MB file
  smb-data/              generated, mounted read-only into Samba
  webdav-data/           generated, mounted into the WebDAV server
  calibre-library/       put or import books + metadata.db here
```

Credentials (test-only, never referenced by production code):

| Service | Address | User | Password |
|---------|---------|------|----------|
| SMB     | `smb://localhost:445/Books` | `shelf` | `shelfpass` |
| WebDAV  | `http://localhost:8080/` | `shelf` | `shelfpass` |
| Calibre | `http://localhost:8081/opds` | *(per server)* | *(per server)* |

## Start

```bash
fixtures/generate-media.sh
docker compose -f fixtures/docker-compose.yml up -d
```

## Stop

```bash
docker compose -f fixtures/docker-compose.yml down
```

## Torrent trackers

Public and private-flag trackers are run with the npm reference tracker because
the available container images are less reliable:

```bash
npx bittorrent-tracker --port 8000          # public
npx bittorrent-tracker --port 8001 --http   # used by the private-flag fixture
```

See `docs/source-test-fixtures.md` for how the automated harness uses these and
which checks are expected to pass.