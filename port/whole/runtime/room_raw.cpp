/* The room sidecar of the offline randomizer (port patch 0238).
 *
 * A room archive, files/StN/rNNN.das, is Yz2 — an adaptive arithmetic coder
 * (game/yz2code.cpp hands over to the assembly decoder of game/yz2asm.cpp) — so
 * a patcher cannot rebuild one offline. Instead the decoded archive is written
 * into the app's own folder, <data>/randomizer/stN/rNNN.das.raw, and ReadAreaData
 * loads that sidecar in place of the room when it exists. The disc file keeps its
 * bytes and its hash, the sidecar survives a new disc import, and deleting the
 * randomizer folder plays the game as the disc has it.
 *
 * The decoded archives come from the app's own native room decoder
 * (NativeBridge.decodeRoom, android_bridge.cpp), which is checked against the
 * original decoder's fingerprints (RoomCheckActivity) — the game itself is not
 * needed for the collection. Nothing here runs without a sidecar.
 *
 * A sidecar holds the archive exactly as the game's decoder yields it: BIG ENDIAN.
 * The port does not keep it that way (runtime/yz2_decode.cpp ends the decode with
 * re4_formats_unpacked, which brings the archive's table and entries to host order,
 * and the game's GetDataExt reads that layout), so read.cpp runs that same step on
 * the loaded sidecar and checks it with re4_room_raw_host_order_ok. A sidecar that
 * fails either check is not used: the game decodes the disc as it is.
 */

#include "re4_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

extern "C" const char* re4_data_dir(void);
extern "C" int re4_crow_trial_active(void);

namespace {

bool exists(const char* path)
{
    struct stat st;

    return path != 0 && stat(path, &st) == 0;
}

/* <data>/randomizer/<name>.raw, `name` as src/game/read.cpp builds it
 * ("st1/r100.das"). One folder for every disc, so the place does not depend on
 * the imported disc's spelling and a re-import leaves the collection alone. */
bool sidecarPath(const char* name, char* out, size_t outSize)
{
    const char* dir = re4_data_dir();

    if (dir == 0 || *dir == 0 || name == 0 || *name == 0) {
        return false;
    }
    if (re4_crow_trial_active()) {
        snprintf(out, outSize, "%s/randomizer/r100-threeway/%s.raw", dir, name);
        return true;
    }
    snprintf(out, outSize, "%s/randomizer/%s.raw", dir, name);
    return true;
}

}  // namespace

/* Decoded sidecar size of the room archive `name`, -1 when there is none. */
extern "C" int re4_room_raw_size(const char* name)
{
    char path[1400];
    struct stat st;

    if (!sidecarPath(name, path, sizeof(path)) || stat(path, &st) != 0) {
        return -1;
    }
    if (st.st_size <= 0 || st.st_size > 64 * 1024 * 1024) {
        return -1;
    }
    return (int) st.st_size;
}

/* Reads the sidecar of `name` into `dst` (re4_room_raw_size bytes): 1 on success. */
extern "C" int re4_room_raw_load(const char* name, void* dst, int size)
{
    char path[1400];
    FILE* f;
    int got;

    if (dst == 0 || size <= 0 || !sidecarPath(name, path, sizeof(path))) {
        return 0;
    }
    f = fopen(path, "rb");
    if (f == 0) {
        return 0;
    }
    got = (int) fread(dst, 1, (size_t) size, f);
    fclose(f);
    if (got != size) {
        re4_log("randomizer: %s: read %d of %d bytes of its sidecar", name, got, size);
        return 0;
    }
    re4_log("randomizer: %s from its sidecar (%d bytes)", name, size);
    return 1;
}

/* Whether `data` starts like a room archive (the count of its sub-files, big endian, at 0x00). A sidecar that
 * fails this is not used: the room is decoded as the disc has it, so a wrong sidecar can never crash the game. */
