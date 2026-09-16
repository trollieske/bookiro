#!/usr/bin/env bash
# Generates the representative media used by the SMB/WebDAV/Calibre fixtures.
# Everything is synthetic and non-sensitive.
#
#   fixtures/generate-media.sh
#
# Produces:
#   fixtures/smb-data/            nested folders + EPUB/PDF/M4B/MP3 + audiobook folder
#   fixtures/webdav-data/         names with spaces, nordic characters and apostrophes
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
smb="$root/smb-data"
webdav="$root/webdav-data"

rm -rf "$smb" "$webdav"
mkdir -p "$smb/Series One/Book 01" "$smb/Series One/Book 02" "$smb/Audiobooks/Demo Book" "$smb/misc"
mkdir -p "$webdav/Books" "$webdav/Books/Nordic"

# --- minimal valid EPUB (zip with the required mimetype) -------------------
make_epub() {
  local out="$1" title="$2"
  local tmp; tmp="$(mktemp -d)"
  printf 'application/epub+zip' > "$tmp/mimetype"
  mkdir -p "$tmp/META-INF" "$tmp/OEBPS"
  cat > "$tmp/META-INF/container.xml" <<XML
<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
XML
  cat > "$tmp/OEBPS/content.opf" <<XML
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:shelf-fixture</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:language>en</dc:language>
    <dc:creator>Shelf Fixture</dc:creator>
  </metadata>
  <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
  <spine><itemref idref="c1"/></spine>
</package>
XML
  cat > "$tmp/OEBPS/chapter1.xhtml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
<body><h1>$title</h1><p>Fixture chapter.</p></body></html>
XML
  (cd "$tmp" && zip -q -X0 "$out" mimetype && zip -q -Xr9 "$out" META-INF OEBPS)
  rm -rf "$tmp"
}

make_epub "$smb/Series One/Book 01/The First Book.epub" "The First Book"
make_epub "$smb/Series One/Book 02/The Second Book.epub" "The Second Book"
make_epub "$smb/misc/Nordic æøå - O'Brien.epub" "Nordic æøå"
make_epub "$webdav/Books/Some Book.epub" "Some Book"
make_epub "$webdav/Books/Nordic/æøå O'Brien.epub" "æøå O'Brien"

# --- placeholder PDF -------------------------------------------------------
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%%%EOF\n' > "$smb/misc/Manual.pdf"

# --- placeholder audio (valid-ish container, enough for transfer tests) ----
head -c 5242880 /dev/urandom > "$smb/Audiobooks/Demo Book/01 - Chapter One.m4b"
head -c 4194304 /dev/urandom > "$smb/Audiobooks/Demo Book/02 - Chapter Two.m4b"
head -c 2097152 /dev/urandom > "$smb/Audiobooks/Demo Book/cover.jpg"
head -c 3145728 /dev/urandom > "$smb/misc/Sample Track.mp3"

# --- a large file for throughput/atomicity tests ---------------------------
head -c 268435456 /dev/urandom > "$smb/misc/Large Sample.m4b"

echo "Fixtures generated:"
echo "  SMB:    $smb"
echo "  WebDAV: $webdav"