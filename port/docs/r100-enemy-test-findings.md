# r100 enemy test — findings

Branch `enemy-randomizer`, 24 Nov 2026. This is the write-up of the first successful on-phone
test that changed real enemies in a room. It is the foundation for a general enemy randomizer:
everything below was measured from the retail G4BE08 disc, the decompilation, the GitHub build
and the phone's logcat.

**Status:** the fixed test button works. The user pressed **Test r100 randomizer**, entered r100,
and confirmed the change. This is **one room, one fixed deal**, not a general randomizer yet.

---

## 1. Short version

- r100's enemies come from a flat table on the disc (`etc/emleon00.esl`), not from the room
  model. A private copy of that table can be written before play and served to the core.
- The test changes the 12 village Ganados into island soldiers (crossbows, big clubs, plain),
  the 5 crows into 2 JJ gatling men + 3 regenerators, and the 7 bear traps into island soldiers.
  The dog and the hardcoded officer-killer keep their kinds.
- Two independent budgets decided almost every failure:
  1. **four enemy *module* slots per room visit** (rel kinds, e.g. Ganado / crow / dog),
  2. **enemy *sound banks* in ARAM below 0x700000** (voice/SE data, not the same thing).
- A third bug hid all of it: the launcher's sidecar was written correctly but the core looked in
  the wrong part of the absolute path, so every dealt list silently fell through to the disc.

| commit | CI run | result |
|---|---|---|
| `c021e721` | 45393290807 | test button written; playtest showed vanilla enemies — sidecar was never served |
| `8cb01bcd` | 45397322883 | sidecar path fixed; playtest spawned the new enemies but crashed on entering r100 |
| `634038f0` | 45403560666 | sound banks skipped for the test; **playtest works** |

The same branch also contains the earlier sidecar read-length fix (`d3498ace`, 0x1FE0 for the
eight story lists) and the seed/options sync, both inherited from this line of work.

---

## 2. How r100 actually gets its enemies

### 2.1 The list file

`checkEmListNo()` (`src/game/stage.cpp`) maps stage 1 / room 0x100 to enemy list 3,
`etc/emleon00.esl`. The file is 255 records of 32 bytes (0x1FE0); the drive zero-fills the
256th entry, so the game's table is 0x2000 bytes. One record (`EM_LIST`, `include/global.h:67`):

```
byte 0x00  be_flag   bit0 alive at entry, bit1 already created, bit2/3 set toggle
byte 0x01  id        enemy kind (0 = empty)
byte 0x02  type      model variant inside that kind's module
byte 0x03  set       named behaviour preset (shared em10 set table)
bytes 0x04 flag      weapon / clothing bits (32 bits)
bytes 0x08 hp
byte 0x0B Character  movement/AI role (Chase, Keep, Rush, ...)
bytes 0x0C pos[3]    x10 to world units
bytes 0x12 rot[3]    x pi/16384
bytes 0x18 room      stage<<8 | room
bytes 0x1A Guard_r   x1000
```

`EmSetFromList()` (`src/game/em_set.cpp:137`) creates every record whose bit0 is set at room
load; `EmSetFromList2(no, flag)` (`em_set.cpp:191`) creates one on a script call. Both copy
type/set/flag/hp/pos/rot/Character/Guard_r into the new `cEm`, mark bit1, remember the death bit
in `Em_flg`, and call `move()` once. A killed entry stays dead on re-entry.

### 2.2 The 25 r100 records

Decoded from the retail `emleon00.esl`:

| kind | count | entries | when it appears |
|---|---|---|---|
| `0x12` village Ganado | 12 | 0, 3, 4, 5, 6, 7, 8, 0x12, 0x13, 0x25, 0x26, 0x27 | scripted; only entry 0x25 is the house Ganado |
| `0x23` crow | 5 | 15, 16, 17, 32, 33 | **the only ones with bit0 set** — spawn on entry |
| `0x21` dog | 1 | 9 | `r100_trap_set()` |
| `0x2a` bear trap | 7 | 10, 11, 12, 13, 14, 29, 0x7A | `r100_trap_set()` |

The officer-killer Ganado is **not in the list**. `R100Init()` (`src/st1/r100.cpp:168`) builds a
local `EM_LIST` by hand (`id=0x12, type=0, set=0x13, flag=0x21000020, hp=500`) and creates it with
`EmSetEvent()`. When it dies, `r100_Sce_zombi_dead()` calls `r100_em_set()` for entries
3, 4, 5, 0x12, 0x13, 0x26, 0x27 — the first three are the ones the locked door, the windows and
the battle-music task watch through `ckFindPL()`. Later `r100_trap_set()` creates the remaining
entries and gives entries 6, 7, 8 their fire event motions.

**Rule:** those scripted actors are cast to `cEmGanado` and poked through Ganado virtuals
(`ckFindPL`, `setNoSuspend`, `setEvtMotion`). Change their `type`/`flag`, never their `id` to a
non-Ganado family. Entries 0x26 and 0x27 are Ganados despite looking like "other kinds" in the
entry number; the number is just a list index.

