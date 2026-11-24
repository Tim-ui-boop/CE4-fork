# The offline enemy randomizer (launcher, sidecar)

Branch `enemy-randomizer`. Built 23.11.2026 after the project owner's correction:
*"the randomizer is not at the launcher… make it offline patching and through the launcher"*,
and *"that offline randomizer branch is very sloppy and broken… you can reference the proven
sidecar thing, just try to be careful with the broken code."*

**What it is:** pick a seed in the launcher, press **Deal enemies**, then **Start game**.
The enemy lists are dealt and written as private copies before play; the core serves those
copies instead of the disc's. Your discs and saves are never written.

---

## 1. Why this replaced the in-game version

The first implementation (commits 37bf9ec1, 5ad5c3a8) was an **in-game cheat toggle**, because
this branch descends from upstream, where the only enemy system is the runtime cheat
`enemy_random.cpp`. That was the wrong shape for this project:

| | in-game cheat (first attempt) | offline launcher (this) |
|---|---|---|
| where you set it | cheat menu, during play | launcher, before Start game |
| how it works | changes enemies live per room | writes private list copies once |
| provability | simulation only | the written files are re-read and checked |
| integrates with a seed | its own seed | one seed, dealt before play |

The runtime version is still on the branch and still works; it is now the *optional live cheat*,
not the main feature. The deal rules are identical in both, so a seed means the same thing.

## 2. Why an enemy list can be dealt offline but a room cannot

This is the key difference from the door randomizer, and it made a much simpler design possible.

- A **room archive** (`files/StN/rNNN.das`) is Yz2-compressed with an adaptive arithmetic coder.
  A patcher cannot rebuild one offline, so the door randomizer writes the *decoded* archive and
  patches the game's decoder to load it (`0238-room-raw-sidecar.patch` on the other branch).
  That patch has to reproduce the port's host-order step and allocate inside the game's heap —
  which is where that branch's trouble lives.
- An **enemy list** (`etc/emleon0N.esl`) is read **raw**: `src/game/stage.cpp` `readEmList` asks
  `DvdReadN` for 0x2000 bytes straight into `pG->Em_list`. No compression, no part table, no
  decoder. It is a flat table of 256 records of 32 bytes.

So the enemy deal needs **no game-side patch at all**. The override sits in the platform's own
file read, `runtime/dvd.cpp` `readFile`, which already opens the imported file from the host
filesystem. One guarded call there serves the dealt copy.

## 3. The three pieces

### The dealer — `EnemyShuffle.java` (launcher side)

Reads `game/disc1/files/etc/emleon00..07.esl` from the user's import, deals them, writes
`randomizer/enemies/etc/emleon0N.esl` plus an `active` marker.

The deal changes only `id` (within the Ganado family), `type` (model variant) and `flag`
(weapon/clothing bits). Everything else is copied byte for byte.

Safety is enforced in this order:

1. **Verify before writing anything.** Every record must keep `set`, `room`, `pos`, `rot`, `hp`,
   `Character`, `Guard_r`, `be_flag`; every dealt id must be a Ganado whose module can show its type.
   One failure refuses the whole deal and writes nothing.
2. **Write to a staging folder**, then re-read each file and compare with what was intended.
3. **Promote** the staged files, and only then write the `active` marker.

A failure at any step leaves the previous playable set alone. `Turn off` deletes the folder.

### The loader — `runtime/enemy_sidecar.cpp` + one hook in `dvd.cpp`

Serves `<data>/randomizer/enemies/<name>` when, and only when:

- the name is one of the eleven `etc/*.esl` lists (never a room, movie, texture or executable);
- the `active` marker exists;
- the read is the whole list at offset 0;
- the file is 8160 or 8192 bytes, a whole number of 32-byte entries;
- every **live** record has a sane id and a valid room.

Anything else falls through to the disc, logged once. A bad sidecar cannot crash the game.

### The launcher UI — `MainActivity.randomizerSection`

Under **Start game**: a seed field, eleven checkboxes, **Deal enemies**, **Turn off**, and a status
line. The deal runs off the UI thread. Seed and options persist in their own prefs file.

## 4. Two wrong assumptions the real data caught

Both were found by *running* the code on the actual disc, not by reading it:

1. **The story lists are 8160 bytes, not 8192.** They hold 255 entries; the game reads 0x2000 and
   the drive zero-fills the last one. The first size gate demanded exactly 8192 and would have
   rejected *every* valid sidecar. Now 8160 or 8192 is accepted and a short file is zero-filled
   exactly as the disc read does. (`emleon08/09` are the full 8192.)
2. **An unused slot can carry a leftover id with no room.** `emleon07` entry 155 on the retail disc
   is `be_flag=00, id=1d, room=0000`. The first record check demanded a valid room for every
   non-zero id and refused every seed. Only a **live** record needs one.

