# PC randomizer: what could and could not be recovered on the phone

Branch `enemy-randomizer`, 22.11.2026. Read-only analysis of
`Rnd_April_11_B/RE4_PC_Randomizer/RE4RND_v2.exe` (154 MB, "April 11th, 2026 Build").
No Windows PC, no Windows tooling, no emulator — everything below ran on the phone
with Python 3 and the repo's own disc readers.

**Result: the C# logic is NOT recoverable, but the complete enemy tables ARE.**
The program was compiled ahead-of-time to native machine code, so the method bodies
are gone. Its metadata survived, which gives us every method name, every string
literal, and therefore the full enemy pool, variant list and room labels.

---

## 1. Why normal decompilation does not work here

A usual .NET program keeps its code as IL (a readable intermediate language) inside
the exe, and tools like ILSpy/dnSpy turn that back into C#. This exe is different:

- The PE **CLR header data directory is empty** (RVA 0, size 0). A normal .NET exe
  points at its runtime header here. Measured directly from the PE headers.
- Section list includes `.CLR_UEF` and `_RDATA`, and the entry code is x86-64.
- Every enemy method's RVA lands on **native machine code, not an IL header**:

  | method | RVA | first bytes | verdict |
  |---|---|---|---|
  | `ShouldExcludeEnemy` | 0xEEE8 | `00 48 33 cc e8 1f 33 5b 00` | native |
  | `GetNonSafeEnemies` | 0x1CCA4 | `a8 04 0f 84 85 00 00 00` | native |
  | `RandomizeEnemies` | 0x20454 | `00 00 26 48 8b 49 18 e8` | native |

  (An IL body starts with a tiny header `(size<<2)|2` or a fat header with flags
  `0x3003`; none of these do. `IsEnemyAllowedForCurrentRoomShuffle` at 0x20428
  superficially looks like a fat header but its declared size 0x818B is absurd —
  it is code, not IL.)

This is **.NET NativeAOT** (or a similar AOT/ReadyToRun publish). Installing the
.NET SDK on the phone would not help: there is no IL for it to read. Decompiling
the x86-64 back into the original per-room rules would be a reverse-engineering
project of its own, far beyond the value it would add.

**So: no, the exact `ShouldExcludeEnemy` / `IsEnemyAllowedForCurrentRoomShuffle`
logic cannot be read back on this phone — or on a PC with standard tools either.**
That is a property of how the tool was built, not a limitation of the phone.

## 2. What DID survive: the metadata

588 `BSJB` byte sequences occur in the file; 576 are plausible metadata roots, and
most belong to bundled libraries. The randomizer's own root is at **0x98263C**,
identified by its `#Strings` heap containing `ShouldExcludeEnemy`.

Its streams:

| stream | offset | size | contents |
|---|---|---|---|
| `#~` | 0x9826A8 | 45676 | the metadata tables |
| `#Strings` | 0x98D914 | 50044 | type/method/field names |
| `#US` | 0x999C90 | 118988 | **all string literals** |
| `#GUID` | 0x9B6D5C | 16 | |
| `#Blob` | 0x9B6D6C | 13840 | signatures/constants |

Table row counts: 141 types, **874 methods**, 1300 fields, 920 member refs.

Two small Python tools now read this without any external dependency:

- `.harness/analysis/dotnet_meta.py` — a minimal ECMA-335 table reader (handles
  heap sizes, simple and coded indexes) that lists methods with their RVAs.
- `.harness/analysis/pc_strings.py` — walks the `#US` heap and dumps all **2439**
  string literals in heap order to `.harness/analysis/pc_user_strings.json`.

Heap order matters: literals belonging to one table are emitted together, so the
enemy pool and the variant list can be read off as contiguous runs.

## 3. Recovered: the enemy pool (the checkbox list)

