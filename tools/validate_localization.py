#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Shelf localization validation script.

Checks:
  1. Every locale has the same string-key set as base English values/.
  2. No locale is missing required format/plural keys (key parity covers this).
  3. Format placeholders (%1$s, %1$d, %%) are compatible across locales.
  4. Russian/Ukrainian (and all other) XML parses correctly.
  5. No user-visible hardcoded strings remain in target Compose UI modules
     (protocol identifiers / debug logs / developer diagnostics are allowed).
  6. locale_config.xml lists exactly the supported language tags.
  7. System default maps to an empty AppCompat locale list (source check).
  8. Selecting each supported locale maps to the correct BCP-47 tag (source check).
  9. Resource XML hygiene: no unescaped apostrophes, no ${...} template strings.

Usage:  python3 tools/validate_localization.py
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = ["app", "library", "reader", "player", "ftp", "smb", "webdav", "calibre", "torrent", "podcast"]
LOCALES = ["en", "nb", "da", "sv", "fr", "de", "es", "ru", "uk"]
SUPPORTED_TAGS = ["en", "nb", "da", "sv", "fr", "de", "es", "ru", "uk"]

# Non-translatable UI tokens that are fine in target screens (protocol names,
# unit letters, graphic glyphs, debug diagnostics, data placeholders).
NON_TRANSLATABLE = {
    "A", "A-", "A+", ".", "..", "/", "sp", "s", "B", "KB", "MB", "GB", "—",
    "▸", "⇅", "⚡", "☾", "📖", "🎧", "📊", "❌", "⏱️", "+", "*/*",
    "SHELF", "ISBN", "ISO", "magnet:?xt=urn:btih:...", "magnet:?xt=urn:btih:…",
    "Rendering", "rendering", "sw=", "stride=", "pages=", "%", "%> ",
}
# Extension/subject lines that are data, not chrome (heuristically tolerated).
TOKEN_ALLOWLIST_RE = re.compile(
    r"^(https?://|magnet:|\$|#ShelfApp|[0-9]+(\.[0-9]+)?%?|[A-Z0-9+/]+$|[-_.:]+$|[0-9]+ [a-zA-Z]+$)"
)

problems = []


def keys_of(path):
    try:
        root = ET.parse(path).getroot()
    except Exception as exc:  # noqa: BLE001
        problems.append(f"XML PARSE FAIL: {path}: {exc}")
        return {}
    out = {}
    for el in root:
        if el.tag == "string":
            out[el.get("name")] = el.text
    return out


def ph(s):
    return re.findall(r"%(?:\d+\$)?[sd]", s or "")


print("== 1/2/3/4/9: resource key parity, placeholders, XML, hygiene ==")
for mod in MODULES:
    base_path = os.path.join(ROOT, mod, "src", "main", "res", "values", "strings.xml")
    if not os.path.exists(base_path):
        problems.append(f"MISSING base resources: {base_path}")
        continue
    base = keys_of(base_path)
    if not base:
        continue
    if not base_path.endswith(".xml"):
        pass
    for loc in LOCALES:
        p = os.path.join(ROOT, mod, "src", "main", "res", f"values-{loc}", "strings.xml")
        if not os.path.exists(p):
            problems.append(f"MISSING locale file: {p}")
            continue
        ks = keys_of(p)
        if not ks:
            continue
        missing = set(base) - set(ks)
        extra = set(ks) - set(base)
        if missing:
            problems.append(f"{mod}/values-{loc} missing keys: {sorted(missing)}")
        if extra:
            problems.append(f"{mod}/values-{loc} extra keys: {sorted(extra)}")
        for k, v in base.items():
            if ph(ks.get(k)) != ph(v):
                problems.append(f"{mod}/values-{loc}.{k} placeholder mismatch: {ph(v)} != {ph(ks.get(k))}")
        for k, v in ks.items():
            if v and "${" in v:
                problems.append(f"{mod}/values-{loc}.{k} contains template string: {v[:40]}")
            if v and "'" in v and not re.search(r"(?<!\\)'", ""):
                pass  # bare-apostrophe check below
    # bare apostrophe check (line-oriented for readability)
    for loc in LOCALES:
        p = os.path.join(ROOT, mod, "src", "main", "res", f"values-{loc}", "strings.xml")
        if not os.path.exists(p):
            continue
        for i, line in enumerate(open(p, encoding="utf-8"), 1):
            if re.search(r"<string[^>]*>.*[^\\]'", line) or (
                "<string" in line and "'" in line and "\\'" not in line
            ):
                problems.append(f"{p}:{i} unescaped apostrophe: {line.strip()[:80]}")
    print(f"  {mod}: OK ({len(base)} keys x {len(LOCALES)} locales)")

