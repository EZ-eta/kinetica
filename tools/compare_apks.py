#!/usr/bin/env python3
"""Check that two APKs differ only in their signature.

  tools/compare_apks.py build-a.apk build-b.apk

Comparing whole-file hashes is the wrong test: apksigner signs with RSASSA-PSS
for minSdk >= 24 and PSS draws a random salt, so signing identical input twice
never yields identical bytes. The APK Signing Block is not required to
reproduce either - a verifier copies it across rather than regenerating it -
while a v2/v3 signature covers every other byte of the file, which therefore
must match exactly.

So split each APK at the signing block and compare what has to match: the ZIP
entries before it and the central directory after it. On a mismatch, the
per-entry CRCs name the file that moved. Exits 0 when the two APKs differ only
in the signature.
"""

from __future__ import annotations

import argparse
import hashlib
import logging
import struct
import sys
import zipfile
from pathlib import Path

LOG = logging.getLogger("compare_apks")

# The APK Signing Block ends with an 8-byte size followed by this magic, and is
# itself immediately followed by the central directory.
SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"


class NotAnApk(Exception):
    """Raised when a file does not look like a signed APK."""


def split_regions(path: Path) -> dict[str, bytes]:
    """Split an APK into entries, signing block, and central directory."""
    data = path.read_bytes()

    eocd = data.rfind(b"PK\x05\x06")
    if eocd == -1:
        raise NotAnApk(f"{path}: no end-of-central-directory record")
    cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]

    magic_at = data.rfind(SIGNING_BLOCK_MAGIC, 0, cd_offset)
    if magic_at == -1 or magic_at + len(SIGNING_BLOCK_MAGIC) != cd_offset:
        raise NotAnApk(
            f"{path}: no APK Signing Block before the central directory - "
            "is this an unsigned APK, or signed with v1 only?"
        )
    block_size = struct.unpack_from("<Q", data, magic_at - 8)[0]
    block_start = cd_offset - (block_size + 8)

    return {
        "entries": data[:block_start],
        "signing block": data[block_start:cd_offset],
        "central directory": data[cd_offset:],
    }


def entry_metadata(path: Path) -> dict[str, tuple[int, int, int, tuple[int, ...]]]:
    """Per-entry CRC, sizes, compression method and timestamp, keyed by name."""
    with zipfile.ZipFile(path) as archive:
        return {
            info.filename: (
                info.CRC,
                info.file_size,
                info.compress_type,
                info.date_time,
            )
            for info in archive.infolist()
        }


def report_entry_differences(left: Path, right: Path) -> None:
    """Name the entries that differ, so a mismatch points at a cause."""
    a, b = entry_metadata(left), entry_metadata(right)

    for name in sorted(set(a) - set(b)):
        LOG.error("only in %s: %s", left.name, name)
    for name in sorted(set(b) - set(a)):
        LOG.error("only in %s: %s", right.name, name)
    for name in sorted(set(a) & set(b)):
        if a[name] != b[name]:
            LOG.error("differs: %s\n  %s: %s\n  %s: %s", name, left.name, a[name], right.name, b[name])

    order_a = [n for n in a if n in b]
    order_b = [n for n in b if n in a]
    if order_a != order_b:
        LOG.error("the shared entries are stored in a different order")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", nargs=2, type=Path, help="the two APKs to compare")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    left, right = args.apk

    try:
        a = split_regions(left)
        b = split_regions(right)
    except (NotAnApk, OSError, struct.error) as exc:
        LOG.error("%s", exc)
        return 2

    LOG.info("%-19s %9s %9s  %-16s", "region", "bytes A", "bytes B", "sha256 A")
    for region in a:
        same = a[region] == b[region]
        LOG.info(
            "%-19s %9d %9d  %s  %s",
            region,
            len(a[region]),
            len(b[region]),
            hashlib.sha256(a[region]).hexdigest()[:16],
            "same" if same else "DIFFERS",
        )

    reproducible = a["entries"] == b["entries"] and (
        a["central directory"] == b["central directory"]
    )
    if reproducible:
        LOG.info("reproducible: the APKs differ only in the signature")
        return 0

    LOG.error("NOT reproducible: something outside the signature moved")
    if a["entries"] != b["entries"]:
        report_entry_differences(left, right)
    return 1


if __name__ == "__main__":
    sys.exit(main())
