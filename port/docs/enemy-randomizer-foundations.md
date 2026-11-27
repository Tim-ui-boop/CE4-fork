# Enemy randomizer — foundations

Branch `enemy-randomizer`, 24 Nov 2026. This is the single consolidated reference for the
offline enemy randomizer work. It merges the two on-phone room tests (r100 and r101) and the
measurements behind them. The older `r100-enemy-test-findings.md` and
`r101-enemy-test-findings.md` remain as detailed playtest logs.

**Status:** two fixed room tests work on the phone. This is **not** a general randomizer yet.
The test buttons and the loader fixes live on `enemy-randomizer`; master carries the earlier
research documents and no enemy-randomizer code.

---

## 1. The design in one paragraph

The enemy lists are flat files on the disc, so the launcher can deal them **before** play into
private copies and the core can serve those copies instead of the disc's. No runtime hook is
needed. Every written list is re-read and compared before it is activated, so a bad deal never
replaces a playable one. Discs and saves are never written; "Turn off" deletes the copies.

## 2. The data we deal with

`checkEmListNo()` (`src/game/stage.cpp`) picks the enemy list by stage, room and story flags.
Rooms r100 and r101 both use `etc/emleon00.esl`. The file holds 255 records of 32 bytes
(`0x1FE0`); the drive zero-fills the 256th, so the game's table is `0x2000`. One `EM_LIST`
record (`include/global.h:67`):

```
0x00 be_flag   bit0 alive at entry, bit1 already created, bit2/3 toggle
0x01 id        enemy kind (0 = empty)
0x02 type      model variant inside that kind's module
0x03 set       behaviour preset (a named motion/behaviour table)
0x04 flag      32 weapon / clothing bits
0x08 hp
0x0B Character movement/AI role
0x0C pos[3]    x10 to world units
0x12 rot[3]    x pi/16384
0x18 room      stage<<8 | room
0x1A Guard_r   x1000
```

`EmSetFromList()` (`src/game/em_set.cpp:137`) creates every record with bit0 set when the room
loads; `EmSetFromList2(no, flag)` creates one on a script call. Both copy type/set/flag/hp/pos/
rot/Character/Guard_r into the new `cEm`, mark bit1, and remember death in `Em_flg`, so killed
enemies stay dead when the room is re-entered.

## 3. The two budgets

### 3.1 Enemy modules — four per room visit

`EmReadModule[4]` (`src/game/read.cpp`) has four slots. `pullEmModule()` returns a free slot or
NULL — no eviction. `EmReadInit()` on every door transition (`game.cpp` `gameDoordemo`) clears
them, so the budget is per room visit. A fifth kind is simply not loaded:
`EmSetFromList2` logs `Em set failed, Id = ...` and the enemy does not appear.

### 3.2 Sound — space *and* the synth

Enemy sound banks live in ARAM below the music/stream area at `0x700000`; enemy ARAM starts at
`0x1F4100`. There are six enemy sound-block slots (blk 8..13, `SndBlkInit`, `snd.cpp:2484`) and
eight sequence works (`SND_SEQ_MAX`, `snd_drv.h:20`). Two **different** failures were observed:

| failure | symptom | fix |
|---|---|---|
| banks overrun `0x700000` (r100: em1d + em36) | SIGSEGV in `__SYNSetupSample`, music read corrupted samples | mark those DRS banks empty |
| synth voice lookup (r101: em1b) | SIGSEGV in `__SYNGetWavetableData` once castle voices played, **with space to spare** | mark those DRS banks empty |

The DRS format (`tools/drs.py`) has exactly the needed marker: an outer record of **type 4** is
the appended sound bank; **type `0xFFFFFFFE`, size 0, offset = file size** means "no sound bank".
`runtime/dvd.cpp` edits that record in the raw big-endian 0x400 part table (records start at
0x20) when the launcher's marker is present. The enemy still spawns, silently. Music and all
other sound stay on.

**Answer to the music hypothesis:** muting the music would not have fixed r101. The three banks
loaded at crash ended at `0x59FDA0`, about 1.4 MB below `0x700000`; all four would end at
`0x6B1AA0`, still below it. Logcat showed no `Snd_seq_work is full.` and no illegal-SE messages.
The fault was a bad instrument/region pointer in the bank's own data, not a capacity problem.

