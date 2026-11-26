// OSLink/OSUnlink: REL modules as shared libraries.
//
// On the GameCube the game reads a REL from disc (often embedded in an
// archive), then OSLink relocates it in place, runs nothing itself and leaves
// the entry points in the header; the game calls header->prolog afterwards.
// In the port every REL is a library libre4_<name>.so (module_loader.cpp).
// OSLink takes the module id from the REL header, loads the library and writes
// the addresses of its _prolog/_epilog/_unresolved into the header, so
// DLL_PROLOG(header) and DLL_EPILOG(header) call into the library.
//
// The header comes from disc and is big-endian. The game reads it natively
// (bssSize before OSLink), so re4_rel_header_to_host converts it in place;
// OSLink does so too (idempotent) for headers nobody converted.
//
// Module state follows the GameCube:
//  - A freshly loaded REL starts from its initial data: if the library is still
//    loaded from an earlier use, it is reloaded (statics reset, constructors rerun).
//  - OSUnlink keeps the library loaded. The room code unlinks a REL to pause it
//    and links the same, untouched header again later (cRoomData::stopRelData /
//    restartRelData); its state must survive that. Such a relink is recognised
//    by the header still holding the entry points OSLink wrote; a REL read
//    from disc again holds section offsets there.
#include "re4_runtime.h"
#include "../include/re4_ptr32.h"

#include <dlfcn.h>
#include <stddef.h>
#include <string.h>

namespace {

typedef unsigned u32;

// OSModuleHeader (include/dolphin/os/OSModule.h), version 1..3; every field is
// 32 bits except the four section numbers at 0x30.
struct ModuleInfo {
    u32 id;
    RE4_P32(ModuleInfo) next;  // four bytes in the disc header also on LP64 (milestone 12)
    RE4_P32(ModuleInfo) prev;
    u32 numSections;
    u32 sectionInfoOffset;
    u32 nameOffset;
    u32 nameSize;
    u32 version;
};

struct ModuleHeader {
    ModuleInfo info;
    u32 bssSize;
    u32 relOffset;
    u32 impOffset;
    u32 impSize;
    unsigned char prologSection;
    unsigned char epilogSection;
    unsigned char unresolvedSection;
    unsigned char bssSection;
    u32 prolog;
    u32 epilog;
    u32 unresolved;
    u32 align;     // version >= 2
    u32 bssAlign;  // version >= 2
    u32 fixSize;   // version >= 3
};

static_assert(sizeof(ModuleInfo) == 0x20, "OSModuleInfo layout on ILP32");
static_assert(sizeof(ModuleHeader) == 0x4C, "OSModuleHeader layout on ILP32");

// The port's own record of the headers OSLink listed. The GameCube keeps its
// list in game memory (MEM1+0x30C8) whose nodes are REL headers living INSIDE
// game archives -- archives the game frees and hands back to its heap. r118
// (report 20261127, builds 6315/6317): pl11's cutscene archive was released,
// em17's 3.9 MB body was allocated over it, and OSLink's walk of the list read
// the freed header's next pointer as archive bytes (0x58fe02fe) and died. The
// port therefore never chains through game memory: membership is an
// address-only scan of this table and the GameCube list stays empty.
ModuleInfo* sListed[256];
unsigned sListedCount;
unsigned sListedNext;

u32 swap32(u32 v)
{
    return v >> 24 | (v >> 8 & 0xFF00) | (v << 8 & 0xFF0000) | v << 24;
}

bool validVersion(u32 v)
{
    return v >= 1 && v <= 3;
}

size_t headerWords(u32 version)
{
    return version >= 3 ? sizeof(ModuleHeader) : version == 2 ? 0x48 : 0x40;
}

// A relink: the header still holds the entry points OSLink wrote for the library
// that is loaded now (a REL read from disc holds section offsets there). This
// asks the header itself; an earlier list of the headers OSLink saw held 64
// and forgot old ones after a long session, so a paused room REL counted as
// fresh, its library was reloaded elsewhere and the room table of the stage
// (St2_data_tbl ...) kept pointers into the old mapping (report 20261103-224733,
// r222 after the sub screen: ScenarioMove jumped to an unmapped address).
bool isRelink(ModuleHeader* h, const char* name)
{
    if (!h->prolog || !re4_module_is_loaded(name)) {
        return false;
    }
    void* handle = re4_module_load(name);  // already loaded: only the handle
    return handle && h->prolog == (u32) (unsigned long) dlsym(handle, "_prolog");
}

bool inList(ModuleInfo* info)
{
    for (unsigned i = 0; i < sListedCount; i++) {
        if (sListed[i] == info) {
            return true;
        }
    }
    return false;
}

void listInsert(ModuleInfo* info)
{
    if (inList(info)) {
        return;
    }
    if (sListedCount < (unsigned) (sizeof(sListed) / sizeof(sListed[0]))) {
        sListed[sListedCount++] = info;
        return;
    }
    sListed[sListedNext] = info;
    sListedNext = (sListedNext + 1) % (unsigned) (sizeof(sListed) / sizeof(sListed[0]));
}

void listRemove(ModuleInfo* info)
{
    for (unsigned i = 0; i < sListedCount; i++) {
        if (sListed[i] == info) {
            sListed[i] = sListed[--sListedCount];
            return;
        }
    }
}

}  // namespace

