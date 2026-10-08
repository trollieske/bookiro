#!/usr/bin/env bash
#
# Verifies that a `playstore` artifact (APK or AAB) contains no torrent
# functionality. Run it against the playstore variant only.
#
# Usage: tools/verify_playstore_variant.sh <path-to-apk-or-aab>
#
set -euo pipefail

ARTIFACT="${1:-}"
if [[ -z "$ARTIFACT" ]]; then
    echo "usage: $0 <path-to-apk-or-aab>" >&2
    exit 2
fi
if [[ ! -f "$ARTIFACT" ]]; then
    echo "error: artifact not found: $ARTIFACT" >&2
    exit 2
fi

echo "Verifying playstore artifact: $ARTIFACT"
fail() { echo "FAIL: $1" >&2; exit 1; }

LISTING="$(unzip -Z1 "$ARTIFACT")"

# 1) No libtorrent4j native library.
#    NOTE: use `grep -c` (not `grep -q`) so the producer is never killed by
#    SIGPIPE under `set -o pipefail`, which would mask a positive match.
if (( $(grep -ci 'libtorrent4j' <<<"$LISTING" || true) > 0 )); then
    fail "libtorrent4j native library is present"
fi

# 2) No torrent module classes in any DEX.
DEXES="$(grep -E '\.dex$' <<<"$LISTING" || true)"
if [[ -n "$DEXES" ]]; then
    TORRENT_CLASSES="$(
        for dex in $DEXES; do unzip -p "$ARTIFACT" "$dex"; done \
            | strings | grep -cE 'com/bookrio/torrent/' || true
    )"
    if (( TORRENT_CLASSES > 0 )); then
        fail "com.bookrio.torrent classes are present ($TORRENT_CLASSES hits)"
    fi
fi

# 3) No user-visible torrent strings / resources (needs aapt2 when an APK).
AAPT2=""
if command -v aapt2 >/dev/null 2>&1; then
    AAPT2="$(command -v aapt2)"
else
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        if [[ -z "$root" ]]; then
            continue
        fi
        if [[ -x "$root/build-tools/36.0.0/aapt2" ]]; then
            AAPT2="$root/build-tools/36.0.0/aapt2"
            break
        fi
        candidate="$(ls -1 "$root"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
        if [[ -n "$candidate" ]]; then
            AAPT2="$candidate"
            break
        fi
    done
fi
if [[ -n "$AAPT2" ]]; then
    RES_HITS="$("$AAPT2" dump resources "$ARTIFACT" 2>/dev/null | grep -ci torrent || true)"
    if (( RES_HITS > 0 )); then
        fail "a torrent resource/string is present ($RES_HITS hits)"
    fi
else
    echo "note: aapt2 not found; skipped resource-string check"
fi

echo "OK: no torrent functionality found in $ARTIFACT"
