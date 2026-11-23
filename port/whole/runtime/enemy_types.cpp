// The enemy TYPE randomizer ("Randomize enemies" of the PC RE4 Randomizer, adapted; the project owner's wish of
// 22.11.2026: "adapt the PC version enemy randomizer and its toggles/settings control for enemies as much as possible").
// Compiled with the game's flags into libre4_main; patch 0255 (src/game/em_set.cpp EmSetFromList) asks it.
//
// Where "Random enemies" (runtime/enemy_random.cpp, patch 0231) moves the room's Ganados to each other's places and
// changes their number, this one changes WHAT they are: the model variant (`type`), the weapon and clothing bits
// (`flag`), and — within the Ganado family — the enemy kind (`id`) itself. So a village Ganado can come as Dr. Salvador
// with the saw, an island soldier as JJ with the minigun.
//
// Why it is safe (port/docs/enemy-type-randomizer-research.md, measured on the disc and in this source):
//   - the game loads at most 4 enemy modules at once (EmReadModule[4], read.cpp:78) and clears them on every door
//     transition (game.cpp:1259, gameDoordemo Rno0 == 4). The budget is per room visit. A deal that would need a 5th
//     module is refused entry by entry, never applied: pullEmModule has no eviction, a 5th kind is silently missing
//     ("Em set failed", em_set.cpp:171) and a door that waits for a death would never open.
//   - the room scripts count and sweep by KIND (SceCountEmAlive(0x10, 0x20), sce_com.cpp:398: `em->id >= lo &&
//     em->id <= hi`). Every kind this deals is inside 0x10..0x20, so a swap keeps the count the script sees. No room
//     script reads Em_list[].type or Em_list[].flag (0 occurrences in src/st1, src/st2, src/st3), so the model and the
//     weapon may change freely.
//   - `set` (a scripted entrance: "R11C 1F IN", "R300 Take Ashley"), `room`, `pos`, `rot`, `hp`, `Character`, `Guard_r`
//     and `be_flag` are NEVER written. The enemy stands where the disc put it and the room's own script still drives it.
//   - entries the room's script names or an area waits for are left alone (the same protection table as patch 0231,
//     enemy_random_rooms.inc), and so are the special forms `ordinary()` of enemy_random.cpp turns down: the chainsaw
//     types, the claw men, the gatling man, the trader.
//
// The PC tool's own rules, adopted (port/docs/pc-randomizer-decompile-findings.md; its tooltips and default profile):
//   - sub-bosses are SALTED IN at a ratio, never a wholesale replacement: Dr. Salvador 1:12 of the villagers,
//     Garrador 1:8 of the zealots, JJ 1:2 of the island soldiers ("a 1:9 ratio ... for 9 regular villagers there will
//     be a Dr. Salvador"). The ratio is the denominator: 12 means one in twelve.
//   - family matching: villagers <-> Salvador, zealots <-> Garrador, island soldiers <-> JJ / super Salvador. A village
//     room never gets a JJ unless the player asks for the mixed pool.
//   - max enemy types per room: the PC default is 3 ("values greater than 6 can be unstable"). Here the hard ceiling is
//     the game's 4 modules, and one is kept free for Ashley/Luis, so the default is 3.
//   - Luis's cabin (r11c/r11d): no Gigante, Regenerator, Iron Maiden or Dr. Salvador — they kill Luis and the story
//     stops. Only the saw is dealt there, and only when the player turns the cabin's own switch on.
//   - the first Don José (r100's chainsaw encounter) is a deliberate single substitution, not a random roll.
//
// With the switch off nothing changes; the next room is read from the disc again. The list itself (saved with the game)
// is never written: every enemy is created from a COPY of its entry, so a save plays with the switch on or off alike.

#include "types.h"
#include "global.h"
#include "em.h"
#include "sce_at.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern GLOBAL_WK* pG;

extern "C" void re4_log(const char* fmt, ...);
extern "C" void re4_core_set_enemy_types(int seed);
extern "C" void re4_core_set_enemy_options(unsigned bits);
extern "C" void re4_enemy_types_room(void);
extern "C" int re4_enemy_types_entry(int no, EM_LIST* out);

