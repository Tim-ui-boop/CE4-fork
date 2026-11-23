# Randomize enemy types — what it does and how to use it

Branch `enemy-randomizer`, commit 37bf9ec1. Adapted from the PC RE4 Randomizer's "Randomize Enemies"
and its enemy toggles (the project owner's wish of 22.11.2026).

**In one sentence:** the Ganados come as other enemies — a villager as Dr. Salvador with the chainsaw,
an island soldier as JJ with the minigun — while keeping their places and their number, so the game
still finishes.

---

## 1. Where it is in the app

**Cheats & Mods → Enemies and world**, beside *Random enemies* and *More enemies*:

- **Randomize enemy types** — the master switch. Like the other two it gets a new seed each time it
  goes on, and it takes effect from the next room.
- Eleven option switches under it, each independent:

| switch | what it does | default |
|---|---|---|
| Dr. Salvador | ~1 villager in 12 comes with the chainsaw | **on** |
| Garrador | ~1 zealot in 8 comes as the blind claw man | off |
| JJ | ~1 island soldier in 2 comes as the gatling man | off |
| Super Salvador | ~1 island soldier in 8 comes as the big chainsaw man | off |
| Other looks | the room's own Ganados in another model variant (faces, clothes, size) | **on** |
| Helmets | ~1 Ganado in 10 wears a covered head that turns bullets aside | off |
| Shields | ~1 zealot or island soldier in 7 carries a wooden shield | off |
| Plagas | ~1 Ganado in 5 spawns a plaga on critical damage | off |
| Dynamite | ~1 villager in 12 throws dynamite | off |
| Mix all enemies | off: each area keeps its own family. On: any of them anywhere | off |
| Keep Luis safe | no chainsaw enemy in Luis's cabin | **on** |

The ratios are the PC tool's own defaults, read from its profile
(`salvadorToVillagerRatioTrackBar=12`, `jjToMilitaryRatioTrackBar=2`,
`garradorToZealotRatioTrackBar=8`). Its tooltip explains the meaning: *"a 1:9 ratio for Dr. Salvador
means that, if a room has villagers, for 9 regular villagers there will be a Dr. Salvador"*.

All three enemy switches work **together**: *Random enemies* moves them, *More enemies* adds copies,
*Randomize enemy types* changes what they are. Patch 0255 works on the copy patch 0231 made, so a moved
enemy keeps its new place **and** its new kind.

## 2. What it never changes

This is what makes it safe, and it is enforced in code (`re4_enemy_types_entry`,
`port/whole/runtime/enemy_types.cpp`): the entry is copied and **only `id`, `type` and `flag` are
written**. Everything else stays the disc's own:

- `set` — the scripted entrance ("R11C 1F IN", "R300 Take Ashley")
- `room`, `pos`, `rot` — where it stands and faces
- `hp`, `Character`, `Guard_r` — its health, behaviour and guard radius
- `be_flag` — whether it is on, already created, dead

The enemy list itself is never written, so **a save plays with the switch on or off alike**.

Left untouched entirely:

- bosses and the kept rooms (the protection table's "untouched" kind)
- entries the room's script names (`EmSetFromList2`, `setEm`, `GetEmPtrFromList`, …)
- entries an area waits for (a door or an item that comes when that enemy dies)
- the special forms already on the disc: the chainsaw types, the claw men, the gatling man,
  super Salvador, the trader
- the extra games (Assignment Ada, Mercenaries), Ashley's part, stage 4

## 3. Why it cannot softlock the game

Three measured facts, not assumptions:

1. **The kill count stays correct.** Doors that open when all enemies are dead count by kind:
   `SceCountEmAlive(0x10, 0x20)` (`src/game/sce_com.cpp:398` — `em->id >= lo && em->id <= hi`).
   Every kind this deals is inside 0x10..0x20, including JJ (0x1D) and super Salvador (0x20).
   A villager turned into JJ is still counted.
2. **No script reads what we change.** Searching every room script (`src/st1`, `src/st2`, `src/st3`):
   `Em_list[N].type` — 0 occurrences, `Em_list[N].flag` — 0 occurrences. The model and the weapon are
   free to change; the kind is not, which is why kinds only move within the family.
3. **The module budget is respected.** The game loads at most 4 enemy modules at once
   (`EmReadModule[4]`, `src/game/read.cpp:78`) and clears them on every door transition
   (`src/game/game.cpp:1259`). There is no eviction: a 5th kind is silently missing
   ("Em set failed", `em_set.cpp:171`). The deal counts the room's kinds and **refuses** a swap that
   would need a new module beyond 3 — the PC tool's own default limit, leaving a slot for Ashley/Luis.

Dr. Salvador costs **no** module at all: `Em10WeaponSet` (`src/em10/em10_set.cpp:268-273`) loads the saw
models for every model type, and `em10.cpp:18082` equips the saw when flag bit 28 is set. That is why
the chainsaw is the default and JJ is not.

## 4. What was verified

| check | result |
|---|---|
| `enemy_types.cpp` syntax at the game's own 32-bit width | **clean**, 0 errors |
| `enemy_random.cpp` (unchanged, same flags) | **clean**, 0 errors |
| patches 0231 + 0244 + 0255 apply in sequence | **all OK** |
| option bits agree between C++ and Java | **12/12 match** |
| JNI names ↔ Java natives ↔ core exports | **all line up** |
| 24 new string resources referenced by the menu | **all present** |
| offline rehearsal, 5 seeds × 2 option sets | **all invariants hold**, deterministic |

The rehearsal (`port/tools/enemy_types/rehearse.py`) mirrors the C++ rules and runs them on the real
disc lists. At the defaults it deals ~70 entries in ~25 rooms; with every enemy on, ~100 entries in
~29 rooms, including 13–17 JJs. It asserts that `set`/`room`/`pos`/`hp`/`Character`/`Guard_r`/`be_flag`
are byte-identical, every dealt type is supported by its module, no room exceeds 4 modules, and Luis's
cabin stays safe.

**Not verified: an actual playtest.** No APK was built and nothing was played. The open question is
whether a saw-carrying Ganado animates correctly in every `set` state (ladder, roof, window) — that
needs a device.

## 5. What was deliberately left out

The PC tool has settings this port cannot honour honestly:

- **Per-enemy population caps and spawn chances** (`dogAmountNumericUpDown`, `regeneratorChanceTrackBar`,
  …). Those control *adding* enemies from a global pool. This deal keeps the room's own number, so a cap
  would have nothing to cap. *More enemies* already covers adding.
- **Random enemy health** (`randomEnemyHealthCheckBox`, the HP min/max boxes). Writing `hp` would break
  the "only id/type/flag" invariant that keeps saves compatible; it deserves its own pass.
- **Novistadors, regenerators, iron maidens, armaduras as pool entries.** The PC tool disables
  Novistadors by default because they crash randomly. These are non-Ganado kinds: swapping them in
  would change the kill count and the module budget in ways the current guards do not cover.
- **Don José's replacement** (`donJoseReplacementComboBox`). The option bit exists
  (`ENEMY_OPT_DON_JOSE`) but r100 is a kept room in the protection table, so it is not dealt yet.
- **The extra enemies for boss areas** (Saddler, U3, Luis cabin, r324). Those add enemies to rooms the
  protection table keeps untouched.

## 6. Files

| file | role |
|---|---|
| `port/whole/runtime/enemy_types.cpp` | the deal: families, ratios, types, guards |
| `port/whole/patches/0255-enemy-type-randomizer.patch` | the hook in `EmSetFromList` |
| `port/whole/runtime/re4_core_ipc.h` | commands 54 (seed) and 55 (options) |
| `port/whole/host/coreproc.cpp` | binds and dispatches them (optional: an older core ignores them) |
| `port/android-decomp/.../core_mode.cpp` | JNI + dlsym + IPC wiring |
| `port/android-decomp/.../CoreBridge.java` | `setEnemyTypes`, `setEnemyOptions` |
| `port/android-decomp/.../CoreActivity.java` | prefs, option constants, startup resend |
| `port/android-decomp/.../CoreMenu.java` | the switch and the eleven options |
| `port/android-decomp/.../strings_ingame.xml` | labels and help texts |
| `port/tools/enemy_types/rehearse.py` | offline seed rehearsal |
| `port/tools/enemy_types/check_hooks.sh` | on-phone syntax check |

## Reproduce

```
python3 port/tools/enemy_types/rehearse.py          # 5 seeds, defaults and all-on
bash port/tools/enemy_types/check_hooks.sh          # syntax check at 32-bit width
```

The rehearsal needs the two disc images two levels above the repo (the workspace root).
Background: `enemy-type-randomizer-research.md`, `pc-randomizer-decompile-findings.md`.
