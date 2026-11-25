# All-room offline enemy randomizer (with count-alias engine patch)

**Status:** draft for review — no code has been changed for this plan.

## Plain-language goal

Extend the offline enemy randomizer from four rooms to the whole story, without repeating the
r100 cutscene crashes. Out-of-range pool enemies (Verdugo `0x2C`, Krauser `0x39`, Armadura `0x3C`,
Regenerator/Iron Maiden `0x36`, El Gigante `0x2B`) will be allowed onto rows that the game's
scripts count, by changing one shared engine helper: while a deal is active, those IDs also count
as "Ganados alive" for the bell/wave scripts. They keep their real IDs, so their AI, sounds and
models still work. Regenerator and Iron Maiden are two variants of the same `0x36` ID (types 0/1
and 2/3), so one alias covers both.

## Decisions already made

- **Scope:** story rooms only — stage 1–3, the eight `etc/emleon00.esl` … `emleon07.esl` lists.
  Mercenaries/Ada, Extra Rooms, beta rooms, and boss rooms stay as the disc has them.
- **Items and doors are out of scope.** They live on a different branch with conflicting patch
  numbers; merging them is a separate project.
- **Room safety classes** come from `port/tools/enemy_random_table.py`, not from guesses:
  class 0 *open* (full swaps), class 2 *places-only* (keep ID, change appearance/gear and safe
  in-range IDs), class 1 *untouched* (boss/hand-built rooms, leave byte-for-byte alone).
  Current totals: 45 open, 52 places-only, 11 untouched.
- **Engine workaround:** patch `SceCountEmAlive` and `SceDestroyEm` in `src/game/sce_com.cpp` to
  treat alias IDs `0x2B`, `0x2C`, `0x36`, `0x39`, `0x3C` as counted when the requested id range is
  the Ganado range `0x10..0x20` and the randomizer is active. Other ranges (for example the
  Gigante count `SceCountEmAlive(0x2B, -1)` in `r11e.cpp`) must behave exactly as before. Do
  **not** fake `em->id` — AI dispatch, sound banks, `cEmGanado*` casts and cutscene module loading
  all use the real ID and would break.
- **Never install an APK on the phone.** Build only via GitHub Actions on a `ci/**` branch; the
  user installs the artifact. Verify offline before every commit.

## Constraints that must hold in every task

1. Four enemy modules per room (`src/game/read.cpp:78`), reset on every door transition
   (`src/game/game.cpp:1259`). Count reserved modules up front; refuse a plan that needs a fifth.
2. Rows a cutscene spawns by list number keep their exact bytes (the two r100 crashes came from
   entries `0x25` and `4/5` in `src/st1/r100.cpp`). The generated table supplies these numbers.
3. Row fields: `id`/`type`/`flag`/`hp`/`set` are the only bytes the dealer may write; `be_flag`,
   position, rotation, room, `Character`, `Guard_r` are never changed. Dynamite rows (flag bit 17)
   and named-motion rows (`set == 0x25`) are skipped.
4. Room scripts read `id`, never `type` or `flag` (`port/docs/enemy-type-randomizer-research.md:162-181`).
5. The sound-bank skip list (`randomizer/enemies/no-sound.txt`) must name every new module the
   dealer uses; see `port/whole/runtime/dvd.cpp:544-593`.
6. The alias patch is gated on `re4_randomizer_enemy_active()`; with the marker absent the game
   must behave exactly as the disc.

## Files involved

| File | Role |
|---|---|
| `port/tools/enemy_random_table.py` | Source-derived room/protected-row table; add a machine-readable export |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyShuffle.java` | Offline dealer; extend to all lists and room classes |
| `port/android-decomp/app/src/main/res/values/strings.xml` | Labels for new modes/status text |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/MainActivity.java` | Launcher buttons/status; no new button unless needed |
| `port/whole/runtime/enemy_sidecar.cpp`, `re4_runtime.h` | Already serve all 11 list names; add the active-marker query for the patch |
| `src/game/sce_com.cpp` | Two counting helpers to patch |
| `port/whole/patches/0255-count-alias.patch` | Generated reviewed patch (next free number; currently highest is 0254) |
| `port/tools/enemy_types/rehearse.py`, `run_deal.py` | Rehearsal scripts with obsolete `deal()` API |
| `.harness/analysis/run_swap_deal.py`, `check_enemy_java.py`, `check_hooks.sh` | Offline checks in the workspace, not in git |

## Tasks

Each task is TDD: add or extend an offline check, watch it fail, then implement, then re-run the
full offline suite before committing. Commit with a sentence explaining why.

1. **Export the room table for the Java dealer.** Add a `--java-out` (or `--json-out`) mode to
   `port/tools/enemy_random_table.py` that writes room class and protected entry numbers.
   *Done when:* `python3 port/tools/enemy_random_table.py --check` is green, the exported table
   contains 108 rooms with the recorded 45/52/11 split, and the file is checked in.

