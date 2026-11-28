# Implementation plan — Phase 2 count alias

Spec: `docs/superpowers/specs/2026-11-26-phase2-count-alias-design.md`.
Execution: me, in this session, offline only. No commit push and no CI build until the user has
playtested build 6315.

## Files

- `port/whole/patches/0257-count-alias.patch` — new native patch.
- `port/whole/runtime/enemy_sidecar.cpp`, `port/whole/runtime/re4_runtime.h` — marker getter.
- `port/android-decomp/.../EnemyShuffle.java` — relax `Member.counted()`.
- `.harness/analysis/check_count_alias.py`, `check_count_gate.py` — new RED-first tests.
- `.harness/analysis/run_all_lists.py` — high-boss-ratio assertions.

## Tasks

1. Write `check_count_alias.py` and `check_count_gate.py`. Run them: both must FAIL (missing patch
   and getter, zero out-of-range picks on counted rows).
2. Build patch 0257 from the real prepared base with `whole_make_patch.py --base`: add the gate
   getter, and alias the five boss ids only for exact `(0x10,0x20)` calls in `SceCountEmAlive` and
   `SceDestroyEm`. Generate the patch; run both new checks GREEN, plus `check_hooks.sh` on
   `sce_com.cpp` and the runtime units.
3. Relax `Member.counted()` in the dealer; extend `run_all_lists.py` so a high-boss run proves
   out-of-range ids reach counted rows while all existing guards stay green. Run until GREEN.
4. Full offline suite: `check_enemy_java.py`, `check_enemy_rooms.py`, `check_script_spawns.py`,
   `check_module_guard.py`, `check_ashley_slot.py`, `run_all_lists.py` with default and high-boss
   options.
5. Commit locally only if everything is green. Do not push. Record the local commit hash and the
   exact remaining steps in `.harness/memory/randomizer-pool-handoff.md`.

## Verification gates

Each task ends with its check GREEN. Final state: tests pass, patch applies cleanly, no push, no
build. After user playtest: push branch + CI, confirm green, report artifact and versionCode.
