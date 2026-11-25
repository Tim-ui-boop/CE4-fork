# Leon movement-speed cheat — short design (25 Nov 2026)

## Intent

For room speedruns, let the player multiply **Leon's normal movement** by 2×, 3× or 4× from the
existing in-game CHEATS page, with an Off setting. This is a cheat, not a randomizer feature: it
does not change seeds, discs or saves.

## Approaches considered

1. **Expose the built-in debug speed mode (chosen).** The game already contains `DBG_KAIOUKEN`
   and `PlKaiou` (`include/global.h:361`, `include/player.h:374`), consumed in `cPlayer::move()`
   (`src/game/player.cpp:346-354`) to repeat the movement routine 1–3 extra times = 2×/3×/4×.
   It is compiled into the core build already; we only need a runtime setter, a menu row and a
   one-line safety guard. Smallest change, exact 2–4× movement.
2. **Expose the same mode without the safety guard (rejected).** Unguarded, the repeat also runs
   event/damage/die player routines, which can double-fire cutscene or damage logic. The extra
   speed is not worth that breakage.
3. **Automate the Ditman/Striker glitch (rejected).** Only ~1.5×, needs the Striker, depends on
   inventory timing, and resets after cutscenes/loading screens. It cannot reach 4×.
4. **Global sequence-speed multiplier (rejected).** `pG->Speed` / `Motion.Seq_speed` would speed
   enemies, events and cutscenes too, making rooms unfair and timing inconsistent.

## Goals / non-goals / anti-goals

- **Goals:** Off/2×/3×/4× row in the CHEATS page; value survives leaving and reopening the menu and
  restarting the app; re-applied after soft reset/room change because `PlayerInit()` clears it.
- **Non-goals:** faster reloads, ladders, kicks, weapons or cutscenes; changing save data; changing
  enemies.
- **Anti-goals:** repeated event/damage routines; global time scaling; dependence on a specific
  weapon or inventory trick.

## Constraints and decisions

- Use the existing `cheatAction` channel (actions 11–14) rather than adding JNI/IPC methods:
  `CoreMenu.Host.cheatAction`, `CoreActivity.cheatAction`, `CoreBridge.cheatAction`, IPC
  `kCheatAction` and `core_api.cpp` already carry integer actions. Extend the accepted action range
  in `port/whole/runtime/core_api.cpp:724-744`.
- Keep the desired level in a small atomic in `port/whole/runtime/cheats.cpp` and re-assert
  `DBG_KAIOUKEN` / `PlKaiou` from the existing per-frame hook (`re4_cheats_install`, `hook()` at
  `cheats.cpp:506-523`, called from `runtime/pad.cpp:412-421`). That survives `PlayerInit()`'s reset.
- Store the chosen level in `GAME_PREFS` in `CoreActivity` (new key, e.g. `LEON_SPEED_LEVEL`) and
  re-send it at startup alongside `CoreBridge.setCheats(...)` (`CoreActivity.java:589-593`).
- UI: an Off / 2× / 3× / 4× arrow selector in the PLAYER group of `CoreMenu.cheats()`, built with
  the existing `selector(...)` helper (`CoreMenu.java:450-464`) and `cheatRow(...)` (`:2118-2124`),
  like the "Stack ammo" row (`:2174-2178`).
- Safety patch: change `src/game/player.cpp:347` from
  `if (DbgFlagChk(pG, DBG_KAIOUKEN))` to
  `if (r_no_0 == 0 && DbgFlagChk(pG, DBG_KAIOUKEN))` so only normal movement repeats. Generate it
  as the next numbered patch (`port/whole/patches/0255-leon-speed-move-only.patch`) with
  `port/tools/whole_make_patch.py`; do not hand-edit patch files.
- Strings: add the row label and four values to `port/android-decomp/app/src/main/res/values/strings_ingame.xml`
  and every locale directory — `check_translations.py` requires all of them.
- Known tradeoff, accepted by the player: at 4× Leon can cross thin triggers between collision
  checks, and foot/weapon animation will slide. The cheat is for speedrunning, not physics fidelity.

## Left to the builder

Exact Java callback name, preference key spelling, action numbers (11..14), and the final patch
number are implementation details. Verification: javac-level check if the menu change is isolated,
`git diff --check`, a clean GitHub Actions build on a new `ci/**` branch, and a phone playtest of
Off → 2× → 3× → 4× in r100/r101 including a soft reset and a cutscene.
