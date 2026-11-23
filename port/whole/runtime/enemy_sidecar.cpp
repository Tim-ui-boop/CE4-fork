/* The offline enemy randomizer's enemy-list sidecar.
 *
 * The enemy lists (etc/emleon00.esl .. emleon07.esl, omake00..02.esl) are read RAW:
 * src/game/stage.cpp readEmList asks DvdReadN for 0x2000 bytes into pG->Em_list, with
 * no compression and no part table. So unlike a room archive (Yz2, which cannot be
 * rebuilt offline) an enemy list can be dealt by the launcher and written as a plain
 * file, and the loader can serve that file instead of the disc's.
 *
 * The launcher writes <data>/randomizer/enemies/<name>.esl (name as the game asks for
 * it, e.g. "etc/emleon00.esl"), plus <data>/randomizer/enemies/active. With no active
 * marker nothing here runs: the disc is read as it is. Deleting the folder plays the
 * game as the disc has it; the import and the saves are never touched.
 *
 * This is deliberately NOT the room sidecar's mechanism. A room sidecar replaces the
 * output of a decoder inside the game's own allocation and has to reproduce the port's
 * host-order step; an enemy list is a flat record table, so the override sits in the
 * platform's file read (runtime/dvd.cpp readFile) and needs no game-side patch at all.
 *
 * Safety, all checked here before a single byte is served:
 *   - only the eleven enemy-list names are ever overridden, never a room, a movie,
 *     a texture or the executable;
 *   - the sidecar must be exactly 0x2000 bytes (256 entries x 32), the size the game
 *     asks for, so it can neither overrun pG->Em_list nor leave it half filled;
 *   - the read must be the whole list at offset 0 (the game only ever reads it whole);
 *   - every record must keep the room it belongs to and a sane id, so a corrupt or
 *     truncated file is refused and the disc is read instead.
 * A refused sidecar is logged once and never crashes the game.
 */

#include "re4_runtime.h"

#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

extern "C" const char* re4_data_dir(void);

namespace {

const int kListBytes = 0x2000;  // what the game asks for: 256 entries of 32 (EM_LIST, include/global.h:211)
const int kEntryBytes = 32;
/* The story lists on the disc are 8160 bytes (255 entries), not 8192: the game reads 0x2000
   and the drive zero-fills the last entry (runtime/dvd.cpp readFile). emleon08/09 are the
   full 8192. So a sidecar may be either, and a short one is zero-filled exactly as the
   disc read does — never the other way round. */
const int kMinListBytes = 255 * kEntryBytes;

/* The eleven names src/game/stage.cpp emlist_name[] asks for. Nothing else is served. */
const char* const kLists[] = {
    "etc/emleon00.esl", "etc/emleon01.esl", "etc/emleon02.esl", "etc/emleon03.esl",
    "etc/emleon04.esl", "etc/emleon05.esl", "etc/emleon06.esl", "etc/emleon07.esl",
    "etc/omake00.esl",  "etc/omake01.esl",  "etc/omake02.esl",
};

bool isListName(const char* name)
{
    if (name == 0) {
        return false;
    }
    for (unsigned i = 0; i < sizeof(kLists) / sizeof(kLists[0]); i++) {
        if (strcmp(name, kLists[i]) == 0) {
            return true;
        }
    }
    return false;
}

bool fileBytes(const char* path, long* size)
{
    struct stat st;

    if (path == 0 || stat(path, &st) != 0) {
        return false;
    }
    *size = (long) st.st_size;
    return true;
}

/* <data>/randomizer/enemies/<name>, only when the launcher's active marker is there. */
bool sidecarPath(const char* name, char* out, size_t outSize)
{
    const char* dir = re4_data_dir();
    char active[1400];
    long size;

    if (dir == 0 || *dir == 0 || !isListName(name)) {
        return false;
    }
    snprintf(active, sizeof(active), "%s/randomizer/enemies/active", dir);
    if (!fileBytes(active, &size)) {
        return false;
    }
    snprintf(out, outSize, "%s/randomizer/enemies/%s", dir, name);
    return fileBytes(out, &size) && size >= kMinListBytes && size <= kListBytes &&
           size % kEntryBytes == 0;
}

/* A dealt list keeps every record's room and a sane id. The deal only ever writes id,
 * type and flag (port/tools/enemy_types/rehearse.py checks the same), so a record whose
 * room moved or whose id is out of range means a damaged file, not a deal. */
bool recordsSane(const unsigned char* p, int bytes)
{
    for (int i = 0; i < bytes / kEntryBytes; i++) {
        const unsigned char* e = p + i * kEntryBytes;
        const unsigned id = e[1];
        const unsigned room = (unsigned) e[0x18] << 8 | e[0x19];

        if (id > 0x60) {
            return false;  // the game's enemy ids end well below this (em.cpp)
        }
        if (id == 0) {
            continue;  // an empty entry: the rest is unused
        }
        if (room == 0 || (room >> 8) > 4) {
            return false;  // stage 1..3 of the story, 4 of the extra games
        }
    }
    return true;
}

}  // namespace

/* Serves the launcher's dealt enemy list `name` into `dst` instead of the disc's:
 * 1 when it was served, 0 to read the disc as usual. Called by runtime/dvd.cpp readFile
 * for a whole-file read at offset 0. Any thread the drive reads on. */
extern "C" int re4_enemy_sidecar_read(const char* name, void* dst, int length)
{
    char path[1400];
    unsigned char head[kListBytes];
    FILE* f;
    size_t got;
    static int told;

    if (dst == 0 || length != kListBytes || !sidecarPath(name, path, sizeof(path))) {
        return 0;
    }
    f = fopen(path, "rb");
    if (f == 0) {
        return 0;
    }
    got = fread(head, 1, sizeof(head), f);
    fclose(f);
    if (got < (size_t) kMinListBytes || got > (size_t) kListBytes || got % kEntryBytes != 0 ||
        !recordsSane(head, (int) got)) {
        if (told++ < 8) {
            re4_log("randomizer: %s: the dealt enemy list is damaged (%u bytes), reading the disc",
                    name, (unsigned) got);
        }
        return 0;
    }
    /* the last entry of a 255-entry list: zeros, exactly as the drive's short read gives */
    memset(head + got, 0, sizeof(head) - got);
    memcpy(dst, head, (size_t) kListBytes);
    if (told++ < 8) {
        re4_log("randomizer: %s: the launcher's dealt enemy list", name);
    }
    return 1;
}
