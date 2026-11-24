# r101 enemy test — findings

Branch `enemy-randomizer`, 24 Nov 2026. Second on-phone room test, after
`r100-enemy-test-findings.md`. **Status: the user confirmed the test works** (CI run
45418178825, commit `0b33588a`).

The room is r101, the village square / bell fight. This test puts castle enemies in a
first-chapter village room: zealots, Garradors and Verdugos. Everything below comes from master's
docs, the retail G4BE08 disc, the decompilation, and three on-phone playtests.

---

## 1. What master's docs told us first

- `port/docs/enemy-type-randomizer-research.md`: the castle family is ids
  **0x11 / 0x14 / 0x19 / 0x1A** (Ganado 2, 2+w, 2+A, 2+R), plus **0x1B / 0x1C**
  (Ganado 2+cm / 2+cm2). A **Garrador is not a separate id** — it is a zealot of id 0x1B or 0x1C
  with **type 10** (the existing offline dealer uses the same rule).
- `port/docs/pc-randomizer-decompile-findings.md`: **Verdugo is `em2c`, id 0x2C** (the r221
  boss). It is outside the Ganado id range.
- Both docs warn that r101 is a protected room: the bell counts
  `SceCountEmAlive(0x10, 0x20)` and the PC tool ships a safe-spawn fix for its exit door.
- The decomp shows r101 **loads em15 and em26 itself** for event data:
  `EmReadSearch(0x15, 0, evt21/evt30 size)` and `EmReadSearch(0x26, ...)` in `R101Init()`
  (`src/st1/r101.cpp:186,198,200`), and later `SearchEmModule(0x15)` + `MemorySwap(...)`
  (`r101.cpp:466,633`). Those two module slots are unavailable to the deal no matter what the
  enemy list says.

## 2. The fixed deal

`EnemyShuffle.dealR101Test()` touches only `etc/emleon00.esl`, only records with room `0x0101`.
The retail list has 46 such records: 43 Ganados (`0x15`), 1 cow (`0x26`), 2 chickens (`0x28`).

