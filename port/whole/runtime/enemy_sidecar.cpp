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

const int kListBytes = 0x2000;  // a full 256 entries of 32 bytes (EM_LIST, include/global.h:211)
/* 255 entries: what the eight story lists are on the disc (the FST says 0x1FE0). readEmList asks
   DvdReadN for size 0, so cDvdQueue::readInit sets the length from the file itself and readMain
   reads 0x1FE0; the drive zero-fills the missing 256th entry. A read of the whole list therefore
   arrives here as 0x1FE0, NOT as 0x2000 — the earlier check for 0x2000 alone never matched, which
   is why a dealt list was written and then silently ignored. */
const int kStoryListBytes = 255 * 32;
const int kEntryBytes = 32;
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
    /* Four enemy module slots exist (EmReadModule[4]); a fifth kind silently fails to spawn and
     * can leave the room empty. A dealt list must fit the same budget as the disc's. Active records
     * are the set the dealer budgets and the game spawns at room start. Keep the working set small:
     * this check runs on the game's DVD thread, whose stack is not generous. */
    unsigned short rooms[256];
    int nrooms = 0;
    unsigned long long seen[2];
    for (int i = 0; i < bytes / kEntryBytes; i++) {
        const unsigned char* e = p + i * kEntryBytes;
        const unsigned id = e[1];
        const unsigned room = (unsigned) e[0x18] << 8 | e[0x19];

        if (id > 0x60) {
            return false;  // the game's enemy ids end well below this (em.cpp)
        }
        if (id == 0 || (e[0] & 1) == 0) {
            continue;  // an empty or unused slot: emleon07 entry 155 has an id but no room
        }
        if (room == 0 || (room >> 8) > 4) {
            return false;  // stage 1..3 of the story, 4 of the extra games
        }
        bool known = false;
        for (int r = 0; r < nrooms; r++) {
            if (rooms[r] == room) { known = true; break; }
        }
        if (!known) rooms[nrooms++] = (unsigned short) room;
    }
    for (int r = 0; r < nrooms; r++) {
        memset(seen, 0, sizeof(seen));
        unsigned kinds = 0;
        for (int i = 0; i < bytes / kEntryBytes; i++) {
            const unsigned char* e = p + i * kEntryBytes;
            if (e[1] == 0 || (e[0] & 1) == 0) continue;
            if (((unsigned) e[0x18] << 8 | e[0x19]) != rooms[r]) continue;
            unsigned id = e[1];
            unsigned long long bit = 1ULL << (id & 63);
            unsigned word = id >> 6;
            if ((seen[word] & bit) == 0) {
                seen[word] |= bit;
                if (++kinds > 4) {
                    return false;  // read the disc instead of blanking this room
                }
            }
        }
    }
    return true;
}

}  // namespace

/* Serves the launcher's dealt enemy list `name` into `dst` instead of the disc's:
 * 1 when it was served, 0 to read the disc as usual. Called by runtime/dvd.cpp readFile
 * for a whole-file read at offset 0. Any thread the drive reads on.
 *
 * `length` is what the drive was asked for: 0x1FE0 for the eight story lists and 0x2000 for
 * emleon08/09. Both are served; the copy is exactly `length` bytes, so the destination is
 * written exactly as far as the game expects and no further. */
extern "C" int re4_enemy_sidecar_read(const char* name, void* dst, int length)
{
    char path[1400];
    unsigned char head[kListBytes];
    FILE* f;
    size_t got;
    static int told;

    if (dst == 0 || (length != kStoryListBytes && length != kListBytes) ||
        !sidecarPath(name, path, sizeof(path))) {
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
    /* a dealt copy shorter than the read: zeros, exactly as the drive's short read gives */
    if ((size_t) length > got) {
        memset(head + got, 0, (size_t) length - got);
    }
    memcpy(dst, head, (size_t) length);
    if (told++ < 8) {
        re4_log("randomizer: %s: the launcher's dealt enemy list (%d bytes)", name, length);
    }
    return 1;
}
