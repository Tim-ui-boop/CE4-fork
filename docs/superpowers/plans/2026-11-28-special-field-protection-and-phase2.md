# Implementation plan — special-field protection + Phase 2

Date: 2026-11-28
Spec: `docs/superpowers/specs/2026-11-28-special-field-protection-and-phase2.md`
Execution: native, in this session. Branch `feature/enemy-module-guard`, mirror `ci/enemy-module-guard`.

## File map

| File | Responsibility |
|---|---|
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyShuffle.java` | Dealer. Add the special-type guard inside `appearanceOnly()`; pass `detail`/`src` so a kept row is logged. |
| `port/tools/enemy_random_table.py` | Generator. Remove r11c rows `0xC9..0xD1` from `KEEP_ROWS`; keep `0xC8`. Regenerates the Java table. |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyRooms.java` | Generated. Must show r11c `KEEPROWS = {0xc8}` and `0xC9..0xD1` in `DRIVEN`. |
| `.harness/analysis/check_special_fields.py` | NEW offline check. Proves special-type rows stay byte-exact in every room and the nine cabin attackers still vary. |
| `.harness/analysis/check_enemy_rooms.py` | Add an explicit r11d wave-row / count-gate assertion. |
| `port/tools/enemy_types/run_deal.py`, `.harness/analysis/run_enemy_deal.py` | Stale `deal()` API → current `dealRooms()` API, or retire in favour of `run_all_lists.py`. |
| `port/docs/enemy-randomizer-foundations.md`, `port/docs/enemy-room-architecture-research.md` | Record what Phase 2 closed, what stays vanilla and why, and the new special-type rule. |

## Task 1 — RED: write the new offline check

Create `.harness/analysis/check_special_fields.py`. Follow the existing pattern in
`check_script_spawns.py`: import `run_all_lists as ral` and `emlist_dump as em`,
build Java classes with `ral.build()`, extract the disc with `ral.extract()`, run
`ral.run(classes, data, disc, seed, options)` for seeds `(1, 42, 777, 31337,
1148088)`, and read `data/randomizer/enemies/<list>` bytes.

Add a tiny Java runner compiled with the classes that calls the private
`EnemyShuffle.variantTypes(int)` by reflection (`getDeclaredMethod`,
`setAccessible(true)`) and prints the allowed types for a given id. This avoids
duplicating the pool table in Python and needs no production visibility change.

Assertions:

1. For every non-empty row in every vanilla ESL list, if the disc `TYPE` (byte
   offset `0x02` in the 32-byte entry) is **not** in `variantTypes(ID)` (byte
   `0x01`), the dealt 32 bytes must equal the vanilla 32 bytes for every seed.
   This is the general rule and covers all rooms.
2. r11c row `0xC8` must be byte-exact (it has id `0x13`, type `0x06`, outside
   `{0,1,3,4}`).
3. r11c rows `0xC9..0xD1` must keep bytes `0x00..0x01`, `0x03..0x07` and
   `0x18..0x1F` (be_flag, id, set, hp, room etc.), but at least one of them must
   differ in TYPE (`0x02`) or FLAG (`0x04..0x0B`) in some seed. This fails today
   because all ten rows are `KEEP_STORY`.
4. Print `RESULT: all special-field checks pass` only when every assertion holds;
   exit non-zero otherwise.

Run it now and confirm it fails for reasons (3) and, if any special-type DRIVEN
row exists, (1). Record the failing output. **Do not touch production code yet.**

## Task 2 — GREEN: guard special types in the dealer

In `EnemyShuffle.java:375-391`, change `appearanceOnly` to accept
`StringBuilder detail`, `byte[] src` and `int room` (matching `logRow`'s needs).
At the top:

- read `int discType = u8(out, at + TYPE)`;
- compute `int[] types = variantTypes(u8(out, at + ID))`;
- if `types.length == 0` or no entry equals `discType`, call
  `logRow(detail, src, out, at, room, no, "SPECIAL_TYPE_KEPT")` and return
  without writing anything.

Otherwise keep the current body verbatim (type from the allowed set when
`VARIETY` is on, non-weapon flag extras, every vanilla weapon bit preserved).
Update the single caller at `EnemyShuffle.java:573-576` to pass `detail`, `src`,
`room`.

Run `python3 .harness/analysis/check_special_fields.py` and
`python3 .harness/analysis/check_enemy_java.py`. Both must pass. Note which
assertion (1) protects beyond r11c — report the room/row count.

## Task 3 — un-freeze the nine ordinary cabin attackers

In `port/tools/enemy_random_table.py:47-54`, leave `0x11c: {0xC8}` in
`KEEP_ROWS` and remove `0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF, 0xD0, 0xD1`.
Update the comment to state the real reason: `0xC8` is Luis's story actor with
special type `0x06`; the other rows have ordinary types and are safe for
appearance-only changes under the new general rule.

Regenerate with:

```
cd .harness/clones/CE4-fork && python3 port/tools/enemy_random_table.py --java-out port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyRooms.java
```

Do **not** regenerate the runtime `.inc`. Verify the generated table has r11c
`KEEPROWS = {0xc8}` and that `0xC9..0xD1` appear in `DRIVEN`. Rerun
`check_special_fields.py` — assertion (3) must now pass.

## Task 4 — r11d count-gate assertion (RED → GREEN)

Add to `.harness/analysis/check_enemy_rooms.py`: for room `0x11d`, rows
`0xD2..0xDB` must all be in `DRIVEN`, `alias(0x11d)` must be true, and no row
outside `DRIVEN` may take a newly dealt counted boss id (`0x2B,0x2C,0x36,0x39,0x3C`)
in a dealt run. Reuse the parsing helpers already in that file. Run it, confirm
the new assertion fails before any change (it should currently pass if the
data already complies — in that case record it as a guard that is green on
first run, and do not manufacture a change).

## Task 5 — stale tools and documentation

- `port/tools/enemy_types/run_deal.py`: replace the `deal(data, disc, seed,
  options)` call and its result fields with `dealRooms(File, File, long, int,
  int[])` and `Result.ok/message`, or delete the script with a note pointing at
  `run_all_lists.py`. Do the same for `.harness/analysis/run_enemy_deal.py`.
- `port/docs/enemy-randomizer-foundations.md` §10: rewrite "What is still open"
  into a done/open split. Open: SPAWN growth, r101 `0x3c..0x46`, r100 `6/7/8`,
  `EmSetEvent` actors. Done: special-field protection, r11c treatment, debug
  logging, r11d guard, stale-tool cleanup.
- `port/docs/enemy-room-architecture-research.md`: add the special-type rule and
  the r11c row split. Note that `SAFE_CABIN` is retained only for saved-settings
  compatibility and superseded by the rule.

## Task 6 — full verification gate

Run every command in the spec's verification section from the workspace root,
in order. Capture the output of each. Any failure stops the push. Also run
`python3 .harness/analysis/check_special_fields.py` with the player's exact
options (`RE4_OPTIONS=128564223`) at seed `1148088` and inspect the generated
`room-debug.txt` r11c section: `0xC8` should say `KEEP_STORY`, `0xC9..0xD1`
should say `DRIVEN_APPEARANCE_ONLY` or `SPECIAL_TYPE_KEPT`.

## Task 7 — commit, push, CI

Stage only the changed files in the clone (dirty `src/Tools` is unrelated).
Commit with a plain message describing the general guard and the cabin unfreeze.
Push `feature/enemy-module-guard` and `ci/enemy-module-guard` to the same SHA.
Wait for the "Android core APK" workflow on `ci/enemy-module-guard`; report the
run number and artifact id. Do not install an APK on the phone.