---

## 3. What the test button writes

`EnemyShuffle.dealR100Test(File dataRoot, File discFiles)` in
`port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyShuffle.java`:

- reads only `game/disc1/files/etc/emleon00.esl`, copies it to a 0x2000 buffer;
- touches only records whose `room == 0x0100`;
- 12 `0x12` records → island `0x1D`, type `14 + (n%8)`; 4 get crossbow flag `1<<15`,
  4 get club flag `1<<11`, 4 stay plain;
- the 5 live crow records → first 2 become JJ (`0x1D`, type 2), other 3 become regenerator
  (`0x36`, type 0), HP raised 10 → 1000 so they are not one-shot;
- the 7 trap records → island `0x1D`, so the trap module is not needed;
- writes to a staging folder, re-reads and compares byte-for-byte, promotes one file, writes
  the `active` marker, `enemies.txt` and `r100-no-sound`, then logs the exact paths.

`Turn off` deletes the whole `randomizer/enemies` folder. Discs and saves are never written.

The launcher button is in `MainActivity.randomizerSection`, label `launch_rand_test_r100`
("Test r100 randomizer"). It runs the deal off the UI thread and shows the report in the status
label. No seed and no options: the result is repeatable.

### Why the mix fits in four modules

`EmReadModule[4]` (`src/game/read.cpp`) has **four** slots, cleared on every door transition
(`game.cpp` `gameDoordemo`). `pullEmModule()` just returns a free slot — no eviction. r100's final
set uses exactly four kinds:

| id | role | module |
|---|---|---|
| `0x12` | hardcoded officer-killer (list rows are island now) | em12 |
| `0x1D` | island soldiers + the two JJ crows | em1d |
| `0x36` | three regenerator crows | em36 |
| `0x21` | dog | em21 |
| `0x2A` | **none** — every trap row was converted | (em2a deliberately avoided) |

A fifth kind in the room is not loaded; `EmSetFromList2` logs `Em set failed, Id = ...` and the
enemy simply does not appear.

---

## 4. Bugs found and fixed

### 4.1 The sidecar was never served (silent)

`port/whole/runtime/dvd.cpp` `readFile()` receives an absolute path like
`<app>/files/game/disc1/files/etc/emleon00.esl`. It searched for the **first** `"/files/"`,
which belongs to the app, and passed `game/disc1/files/etc/emleon00.esl` to the sidecar. No
enemy-list name matches, so it silently read the disc. No `randomizer:` log line existed.

Fix: walk `strstr` to the **last** `"/files/"` — the disc's own folder. After this, logcat shows
`randomizer: etc/emleon00.esl: the launcher's dealt enemy list (8160 bytes)`. This bug also
disabled the normal "Deal enemies" feature until it was fixed; that feature has **not** been
re-playtested since.

Related earlier fix: the eight story ESL files are read with `length == 0x1FE0`, not `0x2000`.
`re4_enemy_sidecar_read()` accepts both (commit `d3498ace`).

### 4.2 Sound-bank collision in ARAM (crash)

After 4.1, r100 spawned the new enemies and immediately crashed. Logcat:

- `em/em1d.drs` → `Trans ARAM Snddata addr 0x00469260 size 0x001b2b10 ENEMY1`, `SND: blk 9, ID 0x1d`
- `em/em36.drs` → `Trans ARAM Snddata addr 0x0061bd80 size 0x00194d08 ENEMY2`, `SND: blk 10, ID 0x36`
- BGM sequence starts, then SIGSEGV in `__SYNSetupSample` / `Snd_seq_work`.
- Enemy sound blocks are 8..13 (`SndBlkInit`, `src/game/snd.cpp:2484`); the music/stream area is
  fixed at ARAM `0x700000` (`SndStrAramAddr` and `aram_base_addr_bgm`, `snd.cpp:46,151`).

The first bank ends at `0x61bd70`; the second runs to about `0x7B0A88`, overwriting the music
area. The music then reads a corrupted sample pointer. This is a **second budget**, separate from
the four module slots: enemy sound banks must stay below `0x700000`.

Solution (the DRS "no sound bank" slot documented in `tools/drs.py`): a DRS container's outer
record type 4 is the appended sound bank; type `0xFFFFFFFE` with size 0 and offset = file size means
"no sound bank". With the launcher's `r100-no-sound` marker present, `runtime/dvd.cpp` edits the
raw big-endian 0x400 part table of `em1d.drs` / `em36.drs` on read (records start at 0x20, eight
u32 each; the game byte-swaps the table afterwards). The enemies spawn silently; music and all
other sound are unaffected. Logcat now reports
`randomizer: em/em1d.drs: sound bank skipped for the r100 test` and the em36 equivalent.

### 4.3 What the failed attempts ruled out

- It was **not** four-module exhaustion: logcat showed a free slot and successful links of
  em1d and em36.
- It was **not** missing DRS files: both banks were read successfully from the import.
- An earlier trial with other enemy ids failed with `readEmData(): error! em/em10.drs` /
  `em1b.drs` — that one really was a partial phone import; re-import both discs when that appears.

