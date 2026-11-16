import difflib
import os

os.chdir(".harness/scratch/0235gen")

files = ["src/game/read.cpp", "src/game/exception.cpp", "include/read.h"]


def edit(path, old, new):
    s = open(path, encoding="utf-8").read()
    assert old in s, "missing in " + path + ": " + old[:60]
    assert s.count(old) == 1, "not unique in " + path + ": " + old[:60]
    open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))


# read.cpp: the array, EmReadInit, SearchEmModule, pullEmModule
edit("mod/src/game/read.cpp",
     "MODULE_DAT EmReadModule[4] __attribute__((aligned(32)));",
     "/* port (patch 0235): eight enemy module slots, so a randomizer's kind can load beside the\n"
     "   room's own four without evicting one (the village raid fills all four: two villager kinds,\n"
     "   the cow, the bird -- a fifth kind killed a villager's load and the room crashed) */\n"
     "MODULE_DAT EmReadModule[8] __attribute__((aligned(32)));")
edit("mod/src/game/read.cpp",
     "// Frees the four enemy module slots (stage / game start).\nvoid EmReadInit()\n{\n    int i;\n\n    for (i = 0; i < 4; i++) {",
     "// Frees the enemy module slots (stage / game start; eight since port patch 0235).\nvoid EmReadInit()\n{\n    int i;\n\n    for (i = 0; i < 8; i++) {")
edit("mod/src/game/read.cpp",
     "    id = checkAshleyId(id);\n    for (m = EmReadModule, i = 0; i < 4; i++, m++) {",
     "    id = checkAshleyId(id);\n    /* port (patch 0235): eight slots */\n    for (m = EmReadModule, i = 0; i < 8; i++, m++) {")
edit("mod/src/game/read.cpp",
     "// A free enemy module slot (pArc NULL), or NULL when all four are used.",
     "// A free enemy module slot (pArc NULL), or NULL when all eight are used (port patch 0235).")
edit("mod/src/game/read.cpp",
     "    for (i = 0; i < 4; i++) {\n        if (EmReadModule[i].pData == NULL) {",
     "    for (i = 0; i < 8; i++) {\n        if (EmReadModule[i].pData == NULL) {")
# exception.cpp: the crash reporter's symbol loop
edit("mod/src/game/exception.cpp",
     "    for (i = 0; i < 4; i++) {\n        if (EmReadModule[i].pDll) {",
     "    /* port (patch 0235): eight enemy module slots (read.cpp) */\n    for (i = 0; i < 8; i++) {\n        if (EmReadModule[i].pDll) {")
# read.h: the extern array and pullEmModule's declaration (the randomizer asks for a free slot)
edit("mod/include/read.h",
     "extern MODULE_DAT EmReadModule[4];",
     "/* port (patch 0235): eight enemy module slots (read.cpp) */\nextern MODULE_DAT EmReadModule[8];")
edit("mod/include/read.h",
     "// The enemy module entry of enemy `id` (the rooms swap event data into the boss module's block).\nMODULE_DAT* SearchEmModule(int id);",
     "// The enemy module entry of enemy `id` (the rooms swap event data into the boss module's block).\nMODULE_DAT* SearchEmModule(int id);\n// A free enemy module slot, or NULL when all eight are used (port patch 0235).\nMODULE_DAT* pullEmModule(void);")

out = []
for f in files:
    a = open("orig/" + f, encoding="utf-8").readlines()
    b = open("mod/" + f, encoding="utf-8").readlines()
    d = list(difflib.unified_diff(a, b, fromfile="a/" + f, tofile="b/" + f, n=3))
    assert d, "no diff for " + f
    out.extend(d)
    out.append("\n")

header = """# Port patch (0.99.17, the owner's wish of 17.11.2026 -- the whole game on Android carries the
# decompiled source, so the engine itself may be modded): eight enemy module slots instead of
# four. A room with its own four kinds (the village raid: two villager kinds, the cow, the bird)
# could never take a randomizer's kind -- the fifth module had no slot, the room's own enemies
# failed to load and the room script followed the error pointer into a crash (the crashes of
# 17.11.2026). With eight slots a randomizer kind loads beside the room's own four; a vanilla
# game fills four at most and sees no difference (the slots sit empty until the randomizer
# asks). All the engine's own users loop over the array -- frees (EmReadInit), the search
# (SearchEmModule), the free-slot handout (pullEmModule), the crash reporter's symbol list
# (exception.cpp) -- and the engine frees every slot at a stage's start, so a stage still ends
# where it began. read.h also names pullEmModule for the randomizer's runtime, whose
# kindLoaded now allows a kind that is loaded or has a free slot to load into.
"""


def write_patch(text):
    import sys
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    path = os.path.join(root, "port/whole/patches/0235-eight-enemy-module-slots.patch")
    open(path, "w", encoding="utf-8").write(text)
    return path


print("patch written, hunks:", sum(1 for l in out if l.startswith("@@")))
print(write_patch(header + "".join(out)))
