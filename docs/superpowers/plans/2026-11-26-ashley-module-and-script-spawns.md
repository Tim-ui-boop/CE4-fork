# Implementation plan — Ashley module slot and script-spawned Ganados

Spec: `docs/superpowers/specs/2026-11-26-ashley-module-and-script-spawn-design.md`.

## File map

- `port/whole/patches/0256-ashley-dedicated-module.patch` — native patch (read.cpp, read.h, main.cpp).
- `port/tools/enemy_random_table.py` — emits Java `SPAWN` table; leaves `DRIVEN` for the runtime `.inc` unchanged.
- `port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyRooms.java` — regenerate with `--java-out`.
- `EnemyShuffle.java` — treat SPAWN rows as fully dealable; reserve/count their original kind.
- `.github/workflows/android.yml` — raise versionCode base above the installed 5314.
- `.harness/analysis/check_ashley_slot.py`, `check_script_spawns.py` — new failing-first offline checks.
- `.harness/analysis/check_enemy_rooms.py`, `run_all_lists.py` — extend to cover SPAWN.

## Tasks

1. Write `check_ashley_slot.py`: generate patch `--base`, assert the source tree has no dedicated
   slot yet, apply all patches with `whole_prepare.py`, assert patched read.cpp routes ids 3/5 to
   `AshleyReadModule` and main.cpp releases it at reset. Run it: must FAIL (patch missing).
2. Create the patch from the real prepared base with `whole_make_patch.py --base`; edit only the
   scratch copies; generate patch 0256; run check from step 1 until GREEN; run `check_hooks.sh` on
   read.cpp and main.cpp and `whole_prepare.py` into scratch.
3. Write `check_script_spawns.py`: generate deals with `RE4_OPTIONS=31460375` and assert r117 rows
   0x50/0x51 and r118 rows 0x79–0x7f change id from vanilla in at least one seed, while r118's dog
   row 0x78 and r102's merchant row 0x4C never change identity. Run it: must FAIL.
4. Add `SPAWN` to the generator and Java table; regenerate `EnemyRooms.java`; ignore ids 3/5 in the
   Java FORCED table (Ashley's dedicated slot). Extend `check_enemy_rooms.py` with the SPAWN
   assertions. Run until GREEN.
5. Update `EnemyShuffle.java` reservation and `kinds()` to include SPAWN rows; rerun
   `run_all_lists.py` with basics on, `check_enemy_java.py`, `check_module_guard.py`, and the
   script-spawn check. No protected bytes or budgets may change.
6. Raise versionCode to `4000 + commit count` (≈6310, above installed 5314). Commit exact files,
   push `feature/enemy-module-guard` and `ci/enemy-module-guard`, confirm CI green. No APK install.

## Verification gates

Every task ends with its check GREEN. Final: all offline checks pass, patch applies cleanly, CI
build succeeds. The user installs and playtests r111 and the church/storm rooms.
