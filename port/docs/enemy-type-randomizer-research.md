# Enemy-type randomizer: research findings

Branch `enemy-randomizer`, 22.11.2026. Read-only research: no game code was changed, no build was run.
Everything below was measured from the disc images and the decompilation in this checkout.

The question was whether a normal Ganado can become a "super Salvador" (a chainsaw Ganado), the way the
PC RE4 randomizers swap enemy types — and whether that is safe enough to ship next to the offline
door/item randomizer.

**Short answer: swapping enemy *ids* is mostly pointless, but swapping the model *type* and the weapon
*flag* inside the module a room already loads is both visible and safe. That is the feature to build.**

---

## 1. What upstream actually does (and does not do)

`port/whole/runtime/enemy_random.cpp` (patch 0231) moves Ganados to each other's **places** and changes
the **count** by ±1/±2. Each enemy keeps its own `id`, `type`, `Character`, `flag` and `hp`. There is no
type swap anywhere in it.

`port/docs/gegner-randomizer-plan.md` §2(a) considered swapping enemy kinds and rejected it:
*"nur sehr eingeschränkt machbar, hohes Risiko, wenig Wirkung"* (very limited, high risk, little effect).
That judgement is correct **for id swaps**, and the measurements below confirm the "little effect" part
quantitatively. It is too pessimistic about `type`.

## 2. The 4-module limit is per room visit, not per chapter

This was the biggest unknown, and it turns out to be favourable.

- `src/game/read.cpp:78` — `MODULE_DAT EmReadModule[4]`: four enemy module slots, fixed size.
- `src/game/read.cpp:563-573` — `pullEmModule()` returns a slot with `pData == NULL`, else `NULL`.
  **There is no eviction and no LRU.** A fifth kind is simply not loaded.
- `src/game/game.cpp:1259` — `EmReadInit()` inside `gameDoordemo()`, state `Rno0 == 4`, i.e. **every door
  transition** clears all four slots. Also `main.cpp:497` (reset), `st1/r106.cpp:359` and
  `st2/r200.cpp:207` (chapter-end cutscenes).

So the budget resets on each room entry. A room may use up to 4 kinds, and what it uses is independent
of its neighbours. Ashley (id 3) and Luis (id 4) each occupy one slot while present.

Failure mode when a 5th kind is asked for (`em_set.cpp:171-175`):

```c
em = EmCreate(d->id);
if (em == 0) {
    pLog->err(0, 0, "EmSetFromList() Em set failed, Id = %x", d->id);
    continue;
}
```

The enemy is **silently missing**, logged only — no crash. The entry is not marked set, so it is retried
later. This is a soft failure, which is good for testing but bad for gameplay: a missing enemy can leave
a "kill them all" door shut forever.

## 3. Measurement: id swaps would be nearly invisible

`.harness/analysis/enemy_kinds_survey.py` reads all 11 ESL lists (`etc/emleon00..07.esl`, `omake00..02.esl`)
straight from both disc images. 85 rooms have live enemies.

Distinct enemy kinds per room:

| kinds in the room | rooms |
|---|---|
| 1 | 59 |
| 2 | 19 |
| 3 | 6 |
| 4 | 1 |

Distinct **Ganado** kinds per room (0x10–0x20, excluding 0x18 Trader):

| Ganado kinds | rooms |
|---|---|
| 0 | 30 |
| 1 | 54 |
| 2 | 1 |

**Only one room in the whole game (r20d) has two Ganado kinds to swap between.** Everywhere else an id
shuffle would exchange a Ganado for… the same Ganado. This is the "wenig Wirkung" of the plan, now with
a number on it. Id swapping is not worth building.

## 4. The real axis: `type` and `flag` inside the loaded module

`EM_LIST` (`include/global.h:67-81`) has three separate appearance/behaviour fields:

- `id` (0x01) — which enemy module to load. Costs a slot. **Expensive, mostly invisible.**
- `type` (0x02) — `cModel::type`, the model variant **inside that module**. Costs nothing.
- `flag` (0x04) — weapon/clothing bits, incl. bit 28 `Weapon ChainSaw`. Costs nothing.

### A chainsaw Ganado needs no extra module