namespace {

// --- the options the app sends (CoreBridge.setEnemyOptions), one bit each -------------------------
// Kept in one word so a core of an older build simply ignores the command (kSetEnemyOptions).
enum {
    kSawVillagers = 1u << 0,   // Dr. Salvador among the villagers (the PC's salvadorToVillagerRatio)
    kGarradors = 1u << 1,      // Garrador among the zealots (garradorToZealotRatio)
    kJJ = 1u << 2,             // JJ among the island soldiers (jjToMilitaryRatio)
    kSuperSalvador = 1u << 3,  // super Salvador among the island soldiers
    kVariety = 1u << 4,        // other model variants of the room's own kinds (cosmetic)
    kHelmets = 1u << 5,        // bulletproof helmets (helmetRatio)
    kShields = 1u << 6,        // wooden shields, zealots and island soldiers only (shieldRatio)
    kPlagas = 1u << 7,         // a plaga on critical damage (plagaRatio)
    kDynamite = 1u << 8,       // dynamite in hand (the PC's dynamite villagers)
    kMixedPool = 1u << 9,      // any family anywhere (off: family matched, the PC's default)
    kSafeCabin = 1u << 10,     // Luis's cabin kept safe (ON by default; off lets the saw in)
    kDonJose = 1u << 11,       // replace the first Don José in r100's house
};
const unsigned kDefaultOptions = kSawVillagers | kVariety | kSafeCabin;

volatile int gSeed;          // 20 bits, 0 off (the app's switch)
volatile unsigned gOptions;  // the bits above
bool gOptionsSet;            // the app sent them once (else the defaults)
int gTestSeed = -1;          // RE4_ENEMY_TYPES (the offline check)

int seedNow()
{
    if (gTestSeed < 0) {
        const char* v = getenv("RE4_ENEMY_TYPES");
        gTestSeed = v && *v ? (int) (strtol(v, 0, 0) & 0xFFFFF) : 0;
        if (gTestSeed) {
            re4_log("enemy types: seed %d (RE4_ENEMY_TYPES)", gTestSeed);
        }
    }
    const int s = __atomic_load_n(&gSeed, __ATOMIC_RELAXED);
    return s ? s : gTestSeed;
}

unsigned optionsNow()
{
    return gOptionsSet ? __atomic_load_n(&gOptions, __ATOMIC_RELAXED) : kDefaultOptions;
}

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

// One in `den` (the PC's ratio: 12 means one in twelve). 0: never.
bool oneIn(u32 h, int den)
{
    return den > 0 && h % (u32) den == 0;
}

// --- the room protection table (the same one patch 0231 uses) -------------------------------------
enum { kOpen = 0, kUntouched = 1, kPlacesOnly = 2 };

struct RoomRow {
    u16 room;
    u8 kind;
    u32 named[8];
};

const RoomRow kRooms[] = {
#include "enemy_random_rooms.inc"
};

const RoomRow* rowOf(int room)
{
    for (unsigned i = 0; i < sizeof(kRooms) / sizeof(kRooms[0]); i++) {
        if (kRooms[i].room == (u16) room) {
            return &kRooms[i];
        }
    }
    return 0;
}

bool namedIn(const RoomRow* row, int no)
{
    return row && (row->named[no >> 5] >> (no & 31) & 1);
}

// --- the Ganado families and the forms this may deal ----------------------------------------------
// The kinds of the game's own list editor (src/t_emlist/t_emlist.cpp EmListIdTbl): 0x10 Ganado 1, 0x11 Ganado 2,
// 0x12 Ganado 1-c, 0x13 Ganado 1+w, 0x14 Ganado 2+w, 0x15 Ganado 1+c, 0x16 Ganado 1+c2, 0x17 Ganado 1-c2,
// 0x18 Trader (never an enemy), 0x19 Ganado 2+A, 0x1A Ganado 2+R, 0x1B Ganado 2+cm, 0x1C Ganado 2+cm2,
// 0x1D Ganado 3+GM (JJ, the gatling man), 0x1E Ganado 3+W, 0x1F Ganado 3, 0x20 Ganado 3+G+C (super Salvador).
enum { kVillage = 0, kCastle = 1, kIsland = 2, kNoFamily = -1 };

int familyOf(int id)
{
    switch (id) {
    case 0x10: case 0x12: case 0x13: case 0x15: case 0x16: case 0x17:
        return kVillage;
    case 0x11: case 0x14: case 0x19: case 0x1A: case 0x1B: case 0x1C:
        return kCastle;
    case 0x1D: case 0x1E: case 0x1F: case 0x20:
        return kIsland;
    default:
        return kNoFamily;
    }
}

bool ganadoId(int id)
{
    return id >= 0x10 && id <= 0x20 && id != 0x18;
}

// The model types each module can show: the `case N:` labels of its EmNNSet switch
// (src/em10/em10_set.cpp:52,98,142,186 and the others; port/docs/enemy-type-randomizer-research.md §4).
// An unknown type is forced to the module's default (em15_set.cpp:46), so a type outside this set is a
// visual fallback rather than a crash — but only these are dealt.
struct TypeRow {
    u8 id;
    u8 types[9];
    u8 n;
};

const TypeRow kTypes[] = {
    {0x10, {0, 1, 3, 4}, 4},
    {0x11, {7, 8, 9}, 3},
    {0x12, {0, 1, 3, 4}, 4},
    {0x13, {0, 1, 3, 4, 6}, 5},
    {0x14, {6, 7, 8, 9}, 4},
    {0x15, {0, 3, 4, 11}, 4},
    {0x16, {3, 4, 11, 12}, 4},
    {0x17, {0, 1, 3, 12}, 4},
    {0x19, {7, 8, 9}, 3},
    {0x1A, {7, 8, 9}, 3},
    {0x1B, {7, 8, 10}, 3},
    {0x1C, {7, 9, 10, 13}, 4},
    {0x1D, {2, 14, 15, 16, 17, 18, 19, 20, 21}, 9},
    {0x1E, {6, 14, 15, 23, 25}, 5},
    {0x1F, {14, 15, 16, 24}, 4},
    {0x20, {14, 15, 16, 22}, 4},
};

const TypeRow* typesOf(int id)
{
    for (unsigned i = 0; i < sizeof(kTypes) / sizeof(kTypes[0]); i++) {
        if (kTypes[i].id == (u8) id) {
            return &kTypes[i];
        }
    }
    return 0;
}

bool typeOk(int id, int type)
{
    const TypeRow* r = typesOf(id);
    if (!r) {
        return false;
    }
    for (int i = 0; i < r->n; i++) {
        if (r->types[i] == (u8) type) {
            return true;
        }
    }
    return false;
}

// The village modules carry the saw: Em10WeaponSet (em10_set.cpp:268-273) loads mot[67]/mot[68] for EVERY
// model type, and em10.cpp:18082 equips it when flag bit 28 is set. So a chainsaw Ganado needs no extra module.
bool sawCapable(int id)
{
    return id == 0x10 || id == 0x12 || id == 0x13 || id == 0x15 || id == 0x16 || id == 0x17;
}

// The flag bits of the game's own editor (t_emlist.cpp em10_ganado_1_flag / em11_ganado_2_flag).
const u32 kFlagSaw = 1u << 28;      // "Weapon ChainSaw"
const u32 kFlagPlaga = 1u << 20;    // "Parasite on"
const u32 kFlagBomb = 1u << 17;     // "Weapon Bomb" (dynamite)
const u32 kFlagHelmet = 1u << 9;    // "Hat on" / "Mask of Gold": the covered head
const u32 kFlagShield = 1u << 31;   // "Shield" (em11's bit 31; zealots and island soldiers only)
const u32 kWeaponMask = kFlagSaw | kFlagBomb | (1u << 27) | (1u << 29) | (1u << 30) | (1u << 31) | (1u << 11);

// A special form the room's script or its own module drives: never touched (the same list `ordinary()` of
// enemy_random.cpp turns down, t_emlist.cpp's type names).
bool specialForm(const EM_LIST& e)
{
    if (e.id <= 0x17 && e.type == 4) {
        return true;  // the chainsaw type ("EM16 C.S.")
    }
    if (e.type == 6 && (e.id == 0x13 || e.id == 0x14 || e.id == 0x1E)) {
        return true;
    }
    if ((e.id == 0x1B && e.type == 10) || (e.id == 0x1C && (e.type == 10 || e.type == 13))) {
        return true;  // the claw men (Garrador)
    }
    if (e.id == 0x1D && e.type == 2) {
        return true;  // the gatling man (JJ)
    }
    if (e.id == 0x20 && (e.type == 22 || (e.flag & kFlagSaw))) {
        return true;  // super Salvador
    }
    return false;
}

bool deadBit(int no)
{
    if (pG->em_list_no < 0) {
        return false;
    }
    const u32* row = EM_FLG_ROW(pG->em_list_no);
    return (row[no >> 5] & (0x80000000u >> (no & 31))) != 0;
}

bool comesNow(const EM_LIST& e, int no)
{
    return (e.be_flag & 1) && !(e.be_flag & 2) && !deadBit(no);
}

// Luis's cabin: the story stops when Luis dies, and the PC tool keeps these out for that reason.
bool cabinRoom(int room)
{
    return room == 0x11C || room == 0x11D;
}

// --- the room's plan -----------------------------------------------------------------------------
struct Deal {
    u8 id, type;
    u32 setFlags, clearFlags;
};

struct Plan {
    bool on;
    int room, list, kind;
    u32 seed;
    unsigned options;
    bool changed[256];
    Deal deal[256];
    int count;
    int kinds[4];      // the modules the room needs after the deal (the game's 4 slots)
    int kindCount;
    int saws, garradors, jjs, supers, variants, helmets, shields, plagas, bombs;
    int refusedBudget, refusedNamed, refusedSpecial;
    char why[96];
};
Plan gPlan;
unsigned gLines;

bool kindKnown(const Plan& p, int id)
{
    for (int i = 0; i < p.kindCount; i++) {
        if (p.kinds[i] == id) {
            return true;
        }
    }
    return false;
}

// The room may load at most 4 modules (read.cpp:78) and one is kept free for Ashley/Luis, so 3 by default
// (the PC tool's own "max. enemy types per room" default). A kind already in the room costs nothing.
bool budgetOk(Plan& p, int id)
{
    if (kindKnown(p, id)) {
        return true;
    }
    const int limit = 3;
    if (p.kindCount >= limit) {
        p.refusedBudget++;
        return false;
    }
    p.kinds[p.kindCount++] = id;
    return true;
}

// What this entry becomes. Returns false to leave it exactly as the disc has it.
bool dealFor(Plan& p, int no, const EM_LIST& e, Deal* out)
{
    const unsigned opt = p.options;
    const int fam = familyOf(e.id);
    const u32 h = hashOf(p.seed, (u32) p.list, (u32) p.room, 0x900u + (u32) no);
    const bool cabin = cabinRoom(p.room);
    Deal d;
    d.id = e.id;
    d.type = e.type;
    d.setFlags = 0;
    d.clearFlags = 0;

    // 1. the sub-boss of the entry's own family, at the PC tool's ratio (one in N)
    if (!cabin || !(opt & kSafeCabin)) {
        if (fam == kVillage && (opt & kSawVillagers) && sawCapable(e.id) && oneIn(h, 12)) {
            // Dr. Salvador: the saw on the village Ganado's own module (no new kind, no new module)
            d.setFlags = kFlagSaw;
            d.clearFlags = kWeaponMask & ~kFlagSaw;
            d.type = 4;  // the chainsaw type: the real saw model (em10_set.cpp:268)
            p.saws++;
        } else if (fam == kCastle && (opt & kGarradors) && (e.id == 0x1B || e.id == 0x1C) && oneIn(h, 8)) {
            // Garrador: the claw man of the zealot's own module (0x1B type 10, 0x1C type 10 or 13)
            d.type = 10;
            d.clearFlags = kWeaponMask;
            p.garradors++;
        } else if (fam == kIsland && (opt & kJJ) && e.id != 0x1D && oneIn(h, 2)) {
            // JJ: the gatling man is a kind of its own (0x1D), so it costs a module slot
            if (!budgetOk(p, 0x1D)) {
                return false;
            }
            d.id = 0x1D;
            d.type = 2;
            d.clearFlags = kWeaponMask;
            p.jjs++;
        } else if (fam == kIsland && (opt & kSuperSalvador) && e.id != 0x20 && oneIn(h, 8)) {
            if (!budgetOk(p, 0x20)) {
                return false;
            }
            d.id = 0x20;
            d.type = 22;
            d.setFlags = kFlagSaw;
            d.clearFlags = kWeaponMask & ~kFlagSaw;
            p.supers++;
        }
    }
    // the mixed pool: any family anywhere, only when the player asks for it
    if ((opt & kMixedPool) && d.id == e.id && !d.setFlags && fam != kNoFamily && !cabin) {
        if (sawCapable(e.id) && (opt & kSawVillagers) && oneIn(h ^ 0x5Au, 20)) {
            d.setFlags = kFlagSaw;
            d.clearFlags = kWeaponMask & ~kFlagSaw;
            d.type = 4;
            p.saws++;
        }
    }
    // 2. another model variant of the room's own kind (cosmetic; the PC's "Cosmetic 1..14")
    if ((opt & kVariety) && d.id == e.id && !d.setFlags && !d.clearFlags && d.type == e.type) {
        const TypeRow* r = typesOf(e.id);
        if (r && r->n > 1) {
            // never into a special form: the chainsaw type 4, the claw type 10/13, the gatling type 2
            int pick[9], n = 0;
            for (int i = 0; i < r->n; i++) {
                const int t = r->types[i];
                if (t == e.type) {
                    continue;
                }
                EM_LIST probe = e;
                probe.type = (u8) t;
                if (!specialForm(probe)) {
                    pick[n++] = t;
                }
            }
            if (n > 0 && oneIn(h ^ 0x11u, 2)) {
                d.type = (u8) pick[(h >> 8) % (u32) n];
                p.variants++;
            }
        }
    }
    // 3. the equipment bits (the PC's helmet / shield / plaga / dynamite ratios)
    if (d.id == e.id && !d.setFlags) {
        if ((opt & kHelmets) && oneIn(h ^ 0x21u, 10)) {
            d.setFlags |= kFlagHelmet;
            p.helmets++;
        }
        if ((opt & kShields) && (fam == kCastle || fam == kIsland) && oneIn(h ^ 0x31u, 7)) {
            d.setFlags |= kFlagShield;
            p.shields++;
        }
        if ((opt & kPlagas) && oneIn(h ^ 0x41u, 5) && !(d.setFlags & kFlagHelmet)) {
            // the PC's note: a covered head cannot spawn a plaga, the helmet has priority
            d.setFlags |= kFlagPlaga;
            p.plagas++;
        }
        if ((opt & kDynamite) && fam == kVillage && oneIn(h ^ 0x51u, 12)) {
            d.setFlags |= kFlagBomb;
            p.bombs++;
        }
    }
    if (d.id == e.id && d.type == e.type && !d.setFlags && !d.clearFlags) {
        return false;
    }
    if (!typeOk(d.id, d.type)) {
        return false;
    }
    *out = d;
    return true;
}

}  // namespace

