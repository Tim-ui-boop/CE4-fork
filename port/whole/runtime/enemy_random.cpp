// The cheat "Random enemies" (0.99.12, decision of the project owner, 12.11.2026: "Ok den Randomizer Schalter wie von
// dir vorgeschlagen 'Stufe 1' und als Button neben dem Item Randomizer"; 0.99.13 "stage 1.5", the owner's choice of
// 13.11.2026 after his own test in r100, "sie sind alle ident zum original"; port/docs/gegner-randomizer-plan.md),
// compiled with the game's flags into libre4_main; patch 0231 (src/game/em_set.cpp EmSetFromList) asks it.
//
// When a story room starts, EmSetFromList creates the room's entries of the chapter's enemy list (pG->Em_list, 256
// entries, saved with the game; deaths kept by entry number in pG->Em_flg). With the switch on (a seed of its own,
// re4_core_set_enemy_random), for the room's ordinary Ganados:
//   - each takes the spot (position and facing) of another entry of the room where a Ganado stood or will stand (its
//     own spot among them), a shuffle from the seed: its kind, model, behaviour, life and guard radius stay its own;
//   - 0.99.13: from that spot it moves 1.5 to 4 m in a direction from the seed, to a place the room's collision says
//     is safe (NearSpot: the floor there within 25 cm of the spot's, no step down or gap on the way, no wall between
//     the two at 40 cm and 1 m, room for a figure above, not nearer than 6 m to Leon unless the spot already was, at
//     least 1 m from every other entry of the room and every place chosen before); eight tries, else the spot itself.
//     So the room really looks different, not only other Ganados on the same spots;
//   - 0.99.13, the number (the owner's "+/- 10 %"): a room with 10 or more Ganados at its start gets -1, 0 or +1, with
//     20 or more -2 .. +2; fewer than 10 keep their number. Left out are Ganados that come now, never the last of a
//     kind (an event may need its code); a copy is one of the room's ordinary Ganados that come now (its kind is
//     loaded) at a safe place beside another one, its death kept at an empty entry of the list (id 0: the game never
//     uses it), so it stays dead like the others; only while the room's enemy works keep room for what is still to
//     come (the entries of the room not there yet, and four more).
// The list itself is never written: the enemy gets a copy of its entry with the new place (EmSetWork), so a save stays
// the game's own (only a copy's death sets a bit of an empty entry). Same seed, same list and room: the same places,
// the same ones left out, the same copies.
//
// Kinds of rooms (port/tools/enemy_random_table.py -> enemy_random_rooms.inc): open; "counts only" (0.99.13: the script
// counts or removes enemies by kind, nothing depends on where they stand: places change, the number never); untouched
// (a boss, list entries named through variables or in the list itself, or counting and more), and rooms without a
// script file. Never touched: the extra games (Ada's, the Mercenaries), Ashley's part, stage 4; the entries a room
// script names by number; the entries an area of the room waits for (waiting_type 1: a door, an item that comes when
// that enemy dies); entries that are not ordinary Ganados (ids 0x10..0x20 except the trader 0x18, `set` 0 =
// "Normal", no chainsaw, trader, Garrador or gatling form, t_emlist.cpp); waves and reinforcements (scripts create
// them later with EmSetFromList2, never through EmSetFromList).
//
// The options of the PC randomizer's "Enemy settings" (0.99.16, the owner's choice of 16.11.2026: its replacements
// pool, its ratios and its variety limit; not its HP tab; 17.11.2026 the owner's additions: the animals crows and
// cows may change kind too, the pool gains the two Krausers -- knife fight and mutated arm, the bosses the game's
// scripts own (Saddler, U3, Salazar) stay out): a room rolls its kinds from the pool
// (re4_core_set_enemy_options), an ordinary Ganado becomes one of them at its new place — a dog, an Armadura, a
// Regenerator — at most `variety` pool kinds a room and at most one of them a sub-boss, never the last of a
// kind, never an entry a script names; the Ganados that stay roll the ratios: a plaga in the head (flag bit 20 of
// every family, t_emlist.cpp), a ballistic helmet (bit 19, military 0x1D..0x20; a helmet rules out the plaga, as
// the PC tool says), a shield (bit 31 of the shield families), a hammer (bit 29, zealot and military) — a
// "1 in 15-k" chance a nibble of the ratios names. The wave entries the room's script creates on demand
// (EmSetFromList2, patch 0234: the cabin's siege, an ambush) take the same kind roll, never another place; a new
// kind sets no flags of the old family (the bits are family-relative, t_emlist.cpp's tables); behaviour, hp and
// guard radius stay the entry's own.
// 0.99.17, fixed 17.11.2026: a room whose Ganados all come as waves kept its plan alive (the wave roll needs it),
// the waves' own checks refused every way a script summons (the alive bit the wrapper sets only after the create,
// the work tag `set`, the by-number name every summon carries), and the villager-form branch sat unreachable. Now
// a room plans its waves even when nothing ordinary stands at the start; a plain Ganado of a wave changes into a
// pool kind; an entry a script names by number becomes only the Chainsaw Man (the villager's own module form,
// so a script's Ganado casts stay safe) at the sub-boss odds and only with Unsafe
// mode on; what a script counts keeps its family rule. Standing Ganados with a work tag and the animals change
// kind at their own post in their own pass; every wave change gets its own log line.
#include "types.h"
#include "global.h"
#include "em.h"
#include "read.h"
#include "sce_at.h"
#include "atari.h"
#include "scroll.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern GLOBAL_WK* pG;

extern "C" void re4_log(const char* fmt, ...);
extern "C" void re4_core_set_enemy_random(int seed);
extern "C" void re4_core_set_enemy_options(int pool, int ratios);
extern "C" void re4_enemy_random_room(void);
extern "C" int re4_enemy_random_entry(int no, EM_LIST* out);
extern "C" int re4_enemy_random_extra(EM_LIST* out);
extern "C" void re4_enemy_random_extra_made(int ok);

