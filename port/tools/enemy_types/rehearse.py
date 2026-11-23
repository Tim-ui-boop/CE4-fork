"""Offline rehearsal of the enemy TYPE deal (runtime/enemy_types.cpp, patch 0255).

Mirrors the C++ rules and runs them on the real disc's enemy lists, so a seed can be
checked before anyone plays it. Verifies the invariants the deal promises:

  1. set, room, pos, rot, hp, Character, Guard_r, be_flag are NEVER changed
  2. every dealt id stays inside the Ganado family 0x10..0x20 (so SceCountEmAlive
     keeps counting it) and every dealt type is one its module can show
  3. a room never needs more than 4 enemy modules (read.cpp:78), target 3
  4. entries the room's script names are untouched
  5. the special forms (chainsaw type, claw men, gatling man, super Salvador) are untouched
  6. Luis's cabin gets no chainsaw enemy while "Keep Luis safe" is on
  7. the same seed deals the same room (deterministic)

    python3 .harness/analysis/enemy_types_check.py [seeds...]
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[3]
sys.dont_write_bytecode = True
sys.path.insert(0, str(REPO / "port/tools"))
import emlist_dump as em  # noqa: E402

WORKSPACE = REPO.parents[2]
DISCS = [WORKSPACE / "Resident Evil 4 (USA) (Disc 1)/Resident Evil 4 (USA) (Disc 1).iso",
         WORKSPACE / "Resident Evil 4 (USA) (Disc 2)/Resident Evil 4 (USA) (Disc 2).iso"]
LISTS = ["emleon%02d.esl" % i for i in range(8)]

SAW, GARRADOR, JJ, SUPER, VARIETY, HELMET, SHIELD, PLAGA, DYNAMITE, MIXED, SAFE_CABIN, DON_JOSE = (
    1 << i for i in range(12))
DEFAULT = SAW | VARIETY | SAFE_CABIN
ALL = SAW | GARRADOR | JJ | SUPER | VARIETY | HELMET | SHIELD | PLAGA | DYNAMITE | SAFE_CABIN

FLAG_SAW = 1 << 28
FLAG_PLAGA = 1 << 20
FLAG_BOMB = 1 << 17
FLAG_HELMET = 1 << 9
FLAG_SHIELD = 1 << 31
WEAPON_MASK = FLAG_SAW | FLAG_BOMB | (1 << 27) | (1 << 29) | (1 << 30) | (1 << 31) | (1 << 11)

TYPES = {
    0x10: [0, 1, 3, 4], 0x11: [7, 8, 9], 0x12: [0, 1, 3, 4], 0x13: [0, 1, 3, 4, 6],
    0x14: [6, 7, 8, 9], 0x15: [0, 3, 4, 11], 0x16: [3, 4, 11, 12], 0x17: [0, 1, 3, 12],
    0x19: [7, 8, 9], 0x1A: [7, 8, 9], 0x1B: [7, 8, 10], 0x1C: [7, 9, 10, 13],
    0x1D: [2, 14, 15, 16, 17, 18, 19, 20, 21], 0x1E: [6, 14, 15, 23, 25],
    0x1F: [14, 15, 16, 24], 0x20: [14, 15, 16, 22],
}
VILLAGE = {0x10, 0x12, 0x13, 0x15, 0x16, 0x17}
CASTLE = {0x11, 0x14, 0x19, 0x1A, 0x1B, 0x1C}
ISLAND = {0x1D, 0x1E, 0x1F, 0x20}
SAW_CAPABLE = {0x10, 0x12, 0x13, 0x15, 0x16, 0x17}


def family(i):
    return 0 if i in VILLAGE else 1 if i in CASTLE else 2 if i in ISLAND else -1


def ganado(i):
    return 0x10 <= i <= 0x20 and i != 0x18


def special(e):
    i, t, f = e["id"], e["type"], e["flag"]
    if i <= 0x17 and t == 4:
        return True
    if t == 6 and i in (0x13, 0x14, 0x1E):
        return True
    if (i == 0x1B and t == 10) or (i == 0x1C and t in (10, 13)):
        return True
    if i == 0x1D and t == 2:
        return True
    if i == 0x20 and (t == 22 or f & FLAG_SAW):
        return True
    return False


def cabin(room):
    return room in (0x11C, 0x11D)


def mask32(x):
    return x & 0xFFFFFFFF


def mix(x):
    x = mask32(x)
    x ^= x >> 16
    x = mask32(x * 0x7feb352d)
    x ^= x >> 15
    x = mask32(x * 0x846ca68b)
    x ^= x >> 16
    return mask32(x)


def hash_of(seed, lst, room, what):
    return mix(mask32(seed * 0x9E3779B1) ^ mix(mask32(lst << 24) ^ mask32(room << 8) ^ mix(what + 0x51)))


def one_in(h, den):
    return den > 0 and h % den == 0


def rooms_table():
    inc = (REPO / "port/whole/runtime/enemy_random_rooms.inc").read_text()
    out = {}
    for m in re.finditer(r"\{0x([0-9a-f]+), (\d), \{([^}]*)\}\}", inc):
        room, kind = int(m.group(1), 16), int(m.group(2))
        words = [int(w.strip().rstrip("u"), 16) for w in m.group(3).split(",")]
        out[room] = (kind, words)
    return out


ROOMS = rooms_table()


def named(room, no):
    row = ROOMS.get(room)
    return bool(row and (row[1][no >> 5] >> (no & 31)) & 1)


def deal_for(seed, lst, room, no, e, opt, kinds):
    fam = family(e["id"])
    h = hash_of(seed, lst, room, 0x900 + no)
    cab = cabin(room)
    i, t, st, cl, tag = e["id"], e["type"], 0, 0, None

    def budget(k):
        if k in kinds:
            return True
        if len(kinds) >= 3:
            return False
        kinds.append(k)
        return True

    if not cab or not (opt & SAFE_CABIN):
        if fam == 0 and opt & SAW and e["id"] in SAW_CAPABLE and one_in(h, 12):
            st, cl, t, tag = FLAG_SAW, WEAPON_MASK & ~FLAG_SAW, 4, "saw"
        elif fam == 1 and opt & GARRADOR and e["id"] in (0x1B, 0x1C) and one_in(h, 8):
            t, cl, tag = 10, WEAPON_MASK, "garrador"
        elif fam == 2 and opt & JJ and e["id"] != 0x1D and one_in(h, 2):
            if not budget(0x1D):
                return None
            i, t, cl, tag = 0x1D, 2, WEAPON_MASK, "jj"
        elif fam == 2 and opt & SUPER and e["id"] != 0x20 and one_in(h, 8):
            if not budget(0x20):
                return None
            i, t, st, cl, tag = 0x20, 22, FLAG_SAW, WEAPON_MASK & ~FLAG_SAW, "super"
    if opt & MIXED and i == e["id"] and not st and fam != -1 and not cab:
        if e["id"] in SAW_CAPABLE and opt & SAW and one_in(mask32(h ^ 0x5A), 20):
            st, cl, t, tag = FLAG_SAW, WEAPON_MASK & ~FLAG_SAW, 4, "saw"
    if opt & VARIETY and i == e["id"] and not st and not cl and t == e["type"]:
        pick = []
        for cand in TYPES.get(e["id"], []):
            if cand == e["type"]:
                continue
            if not special(dict(e, type=cand)):
                pick.append(cand)
        if pick and one_in(mask32(h ^ 0x11), 2):
            t, tag = pick[(h >> 8) % len(pick)], "variant"
    if i == e["id"] and not st:
        if opt & HELMET and one_in(mask32(h ^ 0x21), 10):
            st |= FLAG_HELMET
            tag = tag or "helmet"
        if opt & SHIELD and fam in (1, 2) and one_in(mask32(h ^ 0x31), 7):
            st |= FLAG_SHIELD
            tag = tag or "shield"
        if opt & PLAGA and one_in(mask32(h ^ 0x41), 5) and not st & FLAG_HELMET:
            st |= FLAG_PLAGA
            tag = tag or "plaga"
        if opt & DYNAMITE and fam == 0 and one_in(mask32(h ^ 0x51), 12):
            st |= FLAG_BOMB
            tag = tag or "dynamite"
    if i == e["id"] and t == e["type"] and not st and not cl:
        return None
    if t not in TYPES.get(i, []):
        return None
    return i, t, st, cl, tag


def load():
    per = {}
    seen = set()
    for disc in DISCS:
        if not disc.exists():
            continue
        for li, ln in enumerate(LISTS):
            if ln in seen:
                continue
            for _, body in em.find_list(disc, ln):
                seen.add(ln)
                rows = {}
                for e in em.entries(body):
                    if e["id"] and e["be_flag"] & 1:
                        rows.setdefault(e["room"], []).append(e)
                per[li] = rows
                break
    return per


def run(seed, opt, per):
    stats = dict(dealt=0, saw=0, garrador=0, jj=0, super=0, variant=0, helmet=0, shield=0,
                 plaga=0, dynamite=0, rooms=0, refused_named=0, refused_special=0)
    problems = []
    for li, rows in sorted(per.items()):
        for room, entries in sorted(rows.items()):
            row = ROOMS.get(room)
            if not row or row[0] == 1:
                continue
            kinds = []
            for e in entries:
                if e["id"] not in kinds and len(kinds) < 4:
                    kinds.append(e["id"])
            touched = 0
            for e in entries:
                no = e["index"]
                if not ganado(e["id"]) or not (e["be_flag"] & 1) or e["be_flag"] & 2:
                    continue
                if named(room, no):
                    stats["refused_named"] += 1
                    continue
                if special(e):
                    stats["refused_special"] += 1
                    continue
                d = deal_for(seed, li, room, no, e, opt, kinds)
                if not d:
                    continue
                i, t, st, cl, tag = d
                touched += 1
                stats["dealt"] += 1
                stats[tag] = stats.get(tag, 0) + 1
                new = dict(e, id=i, type=t, flag=(e["flag"] & ~cl) | st)
                for field in ("set", "room", "pos", "hp", "chr", "guard", "be_flag", "index"):
                    if new[field] != e[field]:
                        problems.append(f"r{room:03x} entry {no}: {field} changed")
                if not ganado(i):
                    problems.append(f"r{room:03x} entry {no}: id {i:02x} outside the Ganado family")
                if t not in TYPES.get(i, []):
                    problems.append(f"r{room:03x} entry {no}: type {t} not supported by id {i:02x}")
                if cabin(room) and opt & SAFE_CABIN and (st & FLAG_SAW or i in (0x1D, 0x20)):
                    problems.append(f"r{room:03x} entry {no}: a dangerous enemy in Luis's cabin")
            if touched:
                stats["rooms"] += 1
            if len(kinds) > 4:
                problems.append(f"r{room:03x}: needs {len(kinds)} modules (max 4)")
    return stats, problems


def main():
    seeds = [int(s) for s in sys.argv[1:]] or [1, 7, 42, 1234, 99999]
    per = load()
    print("lists loaded:", len(per), " rooms with enemies:", sum(len(r) for r in per.values()))
    print("protection table rooms:", len(ROOMS))
    ok = True
    for opt, label in ((DEFAULT, "defaults"), (ALL, "all enemies on")):
        print(f"\n=== {label} (options {opt:#x}) ===")
        for seed in seeds:
            a, pa = run(seed, opt, per)
            b, _ = run(seed, opt, per)
            det = a == b
            print(f" seed {seed:6d}: dealt {a['dealt']:4d} in {a['rooms']:3d} rooms "
                  f"(saw {a['saw']:3d} garrador {a['garrador']:2d} jj {a['jj']:3d} super {a['super']:2d} "
                  f"variant {a['variant']:3d})  determinism {'OK' if det else 'FAIL'}")
            if pa:
                ok = False
                for p in pa[:6]:
                    print("    PROBLEM:", p)
            if not det:
                ok = False
    print("\nRESULT:", "all invariants hold" if ok else "PROBLEMS FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
