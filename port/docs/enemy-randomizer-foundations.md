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
models, so it has room for only one other kind. A massive boss (El Gigante, later Mendez) is placed
on the *least numerous* safe kind — one dog or one crow row — and capped at **one per room**;
Krauser, Verdugo and Armadura may fill a whole group. Turning on **Mix all enemies** allows the
wild whole-group swaps (six chickens become Armaduras). The old in-game *Random enemies* cheat must
be **off** while an offline deal is active: on the first test it loaded four extra kinds, filled all
slots and made three dealt enemies fail with `Em set failed, Id = 1d`.

All 19 toggles are now wired except Keep Luis safe, which has no effect in these four rooms (there
is no Luis cabin among them).
