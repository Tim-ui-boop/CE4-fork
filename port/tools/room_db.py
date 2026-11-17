#!/usr/bin/env python3
"""The room database of the enemy randomizer: every enemy list record of the imported disc,
plus the per-room contract its own script imposes.

Built from two sources, both already in this repository:

  - the disc's enemy lists (files/etc/emleon0X.esl, omake0X.esl): 32-byte records, big endian,
    read straight into pG->Em_list (src/game/stage.cpp readEmList, include/global.h EM_LIST)
  - the decompiled room scripts (src/st1, st2, st3): which list slots a room spawns by number
    (EmSetFromList2), which it checks, which enemy kinds it counts

Why the contract matters: a script that spawns slot 4 expects slot 4 to hold what the disc put
there. The randomizer of 17.11.2026 changed such slots and the room broke. Anything this tool
marks as protected must not be touched by a rewrite.

    tools/room_db.py [--data DIR] [--src DIR] [--out FILE] [--stats]

Exit code 1 when the disc data is missing or a list file is malformed.
"""

import argparse
import collections
import json
import os
import re
import struct
import sys

# EM_LIST (include/global.h): 0x20 bytes, big endian
SIZE = 0x20


def records(path):
    """The records of one .esl file, in disk order."""
    data = open(path, 'rb').read()
    if len(data) % SIZE:
        raise SystemExit(f"{path}: {len(data)} bytes is not a whole number of {SIZE}-byte records")
    out = []
    for i in range(len(data) // SIZE):
        e = data[i * SIZE:(i + 1) * SIZE]
        out.append({
            "slot": i,
            "be_flag": e[0],
            "id": e[1],
            "type": e[2],
            "set": e[3],
            "flag": struct.unpack_from('>I', e, 4)[0],
            "hp": struct.unpack_from('>H', e, 8)[0],
            "character": e[11],
            "pos": list(struct.unpack_from('>3h', e, 12)),
            "rot": list(struct.unpack_from('>3h', e, 18)),
            "room": struct.unpack_from('>H', e, 24)[0],
            "guard": struct.unpack_from('>h', e, 26)[0],
        })
    return out


# ---- the room's own contract, read from the decompiled script -------------------------------

SPAWN = re.compile(r'EmSetFromList2\(\s*(0x[0-9A-Fa-f]+|\d+)\s*,')
COUNT = re.compile(r'EmList\w*Cnt|EmCount|countEm|emCount')


def script_contract(src, room_id):
    """What the room's script asks of its list. `room_id` is e.g. 0x100."""
    name = f"r{room_id:03x}.cpp"
    hits = []
    for folder in ("st1", "st2", "st3", "st4"):
        path = os.path.join(src, folder, name)
        if os.path.isfile(path):
            hits.append(path)
    if not hits:
        return None
    spawn_slots, counted, named = set(), [], []
    for path in hits:
        text = open(path, encoding='utf-8', errors='replace').read()
        for m in SPAWN.finditer(text):
            spawn_slots.add(int(m.group(1), 0))
        for line in text.split('\n'):
            if COUNT.search(line):
                counted.append(line.strip()[:160])
            # a script that names an enemy kind decides what the room holds
            if re.search(r'EmSetFromList2\(', line):
                named.append(line.strip()[:160])
    return {
        "script": os.path.relpath(hits[0], os.path.dirname(src)),
        "spawn_slots": sorted(spawn_slots),
        "counts": counted[:40],
        "spawns": named[:60],
    }


def main():
    # room_db.py sits in <repo>/port/tools: the game's sources are two more levels up
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=None, help="the imported disc 1 folder (default: ask)")
    ap.add_argument("--src", default=os.path.join(root, "src"))
    ap.add_argument("--out", default=os.path.join(root, "port", "tools", "room_db.json"))
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()
    if not args.data:
        raise SystemExit("--data: the disc 1 folder holding files/etc/emleon00.esl")

    etc = os.path.join(args.data, "files", "etc")
    files = sorted(f for f in os.listdir(etc) if f.endswith(".esl"))
    if not files:
        raise SystemExit(f"no .esl in {etc}")

    rooms = collections.defaultdict(list)
    lists = {}
    for f in files:
        recs = records(os.path.join(etc, f))
        lists[f] = len(recs)
        for r in recs:
            if r["room"]:
                r["list"] = f
                rooms[r["room"]].append(r)

    # A room can appear in several list files: emleon00..07 are the Story's lists (stage.cpp
    # checkEmListNo picks one by chapter), while emleon08/09 and omake* belong to the bonus modes
    # (Assignment Ada, the Mercenaries). The same room id then carries a different enemy set per
    # mode, so each list variant is its own entry: a rewrite of the Story must never touch the
    # Mercenaries' copy of the same room.
    BONUS = {"emleon08.esl", "emleon09.esl", "omake00.esl", "omake01.esl", "omake02.esl"}
    db = {}
    for room_id, recs in rooms.items():
        contract = script_contract(args.src, room_id)
        protect = set(contract["spawn_slots"]) if contract else set()
        by_list = collections.defaultdict(list)
        for r in recs:
            by_list[r["list"]].append(r)
        for list_name, list_recs in by_list.items():
            used = set()
            variants = []
            for r in list_recs:
                # a slot a script spawns by number is the room's own plan: a rewrite leaves it alone;
                # a slot id used more than once inside one list (a second chapter variant of the same
                # room) is NOT a duplicate to merge -- keep both
                marked = dict(r)
                marked["protected"] = r["slot"] in protect
                variants.append(marked)
            db[f"0x{room_id:03x}@{list_name}"] = {
                "room": f"0x{room_id:03x}",
                "list": list_name,
                "mode": "bonus" if list_name in BONUS else "story",
                "records": variants,
                "contract": contract,
            }

    out = {
        "source": os.path.abspath(etc),
        "lists": lists,
        "rooms": db,
    }
    with open(args.out, "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)

    if args.stats:
        total = sum(len(r["records"]) for r in db.values())
        prot = sum(1 for r in db.values() for x in r["records"] if x["protected"])
        withscript = sum(1 for r in db.values() if r["contract"])
        print(f"lists {len(lists)}, rooms {len(db)}, records {total}, protected {prot}, "
              f"rooms with a script {withscript}")
        print(f"written {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