// The app (CoreBridge.setEnemyTypes): the switch's seed, 0 off; from the next room on. Any thread.
extern "C" void re4_core_set_enemy_types(int seed)
{
    seed &= 0xFFFFF;
    if (__atomic_exchange_n(&gSeed, seed, __ATOMIC_RELAXED) != seed) {
        re4_log("core: enemy types %s", seed ? "on (from the next room)" : "off (from the next room)");
        if (seed) {
            re4_log("core: enemy types seed %d", seed);
        }
    }
}

// The app (CoreBridge.setEnemyOptions): the option bits, one word. Any thread.
extern "C" void re4_core_set_enemy_options(unsigned bits)
{
    __atomic_store_n(&gOptions, bits, __ATOMIC_RELAXED);
    gOptionsSet = true;
    re4_log("core: enemy options %04x (saw %d garrador %d jj %d super %d variety %d helmet %d shield %d plaga %d "
            "dynamite %d mixed %d safeCabin %d donJose %d)",
            bits, (bits & kSawVillagers) != 0, (bits & kGarradors) != 0, (bits & kJJ) != 0,
            (bits & kSuperSalvador) != 0, (bits & kVariety) != 0, (bits & kHelmets) != 0, (bits & kShields) != 0,
            (bits & kPlagas) != 0, (bits & kDynamite) != 0, (bits & kMixedPool) != 0, (bits & kSafeCabin) != 0,
            (bits & kDonJose) != 0);
}

