# Implementation plan — farm module budget, story-dog protection, open r100 (27 Nov 2026)

User reports on build 6322: (1) every seed looks the same — the farm becomes villager Ganados with
no bosses/elites; (2) crows, bear traps and explosive wires (r100) never randomize; (3) the seed of
a save seems to stick. User decisions: animals (including crows) and traps are safe to randomize;
the dog Leon saves must stay a dog. Execution: me, native, in this session; offline verification
before any push; CI build for the playtest.

## Root causes (verified offline)

1. **Farm saturation.** dealRoom's budget loop reserves a module slot for every row whose current
   id is not a pool id (`|| !poolId(id)`). r103's vanilla cows (0x26), birds (0x28) and dog (0x21)
   plus the forced corpse 0x12 fill all four slots before any pick, so every row falls into the
   "reuse a module the room already loads" fallback -> id 0x12. Reproduced: seed 7, VILLAGE off ->
   18 rows of 0x12, report "Dr. Salvador 3" (3 picks + 15 reuse rows). This also made every seed
   look identical (report 3).
2. **r100 is UNTOUCHED by design** (enemy_random_table.py KEEP dict) — crows, traps and villagers
   never change. The first r100 test already dealt these rows on the phone, and dealRoom already
   reserves the hand-built officer-killer (0x12) for r100.
3. Script inventory for r100 (src/st1/r100.cpp): horde rows 3,4,5,0x12,0x13,0x26,0x27 (W->ems[]),
   house row 0x25, after-state rows 9,0xA..0xE,0x1D,0x7A (r100_trap_set), event-motion rows 6,7,8
   (hard-cast to cEmGanado + setEvtMotion — must stay Ganado-family or keep disc bytes), crows
   0x0f,0x10,0x11,0x20,0x21 active at entry. Row 9 is the story dog (id 0x21, after-state); r103
   row 0x56 is the same dog at the trap rescue.

## Tasks

1. **Budget fix** (EnemyShuffle.java, dealRoom budget loop): reserve a slot only for rows that
   keep their id — driven, bomb, set 0x25, trader (0x18), keep rows. Remove `|| !poolId(id)`.
   Gate: farm probe with VILLAGE off shows non-0x12 picks in r103; module-guard and
   run_all_lists stay green.
2. **Keep rows** (enemy_random_table.py + EnemyRooms.java + EnemyShuffle.java): hand-verified
   KEEP_ROWS = {0x100: {0x09}, 0x103: {0x56}} (the story dog); generator emits a KEEP table and
   `keep(room,no)`; the deal loop skips keep rows entirely (exact disc bytes), the budget loop
   reserves them. Gate: extended row-protection check asserts the dog rows are byte-identical
   across seeds.
3. **Open r100** (enemy_random_table.py): remove 0x100 from KEEP; SPAWN[0x100] = {3,4,5,0x12,
   0x13,0x25,0x26,0x27,0xA,0xB,0xC,0xD,0xE,0x1D,0x7A} (script-spawned rows that join the deal;
   rows 6,7,8 event-motion and row 9 dog are keep rows instead). Regenerate EnemyRooms.java.
   Gate: check_enemy_rooms + check_script_spawns green (each SPAWN row changes in >=1 seed);
   a deal run shows r100 dealt with the officer reserve intact.
4. **Full suite + gates**: all offline checks, whole_prepare.py on the case-sensitive clone
   (line-shift gate), commit, push feature + ci branches, CI green, report versionCode.

## Non-goals

Per-room sound-bank design (the mute stays global); Seed/save staleness (explained by task-1
saturation: every seed converged to the same villagers; the sidecar reads the dealt lists fresh
per room load); rebalancing ratios.