2. **Extend the dealer to all eight story lists.** Replace `EnemyShuffle.LIST_NAME` with the list
   of eight names; read, deal and write every list; per room apply its safety class; keep the
   four-module, sound and protected-byte guards. Extend `.harness/analysis/run_swap_deal.py` to
   extract all eight lists and assert only permitted bytes changed, determinism per seed, and a
   non-refused deal for the default toggles on many seeds.
   *Done when:* `python3 .harness/analysis/check_enemy_java.py` and
   `python3 .harness/analysis/run_swap_deal.py 1 42 777 31337` pass on all eight lists.

3. **Appearance-only mode for places-only rooms.** For protected/script-owned entries, allow
   `type` within the same ID's supported variants and safe `flag` bits, but never change `id`,
   `set`, `hp` or position. Add a regression asserting those rows keep their ID bytes while
   some type/flag bytes change across seeds.
   *Done when:* the new check fails first, passes after the change, and the full suite stays green.

4. **Add the count-alias engine patch.** Add `re4_randomizer_enemy_active()` to
   `port/whole/runtime/enemy_sidecar.cpp` (marker check) and its declaration in
   `re4_runtime.h`. In `src/game/sce_com.cpp`, widen the id predicate in `SceCountEmAlive` and
   `SceDestroyEm` to include the five alias IDs only when the requested range is the Ganado range
   and the randomizer is active, preserving the alive/death-bit behaviour. Generate `port/whole/patches/0255-count-alias.patch` with
   `port/tools/whole_make_patch.py`, and verify it applies cleanly through `whole_prepare.py`
   and `bash .harness/analysis/check_hooks.sh`.
   *Done when:* the prepared source builds in a GitHub Actions run on a new `ci/**` branch, and
   with the marker absent the helper behaviour is unchanged.

5. **Permit out-of-range enemies on counted rows.** Once task 4 exists, relax `Member.counted()`
   in `EnemyShuffle.java` for `0x2B`, `0x2C`, `0x36`, `0x39`, `0x3C`, keeping the module budget,
   massive-boss cap and script-owned-row protection. Add a regression that Verdugo, Krauser,
   Armadura and Regenerator/Iron appear on r101/r104 counted rows for some seeds, that other id
   ranges are unaffected, and that no plan needs more than four modules.
   *Done when:* the regression fails before the relaxation and passes after; full suite green.

6. **Update rehearsal and docs.** Fix the obsolete `deal()` API in
   `port/tools/enemy_types/rehearse.py` and `run_deal.py`; document the new table, alias rule and
   playtest status in `port/docs/enemy-randomizer-foundations.md` as a new section.
   *Done when:* rehearsal scripts run without errors and the docs match the shipped behaviour.

7. **Phased rollout.** Push each completed phase to its own `ci/**` branch and let the user
   playtest before the next phase: (a) full swaps in open rooms; (b) appearance-only in
   places-only rooms; (c) alias patch with bosses on counted rows. Roll back by checking out the
   previous branch; no force-push and no branch deletion.

## Risks and open items

- The PC tool's exact per-room pools are compiled into the executable and cannot be copied; the
  source-derived table is the authority, and rooms without a script file remain as on the disc.
- `SceDestroyEm` clears the ESL entry's death bit; the alias patch must retain that behaviour or
  killed bosses will respawn.
- Some counting/sweep loops are room-local rather than through the shared helpers
  (for example stage-4 `r400.cpp`); they are outside the story-only scope.
- Sound banks for newly used modules must be measured/skipped; the earlier `__SYNGetWavetableData`
  crash shows why this is not optional. `em36` serves both Regenerator and Iron Maiden, so one
  bank skip covers both variants.
- Every phase needs a phone playtest; offline checks prove data safety, not playable geometry.

## Work-in-progress state (25 Nov 2026 — paused at user request)

Task 1 is complete and committed: `port/tools/enemy_random_table.py --java-out` generates
`EnemyRooms.java` (108 rooms, 45 open / 11 untouched / 52 places-only), checked by
`.harness/analysis/check_enemy_rooms.py`.

Task 2 is now complete. `EnemyShuffle.java` reads and writes all eight story lists, applies the
room table, protects script-driven rows, and stages/promotes all lists atomically. Two fixes were
needed after the pause: `verify()` now skips the model-table check for unchanged records (seed 777
was refused on an unchanged vanilla `0x29` record after a room was restored for the module budget),
and the older harness was updated for the new report shape. Verified green:
`.harness/analysis/run_all_lists.py 1 42 777 31337` (8 sidecars each),
`.harness/analysis/run_swap_deal.py 1 42 777`, `check_enemy_rooms.py` and `check_enemy_java.py`.

Task 3 is complete: script-owned rows keep id, hp, set and position; with **Try shared
animations** on, their model variant and non-weapon extras may change. The all-list harness now
asserts zero identity changes and 1071 look/gear changes over four seeds; both offline suites and
both syntax checks are green. Phase 1 (all story rooms, no engine patch) is ready for a GitHub
Actions build and a phone playtest before phase 2 starts. The phone still runs the restored
four-room build from run 45597438295. GitHub Actions run **45628362399** on
`ci/all-room-enemies` is green (commit `afc5e9d4`, artifact `CubeEvil4-core-debug-apk`, 24.6 MB).
Phase 2 (the gated count-alias patch) waits for the user's playtest of this build.