Both fixes are in the Java dealer and the C++ loader, and both are covered by tests.

### 4b. Why a dealt set was still ignored after that (fixed 23.11.2026)

The deal was right the whole time — `rehearse.py` holds every invariant on real disc lists. The
lists were dealt, verified, written, and then never loaded. Two more problems, again only visible
by following the bytes from the launcher to `readFile`:

3. **The hook waited for a read that never happens.** `readEmList` calls
   `DvdReadN(name, pG->Em_list, 0, 0, 0, …)` — the size is **0**, so `cDvdQueue::readInit` sets
   `length = fileGetLength()` and `readMain` reads the file's own length. The eight story lists are
   **0x1FE0** (255 entries; the disc's file table says so), so they arrive at `readFile` as 0x1FE0
   and never as 0x2000. Both the `dvd.cpp` hook and the sidecar demanded exactly 0x2000. Neither
   could ever fire for a story list. Both now accept 0x1FE0 and 0x2000, and the copy is exactly
   `length` bytes so the destination is written as far as the game expects and no further.
4. **The launcher and the core disagreed by one folder.** `EnemyShuffle` wrote and checked
   `AppStorage.base(this)/randomizer/enemies`, but the core is started with `imports.root`
   (`CoreBridge.start(imports.root)`, = `<data>/game`) and that is what `re4_data_dir()` returns.
   The marker and the lists sat one directory above where the loader looked, so the status line
   said "off" and the sidecar found nothing. The launcher now uses `imports.root` throughout.

Point 3 is why the feature looked completely dead: with no match in `readFile` the game simply read
the disc, exactly as if no seed had been dealt. The deal's own report was always honest.

## 5. What was verified

| check | result |
|---|---|
| `enemy_sidecar.cpp` syntax | **clean**, 0 errors |
| `enemy_types.cpp`, `enemy_random.cpp` (game flags, 32-bit) | **clean**, 0 errors |
| `dvd.cpp` before vs after the hook | **identical** pre-existing header error, none added |
| `EnemyShuffle.java` `javac -Xlint:all` | **0 warnings, 0 errors** |
| sidecar decision logic vs real + damaged lists | **26/26 pass** |
| the length gate (0x1FE0, 0x2000 served; 0x400, 0x1000, 0x20000 refused) | **covered by the same test** |
| the real Java deal on the real disc lists | **6 seed/option combinations pass** |
| every written byte vs its source | **only id/type/flag differ** |
| same seed → identical files | **deterministic** |
| 22 new launcher strings resolve; XML well-formed | **yes** |

Measured output: at the defaults 103–109 enemies dealt in 33–35 rooms; with every enemy on,
140–149 in 36–39 rooms.

**Not verified: an on-phone playtest.** No APK was built. `MainActivity` cannot be fully compiled
on this phone (no Android SDK); CI builds it. The open question remains whether a saw-carrying
Ganado animates correctly in every `set` state.

## 6. Reproduce

```
python3 port/tools/enemy_types/run_deal.py        # the real Java deal on the real disc lists
bash    port/tools/enemy_types/check_hooks.sh     # C++ syntax at the game's 32-bit width
python3 port/tools/enemy_types/rehearse.py        # the deal rules, independent of the Java
```

`run_deal.py` compiles `EnemyShuffle.java` with a stub `android.util.Log`, extracts the eight lists
from the disc image into the imported-disc layout, runs the deal, and checks every written byte.

## 7. Files

| file | role |
|---|---|
| `port/android-decomp/.../EnemyShuffle.java` | the offline deal + verification |
| `port/android-decomp/.../MainActivity.java` | the launcher section |
| `port/android-decomp/.../res/values/strings.xml` | its labels |
| `port/whole/runtime/enemy_sidecar.cpp` | serves the dealt lists |
| `port/whole/runtime/dvd.cpp` | the one guarded hook in `readFile` |
| `port/whole/runtime/re4_runtime.h` | its declaration |
| `port/whole/CMakeLists.txt` | builds the new unit |
| `port/tools/enemy_types/` | the three checks |

Background: `enemy-types-user-guide.md` (the toggles and ratios),
`enemy-type-randomizer-research.md` (the measurements), `pc-randomizer-decompile-findings.md`.

**Start with `r100-enemy-test-findings.md`** for the on-phone r100 test: it records the sidecar
path fix, the two independent budgets (four modules and the ARAM sound area), the DRS
"no sound bank" solution, and what still has to be proven before a general deal ships.

Then read `r101-enemy-test-findings.md`: the castle-enemy test adds the `set`/motion rule (a
behaviour preset only works in the module that owns its animations), the synth-level sound
failure that is separate from ARAM space, and the generalised DRS sound skip.
