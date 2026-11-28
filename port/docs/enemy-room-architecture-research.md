# How a room and its enemies work (research, 28 Nov 2026)

Plain-language reference gathered from the GameCube sources, the generated room table and the
offline dealer. This is research, not a design change: it explains why some rows stay vanilla and
why a room can go blank. File references are relative to the repo root.

## 1. Two different files are involved

A room is **not** an enemy list.

- `stX/rNNN.das` is the room archive: geometry, textures, events. `ReadAreaData()` reads and
  decompresses it into `pG->pRoom` (`src/game/read.cpp:119-188`).
- `etc/emleonNN.esl` is one of eight flat enemy lists (256 records × 32 bytes). `StageSet()`
  picks the list by stage/room/story flags and `readEmList()` loads it raw into `pG->Em_list`
  (`src/game/stage.cpp:35-133,181-251`). That flatness is why the launcher can deal lists offline.

One `EM_LIST` record is 32 bytes (`include/global.h:66-81`): `be_flag` (0x00), `id` (0x01),
`type` (0x02), `set` (0x03), `flag` (0x04), hp (0x08), room (0x18). Scripts mostly care about
`id`, `set` and the row number; the dealer may change `type` and non-weapon flag bits freely.

## 2. The four enemy module slots

`MODULE_DAT EmReadModule[4]` (`src/game/read.cpp:78`) holds four loaded enemy modules.
`pullEmModule()` (`read.cpp:562-573`) returns the first free slot or NULL — there is no eviction.
Slots are cleared on every door transition (`src/game/game.cpp:1260-1265`). Asking for a fifth
kind makes `Em set failed` and that enemy does not appear.

Ashley no longer consumes one of these four: patch `0256-ashley-dedicated-module.patch` gives her
a private slot, and `ReleaseAshleyModule()` is called at each door.

Sound banks are separate (DRS type 4 records in ARAM). Two measured failure modes: bank space
running out, and a bad sample pointer in em1b's bank causing `__SYNGetWavetableData` to fault.
The launcher writes introduced module names to `randomizer/enemies/no-sound.txt` and the runtime
rewrites those banks to empty records, so dealt enemies spawn silently.

### The blank-room bug (fixed in patch 0258)

`readEm()` temporarily sets `pG->Disp_flg = 0xFFFFFFFF` while loading a module and restores the
saved value on both the "read failed" and "success" paths — **but the `pullEmModule() == NULL`
path returned without restoring it** (`src/game/read.cpp:252-270`). `Disp_flg` bits are
"hide" flags (`0x800` HUD, `0x1000` prompts, `0x2000` HUD, `0x10000` skip OT, `0x40000000`
player weapon, …), so all-ones hides the entire drawn world. Audio and the Android menu overlay
keep working. That is the user-reported "sound and menu but nothing rendered" symptom. Patch
`0258-readem-restore-display-flags.patch` restores the saved flags on that path: a missing
module may now cost one enemy, but it can no longer blank the room.

## 3. Ten ways a room gets an enemy

1. **Automatic list sweep** — `EmSetFromList()` at room init spawns every row with `be_flag` bit0
   (`src/game/em_set.cpp:137-188`).
2. **On-demand list row** — `EmSetFromList2(no, chkDead)`, e.g. r100's ambush rows 3/4/5/0x12/
   0x13/0x26/0x27 (`src/st1/r100.cpp:495-505`).
3. **Hand-built record** — `EmSetEvent(&d)` with no ESL row: r100's officer-killer
   (`r100.cpp:164-185`), r106's boulder pushers and hall enemies (`:187-233,555-638`).
   An offline list deal can never reach these — they need a script/runtime patch.
4. **`setEm`** — `cEmWrap::setEm` / `setEm(no, list, …)` reuses a live enemy or spawns the row;
   used by r101's waves (`src/st1/r101.cpp:661-671,721-738,908-910`) and r108's reinforcements.
5. **`setPtr`** — attaches a controller to an existing enemy, e.g. r106's two Ganados
   (`src/st1/r106.cpp:238-264`).
6. **Fetch-only list APIs** — `GetEmPtrFromList`, `GetEmIdFromList`, `GetListPtrFromEm`
   (`src/game/em_set.cpp:260-297`) are why those row numbers become DRIVEN.
7. **Direct `Em_list[n]` access / lifecycle** — e.g. r100 rewrites rows 4/5's `set` byte
   (`r100.cpp:928-931`), r101 toggles `be_flag` and calls `EmListSetAlive`.
8. **Module preloading** — `EmReadSearch`/`SearchEmModule` decide which module archives exist:
   r100 preloads 0x12, r101 0x15/0x26, r103 0x12 (corpses), r106 four at once, r108 0x17.
9. **Count/sweep/destroy by id** — `SceCountEmAlive`, `SceDestroyEm` (`src/game/sce_com.cpp:382-437`);
   r101's bell and r108's reinforcements depend on them. This is why `id` cannot be changed freely.
10. **Event motions and hit targets** — `setEvtMotion()` hard-casts r100 rows 6/7/8 to
    `cEmGanado` (`r100.cpp:1039-1051`); `SetEmHit()` creates bell/cover targets.

The table generator detects classes 2, 4, 5, 6 and 7 by scanning for those call names
(`port/tools/enemy_random_table.py:2-23,32-35,183-212`).

## 4. Screen state: present at entry vs later

- **r100**: 25 ESL rows (12 villagers, 5 crows, 1 dog, 7 traps); only the crows are alive at
  entry. The officer is hand-built, and the horde arrives from `r100_em_set()` after his death.
