# Leon movement-speed cheat — implementation plan

Design: `docs/superpowers/specs/2026-11-25-leon-speed-cheat-design.md` (approved by the player).

## Plain-language result

An **Off / 2× / 3× / 4×** arrow row in the in-game CHEATS page. It uses the game's own debug
speed mode for Leon's normal movement only, re-applies after room changes and resets, is saved in
preferences, and cannot change event/damage routines, enemies or cutscenes.

## File map

| File | Change |
|---|---|
| `port/whole/runtime/core_api.cpp` | Accept cheat actions 11..14 (`re4_core_cheat_action`, `:724-744`) |
| `port/whole/runtime/cheats.cpp` | Atomic speed level, early cases in `re4CheatPerform` (before the room guard at `:1324`), names in `actionName` (`:159-175`), per-frame re-assert in `apply`/`hook` (`:423-440`, `:506-523`) |
| `src/game/player.cpp` | One-line `r_no_0 == 0` guard at `:347` so only normal movement repeats |
| `port/whole/patches/0255-leon-speed-move-only.patch` | Generated from the `player.cpp` edit; next free number after 0254 |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/CoreMenu.java` | 4-step selector in the PLAYER group beside Steady aim (`:2142-2145`), using `selector` (`:450-464`) and `cheatRow` (`:2118-2124`); host callback in `Host` (`:139-152`) |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/CoreActivity.java` | New `GAME_PREFS` key `LEON_SPEED_LEVEL`; `cheatAction` host implementation (`:1469-1473`); re-send the stored level at startup beside `setCheats` (`:589-593`) |
| `port/android-decomp/app/src/main/res/values/strings_ingame.xml` + every locale | Row label, Off/2×/3×/4× labels, help text |
| `.harness/analysis/check_speed_cheat.py` | New offline static check (workspace, not committed) |

## Tasks

Each task is TDD where a check is possible: extend the static checker first, watch it fail, then
implement. Commit each verified step with a sentence explaining why.

1. **Static checker.** Write `.harness/analysis/check_speed_cheat.py` to assert: the action range is
   1..14, actions 11..14 appear in `actionName` and `re4CheatPerform`, an atomic speed level is
   written/read in `cheats.cpp`, `DBG_KAIOUKEN` and `PlKaiou` are set/cleared per frame, the
   `player.cpp` repeat has the `r_no_0 == 0` guard, the patch file exists, the Java menu has a
   selector with four values, `CoreActivity` stores `LEON_SPEED_LEVEL`, and every
   `strings_ingame.xml` has the new keys. Run it now: it must fail. No production edit in this step.

2. **Native action channel.** Add cases 11..14 to `core_api.cpp` range and `actionName`; in
   `re4CheatPerform`, handle them **before** the room/subscreen guard, store level 0..3 in an
   atomic, log the change, return 1. Do not add a JNI method or a new IPC message.
   *Done when:* the checker's action-range/name/case assertions pass.

3. **Per-frame speed application.** In `cheats.cpp`, add `applySpeed()` called from `apply()` and
   `hook()`: level 0 clears `DBG_KAIOUKEN`/sets `PlKaiou=0`; levels 1..3 set `DBG_KAIOUKEN` and
   `PlKaiou=level` (2×/3×/4×). Re-assert every frame so `PlayerInit()`'s reset cannot leave the
   cheat half-on. Leave all other cheat bits untouched.
   *Done when:* checker's per-frame assertions pass and no other cheat branch changed.

4. **Movement-only safety patch.** Edit `src/game/player.cpp:347` to
   `if (r_no_0 == 0 && DbgFlagChk(pG, DBG_KAIOUKEN))`. Generate
   `port/whole/patches/0255-leon-speed-move-only.patch` with
   `port/tools/whole_make_patch.py NAME ORIGINAL EDITED`, then confirm it applies through
   `port/tools/whole_prepare.py --out <tmp>` and `bash .harness/analysis/check_hooks.sh`.
   *Done when:* the patch is checked in, the checker finds the guard, and preparation succeeds.

5. **Java UI and preference.** Add a 4-value selector to the PLAYER group using the existing
   Stack-ammo pattern; new `Host.leonSpeedChanged(int)` callback; `CoreActivity` persists
   `LEON_SPEED_LEVEL` and calls `CoreBridge.cheatAction(11 + level)`; re-send the stored value once
   at startup. Selector index 0 = Off.
   *Done when:* checker's Java assertions pass and `git diff --check` is clean.

6. **Strings and build.** Add the four new string keys to `strings_ingame.xml` and all locale files;
   run `python3 port/tools/check_translations.py` (or the repo's current translation check) and
   confirm no missing translations. Push to a new `ci/**` branch and wait for the Android core APK
   build.
   *Done when:* translation check passes, CI run is green, artifact is available.

7. **Playtest handoff.** Report the CI run and ask the player to try Off → 2× → 3× → 4× in r100/r101,
   including a soft reset, a door transition and a cutscene, then report any clipping or event
   duplication. Do not install an APK.

## Verification notes

- No offline Java compile covers `CoreMenu`/`CoreActivity`; the GitHub Actions build is the real
  type check. The static checker catches missing wiring before that.
- The game build has no unit-test harness for `cPlayer::move`; the manual playtest is the proof
  that 4× is usable. Known tradeoff accepted in the design: foot sliding and thin-trigger clipping.
- When done, update the design doc with the shipped CI run and any playtest findings.