## 4. Changing an enemy — three independent knobs

| knob | field | effect | safe use |
|---|---|---|---|
| kind | `id` | new creature, module and voice bank | only with a module slot and a sound decision |
| model | `type` | variant inside the same module | no new module; e.g. island `0x1D`: 2 = JJ gatling man, 14..21 = island soldiers |
| weapon/clothes | `flag` bits | `1<<15` crossbow, `1<<11` club, `1<<28` chainsaw, `1<<31` shield, ... | clear other weapon bits; verify the module's `mot[]` has that weapon |

### The `set` rule — the biggest trap

`set` names a behaviour preset, and **its animations live in the destination module's own
archive**. Keeping the old `set` when changing `id` can point at a motion the new module does not
have. r101's Ganados carried presets 0x05–0x0E (*Bucket A*, *Suki A*, *Cart A*, ...) whose motions
are in the em15 event data that r101 swaps in at runtime. The castle module em1b has no such
motions, so `MotionSetCore` was handed a null pointer and `HermiteInterpolation` crashed.

Stack (r101 first playtest):

```
HermiteInterpolation(...)+128 -> MotionSetCore(...)+1520 -> libre4_em1b.so
-> cEm10::move()+724 -> EmSetFromList()+844 -> ScenarioRoomInit -> gameRoomInit
```

Fix: reset `set` to 0 for any enemy moved to a different family. This is the lever / cart /
bucket / puzzle Ganado case: those presets are room-specific event data, not generic animations.
A general randomizer needs a per-module whitelist of safe `set` values or a reset-to-0 rule.

### Shield caveat

`em10ShieldSet()` only creates a physical shield when `w->Ganado == 0`. The island module em1d
sets `Ganado = 2`, so island shield guys cannot be made with flags alone. The club (`Weapon Club`,
bit 11) was used as the large-hammer look. A real island shield needs a game-code change.

## 5. Room case study — r100 (works)

The village approach. Its 25 list records: 12 Ganados `0x12`, 5 crows `0x23`, 1 dog `0x21`,
7 traps `0x2a`. Only the crows are alive at entry; the rest are spawned by the room script. The
officer-killer Ganado is **not** in the list — `R100Init()` builds it by hand with `EmSetEvent()`.

The test changes the 12 villager rows to island soldiers (types 14..21; 4 crossbows, 4 clubs,
4 plain), the 5 crows to 2 JJ (`0x1D` type 2) + 3 regenerators (`0x36`), and the 7 traps to
island soldiers so the trap module is not needed. Four module kinds: `0x12` (hardcoded),
`0x1D`, `0x36`, `0x21`. Crow HP 10 → 1000 so the replacements survive more than one hit.
Their sound banks are skipped (em1d + em36).

**Crows** (`em23`, `src/em23/em23.cpp`): body model + folded/spread wings + peck/takeoff/fly/land
motions. No lock-on, no Ashley help. They wait until Leon is near, fly, circle and land again.
Most guns one-shot them; fire kills them; mines do nothing. Every crow death drops handgun ammo
(`RandomItemCk(0x23)` → item `0x78`, usually 20–40 rounds). Because they are the only enemies
alive at r100 entry, they are the cheapest visible proof that a dealt list is being served —
but an aggressive replacement attacks during the opening car scene.

## 6. Room case study — r101 (works)

The village square / bell fight. Its 46 list records: 43 Ganados `0x15`, 1 cow `0x26`,
2 chickens `0x28`. r101 loads em15 and em26 itself for event data (`R101Init`,
`r101.cpp:186,198,200`; `SearchEmModule(0x15)` + `MemorySwap` at `:466,633`), so those two module
slots are unavailable to the deal. The bell counts `SceCountEmAlive(0x10, 0x20)`.

The test converts all 46 rows into castle enemies: 38 zealots (`0x1B` type 7/8), 6 Garradors
(`0x1B` **type 10**), 2 Verdugos (`0x2C`, em2c, on dormant rows so the bell's initial count is
unchanged). Four module kinds: `0x15`, `0x26`, `0x1B`, `0x2C`. `set` reset to 0. Weapon bits
cleared, HP 1200. Sound banks skipped for em1b, em2c and em26.