namespace {

enum { kOpen = 0, kUntouched = 1, kCountsNarrow = 2, kCountsFamily = 3, kOpenCautioned = 4 };

struct RoomRow {
    u16 room;
    u8 kind;
    u8 boss;    // 0.99.16: a boss room stays locked even in unsafe mode (its script owns the fight's logic)
    u32 named[8];
};

const RoomRow kRooms[] = {
#include "enemy_random_rooms.inc"
};

volatile int gSeed;  // 20 bits, 0 off (the app's switch)

// The enemy options of the app (re4_core_set_enemy_options, 0.99.16: a port of the PC randomizer's "Enemy
// settings"). `gPool`: a bit per kind of kPoolKinds that may replace an ordinary Ganado. `gRatios` the mix, one
// nibble per chance (0 never .. 15 most of them): bits 0..3 plaga, 4..7 helmet, 8..11 shield, 12..15 sub-boss
// (the hammer), bits 16..17 the variety cap of pool kinds a room (1..4, the engine enemy kinds' own limit).
volatile int gPool;
volatile int gRatios;

// A kind of the pool: what an ordinary Ganado becomes (t_emlist.cpp's tables). The kind's own model loads on
// demand (module_loader); a room that cannot hold it simply does not create it (kinds stay within the cap).
struct Kind {
    u8 id;
    u8 type;
    u8 set;
    u16 minHp;  // the PC tool's own floor (0: the entry's own hp stays), so a replacement keeps its strength
};
const Kind kPoolKinds[] = {
    {0x16, 4, 0, 0},     // b0: Chainsaw Man ("EM16 C.S.")
    {0x1D, 2, 0, 0},     // b1: Gatling Man
    {0x1B, 10, 0, 0},    // b2: Garrador ("EM1C ClawMan")
    {0x2D, 0, 0, 0},     // b3: Novistador (InsectHuman; the PC randomizer keeps it off by default too)
    {0x22, 0, 0, 1000},  // b4: Dog (Colmillos, "Enemy Dog") -- also a source: a Crow or Cow may become one
    {0x23, 0, 0, 0},     // b5: Crow
    {0x26, 0, 0, 1000},  // b6: Cow
    {0x3C, 0, 0, 2000},  // b7: Armadura ("Armor", Gray + AXE)
    {0x36, 0, 0, 3000},  // b8: Regenerator ("Regenerater", Normal A)
    {0x36, 2, 0, 3000},  // b9: Iron Maiden (Strong A)
    {0x39, 1, 0, 1250},  // b10: Krauser, the knife fight (em39 type 1, the first battle's ruins stalker)
    {0x39, 2, 0, 2500},  // b11: Krauser, the mutated arm (em39 type 2, the final battle's form)
};
constexpr int kPoolCount = 12;

u32 mix(u32 x)
{
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

u32 hashOf(u32 seed, u32 list, u32 room, u32 what)
{
    return mix(seed * 0x9E3779B1u ^ mix(list << 24 ^ room << 8 ^ mix(what + 0x51)));
}

bool namedIn(const RoomRow* row, int no)
{
    return row && (row->named[no >> 5] >> (no & 31) & 1);
}

bool ganadoId(int id)
{
    return id >= 0x10 && id <= 0x20 && id != 0x18;
}

// A Ganado's spot: ids 0x10..0x20 except the trader, set "Normal"
bool ganadoSpot(const EM_LIST& e)
{
    return ganadoId(e.id) && e.set == 0;
}

// An entry whose kind the pool may replace beyond the ordinary Ganados (0.99.16, the owner's wish of 17.11.2026:
// the animals too): a Crow (0x23) or a Cow (0x26) of the room -- both plain set-0 animals -- may become any kind
// of the pool, the odds tied to the ratios' sub-boss nibble so a farm is not a boss parade.
bool animalSpot(const EM_LIST& e)
{
    return (e.id == 0x23 || e.id == 0x26) && e.set == 0;
}

// A special form of the Ganado family (t_emlist.cpp's tables): the forms a swap must not take away -- a chainsaw
// head ("EM16 C.S." type 4 of 0x10..0x17, "EM1F Chain Saw" type 22), the trader's type 6, a claw form
// ("EM1C ClawMan", "Gatling Man"), Dr. Salvador's head burst (0x20's flag 28). The rest of an entry -- its weapon
// or work tag (`set`) -- is its own: a kind swap takes the whole entry anyway, so `set` may not protect it
// (0.99.17: the farm's field workers).
bool specialForm(const EM_LIST& e)
{
    if (e.id <= 0x17 && e.type == 4) {
        return true;
    }
    if (e.type == 6 && (e.id == 0x13 || e.id == 0x14 || e.id == 0x1E)) {
        return true;
    }
    if ((e.id == 0x1B && e.type == 10) || (e.id == 0x1C && (e.type == 10 || e.type == 13))) {
        return true;
    }
    if (e.id == 0x1D && e.type == 2) {
        return true;
    }
    if (e.id == 0x20 && (e.type == 22 || (e.flag & (1u << 28)))) {
        return true;
    }
    return false;
}

// An ordinary Ganado: a plain "Normal"-set Ganado spot without a special form (t_emlist.cpp: chainsaw "EM16 C.S."
// type 4 of 0x10..0x17 and "EM1F Chain Saw" type 22 or the chainsaw flag of 0x20, "EM18 Trader" type 6,
// "EM1C ClawMan" 0x1B type 10 and 0x1C types 10, 13, "Gatling Man" 0x1D type 2)
bool ordinary(const EM_LIST& e)
{
    return ganadoSpot(e) && !specialForm(e);
}

// The module rule (the crashes of 17.11.2026): the engine holds a fixed number of enemy module
// slots (four vanilla; eight since the engine patch 0235, the owner's wish of 17.11.2026 -- the
// whole game on Android carries the decompiled source, so the engine itself may be modded). A
// kind may load when its slot is free to take: `pullEmModule` (read.cpp) names it. A swap still
// never evicts a kind that is loaded: the room's own enemies may come after the swap's.
bool kindLoaded(const Kind& to)
{
    return SearchEmModule(to.id) != 0 || pullEmModule() != 0;
}

bool deadBit(int no)
{
    if (pG->em_list_no < 0) {
        return false;
    }
    const u32* row = EM_FLG_ROW(pG->em_list_no);
    return (row[no >> 5] & (0x80000000u >> (no & 31))) != 0;
}

// The entry comes at this room's start (switched on, not created yet, not dead)
bool comesNow(const EM_LIST& e, int no)
{
    return (e.be_flag & 1) && !(e.be_flag & 2) && !deadBit(no);
}

// A place in the list's units (centimetres) with its facing
struct Place {
    s16 pos[3];
    s16 rot[3];
};

constexpr int kMaxCopies = 2;
constexpr int kMaxAdds = 4;  // the added-enemy stage's own cap, above the copies' two (a big scenery room may take more)

// The chance nibble of `ratios` (re4_core_set_enemy_options) as a percent roll: a count of k of the nibble's
// 15 steps is "one in (16 - k)": k 0 never, 15 every one of them. The PC randomizer's 1:1..1:9 map 15..7.
bool ratioHit(int ratios, int shift, u32 roll)
{
    const int k = (ratios >> shift) & 0xF;
    return k > 0 && (roll % (u32) (16 - k)) == 0;
}

// The "unsafe" switch of gRatios bit 16 (the owner's wish of 17.11.2026, the name theirs): when on, the cautious
// rooms open up -- an untouched room whose only sin is that the scanner could not follow its indirection plans
// like an open one (the per-entry protections never turn off), and a family-counting room's Ganados may become
// other forms of their family. Boss rooms and narrow counts stay locked either way.
bool unsafeOn()
{
    return (__atomic_load_n(&gRatios, __ATOMIC_RELAXED) >> 16) & 1;
}

// The pool's variety cap of gRatios bits 17..18: 1..3 pool kinds a room (0: the core's own default of 4).
int varietyCap()
{
    const int cap = (__atomic_load_n(&gRatios, __ATOMIC_RELAXED) >> 17) & 3;
    return cap == 0 ? 4 : cap;
}

struct Plan {
    bool on;
    int room, list, kind;
    u32 seed;
    bool moved[256];   // the entry comes at place[entry]
    Place place[256];
    bool left[256];    // left out
    int leftCount, leftDone;
    int change;        // -2 .. +2
    int ganados;       // Ganados that come at the start
    struct Copy {
        int from, beside, entry;
        Place at;
    } copies[kMaxCopies];
    int copyCount, copyNext, copiesMade, copiesFailed;
    int addPhase;     // 0 copies, 1 adds (extra_made's report names the right one)
    int movers, swapped, nearby, spots, protectedByArea, copyRefused;
    // 0.99.17, the added enemies (the owner's idea of 17.11.2026, the other way round: rooms whose list
    // entries the game itself never fills -- r120's farm workers and crows are scenery, not list entries --
    // get randomizer enemies ADDED at the scenery's places instead of waiting for entries to change; the
    // crow kind may fly in as itself). `add[k]` the kPoolKinds index of an enemy to add, `at[k]` its place;
    // the extras ride the copies' machinery (their deaths land on empty entries, the list itself is never
    // written, the same safe-place check as a copy's place)
    struct Add {
        int kind;      // kPoolKinds index, -1 unused
        int entry;     // the empty list entry its death is kept at (extra_made names it)
        Place at;
    } adds[kMaxAdds];
    u32 addEntryTaken[(256 - 0xF0 + 31) >> 5];  // the entries the adds claimed, so no two share one
    int addCount, addNext, addsMade, addsFailed;
    char addRefused[64];
    // 0.99.16, the PC randomizer's kinds: the pool kinds this room rolls, their count bounded by varietyCap();
    // `swapKind[entry]` -1 none, else the kPoolKinds index the entry becomes; `kindSwaps` the ones changed by kind
    int rolledKind[kPoolCount];
    int rolledCount;
    int swapKind[256];
    int kindSwaps;
    int swapRefused;  // 0.99.17: the kind was not loaded in the room (never a fifth enemy module)
    char why[96];
    char refused[64];
};
Plan gPlan;
unsigned gLines;
int gTestSeed = -1;  // RE4_ENEMY_RANDOM (the PC check)
// RE4_ENEMY_RANDOM_CHECK=1 (the PC check): the list as EmSetFromList found it, compared at its end: the randomizer
// writes no entry (the game itself sets be_flag of what it created; a copy's death bit is no part of the list)
const bool gCheck = getenv("RE4_ENEMY_RANDOM_CHECK") && *getenv("RE4_ENEMY_RANDOM_CHECK") == '1';
EM_LIST gBefore[256];
bool gChecking;

void checkList()
{
    if (!gChecking) {
        return;
    }
    gChecking = false;
    int changed = 0, flags = 0, first = -1;
    for (int i = 0; i < 256; i++) {
        EM_LIST a = gBefore[i], b = pG->Em_list[i];
        if (a.be_flag != b.be_flag) {
            flags++;
        }
        a.be_flag = b.be_flag = 0;
        if (memcmp(&a, &b, sizeof(a)) != 0) {
            changed++;
            first = first < 0 ? i : first;
        }
    }
    re4_log("enemies: check r%03x: %s (%d entries' be_flag set by the game)", gPlan.room,
            changed ? "LIST WRITTEN" : "the list's entries unchanged", flags);
    if (changed) {
        re4_log("enemies: check: %d entries changed, the first 0x%02x", changed, first);
    }
}

int seedNow()
{
    if (gTestSeed < 0) {
        const char* v = getenv("RE4_ENEMY_RANDOM");
        gTestSeed = v && *v ? (int) (strtol(v, 0, 0) & 0xFFFFF) : 0;
        if (gTestSeed) {
            re4_log("enemies: random enemies with seed %d (RE4_ENEMY_RANDOM)", gTestSeed);
        }
    }
    const int s = __atomic_load_n(&gSeed, __ATOMIC_RELAXED);
    return s ? s : gTestSeed;
}

const RoomRow* rowOf(int room)
{
    for (const RoomRow& r : kRooms) {
        if (r.room == room) {
            return &r;
        }
    }
    return 0;
}

// ---- a safe place nearby (0.99.13) -------------------------------------------------------------------------------

constexpr float kStepMax = 250.0f;     // the floor at the new place within 25 cm of the spot's (no box, no step up)
constexpr float kGapMax = 500.0f;      // on the way there no drop or rise of more than 50 cm
constexpr float kNearLeon = 6000.0f;   // not nearer than 6 m to Leon at the room's start (unless the spot was)
constexpr float kApart = 1000.0f;      // 1 m from every other entry of the room and every place chosen

struct Avoid {
    float p[256 + kMaxCopies][3];
    int n;
};

// (the game's math.h has no fabsf)
float absOf(float v)
{
    return v < 0.0f ? -v : v;
}

// The floor under (x, z) from height y (the room's collision, as the Ganados themselves find it); false: none near
bool floorAt(float x, float y, float z, float* out)
{
    Vec p = {x, y, z};
    Vec* n = 0;
    const f32 f = SatMgr.getFloor(&p, &n, 600.0f, 1500.0f, 0);
    if (f < -99999.0f) {
        return false;
    }
    if (n && n->y < 0.6f) {
        return false;  // steep: a slope or the side of something
    }
    *out = f;
    return true;
}

bool clearLine(float ax, float ay, float az, float bx, float by, float bz)
{
    Vec a = {ax, ay, az}, b = {bx, by, bz}, hit;
    return SatMgr.hitCheck(&a, &b, &hit, 0, 0, 0) == 0;
}

// A place 1.5 .. 4 m from `base` (list units) in a direction from `h`, checked as the head comment says
bool nearSpot(const Place& base, u32 h, const Avoid& avoid, Place* out)
{
    const float bx = base.pos[0] * 10.0f, by = base.pos[1] * 10.0f, bz = base.pos[2] * 10.0f;
    const float lx = pG->pl_pos.x, lz = pG->pl_pos.z;
    const float leonBefore = sqrtf((bx - lx) * (bx - lx) + (bz - lz) * (bz - lz));
    for (u32 t = 0; t < 8; t++) {
        const u32 r = mix(h + t * 0x9E3779B9u);
        const float ang = (float) (r & 0xFFFF) / 65536.0f * 6.2831853f;
        const float d = 1500.0f + (float) ((r >> 16) % 2500u);
        const float cx = bx + cosf(ang) * d, cz = bz + sinf(ang) * d;
        float cy;
        if (!floorAt(cx, by, cz, &cy) || absOf(cy - by) > kStepMax) {
            continue;
        }
        bool ok = true;
        for (int k = 1; k <= 3 && ok; k++) {
            const float s = (float) k / 4.0f;
            float my;
            ok = floorAt(bx + (cx - bx) * s, by, bz + (cz - bz) * s, &my) && absOf(my - by) <= kGapMax;
        }
        if (!ok || !clearLine(bx, by + 400.0f, bz, cx, cy + 400.0f, cz) ||
            !clearLine(bx, by + 1000.0f, bz, cx, cy + 1000.0f, cz) || !clearLine(cx, cy + 100.0f, cz, cx, cy + 1700.0f, cz)) {
            continue;
        }
        const float leon = sqrtf((cx - lx) * (cx - lx) + (cz - lz) * (cz - lz));
        if (leon < kNearLeon && leon < leonBefore) {
            continue;
        }
        for (int k = 0; k < avoid.n && ok; k++) {
            const float dx = cx - avoid.p[k][0], dz = cz - avoid.p[k][2], dy = cy - avoid.p[k][1];
            ok = dx * dx + dz * dz >= kApart * kApart || absOf(dy) > 2000.0f;
        }
        if (!ok) {
            continue;
        }
        *out = base;
        out->pos[0] = (s16) lroundf(cx / 10.0f);
        out->pos[1] = (s16) lroundf(cy / 10.0f);
        out->pos[2] = (s16) lroundf(cz / 10.0f);
        out->rot[1] = (s16) (out->rot[1] + (s16) (mix(r) & 0x3FFF) - 0x2000);  // a little turned, within 90 degrees
        return true;
    }
    return false;
}

void avoidAdd(Avoid& a, const s16 pos[3])
{
    if (a.n < (int) (sizeof(a.p) / sizeof(a.p[0]))) {
        a.p[a.n][0] = pos[0] * 10.0f;
        a.p[a.n][1] = pos[1] * 10.0f;
        a.p[a.n][2] = pos[2] * 10.0f;
        a.n++;
    }
}

Place placeOf(const EM_LIST& e)
{
    Place p;
    memcpy(p.pos, e.pos, sizeof(p.pos));
    memcpy(p.rot, e.rot, sizeof(p.rot));
    return p;
}

void logLine(const char* fmt, const char* a, const char* b)
{
    if (gLines < 300) {
        gLines++;
        re4_log(fmt, a, b);
    }
}

}  // namespace

// The app (CoreBridge.setEnemyRandom): the switch's seed, 0 off; from the next room on. Any thread.
extern "C" void re4_core_set_enemy_random(int seed)
{
    seed &= 0xFFFFF;
    if (__atomic_exchange_n(&gSeed, seed, __ATOMIC_RELAXED) != seed) {
        re4_log("core: random enemies %s", seed ? "on (from the next room)" : "off (from the next room)");
        if (seed) {
            re4_log("core: random enemies seed %d", seed);
        }
    }
}

// The app (CoreBridge.setEnemyOptions, 0.99.16): the pool of kinds that may replace an ordinary Ganado and the mix
// ratios; from the next room on. Any thread.
extern "C" void re4_core_set_enemy_options(int pool, int ratios)
{
    __atomic_store_n(&gPool, pool, __ATOMIC_RELAXED);
    __atomic_store_n(&gRatios, ratios & 0x0003FFFF, __ATOMIC_RELAXED);
    re4_log("core: random enemies options: pool 0x%x, ratios 0x%x", pool, ratios & 0x0003FFFF);
}

// 0.99.17, the manifest (the owner's decision of 17.11.2026, in-app planner, PC-style): the whole chapter
// section is planned once, when its enemy list loads -- every room the list names and the scanner knows gets
// its kinds rolled ahead, with a sub-boss quota across the section (one chainsaw / Gatling / Garrador / Krauser
// room per four, so a section is not a boss parade) and the same per-room rules as before (the pool, the
// variety cap, the family rule of counting rooms, unsafe mode's opening). A room then reads its plan instead
// of rolling: same seed, same plan; the plan is a block the log can dump whole (and a file the app could share
// later). The per-entry decisions that need the live room -- the places (the room's own collision), the +/-
// 10 % number (how many stand there now) -- still happen at the door, seeded the same as ever. The module rule
// stays too (kindLoaded): only a kind whose module is loaded or has a free slot -- never
// eviction of a kind the room's own script still needs.
struct RoomPlan {
    u16 room;
    u8 kind;         // after unsafe mode's opening of a cautious room
    u8 rolledCount;
    u8 subBoss;      // its rolls hold a chainsaw / Gatling / Garrador / Krauser kind
    u8 rolledKind[kPoolCount];
};
struct Manifest {
    bool on;
    int list;
    u32 seed;
    int pool;
    int ratios;
    int rooms;
    int subBosses;
    RoomPlan plan[64];
};
Manifest gManifest;

const RoomPlan* manifestAt(u16 room)
{
    if (!gManifest.on) {
        return 0;
    }
    for (int i = 0; i < gManifest.rooms; i++) {
        if (gManifest.plan[i].room == room) {
            return &gManifest.plan[i];
        }
    }
    return 0;
}

// The kinds one room's plan rolls. `subBossLeft`: the section's quota of sub-boss rooms; a roll that would
// take one with the quota spent keeps drawing instead. Returns whether a sub-boss kind was taken.
bool manifestRollKinds(RoomPlan* p, u32 seed, int list, int pool, bool familyOnly, int cap, bool subBossLeft)
{
    bool taken = false;
    bool subBossTaken = false;
    p->rolledCount = 0;
    for (int draws = 0; draws < 48 && p->rolledCount < cap; draws++) {
        const int pick =
            (int) (hashOf(seed, (u32) list, (u32) p->room, 0x500u + (u32) draws) % (u32) kPoolCount);
        if (!(pool >> pick & 1)) {
            continue;
        }
        if (familyOnly && pick > 2) {
            continue;  // a counting room rolls only family members (chainsaw, Gatling, Garrador)
        }
        bool have = false;
        for (int k = 0; k < p->rolledCount; k++) {
            have |= p->rolledKind[k] == pick;
        }
        const bool subBoss = pick <= 2 || pick >= 10;
        if (have || (subBoss && subBossTaken) || (subBoss && !subBossLeft)) {
            continue;
        }
        if (subBoss) {
            subBossTaken = true;
            taken = true;
        }
        p->rolledKind[p->rolledCount++] = (u8) pick;
    }
    return taken;
}

// Plans the whole loaded list at once (its 256 entries name every room of the chapter section).
void manifestBuild()
{
    Manifest& m = gManifest;
    const int pool = __atomic_load_n(&gPool, __ATOMIC_RELAXED);
    const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
    const int seed = seedNow();
    const int list = pG ? pG->em_list_no : -1;
    if (!seed || list < 0) {
        m.on = false;
        return;
    }
    memset(&m, 0, sizeof(m));
    m.on = true;
    m.list = list;
    m.seed = (u32) seed;
    m.pool = pool;
    m.ratios = ratios;
    const bool unsafe = unsafeOn();
    // the rooms of this list, in entry order (the loop keeps the first of a kind)
    u16 rooms[64];
    int n = 0;
    for (int i = 0; i < 256 && n < 64; i++) {
        const EM_LIST& e = pG->Em_list[i];
        if (e.id == 0 || !rowOf(e.room)) {
            continue;
        }
        bool have = false;
        for (int k = 0; k < n; k++) {
            have |= rooms[k] == e.room;
        }
        if (!have) {
            rooms[n++] = e.room;
        }
    }
    const int cap = varietyCap();
    // one sub-boss room per four of the section (at least one: a section that plans at all may see one)
    int subBossLeft = n > 0 ? 1 + n / 4 : 0;
    for (int k = 0; k < n && m.rooms < 64; k++) {
        const RoomRow* row = rowOf(rooms[k]);
        RoomPlan* p = &m.plan[m.rooms++];
        p->room = rooms[k];
        p->kind = row->kind;
        if (unsafe && row->kind == kUntouched && !row->boss) {
            p->kind = kOpenCautioned;
        }
        const bool kindsMayChange = p->kind == kOpen
            || (unsafe && (p->kind == kCountsFamily || p->kind == kOpenCautioned));
        const bool familyOnly = p->kind == kCountsFamily || p->kind == kOpenCautioned;
        p->subBoss = pool && kindsMayChange
            && manifestRollKinds(p, m.seed, m.list, pool, familyOnly, cap, subBossLeft > 0);
        if (p->subBoss) {
            m.subBosses++;
            subBossLeft--;
        }
    }
    if (gLines < 300) {
        gLines++;
        re4_log("enemies: manifest list %d (seed %u): %d rooms planned, %d with a sub-boss kind",
                m.list, m.seed, m.rooms, m.subBosses);
    }
}

// The plan is stale when the seed, the list, or the options it was planned from changed.
bool manifestStale()
{
    const int pool = __atomic_load_n(&gPool, __ATOMIC_RELAXED);
    const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
    const int list = pG ? pG->em_list_no : -1;
    return !gManifest.on || gManifest.list != list || gManifest.seed != (u32) seedNow()
        || gManifest.pool != pool || gManifest.ratios != ratios;
}

// Patch 0231, EmSetFromList's start: the room's plan.
extern "C" void re4_enemy_random_room(void)
{
    Plan& p = gPlan;
    memset(&p, 0, sizeof(p));
    const int seed = seedNow();
    if (!seed || !pG) {
        return;
    }
    p.room = pG->stage_no << 8 | pG->room_no;
    p.list = pG->em_list_no;
    p.seed = (u32) seed;
    const RoomRow* row = rowOf(p.room);
    p.kind = row ? row->kind : kUntouched;
    // 0.99.16, unsafe mode (the owner's switch of 17.11.2026): a cautious room -- untouched only because the
    // scanner could not follow its script's indirection -- plans like an open one. The per-entry protections
    // (named, waited, last-of-kind, non-ordinary) never turn off, so what the script truly needs stays; the
    // copies (the +/- 10 % of the number) stay off there, so a script that counts never gets extra enemies.
    // A boss room keeps its lock either way.
    // 0.99.17: a room whose list is stale has no plan yet -- manifestBuild (below) re-plans the whole section,
    // and unsafe mode's opening of a cautious room is decided there; `unsafe` here only gates the why-text
    const bool unsafe = unsafeOn();
    if (unsafe && row && row->kind == kUntouched && !row->boss) {
        p.kind = kOpenCautioned;  // open for places and kinds, but the copies stay off
    }
    if (SysFlagChk(pG, SYS_OMAKE_ADA_GAME) || SysFlagChk(pG, SYS_OMAKE_ETC_GAME) || pG->pl_type == 1) {
        snprintf(p.why, sizeof(p.why), "not in the story's Leon part");
    } else if (pG->stage_no < 1 || pG->stage_no > 3 || p.list < 0 || p.list > 7) {
        snprintf(p.why, sizeof(p.why), "not a story room of stages 1..3");
    } else if (!row) {
        snprintf(p.why, sizeof(p.why), "a room without a script of its own");
    } else if (row->kind == kUntouched && !unsafe) {
        snprintf(p.why, sizeof(p.why), "its script names enemies, has a boss, or counts and does more");
    } else if (row->kind == kUntouched && unsafe && row->boss) {
        snprintf(p.why, sizeof(p.why), "a boss room (locked even in unsafe mode)");
    }
    char roomText[16];
    snprintf(roomText, sizeof(roomText), "r%03x", p.room);
    if (p.why[0]) {
        logLine("enemies: random enemies leave %s as it is (%s)", roomText, p.why);
        return;
    }
    // the entries the room's areas wait for (a door, an item that comes with a death)
    bool waited[256] = {};
    for (SCE_AT_DATA* w = sceAtSetOtStart(); (w = sceAtGetOtAddr(w)) != 0;) {
        if (w->waiting_type == 1) {
            waited[w->waiting_no] = true;
        }
    }
    // the movers (static: alive or not) and the spots, in entry order; the Ganados that come now
    int movers[256], spots[256];
    int nm = 0, ns = 0;
    Avoid avoid;
    avoid.n = 0;
    for (int i = 0; i < 256; i++) {
        const EM_LIST& e = pG->Em_list[i];
        if (e.id == 0 || e.room != (u16) p.room) {
            continue;
        }
        avoidAdd(avoid, e.pos);
        if (ganadoId(e.id) && comesNow(e, i)) {
            p.ganados++;
        }
        const bool protect = namedIn(row, i) || waited[i];
        if (protect && ganadoSpot(e)) {
            p.protectedByArea += waited[i] && !namedIn(row, i);
        }
        if (ordinary(e) && !protect) {
            movers[nm++] = i;
            spots[ns++] = i;  // its own spot
        } else if (ganadoSpot(e) && !(e.be_flag & 1)) {
            // a Ganado's spot that is not used now (a later wave, another part of the chapter)
            bool dup = false;
            for (int k = 0; k < ns && !dup; k++) {
                const EM_LIST& o = pG->Em_list[spots[k]];
                dup = o.pos[0] == e.pos[0] && o.pos[1] == e.pos[1] && o.pos[2] == e.pos[2];
            }
            if (!dup) {
                spots[ns++] = i;
            }
        }
    }
    p.movers = nm;
    p.spots = ns;
    if (nm == 0 && gLines < 300) {
        gLines++;
        // 0.99.17: the plan stays alive anyway -- a room whose enemies all come as the script's waves (the farm,
        // the village raid) rolls kinds for them; before, this return left the wave changer dead in those rooms
        re4_log("enemies: random enemies in r%03x (list %d, seed %u): none of the room's Ganados stands here as an "
                "ordinary one at the start: only the waves roll", p.room, p.list, p.seed);
    }
    p.on = true;
    // 0.99.17: the room reads its kinds from the manifest -- the whole list was planned once (with its sub-boss
    // quota), the same hash per seed as the old per-room roll, so nothing of the picture changes; what moves
    // away is the last-moment dice and the possibility of a section-wide boss parade
    if (manifestStale()) {
        manifestBuild();
    }
    const RoomPlan* mp = manifestAt(p.room);
    // the kinds this room's plan rolled; no entry's kind yet
    for (int i = 0; i < 256; i++) {
        p.swapKind[i] = -1;
    }
    p.rolledCount = 0;
    p.kindSwaps = 0;
    p.swapRefused = 0;
    if (mp) {
        p.rolledCount = mp->rolledCount;
        for (int i = 0; i < mp->rolledCount; i++) {
            p.rolledKind[i] = mp->rolledKind[i];
        }
    } else if (gLines < 300) {
        gLines++;
        re4_log("enemies: random enemies r%03x: no plan of its own in this list (the kinds stay)", p.room);
    }
    // the spots shuffled (Fisher-Yates from the seed), the k-th mover takes the k-th; from there a safe place nearby
    for (int k = ns - 1; k > 0; k--) {
        const int j = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x100u + (u32) k) % (u32) (k + 1));
        const int t = spots[k];
        spots[k] = spots[j];
        spots[j] = t;
    }
    int now[256], nn = 0, idle = -1;
    for (int k = 0; k < nm; k++) {
        const int no = movers[k];
        const Place spot = placeOf(pG->Em_list[spots[k]]);
        Place near;
        if (nearSpot(spot, hashOf(p.seed, (u32) p.list, (u32) p.room, 0x200u + (u32) no), avoid, &near)) {
            p.place[no] = near;
            p.moved[no] = true;
            p.nearby++;
            avoidAdd(avoid, near.pos);
        } else if (spots[k] != no) {
            p.place[no] = spot;
            p.moved[no] = true;
            p.swapped++;
        }
        // 0.99.16: the PC randomizer's mix. The kind roll comes from its own hash, so a place and a kind never
        // decide each other. Only ordinary Ganados that come now may change kind: a script's named entries and
        // the room's special forms stay as they are (the movers loop already kept those out).
        p.swapKind[no] = -1;
        if (p.rolledCount > 0 && ordinary(pG->Em_list[no]) && comesNow(pG->Em_list[no], no)) {
            const u32 roll = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x600u + (u32) no);
            const int pick = (int) (roll % (u32) p.rolledCount);
            // 0.99.17: only a kind the room already has loaded -- the engine holds 4 enemy modules, and a fifth
            // makes it refuse the room's own enemies ("Em set failed") ahead of the scripts' null pointer
            if (!kindLoaded(kPoolKinds[p.rolledKind[pick]])) {
                p.swapRefused++;
            } else {
                p.swapKind[no] = p.rolledKind[pick];
                p.kindSwaps++;
            }
        }
        // 0.99.17: the animals roll in their own pass below (a Crow or Cow is never a mover, so this place never
        // saw them; the owner's wish of 17.11.2026 kept: the sub-boss odds, only in a fully open room)
        if (comesNow(pG->Em_list[no], no)) {
            now[nn++] = no;
        } else if (idle < 0) {
            idle = no;
        }
    }
    // 0.99.17: the kinds of the rest that stand here now: a Ganado with a script's work tag (`set` -- the farm's
    // field workers; a wave entry with such a tag comes through the wave hook instead) and the animals (a Crow or
    // Cow of the room) change kind at their own post, without moving -- the movers loop above already rolled the
    // ordinary set-0 ones. Never a named or waited entry, never a special form; the counting grades keep their
    // family rule; the animals only in a fully open room and at the sub-boss odds (a field of crows is not a
    // Regenerator parade).
    for (int i = 0; i < 256; i++) {
        const EM_LIST& e = pG->Em_list[i];
        if (e.id == 0 || e.room != (u16) p.room || p.swapKind[i] >= 0) {
            continue;
        }
        const bool animal = animalSpot(e);
        if (!animal && (!ganadoId(e.id) || specialForm(e))) {
            continue;
        }
        if (!comesNow(e, i) || namedIn(row, i) || waited[i]) {
            continue;
        }
        bool may = p.kind == kOpen;
        bool family = false;
        if (!may && unsafe && (p.kind == kCountsFamily || p.kind == kOpenCautioned)) {
            may = true;
            family = true;
        }
        if (!may || (animal && family)) {
            continue;  // the animals never in a counting room's family pass
        }
        if (p.rolledCount == 0) {
            continue;
        }
        if (animal && !ratioHit(__atomic_load_n(&gRatios, __ATOMIC_RELAXED), 12,
                                hashOf(p.seed, (u32) p.list, (u32) p.room, 0x610u + (u32) i))) {
            continue;
        }
        const int pick = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x618u + (u32) i) % (u32) p.rolledCount);
        if (family && p.rolledKind[pick] > 2) {
            continue;  // a counting room keeps its family forms
        }
        // 0.99.17: the kind needs its module loaded or a free slot for it (the engine patch
        // 0235 widened the room to eight); never a kind that would evict one the room still needs
        if (!kindLoaded(kPoolKinds[p.rolledKind[pick]])) {
            p.swapRefused++;
            continue;
        }
        p.swapKind[i] = p.rolledKind[pick];
        p.kindSwaps++;
    }
    // the number: +/- 10 % (rooms with 10 or more Ganados at the start), never in a room that counts (all three
    // of its grades), and never in a room unsafe mode opened (its script may count what the scanner could not see)
    const int range = (p.kind == kOpen) ? (p.ganados >= 20 ? 2 : p.ganados >= 10 ? 1 : 0) : 0;
    p.change = range ? (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x4E) % (u32) (2 * range + 1)) - range : 0;
    if (p.change < 0) {
        // left out: Ganados that come now, never the last of a kind in the room
        for (int c = 0; c < -p.change && nn > 0; c++) {
            const int start = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x4C + (u32) c) % (u32) nn);
            for (int k = 0; k < nn; k++) {
                const int no = now[(start + k) % nn];
                if (p.left[no]) {
                    continue;
                }
                int same = 0;
                for (int i = 0; i < 256; i++) {
                    const EM_LIST& o = pG->Em_list[i];
                    same += i != no && !p.left[i] && o.id == pG->Em_list[no].id && o.room == (u16) p.room && comesNow(o, i);
                }
                if (same > 0) {
                    p.left[no] = true;
                    p.leftCount++;
                    break;
                }
            }
        }
    } else if (p.change > 0) {
        // the room's enemy works keep room for what is to come: this start's entries, the room's later ones, 4 more
        int coming = 0;
        for (int i = 0; i < 256; i++) {
            const EM_LIST& e = pG->Em_list[i];
            coming += e.id != 0 && e.room == (u16) p.room && !(e.be_flag & 2) && !deadBit(i);
        }
        int used = 0;  // (counted here: countActiveWork would also move the manager's own statistics)
        for (u32 k = 0; k < EmMgr.getArrayNum(); k++) {
            used += !EmMgr.fastAt(k)->isEmpty();
        }
        const int free = (int) EmMgr.getArrayNum() - used - coming - 4;
        // empty entries of the list keep the copies' deaths
        int empty[256], ne = 0;
        for (int i = 0; i < 255; i++) {
            if (pG->Em_list[i].id == 0) {
                empty[ne++] = i;
            }
        }
        for (int c = 0; c < p.change; c++) {
            if (c >= free) {
                snprintf(p.refused, sizeof(p.refused), "the room's enemy works full");
                break;
            }
            if (nn == 0 || ne <= c) {
                snprintf(p.refused, sizeof(p.refused), nn ? "no empty list entry" : "no Ganado comes now");
                break;
            }
            Plan::Copy& cp = p.copies[p.copyCount];
            cp.from = now[hashOf(p.seed, (u32) p.list, (u32) p.room, 0x46 + (u32) c) % (u32) nn];
            cp.beside = now[hashOf(p.seed, (u32) p.list, (u32) p.room, 0x47 + (u32) c) % (u32) nn];
            const int first = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x45) % (u32) ne);
            cp.entry = empty[(first + c) % ne];
            const Place anchor = p.moved[cp.beside] ? p.place[cp.beside] : placeOf(pG->Em_list[cp.beside]);
            Place at;
            if (!nearSpot(anchor, hashOf(p.seed, (u32) p.list, (u32) p.room, 0x300u + (u32) c), avoid, &at)) {
                p.copyRefused++;
                continue;
            }
            cp.at = at;
            avoidAdd(avoid, at.pos);
            p.copyCount++;
        }
    }
    (void) idle;
    // 0.99.17, the added enemies (the owner's idea of 17.11.2026, the other way round): a room the game
    // fills with scenery -- r120's farm workers and crows stand there as room objects, not as list entries,
    // so no entry swap can ever touch them -- gets randomizer enemies ADDED at the scenery's places. The
    // room script ran already (execInitFunc, sce_sys.cpp: before EmSetFromList), so the objects are there
    // to anchor at. Each added enemy rides the copies' loop at EmSetFromList's end: a real enemy of a kind
    // the plan rolled (a sub-boss only at the sub-boss odds, at most one a room), at a safe place within
    // walking reach of a scenery object, its death kept at an empty list entry; the list itself stays as it
    // is. Only a room whose plan is fully open (a counting room's script may name what stands around it, a
    // cautious room's script may count); the kind needs its module loaded or a free slot (kindLoaded, never
    // evicting one the room still needs); the room's enemy works keep the +4 headroom for what is coming.
    p.addCount = 0;
    p.addNext = 0;
    p.addsMade = 0;
    p.addsFailed = 0;
    p.addRefused[0] = 0;
    if (p.kind == kOpen) {
        int wanted = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x7A0u) % (u32) (kMaxAdds + 1));
        if (p.ganados >= 10) {
            wanted /= 2;  // a room already thick with Ganados takes fewer extra
        }
        // a room whose entries the game never fills (r120's sceneried start) always takes at least one:
        // otherwise a plain phase of the seed leaves it exactly as vanilla, and the owner sees nothing
        if (nm == 0 && wanted == 0) {
            wanted = 1;
        }
        // the room's scenery objects as anchor candidates (object order, capped)
        int anchor[16];
        int na = 0;
        const int objNum = SmdGetObjNum();
        // near Leon first (the owner sees nothing when the add lands in a far corner of a big outdoor
            // room): the anchors are the scenery objects nearest where the player stands (pG->pl_pos, the
            // cached position the game itself places the partner from at a room's start, sce_sys.cpp:82 --
            // pPL is only forward-declared here, so its fields are not ours to read)
            struct AnchorDist { int o; long d2; };
            AnchorDist near[16];
            for (int o = 0; o < objNum; o++) {
                cObj* obj = SmdGetObjPtr((u32) o);
                if (obj == 0) {
                    continue;
                }
                const float dx = obj->pos.x - pG->pl_pos.x;
                const float dy = obj->pos.y - pG->pl_pos.y;
                const float dz = obj->pos.z - pG->pl_pos.z;
            const long d2 = (long) (dx * dx + dy * dy + dz * dz);
            int k = na < 16 ? na++ : 15;
            while (k > 0 && near[k - 1].d2 > d2) {
                if (k < 16) {
                    near[k] = near[k - 1];
                }
                k--;
            }
            near[k].o = o;
            near[k].d2 = d2;
        }
        for (int k = 0; k < na; k++) {
            anchor[k] = near[k].o;
        }
        if (na == 0) {
            snprintf(p.addRefused, sizeof(p.addRefused), "no scenery place to stand at");
        } else {
            // the work margin the copies also keep (+4 for what is still coming)
            int margin = 0;
            for (u32 k = 0; k < EmMgr.getArrayNum(); k++) {
                margin += !EmMgr.fastAt(k)->isEmpty();
            }
            for (int i = 0; i < 256; i++) {
                const EM_LIST& e = pG->Em_list[i];
                if (e.id != 0 && e.room == (u16) p.room && !(e.be_flag & 2) && !deadBit(i)) {
                    margin++;
                }
            }
            margin = (int) EmMgr.getArrayNum() - margin - 4;
            const int pool = __atomic_load_n(&gPool, __ATOMIC_RELAXED);
            int subBossAdds = 0;
            for (int a = 0; a < wanted && p.addCount < kMaxAdds; a++) {
                if (margin - p.addCount <= 0) {
                    snprintf(p.addRefused, sizeof(p.addRefused), "the room's enemy works full");
                    break;
                }
                // the kind: one of the room's rolled ones, else a pool kind beside the sub-bosses
                int kind = -1;
                if (p.rolledCount > 0) {
                    kind = p.rolledKind[hashOf(p.seed, (u32) p.list, (u32) p.room, 0x7B0u + (u32) a)
                                       % (u32) p.rolledCount];
                } else {
                    for (int t = 0; t < kPoolCount && kind < 0; t++) {
                        const int cand = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room,
                                                      0x7B0u + (u32) a * 16u + (u32) t) % (u32) kPoolCount);
                        const bool subBoss = cand <= 2 || cand >= 10;
                        if ((pool >> cand & 1) && !subBoss) {
                            kind = cand;
                        }
                    }
                }
                if (kind < 0) {
                    snprintf(p.addRefused, sizeof(p.addRefused), "no kind of the pool fits here");
                    break;
                }
                // a sub-boss adds only at the sub-boss odds, at most one a room
                const bool subBoss = kind <= 2 || kind >= 10;
                if (subBoss && (subBossAdds > 0 || !ratioHit(__atomic_load_n(&gRatios, __ATOMIC_RELAXED), 12,
                                 hashOf(p.seed, (u32) p.list, (u32) p.room, 0x7C0u + (u32) a)))) {
                    continue;  // the odds said no: this draw adds nothing
                }
                // the module must be loaded or have a free slot (never evict one the room still needs)
                if (!kindLoaded(kPoolKinds[kind])) {
                    continue;
                }
                // the place: a safe walk-in spot near a scenery object
                cObj* obj = SmdGetObjPtr((u32) anchor[hashOf(p.seed, (u32) p.list, (u32) p.room, 0x7D0u + (u32) a)
                                                      % (u32) na]);
                if (obj == 0) {
                    continue;
                }
                Place anchorPlace;
                anchorPlace.pos[0] = (s16) lroundf(obj->pos.x / 10.0f);
                anchorPlace.pos[1] = (s16) lroundf(obj->pos.y / 10.0f);
                anchorPlace.pos[2] = (s16) lroundf(obj->pos.z / 10.0f);
                anchorPlace.rot[0] = 0;
                anchorPlace.rot[1] = 0;
                anchorPlace.rot[2] = 0;
                Place at;
                if (!nearSpot(anchorPlace, hashOf(p.seed, (u32) p.list, (u32) p.room, 0x7E0u + (u32) a), avoid, &at)) {
                    continue;  // no safe place near this anchor: another draw may find one
                }
                avoidAdd(avoid, at.pos);
                Plan::Add& ad = p.adds[p.addCount++];
                ad.kind = kind;
                ad.at = at;
                if (gLines < 300) {
                    gLines++;
                    const Kind& to = kPoolKinds[kind];
                    re4_log("enemies: random enemies r%03x: an added id 0x%02x type %d at (%d, %d, %d)",
                            p.room, to.id, to.type, at.pos[0], at.pos[1], at.pos[2]);
                }
                if (subBoss) {
                    subBossAdds++;
                }
            }
        }
        if (gLines < 300 && (p.addCount > 0 || p.addRefused[0])) {
            gLines++;
            re4_log("enemies: random enemies r%03x: %d added from the scenery%s%s", p.room, p.addCount,
                    p.addRefused[0] ? ", some not: " : "", p.addRefused[0] ? p.addRefused : "");
        }
    }
    if (gCheck) {
        memcpy(gBefore, pG->Em_list, sizeof(gBefore));
        gChecking = true;
    }
    if (gLines < 300 && p.kindSwaps > 0) {
        gLines++;
        re4_log("enemies: random enemies r%03x: %d kinds planned (%d of %d entries change kind)", p.room,
                p.rolledCount, p.kindSwaps, p.movers);
    }
}