extern "C" int re4_room_raw_looks_like_room(const void* data)
{
    const unsigned char* p = (const unsigned char*) data;
    unsigned count;

    if (p == 0) {
        return 0;
    }
    count = ((unsigned) p[0] << 24) | ((unsigned) p[1] << 16) | ((unsigned) p[2] << 8) | (unsigned) p[3];
    if (count < 1 || count > 128) {
        re4_log("randomizer: a sidecar does not start like a room archive (%u sub-files): decoding the disc", count);
        return 0;
    }
    return 1;
}

/* Reads a host-order 32-bit word (the layout re4_formats_unpacked leaves behind on a little-endian build). */
static unsigned host32(const unsigned char* p)
{
    return (unsigned) p[0] | ((unsigned) p[1] << 8) | ((unsigned) p[2] << 16) | ((unsigned) p[3] << 24);
}

/* Whether a sidecar, after the port's format converter ran on it, holds the table the game's GetDataExt reads:
 * a sub-file count and a host-order offset per entry, every entry inside the archive. 1 when it does; a sidecar
 * that fails is not used, and the room is decoded as the disc has it. */
extern "C" int re4_room_raw_host_order_ok(const void* data, int size)
{
    const unsigned char* p = (const unsigned char*) data;
    unsigned count;
    unsigned i;

    if (p == 0 || size < 0x14) {
        re4_log("randomizer: a sidecar is too small for a room archive (%d bytes): decoding the disc", size);
        return 0;
    }
    count = host32(p);
    if (count < 1 || count > 128 || 0x10 + count * 8 > (unsigned) size) {
        re4_log("randomizer: a sidecar does not read as a host-order archive (%u sub-files in %d bytes): decoding the disc",
                count, size);
        return 0;
    }
    for (i = 0; i < count; i++) {
        const unsigned offset = host32(p + 0x10 + i * 4);

        if (offset < 0x10 + count * 8 || offset >= (unsigned) size) {
            re4_log("randomizer: a sidecar's sub-file %u lies at %u of %d bytes: decoding the disc", i, offset, size);
            return 0;
        }
    }
    return 1;
}

/* Whether a model pointer a room script is about to use is null. src/game/model.cpp (patch 0241) calls this
 * before setPos/setAng, because EmSetFromList2 (src/game/em_set.cpp) returns errEm = 0 when a list entry was
 * already created or does not belong to this room, and several room scripts write to the result without
 * asking. The check has to happen across a call the C++ compiler cannot see through, otherwise it assumes
 * `this` is never null and drops the branch. */
extern "C" int re4_null_model(const void* p)
{
    static int logged;

    if (p != 0) {
        return 0;
    }
    if (logged < 5) {
        logged++;
        re4_log("randomizer: a room script used a model the enemy list refused (null guard, patch 0241)");
    }
    return 1;
}

/* Whether the door shuffle is installed: <data>/randomizer/doors.active exists. DoorShuffle writes that marker
 * only when it accepted a seed (a refusal deletes it again) and "Restore original rooms" removes the whole
 * folder, so it is present exactly while a shuffled map is being played. sce_at.cpp asks it once per door bump
 * (the answer is cached after the first stat). Without it the vanilla pair check runs unchanged. */
extern "C" int re4_randomizer_doors(void)
{
    static int cached = -1;
    char path[1400];
    const char* dir;

    if (re4_crow_trial_active()) return 0;
    if (cached < 0) {
        dir = re4_data_dir();
        if (dir == 0 || *dir == 0) {
            return 0;   /* no data folder yet: nothing can be shuffled, keep the disc's behaviour */
        }
        snprintf(path, sizeof(path), "%s/randomizer/doors.active", dir);
        cached = exists(path) ? 1 : 0;
        re4_log("randomizer: %s", cached
                    ? "a door shuffle is installed: Ashley's door check is off (patch 0240)"
                    : "no door shuffle installed: Ashley's door check stays as the disc has it");
    }
    return cached;
}