Master's docs supplied the identities: Garrador is a zealot variant (ids 0x1B/0x1C, type 10);
Verdugo is em2c / id 0x2C (the r221 boss). A `SND: blk 8, ID 0x26` line is r101 loading em26 for
its events, **not** a surviving cow — the cow record was converted too; its bank is just silent.

## 7. Loader bugs found (both fixed)

1. **The sidecar was never served.** `runtime/dvd.cpp` searched for the **first** `"/files/"` in
   the absolute path `<app>/files/game/disc1/files/etc/emleon00.esl`, which is the app's own
   folder. The sidecar received `game/disc1/files/etc/emleon00.esl` and silently fell through to
   the disc. Fix: use the **last** `"/files/"`. Proof in logcat:
   `randomizer: etc/emleon00.esl: the launcher's dealt enemy list (8160 bytes)`.
2. **Read length.** The eight story lists are `0x1FE0` bytes on disc, not `0x2000`
   (commit `d3498ace`); both lengths are accepted by `re4_enemy_sidecar_read()`.

## 8. Verified identities and variants

| id | name / role | notes |
|---|---|---|
| 0x12 | village Ganado (Ganado 1-c) | r100's list villagers; types 0/1/3/4 (4 = chainsaw) |
| 0x15 | Ganado 1+c | r101's original villagers |
| 0x1B / 0x1C | castle zealots / Garrador | type 7/8/9/10/13; type 10 = Garrador |
| 0x1D | island soldier / JJ | type 2 = JJ gatling man; 14..21 = island soldiers |
| 0x21 / 0x2A | dog / bear trap | separate modules; often scripted |
| 0x23 | crow | handgun-ammo drops; alive at r100 entry |
| 0x26 / 0x28 | cow / chicken | small sound banks; r101 loads em26 for events |
| 0x2C | Verdugo | em2c, r221 boss, outside the Ganado id range |
| 0x36 | regenerator | its module overrides HP/flags after spawn |

## 9. Test history and verification

| commit | CI run | result |
|---|---|---|
| `c021e721` | 45393290807 | r100 button; playtest showed vanilla enemies (path bug) |
| `8cb01bcd` | 45397322883 | path fixed; r100 crashed in `__SYNSetupSample` |
| `634038f0` | 45403560666 | r100 **works** (sound banks skipped) |
| `e98824d4` | 45410594830 | r101 crashed in em1b `MotionSetCore` |
| `63c45cba` | 45414754887 | r101 crashed in `__SYNGetWavetableData` |
| `0b33588a` | 45418178825 | r101 **works** (set reset + targeted sound skip) |
| `ec1fa393` | — | documentation of both tests |

Offline checks (from the workspace root):

```
python3 .harness/analysis/r100_test_rehearsal.py
python3 .harness/analysis/r101_test_rehearsal.py
python3 .harness/analysis/r100_sound_skip_rehearsal.py
python3 .harness/analysis/check_enemy_java.py
```

The r101 rehearsal reads the real DRS part tables with `tools/drs.py` and checks the ARAM sum.

## 10. What is still open

1. Re-test the normal **Deal enemies** button now that the sidecar path bug is fixed — it has
   never been observed working on a phone.
2. A **per-room planner** before writing a deal: distinct module ids ≤ 4, safe `set` values per
   module, and a sound decision (space *and* synth risk) for each introduced kind.
3. Generalise the "silent enemies" choice and report it to the player.
4. Handle scripted enemies per room: the r100 officer-killer is hand-built in `r100.cpp`; other
   rooms have their own casts, counts and chapter handovers.
5. Real island shields need a game-code change.
6. No room beyond r100/r101 has been playtested; no campaign-wide verdict exists.

## 11. File index