// Patch 0231, EmSetFromList for entry `no` (it passed the game's checks): -1 left out, 0 as it is, 1 created from
// `out` (the entry with another place).
extern "C" int re4_enemy_random_entry(int no, EM_LIST* out)
{
    Plan& p = gPlan;
    if (!p.on || no < 0 || no > 255) {
        return 0;
    }
    if (p.left[no]) {
        p.leftDone++;
        return -1;
    }
    const int kind = p.swapKind[no];
    if (!p.moved[no] && kind < 0) {
        return 0;
    }
    *out = pG->Em_list[no];
    if (p.moved[no]) {
        memcpy(out->pos, p.place[no].pos, sizeof(out->pos));
        memcpy(out->rot, p.place[no].rot, sizeof(out->rot));
    }
    // 0.99.16, the pool kind: the entry becomes one of the kinds the room rolled (the flag bits are family-
    // relative, so a new kind starts with no flags of the old family; its place, hp and guard radius stay)
    if (kind >= 0) {
        const Kind& to = kPoolKinds[kind];
        out->id = to.id;
        out->type = to.type;
        out->set = to.set;
        out->flag = 0;
        out->Character = 0;  // "Chase": the family's own vanilla behaviour
        // the PC tool's own floors: a replacement is never weaker than its real self (a villager's hp is too
        // little for a Regenerator; the game's own two bytes hold every value here)
        if (to.minHp > out->hp) out->hp = to.minHp;
        return 1;
    }
    // 0.99.16, the mix ratios of the PC randomizer, only for an entry that stays a Ganado of its own kind: plaga
    // bit 20 of every family; the ballistic helmet bit 19 of the military (0x1D..0x20); the shield bit 31 of the
    // shield families (0x11, 0x19..0x20); the hammer ("Weapon M Star") bit 29 of zealot and military. A helmet
    // rules out a plaga, as the PC tool's help says. The bits only ever set what the family's own table names;
    // a room that counts keeps its kinds' looks as they are unless unsafe mode opened it (a family form keeps
    // its family's flags).
    if (p.kind == kOpen || p.kind == kOpenCautioned) {
        const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
        const u32 roll = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x700u + (u32) no);
        const u32 roll2 = roll * 2654435761u >> 8;
        if (ganadoId(out->id) && (out->flag & ((1u << 20) | (1u << 19) | (1u << 31) | (1u << 29))) == 0) {
            if (out->id >= 0x1D && ratioHit(ratios, 4, roll)) {
                out->flag |= 1u << 19;  // the ballistic helmet; its ganado carries no plaga
            } else if (ratioHit(ratios, 0, roll)) {
                out->flag |= 1u << 20;  // a plaga in its head
            }
            if ((out->id == 0x11 || (out->id >= 0x19 && out->id <= 0x20)) && ratioHit(ratios, 8, roll2)) {
                out->flag |= 1u << 31;  // the shield
            }
            if (out->id >= 0x19 && out->id <= 0x20 && ratioHit(ratios, 12, roll2 ^ 0x5Fu)) {
                out->flag |= 1u << 29;  // the hammer
            }
        }
    }
    return 1;
}