| converted rows | result |
|---|---|
| 38 | castle zealots, id `0x1B`, type 7 or 8 |
| 6 | Garradors, id `0x1B`, **type 10** (n = 3, 12, 19, 27, 35, 43) |
| 2 | Verdugos, id `0x2C`, type 0 (n = 11, 37 — both dormant rows, so the bell's initial count is unchanged) |

Weapon bits are cleared, HP set to 1200, `set` reset to 0, and `room`/`pos`/`rot`/
`Character`/`Guard_r`/`be_flag` are kept. The written file is staged, re-read, compared and
promoted before `active` is written. `Turn off` clears the folder and both no-sound markers.

**Module budget:** em15 and em26 (event data, forced by the room), em1b (zealots + Garradors,
one module), em2c (Verdugos). Exactly four; the chickens' em28 module is deliberately eliminated
by converting them.

**Cow note:** the cow record *is* converted. A `SND: blk 8, ID 0x26` line in logcat is r101
loading em26 for its event data, not a surviving cow. Its sound bank is now skipped too.

## 3. Playtest history

| commit | CI run | result |
|---|---|---|
| `e98824d4` | 45410594830 | crash in em1b motion setup on room entry |
| `63c45cba` | 45414754887 | crash in the audio synth after enemies spawned |
| `0b33588a` | 45418178825 | **works** — castle enemies appear, no crash |

---

## 4. The motion lesson — the important one

First crash stack:

```
HermiteInterpolation(_HERMITE_SET*, Vec*, unsigned short*)+128
MotionSetCore(cModel*, void*, void*, void*, int, int, int)+1520
libre4_em1b.so
cEm10::move()+724
EmSetFromList()+844
ScenarioRoomInit() -> gameRoomInit()
```

Cause: an ESL `set` byte is a **named behaviour preset**, and its animation data lives inside
the enemy module's own archive. r101's Ganado rows carry `set` values 0x05–0x0E — the tool's
shared set table (`em10_ganado_1_set`) calls these *R101 Bucket A*, *R101 Suki A*, *R101 Cart A*,
*R101 Walk & Stay*, etc. Those motions are shipped in the **em15 event data** that r101 swaps in
at runtime; the castle module em1b has no such motion entries. `MotionSetCore` was handed a null
motion pointer and `HermiteInterpolation` dereferenced it.

Fix: set the `set` byte to 0 ("Normal") on every converted row. `verifyR101()` now requires it.

### General rule for the randomizer

**Never blindly preserve `set` when changing `id`.** A `set` is only safe if the destination
module actually contains that motion. This is exactly the category of "lever Ganado", "cart
Ganado", "bucket Ganado", puzzle or scripted-position enemies in other rooms: the preset is a
piece of that room's event archive, not a generic animation. A general dealer needs a per-room /
per-module whitelist of safe `set` values, or must reset to 0 when moving an enemy to another
family. The r100 test did not hit this because its villagers used sets shared by all em10-style
modules; r101 did.

## 5. The sound lesson — two different failures

### 5.1 ARAM space (r100)

em1d + em36 banks overran the fixed music/stream area at ARAM 0x700000 and the music read
corrupted samples (`__SYNSetupSample`). Fixed with the DRS "no sound bank" marker.

### 5.2 The r101 synth fault (not ARAM space)

Second crash stack:

```
__SYNGetWavetableData+100
SYNRunAudioFrame
re4_ai_field -> re4_interrupt_point -> re4_os_idle
```

`__SYNGetWavetableData` (`src/lib/synwt.c`) does:

```c
regionIndex = synth->inst[voice->midiChannel]->keyRegion[voice->keyNum];
voice->region = &synth->region[regionIndex];
voice->art   = &synth->art[voice->region->articulationIndex];
voice->sample = &synth->sample[voice->region->sampleIndex];
voice->adpcm = &synth->adpcm[voice->sample->adpcmIndex];
```

Evidence from the failing run, immediately before the crash:

- sound blocks registered: blk8 ID 0x26 (em26, forced event module), blk9 ID 0x15 (em15,
  forced event module), blk10 ID 0x11 (em1b, the castle bank). The Verdugo bank had **not** loaded
  yet — the two Verdugos are dormant rows.
- total enemy bank bytes: em15 `0x1995e0` + em26 `0x1a800` + em1b `0x1f7ec0` +
  em2c `0x111d00` = `0x4bd9a0`, starting at ARAM `0x1F4100` → `0x6B1AA0` if all four ever load,
  still below `0x700000`. The three banks that had loaded at crash time ended at `0x59FDA0`,
  leaving about 1.4 MB free. The music/stream area starts at `0x700000`.
- no `Snd_seq_work is full.` line (there are 8 sequence works, `SND_SEQ_MAX`, `snd_drv.h:20`).
- no `SndCall : blk ... Illegal SE No.` line.

So the fault was **a bad instrument/region pointer inside the sound data**, reached when the
castle enemies actually played their voices — not an out-of-memory condition and not a sequence
work shortage. Killing the music would free the stream area and BGM sequence works, but the log
shows neither was exhausted, so it would not repair that pointer. The reliable fix remains the
same DRS empty-bank slot used for r100, applied to em1b, em2c and em26. The enemies are silent;
music and all other sound stay on.

An open experiment: mute only the room BGM with the bank still loaded and see whether the fault
moves. That would tell us whether the bad pointer is in BGM interaction or purely in em1b's own
DLS/SIT data; it is a diagnosis, not a proposed fix.

## 6. Verification and files

```
python3 .harness/analysis/r101_test_rehearsal.py   # bytes, counts, 4 kinds, sound-bank sizes
python3 .harness/analysis/check_enemy_java.py      # javac with a stub android.util.Log
```

The rehearsal reads the real DRS part tables with `tools/drs.py` and checks the ARAM sum.
Plan: `docs/superpowers/plans/2026-11-24-r101-test-button.md`.

| file | role |
|---|---|
| `port/android-decomp/.../EnemyShuffle.java` | `dealR101Test` + `verifyR101`, both no-sound markers |
| `port/android-decomp/.../MainActivity.java` | the two test buttons |
| `port/whole/runtime/dvd.cpp` | DRS empty-sound marker, now for both tests |
| `port/docs/r100-enemy-test-findings.md` | the first room test |

## 7. Still open for a general randomizer

- A per-room / per-module `set` whitelist (section 4) before any id change.
- A per-room sound-bank budget including the synth-level failure, not just ARAM bytes.
- A general sound-skip decision: which newly introduced kinds go silent, reported to the player.
- Other rooms and other scripted enemies (lever/cart/bucket/puzzle actors) have not been tested.
- The normal **Deal enemies** button still needs a phone playtest now that the sidecar path bug is fixed.