- **r101**: bell waves are spawned by `setEm` at several later points. Rows `0x22-0x3a` are now
  SPAWN (dealt); rows `0x3c-0x46` remain DRIVEN because their named motion presets live in
  em15's event archive.
- **r103**: 18 rows already occupy four kinds (villager, dog, cow, chicken) at entry; the
  rescued dog (row `0x56`) is a story row.
- **r106**: four modules are preloaded by the room script itself before any enemy spawns.

## 5. The generated protection classes (`EnemyRooms.java`)

| Class | Meaning | Dealer behaviour |
|---|---|---|
| `KIND=UNTOUCHED` (10 rooms) | boss / hand-kept room | skipped entirely |
| `KIND=PLACES_ONLY` (52 rooms) | script counts or sweeps rows | every ordinary row treated as counted: boss ids refused unless `ALIAS` |
| `KIND=OPEN` (46 rooms) | neither | full swaps allowed |
| `DRIVEN` | script spawns **or reads the identity of** the row | `appearanceOnly()`: id, hp, set, position, room and weapon bits untouched; only type/extras may change |
| `SPAWN` | script only spawns the row, never reads its identity | may be dealt; Ganado-family ids stay in a basic family; 4-module budget still applies |
| `KEEPROWS` | story rows (r100 `0x09`, r103 `0x56`, the dog, r11c `0xC8`) | exact disc bytes |
| `FORCED` | modules preloaded by the room script | reserved up front in the budget |

After the 28 Nov script audits, SPAWN rows also exist in r104, r105, r10a, r10c, r205, r209, r226
and ten island rooms (r300, r303, r304, r305, r306, r30b, r30d, r30e, r316, r321) — 125 rows in
total. Rows whose script reads identity — `setGoto`/`ckGoto`/`ckFindPL`/`ckResetEnable`,
count gates on vanilla ids, or named motions — stay DRIVEN. Rejected promotions include r206,
r207, r208, r301, r307, r308, r309, r30c, r310, r311, r320, r327 and the boss rooms; their
scripts dispatch on the enemy id through Ganado-only virtuals or read list identities.

## 6. id vs type vs set vs flag

- `id` chooses the enemy module and AI class. Scripts cast some rows to `cEmGanado`, so those must
  keep a Ganado-family id.
- `type` is a model variant **inside** that module; scripts never read it. A few story rows use a
  special value outside the module's variant set (Luis's cabin actor is `0x13`/`0x06`); those must
  not be rewritten, and `appearanceOnly()` now keeps their TYPE and logs `SPECIAL_TYPE_KEPT`.
- `set` is a named behaviour/motion preset whose animation data lives in the *module's own
  archive*. r101's `set` 0x05-0x0E motions live in em15; moving such a row to em1b caused
  `MotionSetCore` → `HermiteInterpolation` null-pointer crashes. Rule: reset `set` to 0 when
  changing a row's id. The dealer does this on every dealt row.
- `flag` carries weapons and clothing bits. Script-owned weapon bits are preserved by
  `keptWeapons()`.

## 7. Phase 2 (the count-alias patch) and what is still open

Phase 2 = patch `0257-count-alias.patch`: while a valid dealt list is active, an exact
`SceCountEmAlive/DestroyEm(0x10,0x20)` request also counts ids `0x2B,0x2C,0x36,0x39,0x3C`.
It never writes `em->id`. This is done and checked by `check_count_gate.py` / `check_count_alias.py`.

Still open (from `docs/superpowers/plans/2026-11-26-phase2-count-alias.md` and the all-rooms plan):

1. Grow the hand-curated SPAWN table past four rooms; audit `EmSetFromList2`/`setEm` call sites and
   promote rows whose identity is never read.
2. Reopen r101 rows `0x3c-0x46` (needs a motion strategy; the dealer's `set=0` reset makes it
   plausible, but the four-module budget may refuse it).
3. Allow in-family id swaps for r100 rows `6/7/8` while keeping the `cEmGanado` cast valid.
4. Hand-built actors (`EmSetEvent`) cannot be randomized by an offline list at all.
5. Housekeeping: the old `deal()`-based tools were retired in favour of `run_all_lists.py` and
   `check_special_fields.py`; the foundations doc now has a done/open split.
6. Done since that plan: the general special-TYPE guard, the r11c row split (only `0xC8` is
   byte-exact, `0xC9..0xD1` are DRIVEN again), the r11d wave-row guard, and `room-debug.txt`
   reasons for every row decision.

Rows that will *never* change by design: the two rescued dogs, merchants (id `0x18` and the
id `0x13/0x14/0x1E` TYPE `0x06` trader-clothed figures, including r10f `0x3D`/`0x49` outside the
Mendez boss room), the ten
untouched rooms, r11c `0xC8`, and every row in a room restored for exceeding four modules.
A DRIVEN row whose disc TYPE is outside `variantTypes(id)` keeps that TYPE in every room.

## 8. Count alias detail

`ALIAS` in `EnemyRooms.java` is true only where *every* count call in the script is literally
`(0x10,0x20)` — e.g. r101, r108, r200, r202, r204, r206, r208, r209, r214, r217, r300/301, r320.
It is false for r100, r103, r106 and for rooms with single-id counts (`0x1D`, `0x36`, `0x2B`).
The dealer refuses boss ids on counted rows unless `alias(room)` is true
(`EnemyShuffle.java:493-500,520`).