extern "C" {

// 1 when the header was converted now, 0 when it already was in host order,
// -1 when it is no REL header.
int re4_rel_header_to_host(void* rel)
{
    ModuleHeader* h = (ModuleHeader*) rel;
    if (validVersion(h->info.version)) {
        return 0;
    }
    u32 version = swap32(h->info.version);
    if (!validVersion(version)) {
        return -1;
    }
    u32* words = (u32*) h;
    size_t end = headerWords(version) / 4;
    for (size_t i = 0; i < end; i++) {
        if (i != offsetof(ModuleHeader, prologSection) / 4) {
            words[i] = swap32(words[i]);
        }
    }
    return 1;
}

int OSLink(ModuleInfo* info, void* bss)
{
    ModuleHeader* h = (ModuleHeader*) info;
    if (re4_rel_header_to_host(h) < 0) {
        re4_log("OSLink: %p is no REL module header", (void*) info);
        return 0;
    }
    const char* name = re4_module_name(h->info.id);
    if (!name) {
        re4_log("OSLink: unknown module id %u", h->info.id);
        return 0;
    }
    bool fresh = !isRelink(h, name);
    if (fresh) {
        // (The old "linked twice" diagnostic walk over the module list is gone:
        // it dereferenced list nodes whose memory the game may have reused.)
        if (re4_module_is_loaded(name)) {
            re4_module_unload(name);  // back to the module's initial data
        }
    }
    void* handle = re4_module_load(name);
    if (!handle) {
        return 0;
    }
    h->prolog = (u32) (unsigned long) dlsym(handle, "_prolog");
    h->epilog = (u32) (unsigned long) dlsym(handle, "_epilog");
    h->unresolved = (u32) (unsigned long) dlsym(handle, "_unresolved");
    if (bss && h->bssSize && fresh) {
        memset(bss, 0, h->bssSize);
    }
    listInsert(info);
    re4_log("OSLink: module %s (id %u)%s", name, h->info.id, fresh ? "" : ", relinked");
    return 1;
}

int OSUnlink(ModuleInfo* info)
{
    if (!inList(info)) {
        return 0;
    }
    // The epilog already ran (DLL_Unlink); on the GameCube it also ran the
    // module's static destructors.
    if (const char* name = re4_module_name(info->id)) {
        void* handle = re4_module_is_loaded(name) ? re4_module_load(name) : 0;
        if (void (*finalize)() = handle ? (void (*)()) dlsym(handle, "re4_module_finalize") : 0) {
            finalize();
        }
    }
    listRemove(info);
    info->next = info->prev = 0;
    return 1;
}

}  // extern "C"