`enemyPoolCheckedListBox` items appear as a contiguous run of **22** names
(string indices 1756–1777, immediately followed by the literal
`enemyPoolCheckedListBox` itself). The default profile stores
`enemyPoolCheckedListBox=111111111111100111111111101`, which is **27 bits**.

**The 22 names and the 27 bits do not line up, so the bit-to-name assignment is NOT
establish.** Five items are stored elsewhere in the heap. A second run at indices
1132–1138 holds `Novistador (Normal)`, `Novistador (Flying)`, `Cow`, `Armadura`,
`Iron Maiden`, `Regenerator`, `Wire Trap` — the same names the tooltips describe as
restricted — but where they sit in the checkbox order cannot be determined without
the native code.

The 22 names, in heap order, are:

| order | enemy |
|---|---|
| 1 | Villagers |
| 2 | Dr. Salvador + Bella Sisters |
| 3 | Zealots |
| 4 | Garrador |
| 5 | Island Soldiers |
| 6 | Armored Island Soldiers |
| 7 | JJ |
| 8 | Super Salvador |
| 9 | Colmillo (Wolf) |
| 10 | Gigante |
| 11 | Verdugo |
| 12 | Saddler (Human form) |
| 13 | Chicken |
| 14 | Walking/Spider Plaga |
| 15 | Krauser (Mutated) |
| 16 | Krauser (Knife) |
| 17 | Bass |
| 18 | Bear trap |
| 19 | Wire trap |
| 20 | Drone |
| 21 | Mendez (first phase type) |
| 22 | NPC Salazar (Invincible) |

If the mask were read against this order, bits 14 and 15 would be off — which
matches the tooltip saying Novistadors are disabled by default only if those slots
were Novistadors, and they are not in this run. **So the naive alignment is wrong
and must not be used.** What is certain, from the tooltips rather than the mask:
Novistadors are off by default because they cause random crashes, and crows and U3
were disabled temporarily.

## 4. Recovered: the enemy variant table

Indices 2246–2320 are the internal variant names — this is the tool's actual
enemy catalogue, and it confirms the two-layer design (id + type/flag):

```
Villager-Regular            Jacket ganado-Regular      Salvador/Bella Sister
Jacket ganado Chainsaw      Cultists-Regular           Island ganado-Regular
Armored Garrador            Hammer Man-Regular         JJ Plaga
Super Salvador Plaga        Super Salvador Mace        Super Salvador Plaga + Mace
Merchant-dynamite           Merchant-torch             Merchant-axe
Cosmetic 1 … Cosmetic 14
Dynamite F1/F2, T1/T2, Cr1/Cr2   Torch B/Mace1, B/Mace2, H1, H2
Knife 1/2                   Crossbow 1/2
Gondola RL / Axe / Shield crossbow / Shield / Dynamite / Crossbow
```

Note **"Super Salvador Mace"** and **"Super Salvador Plaga + Mace"**: the tool
builds sub-boss variants by combining a base enemy with weapon/plaga flags, exactly
the `type`/`flag` axis identified in the earlier research (§4 of
`enemy-type-randomizer-research.md`). "Cosmetic 1…14" are appearance-only variants.

The id-bearing display names (indices 2296–2318) map to our GameCube ids:

| PC label | our id |
|---|---|
| Villager (regular) / Villager 13 / 15 / 17 | 0x12, 0x13, 0x15, 0x17 |
| Villager 15 or Dr.Salvador / Villager or Dr.Salvador | 0x15 / 0x12 + saw flag |
| Zealots 11 / 14 / 19 / 1A | 0x11, 0x14, 0x19, 0x1A |
| Zealots 1B or Garrador / 1C or Garrador | 0x1B, 0x1C |
| Military men or JJ | 0x1D |
| Military men 1E (regular) | 0x1E |
| Military men or Armored Soldier | 0x1F |
| Military men or Super Salvador | 0x20 |
| Merchant 18 | 0x18 |

