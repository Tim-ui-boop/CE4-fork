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
 */

#include "re4_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

extern "C" const char* re4_data_dir(void);

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
