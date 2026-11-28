# Parts pool + two-heavy trial — design

Date: 2026-11-28
Status: design, awaiting approval
Scope: build-time patch in `port/whole/patches/`, dealer in `EnemyShuffle.java`, offline checks

## Evidence

- The r100 codec cutscene log (`core-log.txt`, 19:04:47) shows
  `Event::ExePacket_SetOm : om set failed` then three `Event::GetMod : mod failed[pl0000]`.
  The event never registers Leon's model, so the scene renders nothing and stalls.
- The same log has repeated `ModelInit()  Parts allocate was failed` lines, and earlier at 19:04:36
  `em2c() ModelInit failed` / `EM10 pEm->modelInit() failed`. The per-room `cParts` pool is exhausted.
- `src/game/game.cpp:383-393` sizes the model-info and parts pools from room data; the game already
  doubles both under `DBG_APP_USE_DBMEM`. The randomizer's cast needs the same headroom, but vanilla
  play must stay unchanged.
- The 25 Nov shotgun crash (plan `2026-11-25-all-rooms-enemy-randomizer.md:169-183`, commit
  `d3998c8a`) was the same parts pool: Krauser `0x39` + Regenerator/Iron `0x36` plus the player's
  shotgun starved `cParts`, then the laser sight dereferenced a null matrix. The dealer's response
  was a conservative cap of **one heavy ID per room** (`HEAVY_IDS`, `EnemyShuffle.java:150-152,
  559-560, 619, 644, 693`). No per-id `nParts` measurement exists in the workspace.

## Design

1. **Build-time patch 0259** (`port/whole/patches/0259-randomizer-parts-pool.patch`): in
   `src/game/game.cpp`, declare `extern "C" int re4_randomizer_enemy_active(void);` and change the
   `ModInfoMgr` and `PartsMgr` pool sizing to double when the debug-memory flag **or** a dealt enemy
   list is active. Do not double `EmMgr`/`ObjMgr`. Applies to every room, but only while the
   randomizer marker is active; vanilla sessions remain byte-identical.
2. **Bounded relaxation of the parts cap**: replace the boolean `heavyUsed` in `EnemyShuffle.java`
   with a count of distinct heavy IDs. Allow **at most two** heavy IDs per room (currently one).
   Keep every other rule unchanged: one El Gigante (`massiveUsed`), the four-module budget
   (`fits`, `overBudgetRooms`), one new elite module in script-Ganado rooms (`eliteModuleUsed`),
   one basic family/one id per member, counted/alias rules, and all story/merchant protections.
   A second heavy ID that is already in `kinds` spends no new module slot and is always allowed
   within the new bound.
3. **Offline checks**:
   - `check_parts_patch.py`: patch 0259 applies; prepared `game.cpp` shows the doubled sizing and
     the `re4_randomizer_enemy_active()` declaration; no duplicate doubling under the debug flag.
   - Update `.harness/analysis/run_all_lists.py`: heavy-ID assertion becomes `<= 2` instead of
     `<= 1`; add an assertion that El Gigante stays `<= 1` per room and active module kinds stay
     `<= 4`. Keep the one-heavy assertion for a deal made with the relaxation disabled.
   - Extend `check_count_deal.py` or add a targeted check proving a room can receive two heavy IDs
     together while the module and massive caps still hold.

## Why only two

Parts are consumed per model instance, not per distinct ID; a room full of one heavy ID already
uses many parts. Doubling removes the measured deficit, but without an `nParts` survey an unbounded
revert would be a guess. Two is a testable bound; if the phone still shows parts failures, revert the
Java relaxation and keep the patch rather than raising the bound.

## Verification

Full offline gate (all checks, 64 seeds, 43-seed all-on stress, `whole_prepare` on the case-sensitive
clone), GitHub Actions green, then the user installs, re-deals, and playtests:
- the opening codec cutscene (Leon must be visible, no stall),
- a room with two heavy IDs while aiming the shotgun (no `Parts allocate was failed` or crash),
- r101 bell waves and the four-module budget (no missing late spawns).

## Explicitly unchanged

Module slots, `massiveUsed`, `eliteModuleUsed`, counted/alias rules, story-row protections, the
special-TYPE guard, SPAWN promotions, and the deal-and-show button.