This is the finding that answers the original question directly:

- `src/em10/em10_set.cpp:268-273` — `Em10WeaponSet` loads the saw models `mot[67]`/`mot[68]` for
  **every** model type, not only the chainsaw types:

  ```c
  if (em->type == 4) {
      w->mot[67] = ARC(EM10_BIN_WEAPON_SET_104);   // the real saw model
  } else {
      w->mot[67] = ARC(EM10_BIN_WEAPON_SET_106);   // the other saw model
  }
  w->mot[68] = ARC(EM10_TPL_WEAPON_SET_105);
  ```

- `src/em10/em10.cpp:18082` — the saw is equipped when those models exist **and** flag bit 28 is set:

  ```c
  if (w->mot[67] && w->mot[68] && (em->flag & 0x10000000) && w->pWeapon == 0) {
      w->Wep_type = 4;
  }
  ```

So setting bit 28 on an ordinary village Ganado gives it chainsaw behaviour with the models its room
already loaded. `type` 3/4 additionally give the chainsaw *appearance* (the sack mask), and `type` 4
selects the real saw model. `em10.cpp:27516` also switches the Mercenaries point table to `MT_CHAIN_SAW`.

The village modules that carry the saw: **0x10, 0x12, 0x13, 0x15, 0x16, 0x17** (em10/12/13/15/16/17).

### Model types each module supports

Read from the `case N:` labels of each module's `EmNNSet` switch
(`.harness/analysis/enemy_type_scope.py`):

| id | name | module | model types |
|---|---|---|---|
| 10 | Ganado 1 | em10 | 0, 1, 3, 4 |
| 11 | Ganado 2 | em11 | 7, 8, 9 |
| 12 | Ganado 1-c | em12 | 0, 1, 3, 4 |
| 13 | Ganado 1+w | em13 | 0, 1, 3, 4, 6 |
| 14 | Ganado 2+w | em14 | 6, 7, 8, 9 |
| 15 | Ganado 1+c | em15 | 0, 3, 4, 11 |
| 16 | Ganado 1+c2 | em16 | 3, 4, 11, 12 |
| 17 | Ganado 1-c2 | em17 | 0, 1, 3, 12 |
| 19 | Ganado 2+A | em19 | 7, 8, 9 |
| 1A | Ganado 2+R | em1a | 7, 8, 9 |
| 1B | Ganado 2+cm | em1b | 7, 8, 10 |
| 1C | Ganado 2+cm2 | em1c | 7, 9, 10, 13 |
| 1D | Ganado 3+GM | em1d | 2, 14–21 |
| 1E | Ganado 3+W | em1e | 6, 14, 15, 23, 25 |
| 1F | Ganado 3 | em1f | 14, 15, 16, 24 |

An unknown type is forced to the module default (`em15_set.cpp:46-51`), so an out-of-range type is a
visual fallback, not a crash — but the table above should still be treated as the allowed set.

`type` also drives scale (`em10.cpp:3287-3309`): types 0xA/0xD/2 → 1.25×, 0x16 → 1.3×, 0x18 → 1.2×,
6 → 1.05×. So type changes can make a Ganado visibly bigger.

### Scope

Of **257** live Ganado entries in the story lists:

- **257** sit in a module with 2+ model types (every Ganado module qualifies)
- **131** sit in a saw-capable village module
- only **3** already carry a chainsaw on the retail disc

Cross-referenced with the existing protection table `port/whole/runtime/enemy_random_rooms.inc`
(108 rooms: 45 "open", 52 "places only", 11 "untouched"):

- 89 Ganado entries in **open** rooms (script counts nothing)
- 168 in **places-only** rooms (script counts or names entries)
- **54 saw-capable entries across 6 open rooms**: r103, r10a, r111, r113, r11a, r225

## 5. Safety: scripts do not read `type` or `flag`

This is what makes the type axis safe where the id axis is not.

Searching every room script (`src/st1`, `src/st2`, `src/st3`):

- `Em_list[N].type` — **0 occurrences**
- `Em_list[N].flag` — **0 occurrences**

The only two `->type` comparisons in room scripts are on **live objects**, not list records:
`st1/r119.cpp:348` walks `ObjMgr` active work, and `st2/r22c.cpp:1415` reads a freshly created
`cEmMark` (id 0x3E). Neither is an ESL entry.

