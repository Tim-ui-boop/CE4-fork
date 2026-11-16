#!/usr/bin/env python3
"""Rebuild the em_set patches by real diffs (0231 then 0234), so hunk counts are always right.

Kept in .harness/scratch (runtime artifacts, never committed): python3 this_file.py
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
VANILLA = os.path.join(ROOT, 'src/game/em_set.cpp')
REGEN = os.path.dirname(os.path.abspath(__file__))


def apply(text, sub, add, what):
    if text.count(sub) != 1:
        raise SystemExit('cannot apply: %s (found %d)' % (what, text.count(sub)))
    return text.replace(sub, add)


vanilla = open(VANILLA).read()
new = vanilla

# ---- patch 0231 (the room's start entries: plan, per-entry answer, copies) ----
new = apply(new, 'static int emSetDummy = 0;\n', '''static int emSetDummy = 0;

/* port (patch 0231): the cheat "Random enemies" (runtime/enemy_random.cpp) */
extern "C" void re4_enemy_random_room(void);
extern "C" int re4_enemy_random_entry(int no, EM_LIST* out);
extern "C" int re4_enemy_random_extra(EM_LIST* out);
extern "C" void re4_enemy_random_extra_made(int ok);
''', '0231 externs')

new = apply(new, '''void EmSetFromList()
{
    u32 i;

    for (i = 0; i < 256; i++) {
''', '''void EmSetFromList()
{
    u32 i;

    /* port (patch 0231): the cheat "Random enemies" plans the room's start */
    re4_enemy_random_room();
    for (i = 0; i < 256; i++) {
''', '0231 room plan')

new = apply(new, '''        cEm* em;

        if (!(d->be_flag & 1)) {
''', '''        cEm* em;
        EM_LIST moved;  /* port (patch 0231) */
        int how;

        if (!(d->be_flag & 1)) {
''', '0231 locals')

new = apply(new, '''        em = EmCreate(d->id);
        if (em == 0) {
            pLog->err(0, 0, "EmSetFromList() Em set failed, Id = %x", d->id);
            continue;
        }
        EmSetWork(em, d, i);
''', '''        /* port (patch 0231): left out (-1), as it is (0), or from a copy of the entry with another spot or kind (1);
           the list itself stays as it is. The create uses the copy's id when the kind changed: the new kind is its
           own enemy, not the old one with new numbers */
        how = re4_enemy_random_entry((int) i, &moved);
        if (how < 0) {
            continue;
        }
        em = EmCreate((how > 0 && moved.id != 0) ? moved.id : d->id);
        if (em == 0) {
            pLog->err(0, 0, "EmSetFromList() Em set failed, Id = %x", d->id);
            continue;
        }
        EmSetWork(em, how > 0 ? &moved : d, i);
''', '0231 per-entry answer')

new = apply(new, '''        em->l_sub = 1.0e16f;
        em->move();
    }
}
''', '''        em->l_sub = 1.0e16f;
        em->move();
    }
    /* port (patch 0231): more enemies (up to two), each a copy beside a Ganado of the room; its death is kept at an
       empty entry of the list (id 0, never used by the game) */
    {
        EM_LIST extra;
        int no;

        while ((no = re4_enemy_random_extra(&extra)) >= 0) {
            cEm* em = 0;

            if (!EmSetDieCk((u32) no) && checkListId(no)) {
                em = EmCreate(extra.id);
            }
            re4_enemy_random_extra_made(em != 0);
            if (em != 0) {
                EmSetWork(em, &extra, (u8) no);
                EmSetDist(em);
                em->l_sub = 1.0e16f;
                em->move();
            }
        }
    }
}
''', '0231 copies')

after0231 = new
open(os.path.join(REGEN, 'after_0231.cpp'), 'w').write(after0231)

# ---- patch 0234 (the wave creator), on top of 0231 ----
new = apply(new, '''static int emSetDummy = 0;

/* port (patch 0231)''', '''static int emSetDummy = 0;

/* port (patch 0234): the enemy randomizer's wave creator (runtime/enemy_random.cpp) */
extern "C" int re4_enemy_random_wave(int no, EM_LIST* out);

/* port (patch 0231)''', '0234 extern')

new = apply(new, '''cEm* EmSetFromList2(int no, int flag)
{
    EM_LIST* d = &pG->Em_list[no];
    cEm* em;
''', '''cEm* EmSetFromList2(int no, int flag)
{
    EM_LIST* d = &pG->Em_list[no];
    /* port (patch 0234): the wave entry with the randomizer's plan -- as it is (0), not at all (-1), or a copy
       with another kind (1); the list itself is never written */
    EM_LIST moved;
    int how;

    cEm* em;
''', '0234 locals')

new = apply(new, '''    if (checkListId(no) == 0) {
        return errEm;
    }
    em = EmCreate(d->id);
    if (em == 0) {
        pLog->err(0, 0, "EmSetFromList2() Em set failed, Id = %x", d->id);
        return errEm;
    }
    EM_SET_WORK(em, d, no);
''', '''    if (checkListId(no) == 0) {
        return errEm;
    }
    /* port (patch 0234): the randomizer's answer for this wave entry; only an ordinary Ganado of a planned room
       ever changes, and a script's counted kinds keep what the script expects */
    how = re4_enemy_random_wave(no, &moved);
    if (how < 0) {
        return errEm;
    }
    em = EmCreate(how > 0 ? moved.id : d->id);
    if (em == 0 || em == errEm) {
        pLog->err(0, 0, "EmSetFromList2() Em set failed, Id = %x", d->id);
        return errEm;
    }
    EM_SET_WORK(em, how > 0 ? &moved : d, no);
''', '0234 wave answer')

open(os.path.join(REGEN, 'after_both.cpp'), 'w').write(new)


# ---- the real diffs ----
def diff_of(a_path, b_path, label_a, label_b):
    r = subprocess.run(['diff', '-u', a_path, b_path], capture_output=True, text=True)
    out = r.stdout
    out = out.replace(a_path, label_a).replace(b_path, label_b)
    # strip the @@ function context noise to the port's plain style
    return out


open(os.path.join(REGEN, 'vanilla_copy.cpp'), 'w').write(vanilla)
d0231 = diff_of(os.path.join(REGEN, 'vanilla_copy.cpp'), os.path.join(REGEN, 'after_0231.cpp'),
                'a/src/game/em_set.cpp', 'b/src/game/em_set.cpp')
d0234 = diff_of(os.path.join(REGEN, 'after_0231.cpp'), os.path.join(REGEN, 'after_both.cpp'),
                'a/src/game/em_set.cpp', 'b/src/game/em_set.cpp')
open(os.path.join(REGEN, '0231.diff'), 'w').write(d0231)
open(os.path.join(REGEN, '0234.diff'), 'w').write(d0234)
open(os.path.join(REGEN, 'vanilla_copy.cpp'), 'w').write(vanilla)
print('diffs written to', REGEN)
print('0231 hunks:', d0231.count('@@'))
print('0234 hunks:', d0234.count('@@'))
