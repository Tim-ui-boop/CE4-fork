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
#include "types.h"
#include "global.h"
#include "em.h"
#include "sce_at.h"
#include "atari.h"

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

// An ordinary Ganado: no special form (t_emlist.cpp: chainsaw "EM16 C.S." type 4 of 0x10..0x17 and "EM1F Chain Saw"
// type 22 or the chainsaw flag of 0x20, "EM18 Trader" type 6, "EM1C ClawMan" 0x1B type 10 and 0x1C types 10, 13,
// "Gatling Man" 0x1D type 2)
bool ordinary(const EM_LIST& e)
{
    if (!ganadoSpot(e)) {
        return false;
    }
    if (e.id <= 0x17 && e.type == 4) {
        return false;
    }
    if (e.type == 6 && (e.id == 0x13 || e.id == 0x14 || e.id == 0x1E)) {
        return false;
    }
    if ((e.id == 0x1B && e.type == 10) || (e.id == 0x1C && (e.type == 10 || e.type == 13))) {
        return false;
    }
    if (e.id == 0x1D && e.type == 2) {
        return false;
    }
    if (e.id == 0x20 && (e.type == 22 || (e.flag & (1u << 28)))) {
        return false;
    }
    return true;
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
    int movers, swapped, nearby, spots, protectedByArea, copyRefused;
    // 0.99.16, the PC randomizer's kinds: the pool kinds this room rolls, their count bounded by varietyCap();
    // `swapKind[entry]` -1 none, else the kPoolKinds index the entry becomes; `kindSwaps` the ones changed by kind
    int rolledKind[kPoolCount];
    int rolledCount;
    int swapKind[256];
    int kindSwaps;
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
    if (nm == 0) {
        if (gLines < 300) {
            gLines++;
            re4_log("enemies: random enemies in r%03x (list %d, seed %u): no ordinary Ganado to change", p.room, p.list,
                    p.seed);
        }
        return;
    }
    p.on = true;
    // the kinds this room may hand out (see the roll below); no entry's kind yet
    for (int i = 0; i < 256; i++) {
        p.swapKind[i] = -1;
    }
    // 0.99.16: the kinds this room may hand out (the PC randomizer's replacements pool + its variety limit). The
    // roll is a hash of the seed, list and room, so the same run always rolls the same kinds; sub-boss kinds
    // (chainsaw, Gatling, Garrador) stay rare: at most one of the room's rolls may pick one.
    const int pool = __atomic_load_n(&gPool, __ATOMIC_RELAXED);
    p.rolledCount = 0;
    p.kindSwaps = 0;
    // a room that counts its enemies by kind keeps the kinds it counts: places move, kinds never. A family-wide
    // count ("all Ganados dead", 0x10..0x20) survives a form change inside the family, so in unsafe mode its
    // unnamed Ganados may become chainsaw, Gatling or Garrador forms -- the pool kinds that are family members;
    // never a dog or an Armadura (an outsider: the count would never reach zero). A narrow count pins its kinds
    // always. A room unsafe mode opened from "untouched" takes the family rule too (its script may count).
    const bool kindsMayChange = p.kind == kOpen
        || (unsafe && (p.kind == kCountsFamily || p.kind == kOpenCautioned));
    const bool familyOnly = p.kind == kCountsFamily || p.kind == kOpenCautioned;
    if (pool && kindsMayChange) {
        const int cap = varietyCap();
        bool subBossTaken = false;
        for (int draws = 0; draws < 48 && p.rolledCount < cap; draws++) {
            const int pick = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x500u + (u32) draws) % (u32) kPoolCount);
            if (!(pool >> pick & 1)) {
                continue;
            }
            // a counting room rolls only family members (chainsaw, Gatling, Garrador): the count still holds
            if (familyOnly && pick > 2) {
                continue;
            }
            bool have = false;
            for (int k = 0; k < p.rolledCount; k++) {
                have |= p.rolledKind[k] == pick;
            }
            // sub-bosses (chainsaw, Gatling, Garrador) and the Krausers: at most one of them a room
            const bool subBoss = pick <= 2 || pick >= 10;
            if (have || (subBoss && subBossTaken)) {
                continue;
            }
            if (subBoss) subBossTaken = true;
            p.rolledKind[p.rolledCount++] = pick;
        }
        if (gLines < 300 && p.rolledCount == 0) {
            gLines++;
            re4_log("enemies: random enemies r%03x: the pool rolled no kind", p.room);
        }
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
            p.swapKind[no] = p.rolledKind[pick];
            p.kindSwaps++;
        }
        // 0.99.16: the animals (the owner's wish of 17.11.2026): a Crow or Cow of the room may become a kind of
        // the pool too -- each rolls on its own hash, at the sub-boss odds, so a field of crows is not a
        // Regenerator parade; the counting grades keep the kinds their scripts count.
        if (p.rolledCount > 0 && animalSpot(pG->Em_list[no]) && comesNow(pG->Em_list[no], no)
            && !namedIn(row, no) && !waited[no] && p.kind == kOpen) {
            const int ratios = __atomic_load_n(&gRatios, __ATOMIC_RELAXED);
            if (ratioHit(ratios, 12, hashOf(p.seed, (u32) p.list, (u32) p.room, 0x610u + (u32) no))) {
                p.swapKind[no] = p.rolledKind[hashOf(p.seed, (u32) p.list, (u32) p.room, 0x618u + (u32) no)
                                               % (u32) p.rolledCount];
                p.kindSwaps++;
            }
        }
        if (comesNow(pG->Em_list[no], no)) {
            now[nn++] = no;
        } else if (idle < 0) {
            idle = no;
        }
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
    if (gCheck) {
        memcpy(gBefore, pG->Em_list, sizeof(gBefore));
        gChecking = true;
    }
    if (gLines < 300 && (p.kindSwaps > 0)) {
        gLines++;
        re4_log("enemies: random enemies r%03x: %d kinds rolled, %d of %d entries change kind", p.room, p.rolledCount,
                p.kindSwaps, p.movers);
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

// Patch 0234, EmSetFromList2 for entry `no` (a script creates it on demand -- the cabin's siege, an ambush): the wave
// entries the room's start entries also get, minus the places: a wave's place is the script's own, so only a kind
// ever changes, and only for an ordinary Ganado (never a script-named entry, never a counted kind, never a special
// form). -1 not at all, 0 as it is, 1 created from `out`.
extern "C" int re4_enemy_random_wave(int no, EM_LIST* out)
{
    Plan& p = gPlan;
    if (!p.on || no < 0 || no > 255) {
        return 0;  // no plan (the switch off, or the room protected): the entry comes exactly as the game wants
    }
    const EM_LIST& e = pG->Em_list[no];
    // a script wants this enemy and made it alive now (EmListSetAlive 1); not created, not dead
    if (!ordinary(e) || (e.be_flag & 2) || deadBit(no) || !(e.be_flag & 1)) {
        return 0;
    }
    const RoomRow* r = rowOf(p.room);
    if (namedIn(r, no) || (p.kind != kOpen && !(unsafeOn() && p.kind == kOpenCautioned))) {
        return 0;  // a room that counts or names its enemies keeps the kinds its script expects; unsafe mode opens
                   // the cautious rooms' waves too, with the family rule below
    }
    *out = e;
    // the same kind roll the start entries get, on its own hash so a wave and a start entry never decide each other
    if (p.rolledCount > 0) {
        int pick = (int) (hashOf(p.seed, (u32) p.list, (u32) p.room, 0x680u + (u32) no) % (u32) p.rolledCount);
        // a room that counts (its script may count the wave) stays inside the family with its picks
        if ((p.kind == kCountsFamily || p.kind == kOpenCautioned) && p.rolledKind[pick] > 2) {
            return 0;  // no family kind rolled for this room's wave: the entry stays as it is
        }
        p.swapKind[no] = p.rolledKind[pick];
        p.kindSwaps++;
        const Kind& to = kPoolKinds[p.swapKind[no]];
        out->id = to.id;
        out->type = to.type;
        out->set = to.set;
        out->flag = 0;
        out->Character = 0;
        if (to.minHp > out->hp) out->hp = to.minHp;
        return 1;
    }
    // no kind rolled: the wave Ganado still takes the ratios' mix like any other that stays its own kind
    if (p.kind == kOpen) {
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

// Patch 0231, EmSetFromList's end, again and again until -1: the next copy (a Ganado of the room that came now, at its
// safe place beside another one) and the empty entry its death is kept at. The patch creates it unless that entry is
// marked dead or a live enemy carries it already. The first call writes the room's line.
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
                "%d at a new place nearby, %d on another's spot%s%s",
                p.room, p.list, p.seed, p.kind == kCountsNarrow ? ", counts only" :
                    p.kind == kCountsFamily ? ", counts family-wide" : p.kind == kOpenCautioned ? ", unsafe mode" : "",
                p.nearby, p.swapped, count, p.protectedByArea ? " (some kept: an area waits for them)" : "");
    }
    if (p.copyNext >= p.copyCount) {
        // (after the copies: their entries are empty ones, whose be_flag the game may set; the check sees the rest)
        checkList();
        p.copyNext = kMaxCopies + 1;  // the line is written once
        return -1;
    }
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
        p.copiesMade++;
        return;
    }
    p.copiesFailed++;
    if (gLines < 300) {
        gLines++;
        re4_log("enemies: random enemies in r%03x: a copy did not come (dead before, there already or the room full)",
                p.room);
    }
}
