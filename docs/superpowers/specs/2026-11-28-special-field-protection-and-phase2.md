# Special-field protection + remaining Phase 2 — design

Date: 2026-11-28
Status: design, awaiting approval
Scope: offline enemy randomizer, branch `feature/enemy-module-guard`

## Problem

The Luis cabin siege (r11c) was frozen byte-exact in commit `6b088105` after the
player reported that no enemies entered the cabin and the scene hung. The
handoff said "changing type/set" broke it, but `appearanceOnly()`
(`EnemyShuffle.java:375-391`) never writes `set`. Forensic check of chat5 and the
code shows the real mechanism:

- r11c row `0xC8` is Luis's own story actor, id `0x13`, **type `0x06`**.
- `variantTypes(0x13)` returns only `{0,1,3,4}`, so `appearanceOnly()` rewrote
  type `0x06` into an ordinary villager variant.
- r11c's script drives that actor through Ganado-only work fields, including
  `set`-derived `R11c_in_ck` (`em10.cpp:2909-2913`), and waits for `kill > 39`
  (`src/st1/r11c.cpp:521`). The nine attacker rows `0xC9..0xD1` have ordinary
  types inside `{0,1,3,4}`, so their type churn was not the cause.
- No `Em set failed`, no module exhaustion, no whole-room restore occurred in
  the player's seed.

So the freeze protected one genuinely special row and unnecessarily froze nine
ordinary ones.

## Design decision

A general rule in the dealer, not an engine patch:

1. **Preserve special TYPE.** In `appearanceOnly()`, compute the allowed
   variant types for the row's id. If the disc's `TYPE` is not in that set (or
   the set is empty), do not rewrite that byte and log reason
   `SPECIAL_TYPE_KEPT`. Safe non-weapon flag bits may still change, and id, hp,
   set, position and weapon bits are never written (as before). Otherwise keep
   the current appearance-only behaviour. A disc-wide count showed Luis's
   `0x13`/`0x06` is not unique to one row, so a "unique type" heuristic would
   not protect it; the pool's variant set is the only available authority.
   Measured effect: 438 DRIVEN rows across the campaign keep their TYPE. This
   is deliberately conservative — it costs some type variety in script rooms
   in exchange for not repeating the cabin failure. Narrowing it needs a
   curated per-id type table and another playtest.
2. **Un-freeze the ordinary cabin attackers.** Remove rows `0xC9..0xD1` from
   `KEEP_ROWS` in `port/tools/enemy_random_table.py` and from generated
   `KEEPROWS` in `EnemyRooms.java`. Keep `0xC8` in `KEEP_ROWS` as an explicit
   story actor; the new general rule would also protect its type `0x06`.
3. **No engine patch.** A class dispatcher can avoid the bad Ganado cast but
   cannot emulate `setReset()` for non-Ganados: private work fields
   (`St_pos`, `St_set`, `Reset_enable`, `R11c_in_ck`, `evtMot`) do not exist on
   other classes, respawn-from-row is blocked by sticky `be_flag & 2`, needs at
   least two frames, and can fail on the four-module limit. Since the cabin
   hang did not involve an id change, a dispatcher would not prevent it.

## Phase 2 items included in this change

- Add `.harness/analysis/check_special_fields.py`: across many seeds and all
  disc sidecars, assert that any row whose vanilla type is outside
  `variantTypes(id)` stays byte-exact; assert r11c `0xC8` stays byte-exact and
  `0xC9..0xD1` keep id/hp/set/position while being allowed to vary in type.
- Add a per-row `SPECIAL_TYPE_KEPT` reason to `room-debug.txt` so a player can
  see why a row did not change.
- Add an explicit r11d assertion: its ten wave rows `0xD2..0xDB` must remain in
  DRIVEN, and with `ALIAS=true` no non-DRIVEN r11d row may take a counted boss
  id that would change the `SceCountEmAlive(0x10,0x20) > 10` wave gate.
- Retire the no-op `SAFE_CABIN` bit as a setting: keep the bit value and UI row
  for saved-settings compatibility, but document that it is superseded by the
  general special-field rule and no longer controls r11c. Do not renumber bits.
- Fix stale tools: `port/tools/enemy_types/run_deal.py` and
  `.harness/analysis/run_enemy_deal.py` call the old `deal(data, disc, seed,
  options)` API and read result fields that no longer exist. Point them at
  `dealRooms(File, File, long, int, int[] ratios)` / `Result.ok|message`, or
  remove them in favour of `run_all_lists.py`.
- Update `port/docs/enemy-randomizer-foundations.md` §10 ("What is still open")
  and add a Phase 2 section listing what is done and what is intentionally out
  of scope. Update `port/docs/enemy-room-architecture-research.md` to mention
  r11c's keep row and the special-type rule.

## Explicitly out of scope (document reasons, do not "fix")

- SPAWN table growth past r100/r101/r117/r118: each new room needs a hand audit
  of every `setEm`/`EmSetFromList2` call for identity reads; a wrong promotion
  corrupts casts or kill counts. This stays a separate, playtested task.
- r101 rows `0x3c..0x46` and r100 rows `6/7/8`: their `set` motions live in
  swapped/room-specific archives (`em15`, `r100` arcs). Full id changes crashed
  with `MotionSetCore`; they remain vanilla by design.
- Hand-built `EmSetEvent` actors (`r100.cpp:164-185`) are constructed in code,
  not from ESL rows, so no offline sidecar can reach them.
- Engine script patch / generic Ganado dispatcher: rejected as high risk by the
  user after research.

## Verification gate

Run from the workspace root, exactly as the standing handoff requires:

```
python3 .harness/analysis/check_enemy_java.py
python3 .harness/analysis/check_enemy_rooms.py
python3 .harness/analysis/check_script_spawns.py
python3 .harness/analysis/check_module_guard.py
python3 .harness/analysis/check_ashley_slot.py
python3 .harness/analysis/check_dealt_muted.py
python3 .harness/analysis/check_trader_untouched.py
python3 .harness/analysis/check_count_alias.py
python3 .harness/analysis/check_count_gate.py
python3 .harness/analysis/check_count_deal.py
python3 .harness/analysis/check_speed_cheat.py
python3 .harness/analysis/check_readem_flags.py
python3 .harness/analysis/check_special_fields.py        # new
python3 .harness/analysis/run_all_lists.py               # 64 seeds
python3 .harness/analysis/stress_all_on.py               # 43 seeds
python3 port/tools/whole_prepare.py --out $HARNESS_SCRATCH/ce4case
```

TDD order: write `check_special_fields.py` first and watch it fail against the
current code, then change the dealer/generator. Every new assertion must have
been observed failing for the intended reason.

Push only after all checks pass and CI is green. The user installs and
playtests; do not install an APK on the phone.