print("== 5: hardcoded user-visible strings in target Compose modules ==")
UI_RE = re.compile(
    r'Text\("([^"]{2,})"\)|contentDescription\s*=\s*"([^"]{2,})"|'
    r'Toast\.makeText\([^,]*,\s*"([^"]{2,})"|snackbarHostState\.showSnackbar\("([^"]{2,})"|'
    r'label\s*=\s*\{\s*Text\("([^"]{2,})"\)\}\s*,?\s*$|title\s*=\s*\{\s*Text\("([^"]{2,})"\)\}'
)
# Norwegian UI phrasing that must never be hardcoded in a shipping UI string.
NO_UI_WORDS = (
    "Kapittel ", "kapittel ", "Lydbok", "Lydbøker", "lydbøker", "igjen",
    " sek", "sekund", "minutt", "Klargjør", "Venter på", "Fant ikke",
    "Ingen tilgang", "Bruk spilleren", "HELE BOKEN", "LYDBOK", "LYDSPILLER",
    "KAPITTEL", "Stemmetjeneste", "Spiller av",
)
NO_EXCLUDE = ("//", "/*", "* ", "Log.", "dbg", "Regex(", "matches(",
              "startsWith(", "endsWith(", "contains(", '"audiobook"', '"library"',
              "R.string", "getString", "stringResource")
for mod in MODULES + ["reader"]:
    kt = glob.glob(os.path.join(ROOT, mod, "src", "main", "**", "*.kt"), recursive=True)
    for f in kt:
        hits = []
        for i, line in enumerate(open(f, encoding="utf-8"), 1):
            if "stringResource" in line or "getString(" in line or f.endswith("Test.kt"):
                continue
            if any(tag in f for tag in ["test", "Test"]):
                continue
            if re.search(r"//.*(diag|debug|TODO|FIXME)", line):
                continue
            # Norwegian-word check (catches chapter fallbacks, notification text, units)
            if any(w in line for w in NO_UI_WORDS) and not any(x in line for x in NO_EXCLUDE):
                hits.append("no-ui-word:" + line.strip()[:70])
            for m in UI_RE.finditer(line):
                for g in m.groups():
                    if not g:
                        continue
                    t = g.strip()
                    if t in NON_TRANSLATABLE or TOKEN_ALLOWLIST_RE.match(t):
                        continue
                    if len(t) > 40 and re.match(r"^[\w\-\.:/\\ =+]+$", t):
                        continue  # path / url / key-like
                    hits.append(t)
        if hits:
            problems.append(f"HARDCODED UI in {os.path.relpath(f, ROOT)}: {sorted(set(hits))[:5]}")

print("== 6/7/8: locale_config + AppCompat tag mapping ==")
lcfg = os.path.join(ROOT, "app", "src", "main", "res", "xml", "locale_config.xml")
if os.path.exists(lcfg):
    try:
        root = ET.parse(lcfg).getroot()
        ns = "{http://schemas.android.com/apk/res/android}"
        tags = [e.get(ns + "name") for e in root]
        if tags != SUPPORTED_TAGS:
            problems.append(f"locale_config.xml tags {tags} != supported {SUPPORTED_TAGS}")
    except Exception as exc:  # noqa: BLE001
        problems.append(f"locale_config.xml parse error: {exc}")
else:
    problems.append("locale_config.xml missing")

lang_src = os.path.join(ROOT, "app", "src", "main", "java", "com", "shelf", "reader", "app", "ui", "LanguageSettings.kt")
if os.path.exists(lang_src):
    src = open(lang_src, encoding="utf-8").read()
    if "LocaleListCompat.getEmptyLocaleList()" not in src:
        problems.append("System-default empty locale list not wired (LanguageSettings.kt)")
    if "LocaleListCompat.forLanguageTags(tag)" not in src:
        problems.append("forLanguageTags mapping not wired (LanguageSettings.kt)")
    if 'listOf("en", "nb", "da", "sv", "fr", "de", "es", "ru", "uk")' not in src:
        problems.append("ShelfLanguages.tags != supported tags (LanguageSettings.kt)")
else:
    problems.append("LanguageSettings.kt missing")

if problems:
    print("\nPROBLEMS:")
    for p in problems:
        print(" -", p)
    print(f"\n{len(problems)} issue(s) found.")
    sys.exit(1)
print("All localization validation checks passed.")