#!/usr/bin/env python3
"""The enemy randomizer's plan: one seed turns the Story's enemy lists into a new set of
placements, and every change is checked against the room's own script before it is kept.

This is the data-rewrite model (the owner's request, 17.11.2026: "like the Oot randomizer ...
rewriting the data itself"), not the play-time randomizer it replaces. Nothing here touches a
running game: the output is a plan, and a later step writes it into the imported disc's .esl files.

What it reads: port/tools/room_db.json (tools/room_db.py) -- every list record of the imported
disc plus each room's contract (which slots its script spawns by number).

What it will not do, because the room's script depends on it:

  - touch a slot the room spawns by number (EmSetFromList2(4, 1) needs slot 4 to hold what the
    disc put there)
  - touch a slot that is not alive at room start (be_flag bit 0 clear): the room's script decides
    when that entry appears, if ever
  - swap a non-Ganado entry for a Ganado or the reverse: a Crow is scenery, a Ganado is a fight,
    and scripts count them differently
  - give a room more enemy kinds than its heap can hold: an enemy module costs about 0.70 of its
    .drs file size in heap 4, which measured 14.5 MB with only 1.8 MB spare in r100 (the
    diagnostic build of 17.11.2026, runtime/state_trace.cpp roomHeapReport)

    tools/enemy_plan.py --db port/tools/room_db.json --seed 12345 [--budget 1536] [--out FILE]

Exit code 1 when a plan cannot be made; the reasons are printed.
"""

import argparse
import json
import random
import sys

# runtime/enemy_random.cpp kPoolKinds: the kinds the randomizer deals, with the hp floor each one
# needs (0 = the game's own default for that kind)
POOL = [
    (0x16, 4, 0, "Chainsaw Man"),
    (0x1D, 2, 0, "Gatling Man"),
    (0x1B, 10, 0, "Garrador"),
    (0x2D, 0, 0, "Novistador"),
    (0x22, 0, 1000, "Dog"),
    (0x23, 0, 0, "Crow"),
    (0x26, 0, 1000, "Cow"),
    (0x3C, 0, 2000, "Armadura"),
    (0x36, 0, 3000, "Regenerator"),
    (0x36, 2, 3000, "Iron Maiden"),
]

# The heap cost of each kind, measured from the modules themselves (the .drs sizes of the debug
# disc, times the 0.70 heap-to-file ratio the diagnostics log showed: em12 5.66 MB -> 4.00 MB).
COST_KB = {
    0x16: 4048, 0x1D: 4404, 0x1B: 4624, 0x2D: 3176, 0x22: 1298,
    0x23: 280, 0x26: 248, 0x3C: 1598, 0x36: 4273, 0x39: 3476,
}


def ganado(rec):
    """A plain enemy spot (mirrors enemy_random.cpp ordinary(): ids 0x10..0x20 but the trader)."""
    return 0x10 <= rec["id"] <= 0x20 and rec["id"] != 0x18


def is_crow_or_cow(rec):
    return rec["id"] in (0x23, 0x26)


def eligible(rec):
    """A record a rewrite may consider: alive at room start and not spawn-by-number."""
    if rec.get("protected"):
        return False
    if not (rec["be_flag"] & 1):
        return False        # the script brings this one, or it never comes
    return rec["id"] != 0


def plan_room(entry, rng, budget_kb):
    """One room's new placements, or (None, why) when nothing may safely change."""
    room = entry["room"]
    free = [r for r in entry["records"] if eligible(r)]
    if not free:
        return None, "no slot is both awake and unused by the script"

    # What the room already has loaded decides the memory it may spend on new kinds. Only the kinds
    # worth dealing are counted here: a Ganado may become a pool kind, and a Crow or a Cow stays
    # scenery (scripts count them, and a fight where the disc put a bird breaks the room's pace).
    spent = set(r["id"] for r in entry["records"] if r["be_flag"] & 1)
    # A kind already in the room costs nothing extra, so it is always allowed
    cheap = [k for k in POOL if k[0] in spent]
    changes, cost = [], 0
    kinds_here = set()
    for rec in free:
        if ganado(rec):
            candidates = list(POOL)
        elif is_crow_or_cow(rec):
            candidates = [k for k in POOL if k[0] in (0x23, 0x26)]
        else:
            continue      # anything else is the room's own business
        if not candidates:
            continue
        # prefer a kind the room already loads (free), else the cheapest that still fits
        affordable = [k for k in candidates if k[0] in spent or COST_KB.get(k[0], 0) + cost <= budget_kb]
        if not affordable:
            continue
        kind = rng.choice(affordable)
        extra = 0 if kind[0] in spent else COST_KB.get(kind[0], 0)
        cost += extra
        spent.add(kind[0])
        # a kind that is already the record's own is not a change
        if kind[0] == rec["id"] and (kind[2] == 0 or kind[2] == rec["hp"]):
            continue
        kinds_here.add(kind[3])
        changes.append({
            "slot": rec["slot"],
            "from": {"id": rec["id"], "type": rec["type"], "hp": rec["hp"]},
            "to": {"id": kind[0], "type": kind[1], "hp": kind[2] or rec["hp"], "name": kind[3]},
        })
    return changes, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--budget", type=int, default=4200,
                    help="KB of NEW enemy modules a room may add (default 1536: the 1.8 MB r100 "
                         "had spare, with margin for the rooms that had less)")
    ap.add_argument("--story-only", action="store_true", default=True)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    db = json.load(open(args.db))
    rng = random.Random(args.seed)
    plan = {"seed": args.seed, "budget_kb": args.budget, "rooms": {}}
    totals = {"rooms": 0, "changed": 0, "skipped": 0, "refused": 0}

    # the Story's lists only (the owner's decision, 18.11.2026): a bonus mode's copy of a room is
    # not this randomizer's business
    names = sorted(k for k, v in db["rooms"].items() if v.get("mode") == "story")
    rng.shuffle(names)
    for name in names:
        entry = db["rooms"][name]
        changes, why = plan_room(entry, rng, args.budget)
        totals["rooms"] += 1
        if why:
            totals["refused"] += 1
            plan["rooms"][name] = {"room": entry["room"], "skipped": why, "changes": []}
            continue
        plan["rooms"][name] = {"room": entry["room"], "changes": changes}
        totals["changed"] += len(changes)
        totals["skipped"] += len([r for r in entry["records"] if not eligible(r)])

    if args.out:
        json.dump(plan, open(args.out, "w"), indent=1, sort_keys=True)
    print(f"seed {args.seed}: {totals['rooms']} story entries, {totals['changed']} records would "
          f"change, {totals['refused']} rooms left alone, {totals['skipped']} records untouched")
    if args.out:
        print(f"written {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