// Patch 0234, EmSetFromList2 for entry `no` (a script creates it on demand -- the cabin's siege, an ambush): the
// wave entries take the room's kind roll, never another place (a wave's place is the script's own). 0.99.17: a
// summon no longer fails the waves' own checks -- every summon carries its by-number name (that is how the script
// wrapper works), the alive bit comes only after the create, and `set` is just the work tag a swap overwrites;
// what stays is what matters: a plain Ganado of the family. An entry a script names by number may become only
// the Chainsaw Man (the villager's own module form, so a script's Ganado casts stay safe) at the sub-boss odds,
// and only with Unsafe mode on: the village raid and the cabin's siege open that way. A
// room that counts keeps its family rule. -1 not at all, 0 as it is, 1 created from `out`.
extern "C" int re4_enemy_random_wave(int no, EM_LIST* out)
{
    Plan& p = gPlan;
    if (!p.on || no < 0 || no > 255) {
        return 0;  // no plan (the switch off, or the room protected): the entry comes exactly as the game wants
    }
    const EM_LIST& e = pG->Em_list[no];
    if ((e.be_flag & 2) || deadBit(no) || !ganadoId(e.id) || specialForm(e)) {
        return 0;  // already created, dead, or no plain Ganado entry of the family
    }
    const RoomRow* r = rowOf(p.room);
    // crash-proven 17.11.2026: a named summon is the script's cast (the village raid's house zombie, entry
    // 0x25, is the approaching-the-house cutscene's actor; the swap to the Chainsaw Man -- another class
    // through the same slots -- broke the scene's calls and crashed it). The script's cast never changes kind
    // -- but it may wear another costume: the villager's own flag bits (t_emlist.cpp's tables -- a plaga
    // head, a ballistic helmet, a torch, a hammer) are looks of the same skeleton, and the scene's own
    // animations still fit the body they were made for. The ratios decide, the same odds the PC tool's mix
    // names, on the wave's own hash, so the same seed always dresses the same cast.
    if (namedIn(r, no)) {
        if (!ganadoId(e.id)) {
            return 0;  // the cast's non-Ganado members (the cow, the bird) wear no costume
        }
        const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
        const u32 roll = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x790u + (u32) no);
        const u32 roll2 = roll * 2654435761u >> 8;
        EM_LIST* dressed = out;  // the costume is written into the copy the patch creates from
        *dressed = e;
        u8 id = e.id;
        bool changed = false;
        if (id >= 0x19 && id <= 0x20 && ratioHit(ratios, 4, roll)) {
            // the ballistic helmet of a military ganado: id 0x1D..0x20 (the zealot families' hats are
            // their own models' business, not a flag)
            if (id >= 0x1D) {
                dressed->flag |= 1u << 19;
                changed = true;
            }
        }
        if (id <= 0x17) {
            // a villager's weapon costume (t_emlist.cpp's own flag table: bit 8 hat, bit 9 hat2, bit 11 torch,
            // bit 16 mugen, bit 17 bomb, bit 27 sickle, bit 29 axe, bit 30 bucket, bit 31 suki): clothes and
            // tools of the same body, so the scene's villager animations still fit. The hats always (pure
            // looks), the tools only with Unsafe mode (a torch in a scene's hands changes the beat), and
            // the plaga deliberately left out (a scene's scripted death and a plaga head are not friends).
            const u32 pick = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x795u + (u32) no);
            static const u8 bits[] = {8, 9, 11, 16, 17, 27, 29, 30, 31};
            const int which = (int) (pick % (u32) (sizeof(bits) / sizeof(bits[0])));
            const bool tool = bits[which] >= 16 || bits[which] == 11;
            const u32 bit = 1u << bits[which];
            if ((e.flag & bit) == 0 && (!tool || unsafeOn())
                && ratioHit(ratios, 8, pick >> 4)) {
                dressed->flag |= bit;
                changed = true;
            }
        }
        (void) roll2;
        if (changed && gLines < 300) {
            gLines++;
            re4_log("enemies: random enemies r%03x: the named wave's 0x%02x wears a costume (flag 0x%08x)",
                    p.room, no, dressed->flag);
        }
        return changed ? 1 : 0;
    }
    const bool unsafe = unsafeOn();
    if (p.kind != kOpen && !(unsafe && (p.kind == kCountsFamily || p.kind == kOpenCautioned))) {
        return 0;  // a counting room's waves keep the family rule below; a narrow count and every locked room keeps all
    }
    *out = e;
    // the kinds the plan rolled for this room's unnamed waves (never a script-named entry: its cast is its own)
    if (p.rolledCount > 0) {
        const int pick = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x680u + (u32) no) % (u32) p.rolledCount);
        // a room that counts (its script may count the wave) stays inside the family with its picks
        if ((p.kind == kCountsFamily || p.kind == kOpenCautioned) && p.rolledKind[pick] > 2) {
            return 0;  // no family kind rolled for this room's wave: the entry stays as it is
        }
        if (!kindLoaded(kPoolKinds[p.rolledKind[pick]])) {
            return 0;  // 0.99.17: only a kind the room already has loaded -- never a fifth enemy type
        }
        p.swapKind[no] = p.rolledKind[pick];
    } else {
        p.swapKind[no] = -1;
    }
    if (p.swapKind[no] >= 0) {
        p.kindSwaps++;
        const Kind& to = kPoolKinds[p.swapKind[no]];
        out->id = to.id;
        out->type = to.type;
        out->set = to.set;
        out->flag = 0;
        out->Character = 0;
        if (to.minHp > out->hp) out->hp = to.minHp;
        if (gLines < 300) {
            gLines++;
            re4_log("enemies: random enemies r%03x: the wave's 0x%02x (id 0x%02x) becomes id 0x%02x type %d",
                    p.room, no, e.id, to.id, to.type);
        }
        return 1;
    }
    // no kind rolled: the wave Ganado still takes the ratios' mix like any other that stays its own kind
    if (p.kind == kOpen || p.kind == kOpenCautioned) {
        const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
        const u32 roll = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x780u + (u32) no);
        const u32 roll2 = roll * 2654435761u >> 8;
        if (ganadoId(out->id) && (out->flag & ((1u << 20) | (1u << 19) | (1u << 31) | (1u << 29))) == 0) {
            if (out->id >= 0x1D && ratioHit(ratios, 4, roll)) {
                out->flag |= 1u << 19;
            } else if (ratioHit(ratios, 0, roll)) {
                out->flag |= 1u << 20;
            }
            if ((out->id == 0x11 || (out->id >= 0x19 && out->id <= 0x20)) && ratioHit(ratios, 8, roll2)) {
                out->flag |= 1u << 31;
            }
            if (out->id >= 0x19 && out->id <= 0x20 && ratioHit(ratios, 12, roll2 ^ 0x5Fu)) {
                out->flag |= 1u << 29;
            }
        }
    }
    return 1;
}