// Patch 0255, EmSetFromList's start (after patch 0231's own room plan): what the room's enemies become.
extern "C" void re4_enemy_types_room(void)
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
    p.options = optionsNow();
    const RoomRow* row = rowOf(p.room);
    p.kind = row ? row->kind : kUntouched;
    if (SysFlagChk(pG, SYS_OMAKE_ADA_GAME) || SysFlagChk(pG, SYS_OMAKE_ETC_GAME) || pG->pl_type == 1) {
        snprintf(p.why, sizeof(p.why), "not in the story's Leon part");
    } else if (pG->stage_no < 1 || pG->stage_no > 3 || p.list < 0 || p.list > 7) {
        snprintf(p.why, sizeof(p.why), "not a story room of stages 1..3");
    } else if (!row) {
        snprintf(p.why, sizeof(p.why), "no room script known");
    } else if (p.kind == kUntouched) {
        snprintf(p.why, sizeof(p.why), "a boss or a kept room");
    }
    char roomText[16];
    snprintf(roomText, sizeof(roomText), "r%03x", p.room);
    if (p.why[0]) {
        if (gLines < 300) {
            gLines++;
            re4_log("enemy types: leave %s as it is (%s)", roomText, p.why);
        }
        return;
    }
    p.on = true;
    // the entries an area waits for (a door or an item that comes with a death): their kind must stay
    bool waited[256] = {};
    for (SCE_AT_DATA* w = sceAtSetOtStart(); (w = sceAtGetOtAddr(w)) != 0;) {
        if (w->waiting_type == 1) {
            waited[w->waiting_no] = true;
        }
    }
    // the modules the room already needs: every entry of this room, alive or not (a later wave loads its kind too)
    for (int no = 0; no < 256; no++) {
        const EM_LIST& e = pG->Em_list[no];
        if (e.id && e.room == (u16) p.room && (e.be_flag & 1) && !kindKnown(p, e.id)) {
            if (p.kindCount < 4) {
                p.kinds[p.kindCount++] = e.id;
            }
        }
    }
    const int baseKinds = p.kindCount;
    for (int no = 0; no < 256; no++) {
        const EM_LIST& e = pG->Em_list[no];
        if (!ganadoId(e.id) || e.room != (u16) p.room || !comesNow(e, no)) {
            continue;
        }
        if (namedIn(row, no) || waited[no]) {
            p.refusedNamed++;
            continue;
        }
        if (specialForm(e)) {
            p.refusedSpecial++;
            continue;
        }
        Deal d;
        if (!dealFor(p, no, e, &d)) {
            continue;
        }
        p.changed[no] = true;
        p.deal[no] = d;
        p.count++;
    }
    if (gLines < 300) {
        gLines++;
        re4_log("enemy types: %s seed %u dealt %d of the room's Ganados (saw %d garrador %d jj %d super %d "
                "variant %d helmet %d shield %d plaga %d dynamite %d), kinds %d->%d, kept %d named, %d special, "
                "%d over the module budget",
                roomText, p.seed, p.count, p.saws, p.garradors, p.jjs, p.supers, p.variants, p.helmets, p.shields,
                p.plagas, p.bombs, baseKinds, p.kindCount, p.refusedNamed, p.refusedSpecial, p.refusedBudget);
    }
}

// Patch 0255, EmSetFromList for entry `no`: 0 as the disc has it, 1 created from `out` (a copy with another
// kind, model or weapon). The list itself is never written, so the save stays a normal one.
extern "C" int re4_enemy_types_entry(int no, EM_LIST* out)
{
    Plan& p = gPlan;
    if (!p.on || no < 0 || no > 255 || !p.changed[no]) {
        return 0;
    }
    const Deal& d = p.deal[no];
    *out = pG->Em_list[no];
    out->id = d.id;
    out->type = d.type;
    out->flag = (out->flag & ~d.clearFlags) | d.setFlags;
    // set, room, pos, rot, hp, Character, Guard_r and be_flag stay the disc's own
    return 1;
}
