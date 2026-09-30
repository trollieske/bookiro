# 📚 Shelf – Local-First Ebook & Audiobook Platform

**Shelf** is a premium, local-first Android application that combines an elegant 3D e-book reader, a full-featured audiobook player, an embedded torrent download engine, and smart cloud storage sync (**FTP**, **FTPS**, **SFTP**, **SMB**, **WebDAV**).

Built with **Kotlin 2.0+**, **Jetpack Compose (Material 3)**, **Room DB**, **AndroidX Media3 (ExoPlayer)**, **WorkManager**, and **Navigation Compose**.

> **Locale**: Norwegian (Bokmål) default UI locale with English fallback.

---

## 1. Features at a Glance

### 📖 E-Book Reader (Apple Books-inspired)
* **Reliable Page Turns**: A single-surface reader with instant/slide page transitions and the same 28/44/28 tap zones. The legacy 3D page-curl fork lives in the unwired `:pagecurl` directory for rollback only; the reader no longer depends on it.
* **Minimal Apple Books-style Overlay UI**: Top/bottom bars float **on top** of the page (not in a Column), so the reader keeps 100% of screen width — no squished text. Solid high-contrast Material 3 surfaces with bold book title in the top bar.
* **28 / 44 / 28 Tap Zones**: Left 28% previous, right 28% next, middle 44% toggles menus. Subtle pulsing edge glow indicates chapter boundaries at the start/end of each chapter.
* **Color-Coded Text Highlights**: 5–6 color palette. Long-press selection immediately surrounds text with a colored `<span>` (visible even if the OEM copy-menu sits on top). Color toolbar is placed **below** the selection by default (fixes OnePlus/Oppo overlay issues). Highlight mode opens on the **currently visible page**, not page 0.
* **Publication-Grade Typography**: Dynamic font sizing (12pt–36pt), light/dark/sepia paper themes, adjustable line height, CSS multi-column layout with integer-stride column width (no clipped glyphs on the right edge). Reading position is preserved across font/theme changes via percent → page conversion.
* **Robust Multi-Format Parsing**: EPUB 2/3 (opf/ncx), PDF, MOBI / AZW / AZW3 (KF8 binary-noise filter with readable-block extraction), FB2, CBZ, CBR, TXT, Markdown, HTML. KF8 junk-header filter prevents gibberish at the start of MOBI books.
* **EPUB CFI-friendly Progress**: Reading location, bookmarks, and highlights are stored with durable locators (page index + page offset) rather than fragile absolute page numbers.

### 🎧 Audiobook Player + Android Auto
* **AndroidX Media3 / ExoPlayer Background Service**: Dedicated `AudiobookPlaybackService.kt` with **a single unique `MediaLibrarySession`** (`shelf_audio`) — fixes the fatal "Session ID must be unique" crash when switching between audiobook → ebook playback. Lock-screen controls, Bluetooth/headset actions, and persistent foreground notification.
* **Android Auto Support**: Declared `automotive_app_desc` with `<uses name="mediaPlayback" />`, `minCarApiLevel=1`. `MediaLibraryService` provides browsable audiobook root → book list → chapters for in-car browsing via `onGetChildren`.
* **Multi-Track Auto-Stitching**: Folder-based MP3/M4A/M4B audiobooks are aggregated into a single book with correct chapter ordering.
* **Chapter & M4B Support**: Embedded `chap`/`chpl` atoms and ID3 `CHAP` frame parsing. Accurate seeking by chapter.
* **Playback Quality**: Variable speed (0.5x–3.0x), custom ±10s/±30s skip intervals in notifications, 5-action compact media style, haptic sleep timer with progressive fade-out the last 30s.
* **Resume Position Safety**: Playback percent → chapter + offset is restored after app restart / process death. Chapter-complete threshold is lenient to avoid marking chapters done from accidental scrubbing.

