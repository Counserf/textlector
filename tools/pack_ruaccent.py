#!/usr/bin/env python3
"""Pack RUAccent JSON dictionaries into a compact mmap-friendly binary file.

Format v1 (little-endian):
  0..7   magic: b"RAPACK1\0"
  8..11  entry count (u32)
 12..15  entry size (u32, currently 16)
  then N entries of:
   key_offset, key_length, value_offset, value_length (u32 each)
  followed by one UTF-8 blob. Offsets are relative to the blob start.

List values are joined by ASCII Unit Separator (0x1f). Set mode stores no values.
The index is sorted by normalized UTF-8 key bytes, allowing Swift to binary-search
without materializing the whole dictionary as objects in RAM.
"""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path

MAGIC = b"RAPACK1\0"
ENTRY_SIZE = 16
LIST_SEPARATOR = "\x1f"


def normalize_key(value: str) -> str:
    return value.lower()


def encode_value(value: object, mode: str) -> bytes:
    if mode == "set":
        return b""
    if mode == "string":
        if not isinstance(value, str):
            raise TypeError(f"Expected string value, got {type(value).__name__}")
        return value.encode("utf-8")
    if mode == "list":
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            raise TypeError("Expected list[str] value")
        return LIST_SEPARATOR.join(value).encode("utf-8")
    raise ValueError(f"Unsupported mode: {mode}")


def pack(source: Path, destination: Path, mode: str) -> None:
    with source.open("r", encoding="utf-8") as fh:
        root = json.load(fh)
    if not isinstance(root, dict):
        raise TypeError("RUAccent dictionary root must be a JSON object")

    normalized: dict[str, bytes] = {}
    for raw_key, raw_value in root.items():
        if not isinstance(raw_key, str):
            continue
        key = normalize_key(raw_key)
        normalized[key] = encode_value(raw_value, mode)

    items = sorted(
        ((key.encode("utf-8"), value) for key, value in normalized.items()),
        key=lambda pair: pair[0],
    )

    blob = bytearray()
    entries: list[tuple[int, int, int, int]] = []
    for key, value in items:
        key_offset = len(blob)
        blob.extend(key)
        value_offset = len(blob)
        blob.extend(value)
        entries.append((key_offset, len(key), value_offset, len(value)))

    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as out:
        out.write(struct.pack("<8sII", MAGIC, len(entries), ENTRY_SIZE))
        for entry in entries:
            out.write(struct.pack("<IIII", *entry))
        out.write(blob)

    print(
        f"packed {source.name}: mode={mode} entries={len(entries)} "
        f"json={source.stat().st_size} bytes pack={destination.stat().st_size} bytes"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--mode", choices=("string", "list", "set"), required=True)
    args = parser.parse_args()
    pack(args.source, args.destination, args.mode)


if __name__ == "__main__":
    main()
