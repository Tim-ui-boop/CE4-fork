#!/usr/bin/env python3
"""The enemy randomizer's protected list (0.99.12, port/docs/gegner-randomizer-plan.md): reads the room scripts
src/st1..st3/rXYZ.cpp and writes port/whole/runtime/enemy_random_rooms.inc.

Stage 2 (prototype, branch random-enemies, 16.11.2026, plan section 10): a room script names an enemy list entry
either to drive the live enemy ("driven": EmSetFromList2, setEm, setPtr, GetEmPtrFromList, GetEmIdFromList, an
Em_list[N] access other than its be_flag) or only to switch the entry on or off and keep its death ("lifecycle":
EmListSetAlive, EmSetDie*, Em_list[N].be_flag). Only driven entries are protected (their places stay); a lifecycle
entry may come at another place, the script never looks where it stands. A driven call whose entry is a variable
protects every number of the tables its argument names (a brace initializer in the file), or, when it names none
the tool knows, every number 0..255 of every brace initializer in the file (more than needed, never less).

For each room script:
  - kind 1 "untouched" when a boss is there (lifeMeterBoss, GamePointBossReset, PlRegistBoss) or the room is in
    KEEP below (with the reason);
  - kind 0 "open" when the script neither counts nor sweeps enemies, names no list entry directly and through no
    variable (stage 1): places and number may change (the number only from 10 Ganados on, runtime);
  - kind 2 "places only" otherwise: the places of the room's unprotected ordinary Ganados may change, the number
    never (the script may count them).
Rooms without a script file are not in the table (the runtime leaves them as they are). Prints a summary; --check
compares the include file with what it would write.

    port/tools/enemy_random_table.py [--check]
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "port", "whole", "runtime", "enemy_random_rooms.inc")
DRIVEN = ("EmSetFromList2", "GetEmPtrFromList", "GetEmIdFromList", "setEm", "setPtr")
COUNTS = r"SceCountEmAlive|SceDestroyEm|EmMgr\s*\.|getArrayNum|c_kill_cnt|[kK]ill[A-Z_]*[cC]nt|[dD]ead[A-Z_]*[cC]nt|emNum"
DIRECT = r"Em_list|Em_flg|EM_FLG_ROW|EM_DEAD_BIT"
BOSS = r"lifeMeterBoss|GamePointBossReset|PlRegistBoss"
# Rooms kept as they are although no boss is there (stage 2 prototype; the owner decides)
KEEP = {
}
# Rooms the owner hand-opened although their scripts count or index Em_list directly. The DRIVEN
# rows still keep their disc bytes; only the ordinary rows join the deal.
OPEN_HAND = {
    0x100: "the owner asked for the opening room; the script spawns were verified in r100.cpp",
}
# Rows that must keep their exact disc bytes for story reasons (the dog Leon rescues: row 0x56 in
# the farm, row 0x09 in the village's after-state fight). Hand-verified; never dealt, and their
# module keeps a slot in the room budget.
KEEP_ROWS = {
    0x100: {0x09},
    0x103: {0x56},
}
NUMBER = re.compile(r"^\s*(0[xX][0-9a-fA-F]+|\d+)\s*$")
INT = re.compile(r"\b(0[xX][0-9a-fA-F]+|\d+)\b")
# Modules a room preloads itself (r106 loads four before any enemy spawns, src/st1/r106.cpp:97-100).
FORCED = re.compile(r"\b(?:EmReadSearch|SearchEmModule)\s*\(\s*(0[xX][0-9a-fA-F]+|\d+)")
COUNT_CALL = re.compile(r"\b(?:SceCountEmAlive|SceDestroyEm)\s*\(([^)]*)\)")
# Rows the script only spawns with setEm/EmSetFromList2, never reads the identity of. Verified by
# hand in the room scripts. They may join the ordinary offline deal; the runtime .inc is unchanged.
SPAWN = {
    0x117: {0x50, 0x51},          # church interior, after Ashley is found
    0x118: set(range(0x79, 0x80)),  # church in the storm, after Ashley is found
    # r100's script-spawned rows (src/st1/r100.cpp): the horde (W->ems 3,4,5,0x12,0x13,0x26,0x27),
    # the house Ganado (0x25) and the after-state rows of r100_trap_set. Rows 6,7,8 (setEvtMotion
    # hard-casts them to cEmGanado) and row 9 (the story dog) stay DRIVEN/KEEP instead.
    0x100: {0x03, 0x04, 0x05, 0x12, 0x13, 0x25, 0x26, 0x27,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x1D, 0x7A},
}


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


def argument_count(text, start):
    """The number of arguments of the call whose '(' is at text[start]."""
    depth, count = 0, 1
    for i in range(start, len(text)):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return count
        elif c == "," and depth == 1:
            count += 1
    return count


def tables_of(text):
    """Every brace-initialised array of the file: name -> the numbers 0..255 in its initializer."""
    out = {}
    for m in re.finditer(r"\b(\w+)\s*(?:\[[^\]]*\]\s*)+=\s*\{", text):
        depth, i = 0, m.end() - 1
        for i in range(m.end() - 1, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    break
        nums = {int(v, 0) for v in INT.findall(text[m.end():i])}
        out.setdefault(m.group(1), set()).update(v for v in nums if 0 <= v < 256)
    return out


def numbers_of(expr, tables, every):
    """The list entries a non-number argument may name: the numbers of the tables it names, else all."""
    names = [w for w in re.findall(r"\b[A-Za-z_]\w*\b", expr) if w in tables]
    if not names:
        return set(every)
    out = set()
    for w in names:
        out |= tables[w]
    return out


def kind_of(why):
    """0 open, 1 untouched, 2 places only."""
    if "a boss" in why or any(w.startswith("kept:") for w in why):
        return 1
    if any(w.startswith("open by hand:") for w in why):
        return 0
    return 2 if why else 0


def count_alias(path):
    """True when a room's only SceCount/Destroy calls are the exact (0x10,0x20) range.

    Those are the calls the Phase 2 engine patch aliases; a single-id count such as 0x36 or 0x1D
    must never be treated as aliased, or a boss on a counted row would break that room's script.
    """
    text = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    calls = ["".join(m.group(1).split()) for m in COUNT_CALL.finditer(text)]
    return bool(calls) and all(c == "0x10,0x20" for c in calls)


def room_of(path):
    m = re.match(r"r([1-3])([0-9a-f]{2})\.cpp$", os.path.basename(path), re.I)
    return int(m.group(1), 16) << 8 | int(m.group(2), 16) if m else None


def scan(path, room):
    text = strip_comments(open(path, encoding="utf-8", errors="replace").read())
    tables = tables_of(text)
    every = set()
    for v in tables.values():
        every |= v
    why = []
    if room in OPEN_HAND:
        why.append("open by hand: " + OPEN_HAND[room])
    if re.search(COUNTS, text):
        why.append("counts or sweeps enemies")
    if re.search(DIRECT, text):
        why.append("names list entries directly")
    if re.search(BOSS, text):
        why.append("a boss")
    if room in KEEP:
        why.append("kept: " + KEEP[room])
    protected = set()
    variable = False
    for name in DRIVEN:
        for m in re.finditer(r"\b%s\s*\(" % name, text):
            arg = first_argument(text, m.end() - 1)
            n = NUMBER.match(arg)
            if n:
                v = int(n.group(1), 0)
                if 0 <= v < 256:
                    protected.add(v)
            elif name == "setPtr" and argument_count(text, m.end() - 1) == 2:
                pass  # setPtr(cEm*, errOn) takes a live enemy, not a list entry
            else:
                protected |= numbers_of(arg, tables, every)
                variable = True
    # an Em_list entry read or written other than its be_flag: driven
    for m in re.finditer(r"Em_list\s*\[([^\]]*)\]\s*(\.\s*be_flag\b)?", text):
        if m.group(2):
            continue
        n = NUMBER.match(m.group(1))
        if n:
            v = int(n.group(1), 0)
            if 0 <= v < 256:
                protected.add(v)
        elif not re.match(r"^\s*i\s*$", m.group(1)) or "r327" not in path:
            # (r327's loop over every entry only clears the be_flag of the 0x1D ones: lifecycle)
            protected |= numbers_of(m.group(1), tables, every)
            variable = True
    if variable:
        why.append("variable list entries")
    forced = {int(v, 0) for v in FORCED.findall(text) if 0 <= int(v, 0) < 256}
    return why, protected, forced


def java_source(rows):
    """The same table as a Java source file for the offline launcher dealer."""
    lines = [
        "package com.ast.re4decomp;",
        "",
        "// Generated by port/tools/enemy_random_table.py from the room scripts (src/st1..st3/rXYZ.cpp):",
        "// do not edit by hand. KIND: 0 open, 1 untouched, 2 places only. DRIVEN entries are the",
        "// list numbers the script spawns or reads; their records keep their exact disc bytes.",
        "final class EnemyRooms {",
        "  private EnemyRooms() {}",
        "",
        "  static final int OPEN = 0, UNTOUCHED = 1, PLACES_ONLY = 2;",
        "",
        "  static final int[] ROOM = {",
    ]
    for room, _why, _protected, _forced, _alias in rows:
        lines.append("    0x%03x," % room)
    lines += ["  };", "", "  static final byte[] KIND = {"]
    for room, why, _protected, _forced, _alias in rows:
        lines.append("    %d, // r%03x" % (kind_of(why), room))
    lines += [
        "  };",
        "",
        "  // Rooms whose only count calls are SceCountEmAlive/DestroyEm(0x10,0x20), the exact range",
        "  // the gated engine patch aliases. Single-id counts (0x1D, 0x36, 0x2B, ...) are never aliased.",
        "  static final boolean[] ALIAS = {",
    ]
    for room, _why, _protected, _forced, alias in rows:
        lines.append("    %s, // r%03x" % ("true" if alias else "false", room))
    lines += ["  };", "", "  static final int[][] DRIVEN = {"]
    for room, _why, protected, _forced, _alias in rows:
        nums = ", ".join("0x%02x" % v for v in sorted(protected - SPAWN.get(room, set())
                                                  - KEEP_ROWS.get(room, set())))
        lines.append("    {%s}, // r%03x" % (nums, room))
    lines += [
        "  };",
        "",
        "  // Rows the room script only spawns, never reads the identity of; the dealer may change them.",
        "  static final int[][] SPAWN = {",
    ]
    for room, _why, _protected, _forced, _alias in rows:
        nums = ", ".join("0x%02x" % v for v in sorted(SPAWN.get(room, set())))
        lines.append("    {%s}, // r%03x" % (nums, room))
    lines += [
        "  };",
        "",
        "  // Rows that keep their exact disc bytes for story reasons (the rescued dog); the dealer",
        "  // never touches them and their module keeps a room-budget slot.",
        "  static final int[][] KEEPROWS = {",
    ]
    for room, _why, _protected, _forced, _alias in rows:
        nums = ", ".join("0x%02x" % v for v in sorted(KEEP_ROWS.get(room, set())))
        lines.append("    {%s}, // r%03x" % (nums, room))
    lines += [
        "  };",
        "",
        "  // Modules the room preloads itself with EmReadSearch/SearchEmModule. Ashley's 3/5 now has",
        "  // a dedicated slot and is not one of the four enemy modules.",
        "  static final int[][] FORCED = {",
    ]
    for room, _why, _protected, forced, _alias in rows:
        nums = ", ".join("0x%02x" % v for v in sorted(forced - {3, 5}))
        lines.append("    {%s}, // r%03x" % (nums, room))
    lines += [
        "  };",
        "",
        "  static int kind(int room) {",
        "    for (int i = 0; i < ROOM.length; i++) if (ROOM[i] == room) return KIND[i];",
        "    return -1;",
        "  }",
        "",
        "  static boolean driven(int room, int entry) {",
        "    for (int i = 0; i < ROOM.length; i++) {",
        "      if (ROOM[i] != room) continue;",
        "      for (int n : DRIVEN[i]) if (n == entry) return true;",
        "      return false;",
        "    }",
        "    return false;",
        "  }",
        "",
        "  static boolean spawn(int room, int entry) {",
        "    for (int i = 0; i < ROOM.length; i++) {",
        "      if (ROOM[i] != room) continue;",
        "      for (int n : SPAWN[i]) if (n == entry) return true;",
        "      return false;",
        "    }",
        "    return false;",
        "  }",
        "",
        "  static boolean keep(int room, int entry) {",
        "    for (int i = 0; i < ROOM.length; i++) {",
        "      if (ROOM[i] != room) continue;",
        "      for (int n : KEEPROWS[i]) if (n == entry) return true;",
        "      return false;",
        "    }",
        "    return false;",
        "  }",
        "",
        "  static boolean alias(int room) {",
        "    for (int i = 0; i < ROOM.length; i++) if (ROOM[i] == room) return ALIAS[i];",
        "    return false;",
        "  }",
        "",
        "  static int[] forced(int room) {",
        "    for (int i = 0; i < ROOM.length; i++) if (ROOM[i] == room) return FORCED[i];",
        "    return new int[0];",
        "  }",
        "}",
    ]
    return "\n".join(lines) + "\n"


def main():
    rows = []
    for stage in ("st1", "st2", "st3"):
        folder = os.path.join(ROOT, "src", stage)
        for name in sorted(os.listdir(folder)):
            room = room_of(name)
            if room is None:
                continue
            path = os.path.join(folder, name)
            why, protected, forced = scan(path, room)
            rows.append((room, why, protected, forced, count_alias(path)))
    lines = ["// Generated by port/tools/enemy_random_table.py from the room scripts (src/st1..st3/rXYZ.cpp): do not",
             "// edit by hand. One row a room: the room (stage << 8 | room), its kind (0 open: places and number may",
             "// change; 1 untouched: a boss, or kept by hand; 2 places only: the script counts, sweeps or names list",
             "// entries, the places of the others may change, the number never), the list entries the script drives",
             "// (bits, entry 0 the lowest bit of word 0): their places stay.",
             ""]
    for room, why, protected, _forced, _alias in rows:
        words = [0] * 8
        for v in protected:
            words[v >> 5] |= 1 << (v & 31)
        note = ", ".join(why)
        if protected:
            note += ("; " if note else "") + "%d driven" % len(protected)
        lines.append("{0x%03x, %d, {%s}},  // r%03x%s" % (room, kind_of(why),
                                                          ", ".join("0x%08xu" % w for w in words), room,
                                                          ": " + note if note else ""))
    out = "\n".join(lines) + "\n"
    if "--java-out" in sys.argv:
        dest = sys.argv[sys.argv.index("--java-out") + 1]
        with open(dest, "w", encoding="utf-8", newline="\n") as f:
            f.write(java_source(rows))
        print("wrote %s" % dest)
        return 0
    if "--check" in sys.argv:
        old = open(OUT, encoding="utf-8").read().replace("\r\n", "\n") if os.path.exists(OUT) else ""
        print("enemy_random_rooms.inc: %s" % ("up to date" if old == out else "OUT OF DATE"))
        return 0 if old == out else 1
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    untouched = [r for r in rows if kind_of(r[1]) == 1]
    places = [r for r in rows if kind_of(r[1]) == 2]
    print("%d room scripts: %d untouched, %d places only, %d open (%d of them drive entries by number)" % (
        len(rows), len(untouched), len(places), len(rows) - len(untouched) - len(places),
        sum(1 for r in rows if not r[1] and r[2])))
    print("untouched: " + " ".join("r%03x" % r[0] for r in untouched))
    print("places only: " + " ".join("r%03x" % r[0] for r in places))
    print("open: " + " ".join("r%03x" % r[0] for r in rows if not r[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