### ⚡ Torrent & Cloud Storage Engine
* **Embedded Torrent Client**: Background download engine (`TorrentEngine.kt`, `TorrentDownloadWorker.kt`) supporting magnet links, peer/seed stats, and auto-importing completed downloads into the library via **libtorrent4j** (BSD-3-Clause) JNI bindings for all ABIs.
* **FTP / FTPS / SFTP Sync**: Browse remote servers, public-key SSH auth, Keystore-encrypted credentials, and a durable, resumable transfer queue (`FtpSyncWorker.kt`) via **Apache Commons Net** and **SSHJ**.
* **SMB (Windows Share) Integration**: Native SMB2/SMB3 connector (`SmbClientEngine.kt`, `SmbSyncWorker.kt`) via **jcifs-ng** for local NAS and PC shares. Downloads are staged and moved atomically; durable WorkManager sync is still in progress (see `docs/source-overhaul-architecture.md`).
* **WebDAV Support**: WebDAV server browser and sync (`WebdavSyncWorker.kt`) via **OkHttp**, with namespace-tolerant `PROPFIND` parsing, origin-safe redirects and Range-based resume.
* **Calibre Content Server**: First-class source (`:calibre` module) that browses the Calibre Content Server OPDS interface, supports Basic/Digest auth, search, and a durable download/import queue (`CalibreSyncWorker.kt`).
* **LAN Discovery**: Built-in local-network scan for FTP/SMB/WebDAV/Calibre servers. The generic OPDS catalog feature and the "known OPDS catalogs" shortcuts were removed because they were not implemented.

### 🌲 Library UI (Apple Books-style 3-tab Bottom Nav)
* **3 Bottom Tabs Only**: **Bøker** (Ebooks), **Lydbøker** (Audiobooks), **Innstillinger** (Settings) — matches the Apple Books shelf layout.
* **Library Filters**: Each top-level tab auto-filters its format (`LibraryFilter.EBOOKS` / `AUDIOBOOKS`). Filter chips, sort, search, and 3 view types (Shelf / Grid / List).
* **Adaptive 3-Column Cover Grid**: `LazyVerticalGrid(GridCells.Adaptive(minSize = 115.dp))` — clean shelf-width-aware cover layout.
* **Sources Moved to Settings**: "Kilder & synkronisering" (FTP/SMB/WebDAV/Torrent) is now a dedicated card at the top of the Settings screen rather than cluttering the main navigation.
* **Persistent Mini-Player Strip**: Active audiobook mini-player row is anchored directly above the bottom navigation bar — one tap jumps back to the player screen.
* **Clean Metadata & Covers**: `MetadataCleaner.kt` strips release noise (`_libgen.li`, `_z-lib.org`, `.epub`, `.pdf`), normalizes underscores, and formats author/title. `CoverRepository.kt` fetches high-resolution covers from **Open Library Covers API** and **Google Books API** with typographic SVG covers as fallback.

---

## 2. Project Architecture & Modules

```
shelf/
├── app/                 # Entry point: MainActivity, 3-tab BottomNav, navigation graphs, Sources, Settings
├── core/                # Shared domain models, parsers, MetadataCleaner
├── data/                # Room DB (ShelfDatabase), DAOs (BookDao, ShelfDao, ProgressDao, HighlightDao), DataStore
├── designsystem/        # Material 3 theme, color tokens, reusable components
├── library/             # Library dashboard, BookImportRepository, CoverRepository, LibraryViewModel
├── reader/              # Ebook reader: ReaderScreen (Apple Books overlay), HtmlPageRenderer, BookLoaderEngine
├── pagecurl/            # UNWIRED local fork of oleksandrbalan/pagecurl (Apache 2.0), kept for rollback only — not built or depended on
├── player/              # Audiobook player: AudiobookEngine, AudiobookPlaybackService (Media3 / MediaLibrarySession), PlayerScreen
├── torrent/             # Torrent Engine, TorrentDownloadWorker, magnet link handling (libtorrent4j)
├── ftp/                 # FTP / FTPS / SFTP client engine, server store, FtpSyncWorker
├── smb/                 # SMB / Windows share client engine & sync worker
└── webdav/              # WebDAV client engine & sync worker
```

* **Package Name**: `com.shelf.reader`
* **SDK Config**: `minSdk 26` (Android 8.0) | `targetSdk 35` (Android 15)
* **Kotlin 2.0** | **Jetpack Compose (Material 3)** | **AndroidX Media3** | **Room + DataStore** | **Hilt-ready MVVM**

---

## 3. Supported Ebook & Audio Formats

