# The spine generator — how the door randomizer should work next

Branch `feature/offline-randomizer`. Written 21.11.2026 after the pair-swap build (309b68eb) was played on the
phone. The user's decision: **stop swapping pairs, build a PC-style ordered main path ("spine") with branches
attached to it**, because swapping creates room orders the game scripts cannot survive.

## Why swaps are the problem

A swap keeps every room's number of doors and its pairings reciprocal, and only re-pairs links. That is safe
for the *map*, but it says nothing about the *order* in which the player walks through the game:

- a chapter-end door and a key-lock end up guarding rooms nobody is ready for;
- Em_list slots (`src/game/em_set.cpp:192 EmSetFromList2`, `errEm == 0`) are spent when a room is re-entered,
  and scripts write through the result — the r11b boat crash (patch 0241 now survives it, but the room loses its
  prop);
- `doors.txt` reports "30 gates unproven" on every seed because a swap cannot know where a key will end up.

The PC randomizer never creates those orders: it picks a path first and writes destinations afterwards.

## Ground truth we already have (do not re-derive)

Disc survey `.harness/analysis/gc_survey.json` (both G4BE08 discs, regenerate with `analysis/gc_rooms.py survey`):
127 rooms (st1 29, st2 42, st3 42, st4 14), 265 door areas, 1607 item records (595 held), 34 item gates in 23
rooms, 9 locked doors, 12 one-way doors, 12 doors that name a room part, 3 doors with fade 2. The door graph
has one 97-room component from `r100` (all of st2, 28 of st1, 27 of st3); the islands (st3 r30d..r318, all of
st4, r120, r313, r314, r322..r324, r328) are entered only by script or chapter doors.

Already in `DoorShuffle.java` and worth reusing as-is:
- AEV/ITA format and the `Dest` payload reader/writer (`Dest.of`, `Dest.into`), stride 0x9C, offsets 0x5C..0x74;
- `scan()` (metadata only, never keeps room bytes), `applySnapshot`/`writeSnapshot` (`doors.orig.txt`), the
  amortised one-room-at-a-time patch pass, `restore` in `ItemShuffle`;
- pins: `CHAPTER_DOORS` (8 chapter-end areas) and `PIN_ROOMS` (r22c + r207 r211 r223 r303 r31d);
- gate-aware key placement and the `randomizer/doors.active` marker + patch 0240 (Ashley) and 0241 (null model);
- the log layout in `writeRefusal`/`shuffleAll` (verdict, counts, "doors this seed moved", gate list).

Port patches: 0238 (room sidecar), 0239 (door stage + jump log), 0240 (Ashley), 0241 (null model).

## Room classes to build (from the survey, not from guesswork)

| class | definition from the data | use in the spine |
|---|---|---|
| `regular` | >= 2 door links, every door paired | can sit anywhere on the spine or a branch |
| `single` | exactly one door link | dead end: only a branch tip |
| `oneway` | has a door record with no return door (13 links) | destination is written, never a stepping stone |
| `gated` | room carries a `use` area (34 gates) | allowed, but its key must be reachable first |
| `locked` | room carries a `key_id` door (9) | allowed, key ordering enforced |
| `reserved` | `PIN_ROOMS` + chapter-door rooms + `r120` | never re-wired, never a spine step |
| `scripted` | in the islands (no door graph) | never touched, only reachable by chapter/script |

## Algorithm

1. **Collect** (unchanged): sidecars -> `scan()` -> `link()`.
2. **Classify** each room as above and store the result in `doors.txt` so a seed can be read offline.
3. **Pick the spine**: ordered walk starting at `r100`, always stepping to a `regular` room that is not
   reserved, biased to keep stage variety (target: the 26-room feel of `MainPathRooms.txt`, but drawn from the
   ~90 regular rooms and allowed to cross stages at any step). Reject a step if the candidate room has no
   usable door back (`regular` guarantees it).
4. **Attach branches**: for every spine room, hang 1..N `regular`/`single` rooms that are still unused
   (PC: `Set amount of branches`; ours: 3..10 depending on spine length). A branch may loop back to another
   spine room ("allow more loops").
5. **Write destinations**: each link on the spine is written as
   `spine[i].door -> spine[i+1]` using **the target room's own arrival payload** (position/angle/part come from
   the pair the target room already has). Symmetry: the return door gets the arrival payload of the room it
   leads back to. `Dest` already carries stage, room, part and the position bytes, so this is the same
   `into()` write the swap code uses today — no new format work.
6. **Pins**: chapter doors and `PIN_ROOMS` keep their payloads exactly as the disc has them; a chapter door may
   not be used as a spine step, but the spine may start from the room it lands in.
7. **Validate**: run `reach()` on the finished map (must contain the 97 vanilla-reachable rooms, or at least
   every room used by the spine + all gates needed for it), then run the key walk; **refuse to write** if a
   required gate's item is not reachable before it. Unlike today, aim for zero "unproven" gates: the spine can
   simply decline to place a gate room before its key.
8. **Log**: verdict, spine (ordered room list with stage), branches, keys, and the moved-door table (already
   coded). Add the spine itself so a bad run is readable without the app.

UI: keep the existing button but add a second one, `Shuffle with a spine` (default) next to the current
`Shuffle doors and keys` (pairs) so the two can be compared on the same seed. Both share the seed field and the
"New seed on every shuffle" switch.

## Acceptance before a build goes to the phone

- `.harness/analysis/check_java.py` clean (javac -Xlint:all).
- A new offline replay `analysis/spine_check.py` over `gc_survey.json`: 20 seeds, every seed must produce
  (a) spine reachable from `r100` end to end, (b) 97/97 rooms reachable, (c) zero moved chapter doors,
  (d) zero moved `PIN_ROOMS` links, (e) gate ordering proven for every gate room used.
- CI green (the prepare step applies patches 0238/0239/0240/0241; do not add lines near recorded sites).

## Open questions for the next chat

- Spine length: PC uses a fixed main path of 26 rooms + branches; ours can be parameterised (`Set minimum room
  amount`, default 24).
- Should the spine visit every chapter room at least once (so the story still functions), or may a chapter be
  skipped entirely? The chapter doors are pinned, so a skipped chapter still *happens*; only its rooms would be
  off-path.
- One-way rooms (`r225 -> r226 -> r227 -> r228`) can only be branch tips; decide whether they are allowed.
- Em_list crash class (patch 0241 hides the symptom): the spine reduces re-entries but cannot remove them. If a
  room loses its prop visibly, add it to a "no re-entry" list — the port logs the first five null hits.
