#!/usr/bin/env python3
"""Turns a plan into the randomizer's sidecar files.

The loader (port/whole/runtime/dvd.cpp entryPath) prefers a file beside the disc's own one with
".re4" appended: files/etc/emleon00.esl.re4 over files/etc/emleon00.esl. So the disc stays
byte-identical (the app's hash check keeps passing) and deleting the sidecar restores vanilla --
no backup, no defaults file.

A sidecar is a complete list, not a patch: the game asks the file system for a whole file, so the
sidecar is the original's bytes with the planned records rewritten. 8 KB per touched list, at most
13 files (104 KB), which is small enough not to matter.

    tools/enemy_write.py --db room_db.json --plan plan.json --data <disc1 folder> [--apply DIR]

Writes nothing unless --apply names the disc 1 folder; with it, the .re4 files go beside the
originals in files/etc. Without it, the byte changes are only reported.
"""

import argparse
import json
import os
import struct
import sys

SIZE = 0x20


def patch_record(rec, change):
    """One record with the plan's id, type and hp applied; every other byte is the disc's own."""
    r = bytearray(rec)
    to = change["to"]
    r[1] = to["id"] & 0xFF
    r[2] = to["type"] & 0xFF
    struct.pack_into('>H', r, 8, to["hp"] & 0xFFFF)
    # alive, and not yet set: the game creates it at room start, like a record it shipped with
    r[0] = (r[0] & ~0x02) | 0x01
    return bytes(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--plan", required=True)
    ap.add_argument("--data", required=True, help="the imported disc 1 folder (holds files/etc)")
    ap.add_argument("--apply", action="store_true",
                    help="really write the .re4 sidecars; without it only the changes are listed")
    args = ap.parse_args()

    db = json.load(open(args.db))
    plan = json.load(open(args.plan))
    etc = os.path.join(args.data, "files", "etc")
    if not os.path.isdir(etc):
        raise SystemExit(f"not a disc 1 folder: {etc}")

    # group the planned changes by list file and room
    by_file = {}
    for entry_name, entry in plan["rooms"].items():
        changes = entry.get("changes") or []
        if not changes:
            continue
        room = entry["room"]
        list_name = entry_name.split("@", 1)[1]
        by_file.setdefault(list_name, {})[room] = changes

    written, records = [], 0
    for list_name, rooms in sorted(by_file.items()):
        src = os.path.join(etc, list_name)
        if not os.path.isfile(src):
            print(f"  missing, skipped: {list_name}")
            continue
        data = bytearray(open(src, "rb").read())
        n = len(data) // SIZE
        changed_here = 0
        for i in range(n):
            room = struct.unpack_from('>H', data, i * SIZE + 24)[0]
            key = f"0x{room:03x}"
            for change in rooms.get(key, ()):
                if change["slot"] != i:
                    continue
                data[i * SIZE:(i + 1) * SIZE] = patch_record(bytes(data[i * SIZE:(i + 1) * SIZE]), change)
                changed_here += 1
        if changed_here == 0:
            continue
        records += changed_here
        dst = src + ".re4"
        if args.apply:
            # write beside, then rename: a half-written sidecar must never be read
            tmp = dst + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, dst)
        written.append((list_name, changed_here))

    for name, count in written:
        print(f"  {name:16s} {count:3d} records" + ("" if args.apply else f"  -> would write {name}.re4"))
    print(f"{records} records over {len(written)} lists" + ("" if args.apply else " (dry run: --apply to write)"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
