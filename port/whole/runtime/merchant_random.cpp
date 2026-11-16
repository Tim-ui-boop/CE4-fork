// The cheat "Random merchant stock": the merchant sells items drawn from one shared pool, so a weapon that the game
// otherwise keeps for a late chapter (or for a cleared game, like the Handcannon or the Chicago Typewriter) can stand
// at the very first merchant. Compiled with the game's flags into libre4_main; patch 0233 (src/game/merchant.cpp
// stockDataAdd) asks it.
//
// The merchant builds his stock by merging small tables into it, one table per room and stage
// (MerchantGameInit, MerchantRoomInit, Merchant2ndRoundInit). Each table is a list of {item id, count} ending in
// 0xFFFF. With the switch on (a seed of its own, re4_core_set_merchant_random) every table is replaced by one of the
// same length whose ids are drawn from the shared pool; the counts follow the game's own defaults per item
// (item.cpp itemInfo defNum), so a stackable arrives in the amount it usually does.
//
// The pool is the merchant's own price table (merchant.cpp g_item_price_tbl), restricted to what it is sensible to
// sell at any time: weapons, ammunition, grenades, recovery, pesetas and treasures (item types 1, 2, 3, 5, 6, 8, 9
// and 12). Never keys, files, maps or the case upgrades (types 7, 10, 11): those are story items and would be a
// softlock. The FN57 0x40 (a mission item the game hands out itself) and 0xC5 (never sold) stay out as well, as
// checkSellingItem has them.
//
// The "new" mark of the shop is not touched: stockDataAdd sets isNew on what it appends, so an item that really is
// new to the stock still lights up exactly as in the untouched game.
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

volatile int gSeed;  // 20 bits, 0 off (the app's switch)

// The pool, built once from the price table.
u16 gPool[512];
int gPoolNum = -1;

// Item types that may be sold from the shared pool. Types 7 (files), 10 (files), 11 (keys, maps, case upgrades),
// 13 and 14 never, whatever the price table holds: they are story items.
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

u32 hashOf(u32 seed, u32 room, u32 what)
{
    return mix(seed * 0x9E3779B1u ^ mix(room << 8 ^ mix(what + 0x51)));
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
}

int tableLen(const StockEntry* t)
{
    int n = 0;
    while (n < 64 && t[n].id != 0xFFFF) {
        n++;
    }
    return n;
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
    // RE4_MERCHANT_RANDOM=N (the PC check): the seed without the app
    static int test = -1;
    if (test < 0) {
        const char* v = getenv("RE4_MERCHANT_RANDOM");
        test = v && *v ? (int) (strtol(v, 0, 0) & 0xFFFFF) : 0;
    }
    return test;
}

int gLines;

void logLine(const char* fmt, int a, int b, int c)
{
    if (gLines < 200) {
        gLines++;
        re4_log(fmt, a, b, c);
    }
}

}  // namespace

extern "C" {

// The app (CoreBridge.setMerchantRandom): the switch's seed, 0 off; from the next room on. Any thread.
void re4_core_set_merchant_random(int seed)
{
    seed &= 0xFFFFF;
    if (__atomic_exchange_n(&gSeed, seed, __ATOMIC_RELAXED) != seed) {
        re4_log("core: random merchant stock %s", seed ? "on (from the next room)" : "off (from the next room)");
        if (seed) {
            re4_log("core: random merchant stock seed %d", seed);
        }
    }
}

// Patch 0233, stockDataAdd: `tbl` the table the game is about to merge, `out` a buffer of `max` entries. Returns
// `tbl` as it is (switch off, empty table, no pool) or `out` with the drawn ids.
StockEntry* re4_merchant_random(StockEntry* tbl, StockEntry* out, int max)
{
    const int seed = seedNow();
    if (!seed || !tbl || !out) {
        return tbl;
    }
    const int n = tableLen(tbl);
    if (n <= 0 || n >= max) {
        return tbl;
    }
    buildPool();
    if (gPoolNum <= 0) {
        return tbl;
    }

    const u32 room = pG ? (u32) pG->room_id : 0u;
    // the table's own signature keeps two tables of one room apart (the debug "full stage" helpers add ten in a row)
    const u32 sig = (u32) tbl[0].id << 16 ^ (u32) n;
    u32 h = hashOf((u32) seed, room, sig);

    // A shuffle of the pool (Fisher-Yates from the seed): distinct ids, so a table keeps its length
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

    for (int i = 0; i < n; i++) {
        out[i] = tbl[i];
        out[i].id = order[i % m];
        out[i].num = countFor(out[i].id);
        out[i].isNew = 0;
    }
    out[n].id = 0xFFFF;  // the game's end marker (the loop above only fills the first n entries)
    logLine("merchant: r%03x table %d entries dealt (seed %d)", (int) room, n, seed);
    return out;
}

}  // namespace
