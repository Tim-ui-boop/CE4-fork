// The cheat "Random merchant stock": the merchant sells items drawn from one shared pool, so a weapon that the game
// otherwise keeps for a late chapter (or for a cleared game, like the Handcannon or the Chicago Typewriter) can stand
// at the very first merchant. Compiled with the game's flags into libre4_main; patch 0233 (src/game/merchant.cpp
// Merchant::Merchant) asks it when a shop opens.
//
// Why the shop and not the room: the merchant's stock is part of the game's save data (card.cpp SAVE_MERCHANT,
// game.cpp MerchantDataSave/Load). A game that was started or passed a shop before the switch went on therefore
// carries the untouched stock, and re-dealing at the room start would never show. Dealing the finished stock when
// the shop session is built (ss_shop.cpp `new Merchant(&merchantChar)`) works on whatever the save holds now and
// shows on the very next visit, in old and new games alike.
//
// The pool is the merchant's own price table (merchant.cpp g_item_price_tbl), restricted to what it is sensible to
// sell at any time: weapons, ammunition, grenades, recovery, pesetas and treasures, and weapon parts (item types 1,
// 2, 3, 5, 6, 8, 9, 12). Never keys, files, maps or the case upgrades (types 7, 10, 11): those are story items and
// would be a softlock. The FN57 0x40 (a mission item the game hands out itself) and 0xC5 (never sold) stay out as
// well, as merchant.cpp checkSellingItem has them.
//
// The "new" marks stay meaningful: an item that was not in the stock before this visit is marked new, as the game
// marks what it appended.
//
// With the switch off nothing changes.
#include "types.h"
#include "global.h"
#include "item.h"
#include "merchant.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

extern GLOBAL_WK* pG;

extern "C" void re4_log(const char* fmt, ...);
extern "C" void re4_core_set_merchant_random(int seed);

namespace {

volatile int gSeed;    // 20 bits, 0 off (the app's switch)
int gVisits;           // shops dealt so far (a fresh draw on every visit)
int gAnnounced;        // the pool has been logged once
bool gHooked;          // the game code reached us at least once (a diagnostic line)

// The pool, built once from the price table.
u16 gPool[512];
int gPoolNum = -1;

// Item types that may be sold from the shared pool. Types 7 (files), 10 (files), 11 (keys, maps, case upgrades),
// 13 and 14 never: they are story items.
bool poolType(unsigned t)
{
    switch (t) {
    case 1:   // weapon
    case 2:   // ammunition
    case 3:   // grenade
    case 5:   // treasure
    case 6:   // recovery
    case 8:   // pesetas
    case 9:   // weapon part
    case 12:  // treasure, the second kind (cheats.cpp treasureId)
        return true;
    }
    return false;
}

// Items the game itself keeps out of the shop.
bool blocked(u16 id)
{
    return id == 0x40 || id == 0xC5;
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

void buildPool()
{
    if (gPoolNum >= 0) {
        return;
    }
    gPoolNum = 0;
    for (const PRICE_INFO* p = g_item_price_tbl; p->id != 0xFFFF; p++) {
        if (blocked(p->id) || !poolType(itemType(p->id))) {
            continue;
        }
        if (gPoolNum < (int) (sizeof(gPool) / sizeof(gPool[0]))) {
            gPool[gPoolNum++] = p->id;
        }
    }
    if (!gAnnounced) {
        gAnnounced = 1;
        re4_log("merchant: pool of %d sellable items", gPoolNum);
    }
}

// How many of `id` a shop entry holds: one for a weapon or a part, the game's default count for a stackable.
s16 countFor(u16 id)
{
    const u8 type = itemType(id);
    if (type == 1 || type == 9) {
        return 1;
    }
    const u16 def = itemDefNum(id);
    return def ? (s16) def : 1;
}

int seedNow()
{
    const int s = __atomic_load_n(&gSeed, __ATOMIC_RELAXED);
    if (s) {
        return s;
    }
    // RE4_MERCHANT_RANDOM=N (a check without the app)
    static int test = -1;
    if (test < 0) {
        const char* v = getenv("RE4_MERCHANT_RANDOM");
        test = v && *v ? (int) (strtol(v, 0, 0) & 0xFFFFF) : 0;
    }
    return test;
}

}  // namespace

extern "C" {

// The app (CoreBridge.setMerchantRandom): the switch's seed, 0 off; from the next shop on. Any thread.
void re4_core_set_merchant_random(int seed)
{
    seed &= 0xFFFFF;
    if (__atomic_exchange_n(&gSeed, seed, __ATOMIC_RELAXED) != seed) {
        re4_log("core: random merchant stock %s", seed ? "on (from the next shop)" : "off (from the next shop)");
        if (seed) {
            re4_log("core: random merchant stock seed %d", seed);
        }
    }
}

// Patch 0233, Merchant::Merchant: `e` the session's stock as the save holds it, `max` its slots. Deals the entries
// that are there anew (the count of things on sale stays the same); an empty stock stays empty.
void re4_merchant_random_stock(StockEntry* e, int max)
{
    if (!e || max <= 0) {
        return;
    }
    if (!gHooked) {
        gHooked = true;
        re4_log("merchant: the stock hook is live (patch 0233)");
    }
    const int seed = seedNow();
    if (!seed) {
        return;
    }
    buildPool();
    if (gPoolNum <= 0) {
        return;
    }

    int n = 0;
    while (n < max && e[n].id != 0xFFFF) {
        n++;
    }
    if (n <= 0) {
        re4_log("merchant: stock empty, nothing to deal");
        return;
    }

    // what was on sale before this visit, for the "new" marks
    u16 before[64];
    const int m0 = n < 64 ? n : 64;
    for (int i = 0; i < m0; i++) {
        before[i] = e[i].id;
    }

    const u32 room = pG ? (u32) pG->room_id : 0u;
    u32 h = mix((u32) seed * 0x9E3779B1u ^ mix(room << 8 ^ (u32) (++gVisits)));

    // A shuffle of the pool (Fisher-Yates from the seed): distinct ids, so the shop keeps its length
    static u16 order[512];
    const int m = gPoolNum;
    for (int i = 0; i < m; i++) {
        order[i] = gPool[i];
    }
    for (int i = m - 1; i > 0; i--) {
        h = mix(h + (u32) i);
        const int j = (int) (h % (u32) (i + 1));
        const u16 t = order[i];
        order[i] = order[j];
        order[j] = t;
    }

    int fresh = 0;
    for (int i = 0; i < n; i++) {
        const u16 id = order[i % m];
        e[i].id = id;
        e[i].num = countFor(id);
        bool had = false;
        for (int k = 0; k < m0; k++) {
            if (before[k] == id) {
                had = true;
                break;
            }
        }
        e[i].isNew = had ? 0 : 1;
        if (!had) {
            fresh++;
        }
    }
    // ids of the same table must not repeat in a way stockPtr would merge: the shuffle above is distinct, so the
    // entries stay distinct (n <= the pool's size in every real stock, 64 <= the pool).
    re4_log("merchant: r%03x stock dealt: %d entries, %d new (seed %d, visit %d)", (int) room, n, fresh, seed,
            gVisits);
}

}  // namespace
