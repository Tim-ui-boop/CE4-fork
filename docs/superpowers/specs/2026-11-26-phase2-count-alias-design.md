# Phase 2 design — gated count alias for boss enemies (26 Nov 2026)

## Goal

Let the offline enemy dealer place boss kinds on rows that room scripts count, without changing
the enemy's id in memory. Phase 1 keeps those rows vanilla because `SceCountEmAlive(0x10,0x20)`
would not see an out-of-range id and a bell/wave script could wait forever.

This is the last defined phase. Full r100 randomization and merging the item/door randomizer stay
separate projects.

## Engine patch (0257; 0256 is taken)

`src/game/sce_com.cpp`: in `SceCountEmAlive` and `SceDestroyEm`, only when the request is exactly
`(0x10, 0x20)` and the randomizer marker is active, treat ids `0x2B`, `0x2C`, `0x36`, `0x39`,
`0x3C` as inside that range. Both callers use that exact range; single-id counts (`0x11`, `0x1D`,
`0x2B`, `0x36`, etc.) must stay unchanged. Do not write `em->id` or any `EM_LIST.id`.

Gate: `re4_randomizer_enemy_active()` in `port/whole/runtime/enemy_sidecar.cpp`, declared in
`re4_runtime.h`. It is true only while a valid dealt enemy list has been served. Turning the deal
off removes the marker; the next list read clears the flag. Non-randomized play and damaged
sidecars keep vanilla count behavior.

## Dealer change

`EnemyShuffle.java` `Member.counted()` may allow the boss ids, so a counted row can take them.
Keep every other guard: four enemy modules, one heavy model, script-owned rows untouched, r100
untouched, and the new Ashley module and SPAWN tables intact.

## Tests first (RED then GREEN)

1. `check_count_alias.py`: patch applies; gate off counts only `0x10..0x20`; gate on adds exactly
   the five aliases for `(0x10,0x20)` only; single-id and other ranges unchanged; `SceDestroyEm`
   follows the same rule; no assignment to `em->id`.
2. `check_count_gate.py`: the getter is false with no marker/damaged list, true after a valid
   served list, false again when the marker is removed.
3. Extend `run_all_lists.py`: with high boss ratios, counted rows in r101/r104 can receive
   out-of-range ids; protected bytes, module budgets, heavy cap, determinism and r100 stay green.
4. Keep `check_enemy_java.py`, `check_enemy_rooms.py`, `check_script_spawns.py`,
   `check_module_guard.py` and `check_ashley_slot.py` green.

## Delivery

Implement and verify **offline only**. No commit push and no CI build until the user has installed
and playtested build 6315. After that, push to a `ci/**` branch and confirm GitHub Actions.
