#!/usr/bin/env python3
"""Generate Kinetica's emoji assets.

Produces:
  app/src/main/assets/emoji/emoji.tsv       group headers and one line per emoji
  app/src/main/assets/emoji/kw_<lang>.tsv   search words, line N for the emoji on line N

Sources (downloaded, pinned):
  - Unicode emoji-test.txt 16.0 (Unicode License v3): the set, its order and its groups.
    Fully-qualified forms only. Skin-tone variants and the Component group are left out:
    the panel has no tone picker. ZWJ sequences are kept; the app hides any the device
    font cannot draw as one glyph (Paint.hasGlyph).
  - Unicode CLDR 46.1.0 annotations and derived annotations (Unicode License v3), from
    the cldr-json npm packages, whose published versions are immutable: the name and
    keywords per language. Derived annotations cover flags and ZWJ sequences.
  - tools/emoji_extra_en.tsv: hand-written English words (lol, rofl) kept from the first
    emoji set. Those CLDR lacks are written as a tier ranked above the CLDR words.

emoji.tsv:
  @<group> TAB <tab icon>
  <emoji> TAB <English name>

kw_<lang>.tsv, one line per emoji in emoji.tsv order:
  <hand-written words> TAB <CLDR name words> TAB <other CLDR words>
Words are lowercase and space-separated, each once. Phrases are split into words
because search matches word prefixes, which keeps the 14 files near 1.5 MB.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import sys
import urllib.request
from pathlib import Path

LANGS = ("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ro", "ru", "he", "ar", "uk")

EMOJI_TEST_URL = "https://www.unicode.org/Public/emoji/16.0/emoji-test.txt"
EMOJI_TEST_SHA256 = "24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697"
CLDR_VERSION = "46.1.0"
CLDR_URLS = (
    ("annotations", "https://unpkg.com/cldr-annotations-full@{v}/annotations/{lang}/annotations.json"),
    ("annotationsDerived",
     "https://unpkg.com/cldr-annotations-derived-full@{v}/annotationsDerived/{lang}/annotations.json"),
)

# Unicode's group names to the panel's tabs, in Unicode order. The icons are the tabs the
# first set used, so the strip still reads the same; People and Flags are new. Symbols shows
# input symbols, not a heart: hearts are in Smileys & Emotion, so a heart named an empty panel.
GROUPS = {
    "Smileys & Emotion": ("Smileys", "\U0001F600"),
    "People & Body": ("People", "\U0001F44D"),
    "Animals & Nature": ("Animals", "\U0001F436"),
    "Food & Drink": ("Food", "\U0001F355"),
    "Travel & Places": ("Travel", "\u2708\ufe0f"),
    "Activities": ("Activities", "\u26bd"),
    "Objects": ("Objects", "\U0001F4A1"),
    "Symbols": ("Symbols", "\U0001F523"),
    "Flags": ("Flags", "\U0001F3C1"),
}

SKIN_TONES = range(0x1F3FB, 0x1F400)
WORD_SPLIT = re.compile("[\\s:,.;!?()\\[\\]\"\u201c\u201d\u00ab\u00bb\u201e\\-\u2013\u2014/\u2019']+")

log = logging.getLogger("generate_emoji")


def fetch(url: str, timeout: int = 120) -> bytes:
    log.info("fetch %s", url)
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.read()


def parse_emoji_test(text: str) -> list[tuple[str, str, str]]:
    """(emoji, tab group, English name) in Unicode order, tones and components dropped."""
    out: list[tuple[str, str, str]] = []
    group = ""
    for line in text.splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line.strip() or line.startswith("#"):
            continue
        cps, rest = line.split(";", 1)
        status, _, comment = rest.partition("#")
        if status.strip() != "fully-qualified" or group == "Component":
            continue
        ch = "".join(chr(int(cp, 16)) for cp in cps.split())
        if any(ord(c) in SKIN_TONES for c in ch):
            continue
        # The comment reads "<emoji> E<version> <name>".
        name = comment.strip().split(" ", 2)[2]
        out.append((ch, GROUPS[group][0], name))
    return out


def words(phrases: list[str]) -> list[str]:
    seen: dict[str, None] = {}
    for p in phrases:
        for w in WORD_SPLIT.split(p.lower()):
            if w:
                seen.setdefault(w, None)
    return list(seen)


def annotations(lang: str, cache: Path | None) -> dict[str, tuple[list[str], list[str]]]:
    """Emoji, with variation selectors stripped, to its name and its keywords."""
    merged: dict[str, tuple[list[str], list[str]]] = {}
    for kind, url in CLDR_URLS:
        local = cache / f"{lang}_{kind}.json" if cache else None
        if local and local.exists():
            raw = local.read_bytes()
        else:
            raw = fetch(url.format(v=CLDR_VERSION, lang=lang))
            if local:
                local.write_bytes(raw)
        table = json.loads(raw)[kind]["annotations"]
        for ch, v in table.items():
            merged[ch.replace("\ufe0f", "")] = (v.get("tts") or [], v.get("default") or [])
    return merged


def extras(path: Path) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        ch, kw = line.split("\t", 1)
        out[ch.replace("\ufe0f", "")] = kw.split()
    return out


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out-dir", type=Path, default=root / "app/src/main/assets/emoji")
    parser.add_argument("--emoji-test-file", type=Path, help="local copy of emoji-test.txt 16.0 (skips download)")
    parser.add_argument("--cache-dir", type=Path, help="keeps the CLDR downloads, read back on the next run")
    parser.add_argument("--dry-run", action="store_true", help="report sizes without writing files")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    raw = args.emoji_test_file.read_bytes() if args.emoji_test_file else fetch(EMOJI_TEST_URL)
    digest = hashlib.sha256(raw).hexdigest()
    if digest != EMOJI_TEST_SHA256:
        log.error("emoji-test.txt sha256 %s, expected %s", digest, EMOJI_TEST_SHA256)
        return 1
    entries = parse_emoji_test(raw.decode("utf-8"))
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)

    lines: list[str] = []
    group = ""
    for ch, g, name in entries:
        if g != group:
            group = g
            icon = next(icon for tab, icon in GROUPS.values() if tab == g)
            lines.append(f"@{g}\t{icon}")
        lines.append(f"{ch}\t{name}")
    files = {"emoji.tsv": "\n".join(lines) + "\n"}

    hand = extras(Path(__file__).resolve().parent / "emoji_extra_en.tsv")
    for lang in LANGS:
        table = annotations(lang, args.cache_dir)
        rows = []
        missing = 0
        for ch, _, _ in entries:
            key = ch.replace("\ufe0f", "")
            name, kw = table.get(key, ([], []))
            if not name and not kw:
                missing += 1
            named = words(name)
            rest = [w for w in words(kw) if w not in named]
            # A hand word ranks first only where CLDR lacks it: most of them repeat the name
            # (`heart` on every heart), and kept there they would lift the rarer hearts above the red one.
            top = [w for w in words(hand.get(key, [])) if w not in named and w not in rest] if lang == "en" else []
            rows.append(" ".join(top) + "\t" + " ".join(named) + "\t" + " ".join(rest))
        log.info("%s: %d emoji without CLDR words", lang, missing)
        files[f"kw_{lang}.tsv"] = "\n".join(rows) + "\n"

    total = 0
    for name, text in files.items():
        size = len(text.encode("utf-8"))
        total += size
        log.info("%-12s %8d bytes", name, size)
        if not args.dry_run:
            args.out_dir.mkdir(parents=True, exist_ok=True)
            (args.out_dir / name).write_text(text, encoding="utf-8")
    log.info("%d emoji, %d bytes in all", len(entries), total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