---

## 5. Rules for changing enemy kinds

Three different levers live in one ESL record:

| lever | field | what it does | safe use |
|---|---|---|---|
| kind | `id` | whole creature + module + voice bank | only with a module **and** a sound-bank budget |
| model | `type` | variant inside the same module | no new module; island `0x1D`: 2 = JJ gatling man, 14..21 = island soldiers |
| weapon/clothes | `flag` bits | `1<<15` crossbow, `1<<11` club, `1<<28` chainsaw, `1<<31` shield, ... | clear other weapon bits first; verify the module's `mot[]` table has that weapon |

Shield caveat: `em10ShieldSet()` only creates a physical shield when `w->Ganado == 0`. The island
module em1d sets `Ganado = 2`, so island shield guys are **not** possible through flags. The test
uses the club (`Weapon Club`, flag bit 11) as the large-hammer look. A real island shield needs a
game-code change.

Regenerator (`0x36`) is not in the Ganado family and has no `type` variants in the editor tables;
it uses type 0. Its own module overrides some fields (logcat showed HP 2000/3000 and its own
flags after spawn), so the crow's HP 10 was only a starting point.

---

## 6. The crows, specifically

`em23` (`src/em23/em23.cpp`) is the crow module: one body model (`ARC 0x005`) + TPL `0x008`,
folded/spread wing models (`0x006`/`0x007`) swapped by `em23SetWing()`, and peck/takeoff/fly/land/
die motions. No lock-on, no Ashley help, random scale 1.0–1.1, flying height from `emset_no % 5`.
It waits until the player is near (or fires), takes off, circles, lands and pecks again. Most guns
one-shot it; fire kills it; mines do nothing. Every crow death drops handgun ammo
(`RandomItemCk(0x23)` → item `0x78`, usually 20–40 rounds).

Because the five crows are the only r100 records alive at entry, they are the cheapest visible
proof that a dealt list is being served — but an aggressive replacement attacks during the
opening police-car scene. For a pure loader test, swapping drops between crow perches (the
earlier `r100_pilot.py` rehearsal) avoids changing gameplay.

---

## 7. Verification used

```
python3 .harness/analysis/r100_test_rehearsal.py        # exact dealt bytes, counts, 4-kind budget
python3 .harness/analysis/r100_sound_skip_rehearsal.py  # DRS patch against retail DRS via drs.py
python3 .harness/analysis/check_enemy_java.py           # javac with a stub android.util.Log
```

GitHub Actions (`.github/workflows/android.yml`, `ci/enemy-randomizer`) builds the native core and
the APK; run 45403560666 is the green one. The on-phone verdict came from logcat plus the user's
playtest. The plan for the button is
`docs/superpowers/plans/2026-11-24-r100-test-button.md`.

Useful logcat filters: `randomizer:`, `r100`, `readEmData`, `Snd`, and the `crash` buffer for the
native stack.

---

## 8. What is still open for a general randomizer

1. **Re-test the normal "Deal enemies" button** now that the `/files/` path bug is fixed. It was
   never observed working on the phone.
2. **Budget planner before writing a deal.** For each room count both the distinct enemy module
   ids (max 4, reset on every door transition) and the total enemy sound bytes below ARAM
   `0x700000`. Refuse a deal that exceeds either. This is the main missing piece.
3. **Generalise the sound skip.** Today it is keyed to two file names and one marker. The same DRS
   empty-bank slot should be applied to any newly introduced enemy kind whose bank does not fit,
   with a launcher-side report of which enemies will be silent.
4. **Keep scripted actors in-family.** The r100 officer-killer is hand-built in `r100.cpp`, not in
   the ESL; other rooms have their own script casts and chapter handovers. A general randomizer
   needs a per-room exclusion list for those.
5. **Shield guys need a native change**; the club variant is a stand-in.
6. **No other room has been playtested.** The 4-module result and the ARAM result are measured
   from r100 and from the code, not proven across the campaign.
7. The test overwrites only `emleon00.esl`. A full deal writes eight story lists; that path exists
   and verifies bytes, but its phone behaviour after the path fix is untested.

---

## 9. Files touched by this test

| file | role |
|---|---|
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/EnemyShuffle.java` | fixed r100 deal, its verifier, `r100-no-sound` marker |
| `port/android-decomp/app/src/main/java/com/ast/re4decomp/MainActivity.java` | the test button |
| `port/android-decomp/app/src/main/res/values/strings.xml` | button label |
| `port/whole/runtime/enemy_sidecar.cpp` | serves the dealt list; validates ids/rooms |
| `port/whole/runtime/dvd.cpp` | last-`/files/` path fix + DRS sound-bank skip |
| `port/docs/OFFLINE-ENEMY-RANDOMIZER.md` | the offline deal design |
| `port/docs/enemy-type-randomizer-research.md` | measurements behind id/type/flag |
| `tools/drs.py` | DRS container format, including the empty sound-bank slot |