By contrast, scripts *do* depend on `id`:
`SceCountEmAlive(0x10, 0x20)` counts by kind, `SceDestroyEm` sweeps by kind, r101 counts Ganados for the
bell, r224 waits for El Gigante, r327 refills when 0x1D are missing. That is exactly why id swaps are
dangerous and type swaps are not: **the count stays the same, the kind stays the same, only the look and
the weapon change.**

`set` (0x03) must still be left alone — it names a scripted entrance ("R11C 1F IN", "R300 Take Ashley").

### Remaining risks to respect

1. **Cutscene memory borrowing.** `event.cpp:2327-2351` (`EventMgr::EvtReadSub`) loads an enemy module
   and `MemorySwap`s the event into it. If the expected kind is absent the scene faults; patch 0157 fixes
   such a case in r203. A type-only shuffle never removes a kind, so it does not trigger this — but any
   future id-level work must.
2. **Scripts that preload by id.** Several rooms call `EmReadSearch` with a hardcoded kind
   (`r100.cpp:132` → 0x12, `r11e.cpp:97` → 0x2B, `r320.cpp:449` → 0x1D). A few derive it from the list:
   `r20f.cpp:154-158` `GetEmIdFromList(0xED)`, also r20b, r20c, r308, r329. Again: safe under type-only.
3. **Weapon-carrying animations.** Bit 28 changes the attack set. A Ganado whose `set` puts it on a
   ladder, a roof or a window may not have saw animations for that state. Needs an on-phone test.
4. **Difficulty.** Chainsaw Ganados are much stronger. This is a difficulty knob, not a bug, but it
   should be a separate, clearly labelled option and probably rate-limited (e.g. at most 1 per room).

## 6. Recommended design

Build an **offline "enemy flavour" pass** over the ESL lists, in the same style as the door/item deal:
read the disc records, rewrite them, re-read and validate before play.

Rules, in order of confidence:

1. **Never change `id`.** Keeps the module budget, the script counts and the cutscenes intact.
2. **Never change `set`, `room`, `pos`, `rot`, `hp`, `be_flag`.** Positions are upstream's feature and
   already solved; this pass is about appearance and weapons only.
3. **Shuffle `type` within the module's supported set** (table in §4), per room, from the seed. Prefer
   types the room does not already use, so the change is visible.
4. **Optionally set flag bit 28** on a limited number of saw-capable village Ganados in *open* rooms
   (the 6 rooms / 54 entries measured above), at most 1 per room to start.
5. Keep the existing protection table as the room gate: skip "untouched" rooms entirely; allow type
   changes in "places only" rooms (the count is unchanged, so counting scripts stay correct); allow the
   saw only in "open" rooms until playtested.
6. Validate by re-reading: every entry's `id`, `set`, `room` and count must be byte-identical to the
   disc; only `type` and the weapon bits may differ, and every `type` must be in its module's set.

That validation is the same "prove it before you play" property the door randomizer has, and it is
achievable here because the invariant is simple: **same enemies, same number, same places — different
faces and weapons.**

## 7. What was NOT verified

- No build, no APK, no on-phone test was run. All findings are from reading code and disc data.
- Whether a saw-carrying Ganado animates correctly in every `set` state (ladder/roof/window) — needs a
  playtest.
- Which chainsaw model type is the "super" Salvador: `em15_set.cpp` documents types 3 and 4 both as
  chainsaw, with type 4 getting the real saw model. No source in this repo labels a "super" variant.
- The exact `FileTbl` index → `emXX.drs` filename mapping was inferred from the module directories, not
  quoted from `FileTbl`.
- Behaviour in Assignment Ada / Mercenaries (`omake*.esl`) was surveyed but not designed for; the
  recommendation is story-only, matching the item randomizer.

## Reproduce

```
python3 .harness/analysis/enemy_kinds_survey.py   # kinds per room -> enemy_kinds_survey.json
python3 .harness/analysis/enemy_type_scope.py     # model types + saw scope -> enemy_type_scope.json
```

Both are read-only and need the two disc images in the workspace root.
