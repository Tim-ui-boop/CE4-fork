/* The room sidecar of the offline randomizer (port patch 0238).
 *
 * A room archive, files/stN/rNNN.das, is Yz2 — an adaptive arithmetic coder
 * (game/yz2code.cpp hands over to the assembly decoder of game/yz2asm.cpp).
 * A patcher cannot rebuild one of those offline without a bit-exact encoder, so
 * it never touches the .das. Instead the game decodes the room once and writes
 * that decoded archive beside the original as <name>.raw; from then on
 * ReadAreaData loads the sidecar, so the patcher edits plain bytes, the disc
 * file keeps its bytes and its hash, and deleting the .raw files restores the
 * game as the disc has it.
 *
 * The dump runs only while it is asked for: the marker file
 * <data>/room-dump.on, or the environment variable RE4_ROOM_DUMP=1. The app
 * writes the marker for the one tour that collects every room before a seed is
 * generated.
 *
 * A sidecar is used only when it exists; with none present nothing here runs.
 */

#include "re4_runtime.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

extern "C" const char* re4_data_dir(void);
extern "C" int re4_dvd_inserted_disc(void);

namespace {

bool isDir(const char* path)
{
    struct stat st;

    return path != 0 && stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

bool exists(const char* path)
{
    struct stat st;

    return path != 0 && stat(path, &st) == 0;
}

/* The folder holding sys/ and files/, picked as runtime/dvd.cpp picks it:
 * disc0 when there is one (a single-folder import), else the app's disc1 and
 * disc2, else the data folder itself (a folder with sys/ and files/ inside). */
bool rootOf(int disc, char* out, size_t outSize)
{
    const char* dir = re4_data_dir();
    char probe[1200];
    int first;

    if (dir == 0 || *dir == 0) {
        return false;
    }
    snprintf(probe, sizeof(probe), "%s/disc0", dir);
    first = isDir(probe) ? 0 : 1;

    snprintf(out, outSize, "%s/disc%d", dir, disc + first);
    if (isDir(out)) {
        return true;
    }
    snprintf(out, outSize, "%s", dir);
    return isDir(out);
}

/* <root>/files/<name>.raw. The inserted disc is tried first: a room number
 * belongs to one disc, so the other one never has it how ever many imports
 * there are. With `mustExist` 0 the path is returned even when it is not there
 * yet (the dump writes it). */
bool sidecarPath(const char* name, char* out, size_t outSize, bool mustExist)
{
    int cur = re4_dvd_inserted_disc();
    int discs[2];
    int i;

    if (name == 0 || *name == 0) {
        return false;
    }
    discs[0] = (cur >= 0 && cur <= 1) ? cur : 0;
    discs[1] = 1 - discs[0];
    for (i = 0; i < 2; i++) {
        char root[1200];

        if (!rootOf(discs[i], root, sizeof(root))) {
            continue;
        }
        snprintf(out, outSize, "%s/files/%s.raw", root, name);
        if (!mustExist || exists(out)) {
            return true;
        }
    }
    return false;
}

bool dumpWanted()
{
    static int wanted = -1;

    if (wanted < 0) {
        const char* env = getenv("RE4_ROOM_DUMP");
        const char* dir = re4_data_dir();
        char marker[1200];

        wanted = 0;
        if (env != 0 && *env != 0 && *env != '0') {
            wanted = 1;
        } else if (dir != 0 && *dir != 0) {
            snprintf(marker, sizeof(marker), "%s/room-dump.on", dir);
            wanted = exists(marker) ? 1 : 0;
        }
        if (wanted) {
            re4_log("rooms: collecting each room's decoded archive beside its .das");
        }
    }
    return wanted != 0;
}

}  // namespace

/* Decoded sidecar size of the room archive `name`, -1 when there is none. */
extern "C" int re4_room_raw_size(const char* name)
{
    char path[1400];
    struct stat st;

    if (!sidecarPath(name, path, sizeof(path), true)) {
        return -1;
    }
    if (stat(path, &st) != 0 || st.st_size <= 0 || st.st_size > 64 * 1024 * 1024) {
        return -1;
    }
    return (int) st.st_size;
}

/* Reads the sidecar of `name` into `dst` (re4_room_raw_size bytes): 1 on success. */
extern "C" int re4_room_raw_load(const char* name, void* dst, int size)
{
    char path[1400];
    FILE* f;

    if (dst == 0 || size <= 0 || !sidecarPath(name, path, sizeof(path), true)) {
        return 0;
    }
    f = fopen(path, "rb");
    if (f == 0) {
        return 0;
    }
    int got = (int) fread(dst, 1, (size_t) size, f);
    fclose(f);
    if (got != size) {
        re4_log("rooms: %s: read %d of %d bytes of its sidecar", name, got, size);
        return 0;
    }
    return 1;
}

/* Writes the decoded archive of `name` beside its .das while the dump is asked
 * for. Never overwrites a differing sidecar silently: it is written once, and a
 * second tour leaves the patched one alone. */
extern "C" void re4_room_raw_dump(const char* name, const void* src, unsigned size)
{
    char path[1400];
    FILE* f;

    if (!dumpWanted() || src == 0 || size == 0) {
        return;
    }
    if (!sidecarPath(name, path, sizeof(path), false)) {
        return;
    }
    if (exists(path)) {
        return;  // already collected (or already patched): leave it as it is
    }
    f = fopen(path, "wb");
    if (f == 0) {
        re4_log("rooms: %s: cannot write its sidecar", name);
        return;
    }
    if (fwrite(src, 1, (size_t) size, f) != (size_t) size) {
        re4_log("rooms: %s: short write of its sidecar", name);
    }
    fclose(f);
    re4_log("rooms: %s.raw collected (%u bytes)", name, size);
}