| file | role |
|---|---|
| `port/android-decomp/.../EnemyShuffle.java` | offline deals, verifiers, no-sound markers |
| `port/android-decomp/.../MainActivity.java` | launcher buttons |
| `port/whole/runtime/enemy_sidecar.cpp` | serves dealt lists; validates ids/rooms |
| `port/whole/runtime/dvd.cpp` | last-`/files/` fix + DRS empty-sound marker |
| `port/docs/offline-enemy-randomizer.md` | original offline design |
| `port/docs/enemy-type-randomizer-research.md` | id/type/measurements on master |
| `port/docs/pc-randomizer-decompile-findings.md` | PC tool rules and enemy identities |
| `tools/drs.py` | DRS container format, including the empty sound-bank slot |

## 12. The combined swap engine (`feature/enemy-swap-engine`)

The two fixed test buttons are gone. One **Deal test rooms** button deals r100, r101, r103 and r104
from a seed and 19 toggles. It replaces records **one-for-one** — a Verdugo becomes a Krauser by
changing module em2c to em39, not by adding a fifth kind — keeps rows that `SceCountEmAlive(0x10,
0x20)` needs inside that range, clears `set` for bosses, and writes every introduced module to
`randomizer/enemies/no-sound.txt`. `runtime/dvd.cpp` reads that list instead of the old
`r100-no-sound` / `r101-no-sound` markers.

Defaults reproduce the two proven room tests exactly. Added toggles: Regenerator, Iron Maiden
(the spiky Regenerator), Krauser, Mutated Krauser, El Gigante, Armadura, Try shared animations
(experimental, keeps `set` on basic-family swaps) and Allow animation reset.

**Easy revert:** `git checkout enemy-randomizer`. The untouched pre-change state is also pushed to
GitHub as `backup/enemy-randomizer-pre-swap` at commit `5019393f`.

Verification before commit: `python3 .harness/analysis/run_swap_deal.py` runs the real Java engine
on the real disc over four seeds and five toggle sets — all offline checks pass. The NDK build in
GitHub Actions is the final check.

**Module-slot rule (learned from the first phone test):** r103 also reserves em12 for its corpse
models, so it has room for only one other kind. A massive boss (El Gigante) is placed
on the *least numerous* safe kind — one dog or one crow row — and capped at **one per room**;
Krauser, Verdugo and Armadura may fill a whole group. Turning on **Mix all enemies** allows the
wild whole-group swaps (six chickens become Armaduras). The old in-game *Random enemies* cheat must
be **off** while an offline deal is active: on the first test it loaded four extra kinds, filled all
slots and made three dealt enemies fail with `Em set failed, Id = 1d`.

All 19 toggles are now wired except Keep Luis safe, which has no effect in these four rooms (there
is no Luis cabin among them).

## 13. Two-pool model (`feature/enemy-pools`)

The priority chains are gone. There are two pools, and for each room the seed picks **one member
from each** among the toggles that are on and whose module still fits:

- **Elites:** JJ, Dr. Salvador, Bella Sisters (the female Ganado look of em16, types 11/12 — r11D
  really has five of them), Regenerator / Iron Maiden (types 0/1 and 2/3 of em36), Armadura
  (types 0/1 and 2/3 of em3c, now two separate toggles) and Garrador (type 10).
- **Bosses:** Krauser (0x39, human and mutated are separate toggles), El Gigante (0x2B),
  Super Salvador (0x20 type 22) and Verdugo (0x2C). El Gigante is **massive**: at most one per room.

A pick reuses an already-loaded module where it can; otherwise it is only allowed if a slot is free.
The report says `boss none` when the four-module limit leaves no room. Each entry has a chance value
reserved for a future per-member 1-in-N mode, like the PC tool; the selection function is the only
place that would need to change.

**When no pool member wins the 1-in-N roll for a row**, the fallback picks any enabled member as a
base (not just the three basic families), with the same module/budget and heavy-model guards as a
normal pick. A dormant record the room never spawns may only reuse a module already loaded. If no
member fits at all, the row keeps its exact vanilla bytes — it is never repointed to another id.
This matters with VILLAGE off: the old fallback only looked at village/castle/island, so a farm row
had no base once those slots filled, and every unmatched row was rewritten to the first reserved
module (the vanilla villager). That made all seeds look the same and only em15 loaded. The offline
suite now deals with real default ratios and VILLAGE off and refuses a farm dominated by villagers.

