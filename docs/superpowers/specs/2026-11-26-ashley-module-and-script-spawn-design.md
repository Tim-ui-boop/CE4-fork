# Design: Ashley's module slot and script-spawned enemies (26 Nov 2026)

## Problem

1. r111 still blanks. The dealer writes four enemy kinds; Ashley's companion loads module `3`/`5`
   into one of the same four `EmReadModule[4]` slots before the room's list spawns, so the room's
   last kind (the `0x2a` traps) cannot load. The earlier idea of budgeting only three kinds also
   breaks rooms that legitimately need four (r100, r106), so it was reverted.
2. The two Ganados inside the church (r117, list rows `0x50`/`0x51`) and the seven Ganados outside
   in the storm (r118, rows `0x79`–`0x7f`) do not change family. They are dormant list rows spawned
   by `setEm`/`EmSetFromList2` after Ashley is freed, and the generated table currently marks them
   DRIVEN, so the dealer only changes their look, never their id.

## Decision

Give Ashley's companion a dedicated module slot in the engine. Leave the four enemy slots and the
dealer's four-kind budget unchanged. Separately, mark a small, verified set of "spawn-only" list
rows as eligible for the ordinary deal.

## Engine change (patch 0256)

All companion module requests already go through `src/game/read.cpp`.

- Add `MODULE_DAT AshleyReadModule` beside `EmReadModule[4]`; declare it in `include/read.h`.
  (Correction, 27 Nov 2026: the companion's archive is allocated in the room heap, and
  gameRoomMemInit() recreates that heap at every door — MemReplaceHeap(3, 4) with heap 3 already
  gone rebuilds heap 4 over its full range. The slot therefore must NOT survive doors: the door
  path calls ReleaseAshleyModule() next to EmReadInit() (game.cpp), and the next SubCharInit
  simply reads the module again. Keeping the pointer across a door served recycled memory and
  crashed r118 — cSubAshley::modelSet read em17's body over the dead archive. The r117/r11c event
  swaps happen inside one room and are unaffected.)
- Add a dedicated loader that uses the passed slot instead of `pullEmModule()`; leave `readEm()`
  itself untouched.
- In `EmReadSearch`/`SearchEmModule`, route ids `3`/`5` (after `checkAshleyId`) to the dedicated
  slot. Reuse it only when its id matches and its `DataSize` is large enough for the request;
  otherwise release and reload. This matters for r117, which asks for a larger event archive.
- Do **not** clear the dedicated slot in `EmReadInit()` (called on every room change); release it
  only at system reset (`src/game/main.cpp` `systemResetCommon`) alongside the player/weapon slots.
- No changes to `pl_sub.cpp`, room scripts, `EnemyShuffle.java` budget, or the runtime sidecar
  guard. The r117 event and costume switching keep working through the same functions.

## Script-spawn change

- `port/tools/enemy_random_table.py` emits a `SPAWN` table alongside `DRIVEN`: a row is spawn-only
  when it is named only by a literal `setEm(n, ...)`/`EmSetFromList2(n, ...)` call whose return is
  discarded, and no script reads its id, whole record, or class. Identity-reading rows stay DRIVEN.
- Start with the verified r117 pair (`0x50`, `0x51`) and r118's seven rows (`0x79`–`0x7f`). Keep
  r118 row `0x78` protected: its id is `0x3b` (TRUCK, not a dog), and `R118Init` stores the spawned
  pointer in `r118_work->em` and later calls `setPtr`/`getPtr` on it. The actual dogs are rows
  `0x82`–`0x84` (id `0x22`); the script only clears their `be_flag` once Ashley is found, so they
  stay eligible for the ordinary deal. Do not auto-enable r102's merchant row or rows used by
  `GetEmIdFromList`/record copies; those stay protected.
- `EnemyShuffle.dealRoom()` treats SPAWN rows as ordinary full deals but keeps them reserved in the
  module budget; `kinds()` counts them so dormant rows that the game really spawns are not missed.
- `run_all_lists.py` parses SPAWN and asserts r117's pair and r118's seven rows are included while
  r118's pointer-stored row `0x78`, the merchant, and identity-reading rows remain DRIVEN.

Build number: the installed app is 5314, higher than the last build's 5309, so the workflow must
move its versionCode base above 4000 before the next build (otherwise Android refuses the update).

## Verification before any push

- `check_enemy_java.py`, `check_enemy_rooms.py`, `check_hooks.sh` on changed native units.
- `whole_make_patch.py --base` then generate patch 0256 and apply it with `whole_prepare.py`.
- `run_all_lists.py` with basic families enabled: no protected bytes change, budgets hold, and
  r117's two rows change id in at least one seed.
- `check_module_guard.py`: real disc and dealt lists served, synthetic four-kind r111 refused.
- CI build on a `ci/**` branch; user installs and playtests r111 and the church.

## Out of scope

r100 full randomization, Phase 2 counted-row bosses, and expanding SPAWN beyond verified rows.
