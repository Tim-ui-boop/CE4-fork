#!/usr/bin/env python3
"""The enemy randomizer's protected list (0.99.12, port/docs/gegner-randomizer-plan.md): reads the room scripts
src/st1..st3/rXYZ.cpp and writes port/whole/runtime/enemy_random_rooms.inc.

For each room script:
  - the enemy list entries it names with a number (the first argument of EmSetFromList2, EmListSetAlive,
    GetEmPtrFromList, GetEmIdFromList, setEm, setPtr, EmSetDie*): protected, the randomizer leaves them as they are;
  - "untouched" when a boss is there, it names list entries through variables or tables the tool cannot follow
    or directly in the list (Em_list / Em_flg, lifeMeterBoss, GamePointBossReset, PlRegistBoss, a non-number first
    argument), or it counts enemies and does one of these as well;
  - "counts only" (0.99.13, stage 1.5) when it only counts or sweeps enemies (SceCountEmAlive, SceDestroyEm, EmMgr
    loops, kill counters): the places may change there, the number never (port/docs/gegner-randomizer-plan.md).
Rooms without a script file are not in the table (the randomizer may use them; the runtime protects the entries the
room's areas wait for). Prints a summary; --check compares the include file with what it would write.

    port/tools/enemy_random_table.py [--check]
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "port", "whole", "runtime", "enemy_random_rooms.inc")
CALLS = ("EmSetFromList2", "EmListSetAlive", "GetEmPtrFromList", "GetEmIdFromList", "setEm", "setPtr",
         "EmSetDieCk", "EmSetDieOn", "EmSetDie")
UNTOUCHED = {
    "counts or sweeps enemies": r"SceCountEmAlive|SceDestroyEm|EmMgr\s*\.|getArrayNum|c_kill_cnt|[kK]ill[A-Z_]*[cC]nt|"
                                r"[dD]ead[A-Z_]*[cC]nt|emNum",
    "names list entries directly": r"Em_list|Em_flg|EM_FLG_ROW|EM_DEAD_BIT",
    "a boss": r"lifeMeterBoss|GamePointBossReset|PlRegistBoss",
}
# A room whose lock is only the scanner's caution (names entries through variables, counts and more): the
# runtime can tell a boss room from a cautious room (0.99.16, unsafe mode) by the marker in its row comment.
BOSS_ONLY = re.compile(r"lifeMeterBoss|GamePointBossReset|PlRegistBoss")

# Counting that keeps the whole Ganado family (0x10..0x20) in its range ("all villagers dead"): the count
# still holds when a Ganado becomes another form of the family, so the room is kind-safe in unsafe mode
# (0.99.16, the owner's wish of 17.11.2026). A narrow range (or a destroy by kind) pins the kinds.
FAMILY_COUNT = re.compile(r"SceCountEmAlive\s*\(\s*(0[xX]10|16)\s*,\s*(0[xX]20|32)\s*\)")
NARROW_COUNT = re.compile(r"SceCountEmAlive\s*\(\s*(?!0[xX]10\b|16\b)(0[xX][0-9a-fA-F]+|\d+)\s*[,)]")
NARROW_DESTROY = re.compile(r"SceDestroyEm\s*\(")
NUMBER = re.compile(r"^\s*(0[xX][0-9a-fA-F]+|\d+)\s*$")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def first_argument(text, start):
    """The first argument of the call whose '(' is at text[start]."""
    depth, i = 0, start
    for i in range(start, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        elif c == "," and depth == 1:
            return text[start + 1:i]
    return text[start + 1:]


def kind_of(why, narrow=False):
    """0 open, 1 untouched, 2 counts only, and -- with family-wide counts only -- 3: kind-safe counts (0.99.16:
    in unsafe mode a Ganado of such a room may become another form of its family; the count still holds)."""
    if not why:
        return 0
    if why == ["counts or sweeps enemies"]:
        return 2 if narrow else 3
    return 1


def room_of(path):
    m = re.match(r"r([1-3])([0-9a-f]{2})\.cpp$", os.path.basename(path), re.I)
    return int(m.group(1), 16) << 8 | int(m.group(2), 16) if m else None


def scan(path):
    text = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    why = []
    for reason, pattern in UNTOUCHED.items():
        if re.search(pattern, text):
            why.append(reason)
    protected = set()
    for name in CALLS:
        for m in re.finditer(r"\b%s\s*\(" % name, text):
            arg = first_argument(text, m.end() - 1)
            n = NUMBER.match(arg)
            if n:
                v = int(n.group(1), 0)
                if 0 <= v < 256:
                    protected.add(v)
            elif "variable list entries" not in why:
                why.append("variable list entries")
    # 0.99.16: family-wide counts (0x10..0x20) are kind-safe; a narrow count or a destroy pins the kinds
    narrow = bool(NARROW_COUNT.search(text)) or bool(NARROW_DESTROY.search(text))
    family = bool(FAMILY_COUNT.search(text)) and not narrow
    return why, protected, narrow, family, bool(BOSS_ONLY.search(text))


def main():
    rows = []
    for stage in ("st1", "st2", "st3"):
        folder = os.path.join(ROOT, "src", stage)
        for name in sorted(os.listdir(folder)):
            room = room_of(name)
            if room is None:
                continue
            why, protected, narrow, family, boss = scan(os.path.join(folder, name))
            rows.append((room, why, protected, narrow, family, boss))
    lines = ["// Generated by port/tools/enemy_random_table.py from the room scripts (src/st1..st3/rXYZ.cpp): do not",
             "// edit by hand. One row a room: the room (stage << 8 | room), its kind (0 open; 1 untouched: the script",
             "// names list entries through variables or directly in the list, has a boss, or counts and does more; 2 counts",
             "// only, its counts narrow: the places of its ordinary Ganados may change, their number and kinds never; 3",
             "// counts only, and family-wide: the count spans the whole Ganado family, so in unsafe mode a Ganado of",
             "// the room may become another form of its family and the count still holds), whether a boss owns the",
             "// room (locked even in unsafe mode), and the list entries the script names by number (bits, entry 0 the",
             "// lowest bit of word 0).",
             ""]
    for room, why, protected, narrow, family, boss in rows:
        words = [0] * 8
        for v in protected:
            words[v >> 5] |= 1 << (v & 31)
        lines.append("{0x%03x, %d, %d, {%s}},  // r%03x%s" % (room, kind_of(why, narrow), 1 if boss else 0,
                                                          ", ".join("0x%08xu" % w for w in words), room,
                                                          ": " + ", ".join(why) if why else
                                                          (": %d named" % len(protected) if protected else "")))
    out = "\n".join(lines) + "\n"
    if "--check" in sys.argv:
        old = open(OUT, encoding="utf-8").read().replace("\r\n", "\n") if os.path.exists(OUT) else ""
        print("enemy_random_rooms.inc: %s" % ("up to date" if old == out else "OUT OF DATE"))
        return 0 if old == out else 1
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    untouched = [r for r in rows if kind_of(r[1], r[3]) == 1]
    counts = [r for r in rows if kind_of(r[1], r[3]) == 2]
    family = [r for r in rows if kind_of(r[1], r[3]) == 3]
    print("%d room scripts: %d untouched (%d with a boss), %d counts only (narrow), %d counts family-wide, "
          "%d open (%d of them name entries by number)" % (
        len(rows), len(untouched), sum(1 for r in untouched if r[5]), len(counts), len(family),
        len(rows) - len(untouched) - len(counts) - len(family),
        sum(1 for r in rows if not r[1] and r[2])))
    print("untouched: " + " ".join("r%03x" % r[0] for r in untouched))
    print("counts only: " + " ".join("r%03x" % r[0] for r in counts))
    print("counts family-wide: " + " ".join("r%03x" % r[0] for r in family))
    print("open: " + " ".join("r%03x" % r[0] for r in rows if not r[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