**Script-spawn rows and the four-module budget.** The dealer used to reserve each script-spawn
row's *vanilla* id before dealing. In r100 that filled all four module slots with `0x12` (villagers),
`0x2a` (traps) and `0x21` (dog), so no pick could fit and the farm — and the crows, cows, chickens
and traps in other rooms — stayed on their disc ids. Those reservations are gone: a dealt row's NEW
id is budgeted by `fits()` like any other. Rows the script spawns **and casts to `cEmGanado`**
(still a Ganado-family id) skip the elite/boss roll and keep a basic family, so the script's
`ckFindPL` / `setNoSuspend` / `setEvtMotion` calls remain valid; animals and traps may become any id.
When a room genuinely needs more than four modules after the deal, only that room is restored to
the disc — the old code restored *every* dealt room in the list, wiping good rooms along with it.

**One family, one id per room.** A pool member with several ids (Dr. Salvador has five) used to pick
a different id for every row, and a room could end up with both zealots and soldiers. The dealer now
chooses one id per member and one basic family per room, so the remaining slot can hold an elite or
boss. Rooms with script-driven Ganados let elites claim at most one new module, leaving a slot for
the basic family. r101's bell waves (rows `0x22`–`0x3a`, except the risky `0x3c`–`0x46` event-motion
rows) moved from DRIVEN to SPAWN so they get dealt to a basic Ganado family; their `set` is reset to
0 because the old presets live in em15's event archive.

**Blank-room fix (patch 0258).** `readEm()` sets `Disp_flg` to all-ones while loading a module;
its no-free-slot failure path used to return without restoring the flag, hiding the whole drawn
world while audio and the menu kept working. The patch restores the saved flags on that path.
See `enemy-room-architecture-research.md` for the full room/enemy reference.

Two new toggles — Bella Sisters and Verdugo — join the grid. The launcher also has a
**Preview seed rolls** button: it runs the exact same planning step in memory and shows the report
in a dialog, without writing the sidecar or touching app data. The written `enemies.txt` and the
preview are byte-for-byte the same text (asserted by the rehearsal).

**Revert:** `git checkout feature/enemy-swap-engine` restores the previous engine; the untouched
original is still `backup/enemy-randomizer-pre-swap`.

Verification: `python3 .harness/analysis/run_swap_deal.py` — three seeds, six toggle sets, checks
determinism, byte-level protection, one entry per pool per room, the four-module limit, the massive
cap and preview/report equality. All offline checks pass.

## 14. In-game cheats removed (`feature/no-ingame-cheats`)

The old in-game **Random enemies**, **More enemies** and **Randomize enemy types** switches are gone
from the menu, along with their native modules (`enemy_random.cpp`, `enemy_types.cpp`), their patches
(0231, 0244, 0255) and the IPC/JNI/preference plumbing. They could run at the same time as an offline
deal and filled the four module slots, causing missing enemies and a blank room on the first phone
test. `More enemies` went too because its patch depended on 0231.

The launcher deal is now the only enemy randomizer. On the farm r103 the six chickens and six cows
each change kind from a pool pick, the five Ganados become island soldiers, and the single dog
becomes a Ganado so em21 is free — em12 stays reserved for the room's corpses. In r104 the twelve
plain Ganado rows are split between the two pool picks. Preview and report describe the same plan.

**Revert:** `git checkout feature/enemy-pools` restores the pool build with the cheats; older states
are `feature/enemy-swap-engine` and `backup/enemy-randomizer-pre-swap`.

The first phone test of this build exposed two more issues, both fixed here: r100 and r101 still
showed only the old test's island soldiers and zealots because the pool picks touched just two rows —
now the boss takes two crow rows in r100 / the two dormant rows in r101 and the elite takes the rest.
And when several enemies were enabled the same one could be picked in every room — pool selection is
now ordered by seed and advanced per room, so the enabled enemies spread across the four rooms.

## 15. Full pool + ratios (`feature/pool-ratios`)