### Ebooks & Documents
| Format | Parser Engine | Features |
|---|---|---|
| **EPUB 2 / 3** | `EpubRealParser` | OPF/NCX manifest parsing, HTML chapter extraction, cover extraction. |
| **PDF** | `PdfRenderer` | Vector page rendering, page thumbnails. |
| **MOBI / AZW / AZW3** | PalmDOC / MOBI Parser | Record parsing, cover extraction, HTML text extraction. |
| **FB2 / FB2.ZIP** | FictionBook XML Parser | Structural XML parsing, embedded base64 image extraction. |
| **CBZ / CBR** | Comic Archive Importer | Image extraction & sequential page display. |
| **TXT / Markdown** | `BookLoaderEngine` | Paragraph auto-chunking, multi-charset fallback (**UTF-8**, **Windows-1252** for Norwegian `æ`, `ø`, `å`). |
| **ZIP Containers** | `ArchiveImporter` | Intelligent auto-unpacking and recursive book discovery. |

### Audiobooks
| Format | Decoder | Features |
|---|---|---|
| **M4B** | ExoPlayer | Full embedded chapter atom parsing. |
| **MP3 / M4A / AAC** | ExoPlayer | ID3 `CHAP` frame parsing, multi-file track stitching. |
| **FLAC / OGG / OPUS** | ExoPlayer | Lossless & high-efficiency playback. |

---

## 4. How to Build & Run

### Prerequisites
* **Android Studio**: Iguana / Jellyfish / Ladybug (2024.1+) with Kotlin 2.0 plugin.
* **JDK**: Version 17 or higher.
* **Android SDK**: API 35 (Android 15).

### Command Line
```powershell
# Windows PowerShell
.\gradlew.bat :app:assembleDebug          # Build Debug APK
.\gradlew.bat :app:assembleRelease        # Build Release APK
.\gradlew.bat :app:installDebug           # Build and install to connected device via ADB
```

```bash
# macOS / Linux
./gradlew :app:assembleDebug
```

---

## 5. Security, Permissions & Data Privacy

Shelf is **100% local-first**. All data, database entries, and reading progress reside strictly on the user's device (`/data/data/com.shelf.reader/databases/shelf.db`).

* **No Accounts / No Telemetry**: Shelf contains zero tracking, analytics, or third-party ad SDKs.
* **Scoped Storage & SAF**: Uses Android Storage Access Framework (SAF) for picking files/folders and obtaining **persistable URI permissions**.
* **Keystore Encryption**: All FTP/SFTP passwords and private key passphrases are encrypted using `EncryptedSharedPreferences` backed by the hardware **Android Keystore**.

---

## 6. Developer Documentation & Handover

For detailed technical specifications, diagnostic analyses of rendering components, database query rules, and architectural handoff specs, refer to:

* 📄 **[Technical Handover Report (`handover_report.md`)](file:///c:/Users/cKlappy/Documents/trae_projects/Book/handover_report.md)**
* 📜 **[Changelog (`CHANGELOG.md`)](file:///c:/Users/cKlappy/Documents/trae_projects/Book/CHANGELOG.md)**

---

## 7. 🙏 Acknowledgements, Credits & Open Source

Shelf would not have been possible without the incredible work of the open-source community. A massive thank-you to all the developers, maintainers, and contributors behind these wonderful libraries:

### 📖 Ebook Reader & Page Curl
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[PageCurl](https://github.com/oleksandrbalan/pagecurl)** | **Oleksandr Balan** | Apache 2.0 | Former page-curl effect (vendored fork, unwired). Kept in-tree for rollback; not built or linked. 🙏 |
| **[jsoup](https://jsoup.org/)** | Jonathan Hedley | MIT | Sanitization and clean-up of EPUB HTML documents before rendering. |
| **AndroidX WebView** | Google | Apache 2.0 | Off-screen HTML → Bitmap rendering engine for publication-grade typography (`HtmlPageRenderer`). |

### 🎧 Audiobook Player & Media
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3)** | Google (AndroidX) | Apache 2.0 | The rock-solid, production-grade media player engine powering all audiobook playback, MediaSession, MediaLibraryService (Android Auto), lock-screen controls, and notifications. |
| **[Guava](https://github.com/google/guava)** | Google | Apache 2.0 | `ListenableFuture` support required by the Media3 session callback API. |
| **[AndroidX Media](https://developer.android.com/jetpack/androidx/releases/media)** | Google | Apache 2.0 | Legacy MediaSession & media-compat support for older devices. |

### 🗄️ Database & Persistence
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[AndroidX Room](https://developer.android.com/training/data-storage/room)** | Google | Apache 2.0 | Fast, type-safe, SQLite-backed local database for books, bookmarks, highlights, progress, and library metadata. |
| **[AndroidX DataStore](https://developer.android.com/topic/libraries/architecture/datastore)** | Google | Apache 2.0 | Modern, coroutine-based `SharedPreferences` replacement for app settings. |
| **[AndroidX Security Crypto](https://developer.android.com/jetpack/androidx/releases/security)** | Google | Apache 2.0 | `EncryptedSharedPreferences` backed by **Android Keystore** for hardware-level encryption of FTP/SMB/WebDAV passwords and SSH private-keys. |
| **[Bouncy Castle](https://www.bouncycastle.org/)** | Legion of the Bouncy Castle | MIT | Robust cryptography provider for SSH/SFTP key exchange and TLS. |

### 🖼️ Images, Covers & UI
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[Coil](https://coil-kt.github.io/coil/)** | [Coil Contributors](https://github.com/coil-kt/coil) | Apache 2.0 | Lightning-fast, Kotlin-first, coroutine-based image loader for book covers, SVG decoding, and animated GIF support. |
| **[Open Library Covers API](https://openlibrary.org/dev/docs/api/covers)** | [Open Library / Internet Archive](https://openlibrary.org/) | CC0 / Public Domain API | High-resolution book cover artwork lookup by ISBN, OCLC, LCCN, or OLID. |
| **[Google Books API](https://developers.google.com/books/docs/v1/using)** | Google | Google APIs Terms | Fallback cover-art lookup and metadata search. |

### ☁️ Cloud Storage & Network Sync
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[Apache Commons Net](https://commons.apache.org/proper/commons-net/)** | Apache Software Foundation | Apache 2.0 | FTP / FTPS client protocol implementation. |
| **[SSHJ (SSH/J)](https://github.com/hierynomus/sshj)** | Jeroen van Erp (Hierynomus) | Apache 2.0 | Modern, clean SFTP (SSH File Transfer Protocol) client with public-key and password authentication. |
| **[jcifs-ng](https://github.com/AgNO3/jcifs-ng)** | AgNO3 | LGPL 2.1 | Native, pure-Java SMB / CIFS (Windows File Sharing) client for browsing NAS and PC shares. |
| **[OkHttp](https://square.github.io/okhttp/)** | Square Inc. | Apache 2.0 | HTTP/WebDAV client engine powering WebDAV library browsing, cover fetches, and download operations. |
| **[AndroidX WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)** | Google | Apache 2.0 | Reliable deferred & periodic background-sync workers for FTP/SMB/WebDAV folder watching and library auto-refresh (`FtpSyncWorker`, `SmbSyncWorker`, `WebdavSyncWorker`). |

### 🌊 Torrent Download Engine
| Library | Author / Maintainer | License | Purpose |
|---|---|---|---|
| **[libtorrent4j](https://github.com/aldenml/libtorrent4j)** | Aldenml (aldenml) | BSD-3-Clause | Android JNI bindings for the **Rasterbar libtorrent** BitTorrent engine — the de-facto C++ library powering fast, robust magnet-link and torrent downloads on all ABIs (arm64-v8a, armeabi-v7a, x86_64). |

### 🧱 Platform & Architecture (AndroidX & Jetpack)
Shelf stands on the shoulders of the official **AndroidX / Jetpack** libraries, all released under the **Apache 2.0** license by Google:

- **Jetpack Compose & Material 3** (`compose-ui`, `compose-material3`, `compose-foundation`) — modern declarative UI toolkit.
- **Lifecycle / ViewModel** — lifecycle-aware state holders.
- **Navigation Compose** — type-safe navigation and deep-link routing.
- **Core-KTX** — idiomatic Kotlin extensions for platform APIs.
- **Kotlinx Coroutines** — structured concurrency & Flow.
- **Compose ViewModel / Hilt-ready state patterns.**

And last but not least: **Kotlin 2.0** from **JetBrains** — the calm, expressive language that makes Android development a joy.

---

*Built with care. Keep it calm. Keep it tactile. Keep it yours.*


