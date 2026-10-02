#!/usr/bin/env python3
"""i18n completeness checker for Bookiro.

Compares every locale `strings.xml` against the module default (`values/strings.xml`).

Reports, per module and locale:
  * MISSING  keys present in the default but absent in the locale
  * EXTRA    keys present in the locale but absent in the default
  * PLACEHOLDER mismatches (positional %1$s, %1$d, and bare %s/%d)
  * PLURALS  missing/extra <plurals> names and mismatched item counts
  * UNTRANSLATED strings whose text is byte-identical to the default (heuristic;
    ignored for keys whose value is a brand/placeholder-only string)

Exit code is non-zero when any hard problem (missing key, placeholder mismatch,
plural mismatch) is found, so it can gate CI.

Usage:  python3 tools/i18n_check.py [--root .] [--all]
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Keys whose value is intentionally identical in every locale.
TRANSLATION_EXEMPT = re.compile(
    r"^("
    r"app_name|.*_brand|.*_protocol|.*_url_hint|.*_file_extension"
    r")$"
)
# Values that are only format placeholders / punctuation / numbers.
PLACEHOLDER_ONLY = re.compile(r"^[\s%0-9$sd.,:/\-–—()\[\]]*$")
# AndroidResource: %1$s, %2$d, %s, %d, %f, %%
TOKEN_RE = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def normalize_tokens(value: str) -> list[str]:
    """Return format specifiers with positional indexes normalised, ignoring %%."""
    out: list[str] = []
    for m in TOKEN_RE.finditer(value):
        tok = m.group(0)
        if tok == "%%":
            continue
        # Normalise %1$s and %s to the same shape for comparison.
        out.append(re.sub(r"^\d+\$", "", tok))
    return sorted(out)


def parse(path: Path):
    root = ET.parse(path).getroot()
    strings: dict[str, str] = {}
    plurals: dict[str, list[str]] = {}
    for el in root:
        if el.tag == "string":
            if el.get("translatable") == "false":
                continue
            name = el.get("name")
            if name:
                strings[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            name = el.get("name")
            if name:
                plurals[name] = [i.get("quantity", "?") for i in el if i.tag == "item"]
    return strings, plurals


def find_modules(root: Path) -> list[tuple[str, Path]]:
    modules = []
    for xml in sorted(root.glob("*/src/main/res/values/strings.xml")):
        res_dir = xml.parents[1]
        modules.append((res_dir.parents[2].name, res_dir))
    return modules


def locale_files(res_dir: Path) -> list[tuple[str, Path]]:
    out = []
    for d in sorted(res_dir.glob("values-*")):
        candidate = d / "strings.xml"
        if candidate.exists():
            out.append((d.name[len("values-"):], candidate))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--all", action="store_true", help="also list untranslated strings")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    hard_problems = 0
    untranslated_total = 0

    for module, res_dir in find_modules(root):
        default_strings, default_plurals = parse(res_dir / "values" / "strings.xml")
        locales = locale_files(res_dir)
        for locale, path in locales:
            strings, plurals = parse(path)
            missing = sorted(set(default_strings) - set(strings))
            extra = sorted(set(strings) - set(default_strings))
            placeholder_mismatch = []
            untranslated = []
            for key in sorted(set(default_strings) & set(strings)):
                if normalize_tokens(default_strings[key]) != normalize_tokens(strings[key]):
                    placeholder_mismatch.append(key)
                if (
                    strings[key] == default_strings[key]
                    and not TRANSLATION_EXEMPT.match(key)
                    and not PLACEHOLDER_ONLY.match(strings[key])
                    and len(strings[key]) > 3
                ):
                    untranslated.append(key)
            plural_missing = sorted(set(default_plurals) - set(plurals))
            plural_extra = sorted(set(plurals) - set(default_plurals))
            plural_quantity = sorted(
                k for k in set(default_plurals) & set(plurals)
                if sorted(default_plurals[k]) != sorted(plurals[k])
            )

            untranslated_total += len(untranslated)
            if missing or placeholder_mismatch or plural_missing or plural_quantity:
                hard_problems += 1

            if missing or extra or placeholder_mismatch or plural_missing or plural_extra or plural_quantity:
                print(f"[{module}:{locale}]")
                if missing:
                    print(f"  MISSING ({len(missing)}): {', '.join(missing)}")
                if extra:
                    print(f"  EXTRA   ({len(extra)}): {', '.join(extra)}")
                if placeholder_mismatch:
                    print(f"  PLACEHOLDER ({len(placeholder_mismatch)}): {', '.join(placeholder_mismatch)}")
                if plural_missing:
                    print(f"  PLURALS MISSING: {', '.join(plural_missing)}")
                if plural_extra:
                    print(f"  PLURALS EXTRA: {', '.join(plural_extra)}")
                if plural_quantity:
                    print(f"  PLURALS QUANTITY: {', '.join(plural_quantity)}")
            if args.all and untranslated:
                print(f"[{module}:{locale}] UNTRANSLATED ({len(untranslated)}): {', '.join(untranslated)}")

    print()
    print(f"Hard problems (missing keys / placeholder / plural): {hard_problems}")
    print(f"Strings identical to default (untranslated heuristic): {untranslated_total}")
    return 1 if hard_problems else 0


if __name__ == "__main__":
    sys.exit(main())