`Em40`…`Em46` and `Villager 42/44/45/46` are PC-only extras; verified absent from
both of our discs. `Em4041` is a combined entry.

## 5. Recovered: room labels and the boss-swap list

Indices 2340+ give the tool's own room descriptions (`R100, Game start`,
`R101, Bingo room/Bingo room after Mendez house`, `R11C, Luis cabin`,
`R10F, Góndola`, …) — a ready-made human-readable room map for our UI.

The boss rooms it swaps **explicitly**, not randomly (indices 1081–1093):
`R119` (Gigante 1), `R11E` (Gigante 2), `R11F` (Mendez 1/2), `R221` (Verdugo),
plus `R309`, `R224` in the same run.

Other recovered specifics:

- `Spiders and bats from R106 disabled.` (index 1186)
- `Error: run out of enemies to add to room … Try selecting more enemies from the
  enemy pool, or set the max. enemy variety to a lower number.` (1115–1116) —
  confirms the variety limit is enforced at deal time and can fail.
- `Don José was replaced by: {0}.` (1117) — the r100 chainsaw encounter is a
  deliberate single substitution.
- `Tools/MDT/r100_its/0000.MDT`, `r100_she`, `r100_he` (1118–1121) — it patches
  r100's message/text files per language for that substitution.
- Room+enemy pair literals like `r204:1`, `r117:0`, `r117:11`, `r206:6`,
  `r20d:16`, `r210:3`, `r214:2`, `r214:3`, `r219:2`, `r219:4`, `r329:1`,
  `r329:2`, `r332:0` — these look like per-room enemy overrides, but without the
  code we cannot say what the number means (enemy id? entry index? count?).
  **Recorded as unknown, not interpreted.**

## 6. Honest limits

- The per-room allow/deny logic (`ShouldExcludeEnemy`,
  `IsEnemyAllowedForCurrentRoomShuffle`, `GetNonSafeEnemies`, `GetSafeBosses`,
  `EnemyFilterB`) exists as native code only. Its exact rules are **not recovered**.
- The 5 remaining pool bits are not mapped to names.
- The meaning of the `rXXX:N` literals is unknown.
- Nothing here was executed; the exe is a Windows binary and was never run.

## 7. What we should do instead

We do not need their code, because we have something they did not: **the game's
own source**. Our guards can be derived from the decompilation and verified, which
is stronger than copying a black box:

1. **Module budget** — count distinct enemy ids per room after the deal; refuse
   above 4 (hard limit, `read.cpp:78`), prefer 3 to match the PC default and leave
   a slot for Ashley/Luis.
2. **Script-driven entries** — already generated by
   `port/tools/enemy_random_table.py` into `enemy_random_rooms.inc`
   (108 rooms: 45 open, 52 places-only, 11 untouched).
3. **Counting scripts** — `SceCountEmAlive(0x10, 0x20)` includes JJ (0x1D) and
   super Salvador (0x20), so within-family swaps keep the count correct.
4. **Adopt their published exclusions** where the room exists on our disc:
   Luis cabin (no Gigante/Regenerator/Iron Maiden/Salvador), r101 exit-door
   positions, r106 spiders/bats, explicit boss handling, no key items on
   invincible NPCs.
5. **Use their ratios** as the default balance: Salvador 1:12 villagers,
   JJ 1:2 military, Garrador 1:8 zealots, Megas 1:8, HammerMan 1:10.
6. **Family matching** — villagers↔Salvador, zealots↔Garrador,
   military↔JJ/Megas/HammerMan. Never a JJ in a village room by default.

## Reproduce

```
python3 .harness/analysis/pc_strings.py            # metadata summary + method list
python3 .harness/analysis/pc_strings.py 1756 1778  # the enemy pool run
python3 .harness/analysis/pc_strings.py 2246 2320  # the variant catalogue
```

Read-only; needs only `Rnd_April_11_B/RE4_PC_Randomizer/RE4RND_v2.exe`.