// Patch 0231, EmSetFromList's end, again and again until -1: first the copies, then the added enemies
// (the scenery-anchored ones the plan rolled at their own places; 0.99.17) -- each with an empty list
// entry its death is kept at. The patch creates it unless that entry is marked dead or a live enemy
// carries it already. The first call writes the room's line.
extern "C" int re4_enemy_random_extra(EM_LIST* out)
{
    Plan& p = gPlan;
    if (!p.on) {
        checkList();
        return -1;
    }
    if (p.copyNext == 0 && gLines < 300) {
        gLines++;
        char count[160] = "";
        int len = 0;
        if (p.kind == kCountsNarrow || p.kind == kCountsFamily || p.kind == kOpenCautioned) {
            len = snprintf(count, sizeof(count), "; the number stays (the room counts its enemies)");
        } else if (p.change == 0) {
            len = snprintf(count, sizeof(count), "; the number stays (%s)", p.ganados >= 10 ? "as drawn" : "under 10");
        } else if (p.change < 0) {
            len = snprintf(count, sizeof(count), "; %d -> %d, left out:", p.ganados, p.ganados - p.leftDone);
            for (int i = 0; i < 256 && len < (int) sizeof(count) - 8; i++) {
                if (p.left[i]) {
                    len += snprintf(count + len, sizeof(count) - len, " 0x%02x", i);
                }
            }
        } else {
            len = snprintf(count, sizeof(count), "; %d -> %d:", p.ganados, p.ganados + p.copyCount);
            for (int c = 0; c < p.copyCount && len < (int) sizeof(count) - 40; c++) {
                len += snprintf(count + len, sizeof(count) - len, " a copy of 0x%02x beside 0x%02x (death at 0x%02x)",
                                p.copies[c].from, p.copies[c].beside, p.copies[c].entry);
            }
            if (p.copyCount < p.change) {
                snprintf(count + len, sizeof(count) - len, " (%d not: %s)", p.change - p.copyCount,
                         p.refused[0] ? p.refused : "no safe place");
            }
        }
        re4_log("enemies: random enemies in r%03x (list %d, seed %u%s): %d Ganados at the start, %d ordinary, %d spots: "
                "%d at a new place nearby, %d on another's spot, %d changed kind (%d kept: not loaded here)%s%s",
                p.room, p.list, p.seed, p.kind == kCountsNarrow ? ", counts only" :
                    p.kind == kCountsFamily ? ", counts family-wide" : p.kind == kOpenCautioned ? ", unsafe mode" : "",
                p.ganados, p.movers, p.spots,
                p.nearby, p.swapped, p.kindSwaps, p.swapRefused, count,
                p.protectedByArea ? " (some kept: an area waits for them)" : "");
    }
    if (p.copyNext >= p.copyCount && p.addNext >= p.addCount) {
        // (after the copies and the adds: their entries are empty ones, whose be_flag the game may set; the
        // check sees the rest)
        checkList();
        p.copyNext = kMaxCopies + 1;  // the line is written once
        p.addNext = kMaxAdds + 1;
        return -1;
    }
    // 0.99.17, the added enemies: a kind the plan rolled, at its scenery-anchored safe place, born as its
    // own enemy (the pool kind's own hp floor and set); its death lands on an empty list entry like a copy's
    if (p.copyNext >= p.copyCount) {
        p.addPhase = 1;
        const Plan::Add& ad = p.adds[p.addNext++];
        const Kind& to = kPoolKinds[ad.kind];
        EM_LIST e;
        memset(&e, 0, sizeof(e));
        e.id = to.id;
        e.type = to.type;
        e.set = to.set;
        e.flag = 0;
        e.Character = 0;
        e.hp = to.minHp ? to.minHp : 1000;
        e.room = (u16) p.room;
        memcpy(e.pos, ad.at.pos, sizeof(e.pos));
        memcpy(e.rot, ad.at.rot, sizeof(e.rot));
        e.Guard_r = 1000;
        *out = e;
        // an empty entry for its death, one never handed to another add (the adds claim 0xF0 down)
        int empty = -1;
        for (int i = 255; i >= 0xF0; i--) {
            if (pG->Em_list[i].id == 0 && !(p.addEntryTaken[i - 0xF0 >> 5] >> (i - 0xF0 & 31) & 1)) {
                bool fresh = true;
                for (int c = 0; c < kMaxCopies && fresh; c++) {
                    fresh &= p.copies[c].entry != i || c >= p.copyCount;
                }
                if (fresh) {
                    empty = i;
                    break;
                }
            }
        }
        if (empty < 0) {
            p.addsFailed++;
            return -1;  // no empty entry this time: the add skips, the room keeps its plan
        }
        p.addEntryTaken[empty - 0xF0 >> 5] |= 1u << (empty - 0xF0 & 31);
        p.adds[p.addNext - 1].entry = empty;  // (the made-report names it)
        return empty;
    }
    p.addPhase = 0;
    const Plan::Copy& cp = p.copies[p.copyNext++];
    *out = pG->Em_list[cp.from];
    memcpy(out->pos, cp.at.pos, sizeof(out->pos));
    memcpy(out->rot, cp.at.rot, sizeof(out->rot));
    out->be_flag = 1;
    return cp.entry;
}

// Patch 0231: the copy was created (1) or not (0: dead before, a live one carries its entry, the room is full)
extern "C" void re4_enemy_random_extra_made(int ok)
{
    Plan& p = gPlan;
    if (ok) {
        // (a copy's or an add's enemy was created: the counts tell the room's line's story)
        if (p.addPhase) {
            p.addsMade++;
        } else {
            p.copiesMade++;
        }
        return;
    }
    if (p.addPhase) {
        p.addsFailed++;
        if (gLines < 300) {
            gLines++;
            re4_log("enemies: random enemies in r%03x: an added enemy did not come (dead before, there already or the room full)",
                    p.room);
        }
        return;
    }
    p.copiesFailed++;
    if (gLines < 300) {
        gLines++;
        re4_log("enemies: random enemies in r%03x: an added enemy did not come (dead before, there already or the room full)",
                p.room);
    }
}