The fixed per-room conversions are gone. Every ordinary record in r100/r101/r103/r104 is dealt from
one pool: three basic families (village Ganado, castle zealot, island soldier) plus seven elites and
five bosses. A record rolls the seed for each enabled enemy; a 1-in-N ratio decides whether that
enemy takes the record (PC defaults: Salvador 1:12, JJ 1:2, Garrador 1:8, Super Salvador 1:8).
The launcher shows a checkbox and a **1 in N** stepper for each of the fifteen pool entries, with the
ten remaining switches below as simple modifiers. (The next section replaces this with sixteen
entries and nine modifiers.) Ratios are stored in their own `enemyRatios`
preferences. Rows with dynamite or named event motions are left alone, counted rows keep ids inside
0x10..0x20, one massive boss per room, four modules per room, and the preview uses the same plan.

**Revert:** `git checkout feature/no-ingame-cheats` restores the previous build.

## 16. Phone feedback on the pool build — pending work (25 Nov 2026)

The player confirmed the bosses **do** appear once their ratios are raised, but remain rarer than the
ratio alone suggests. That is expected: a boss with an out-of-range id only fits rows the counting
scripts do not need, and it also competes for the four module slots. No code change is needed for
that observation; the earlier suspicion that the toggles were broken was withdrawn.

Three items are still open and were explicitly confirmed by the player:

1. **Variants must be separate toggles.** Armadura white/grey (`0x3C` types 0/1) and black (types 2/3)
   should be independent pool entries so both can appear in one run. Krauser human (`0x39` types 0/1)
   and mutated (type 2) should be split the same way. The global **Mutated** modifier is then
   redundant and should be removed.
2. **Remove Mendez.** The `0x34` model is not the boss model, so the entry does nothing and should be
   deleted from the pool, the launcher, the strings and this document.
3. **Basic Ganados have lost their weapons.** The pool writer clears `WEAPON_MASK` and the basic
   families carry no weapon flag, so sickles, dynamite and island stun rods no longer appear. The
   fix must preserve the disc's weapon bits for basic families (and let Dynamite add bombs), checked
   against the flag names in `src/t_emlist/t_emlist.cpp`.

All three were implemented on `feature/enemy-pool-fixes`; see §17 below.

## 17. Variant toggles, no Mendez, weapons kept (`feature/enemy-pool-fixes`)

The three items from §16 are now code:

1. **Variants are their own pool entries.** Armadura white/grey (`0x3C` types 0/1) and Armadura
   black (types 2/3) are two checkboxes with two ratios, so both can be dealt in one run; the same
   for Krauser human (`0x39` types 0/1) and Krauser mutated (type 2). The global **Mutated**
   modifier is gone and the modifier grid now has nine switches. The new bits are `1 << 25` and
   `1 << 26` so old saved settings cannot silently switch a new variant on. The new members were
   appended to the pool; deleting Mendez shifts the saved ratio for Verdugo by one slot, so the
   Verdugo and new-variant steppers may need re-setting once.
2. **Mendez is gone** from the pool, launcher, strings and documentation. `0x34` was never a model
   the game could show, so the entry did nothing. El Gigante is now the only massive boss, capped
   at one per room.
3. **Basic Ganados keep their disc weapons.** The writer used to clear every weapon bit, which made
   sickles, dynamite rows and island stun rods vanish. Rows still dealt to an ordinary family now
   keep that family's weapon bits from the disc; rows dealt to a special enemy keep only that
   enemy's own weapon flag. The **Dynamite** switch now adds a bomb flag to a seeded one in six of
   the ordinary records (the disc's own dynamite rows are script rows and are never rewritten).
   Flag names were checked against `src/t_emlist/t_emlist.cpp`: bit 17 `Weapon Bomb`, bit 27
   `Weapon Sickle` / `Weapon StunRod`, bit 28 `Weapon ChainSaw`.

Verification: `python3 .harness/analysis/check_enemy_java.py` (javac clean) and
`python3 .harness/analysis/run_swap_deal.py 1 42 777` (all checks pass, including new assertions
that each variant only writes its own types, both forms can appear together, Mendez is absent,
sickle flags survive on ordinary rows and Dynamite adds bombs). GitHub Actions run
**45572518365** on `ci/enemy-pool-fixes` is green (commit `6c93f0fd`, artifact
`CubeEvil4-core-debug-apk`, 24.6 MB). The APK on a phone stays at commit `b9427e5e` until the
player installs a new one.
