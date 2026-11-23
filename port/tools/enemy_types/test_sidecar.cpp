/* Standalone test of the enemy sidecar's decision logic (runtime/enemy_sidecar.cpp).
 *
 * The real file needs the port's runtime headers, which this phone's sysroot cannot
 * fully provide (asm/sigcontext.h). The logic that matters — which names are served,
 * the size gate, the record sanity check — is copied here verbatim and exercised
 * against real disc lists and deliberately damaged ones.
 *
 *   bash port/tools/enemy_types/test_sidecar.sh
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

static const int kListBytes = 0x2000;
static const int kEntryBytes = 32;
static const int kMinListBytes = 255 * kEntryBytes;

static bool sizeOk(long n)
{
    return n >= kMinListBytes && n <= kListBytes && n % kEntryBytes == 0;
}

static const char* const kLists[] = {
    "etc/emleon00.esl", "etc/emleon01.esl", "etc/emleon02.esl", "etc/emleon03.esl",
    "etc/emleon04.esl", "etc/emleon05.esl", "etc/emleon06.esl", "etc/emleon07.esl",
    "etc/omake00.esl",  "etc/omake01.esl",  "etc/omake02.esl",
};

static bool isListName(const char* name)
{
    if (name == 0) return false;
    for (unsigned i = 0; i < sizeof(kLists) / sizeof(kLists[0]); i++)
        if (strcmp(name, kLists[i]) == 0) return true;
    return false;
}

/* verbatim from enemy_sidecar.cpp */
static bool recordsSane(const unsigned char* p, int bytes)
{
    for (int i = 0; i < bytes / kEntryBytes; i++) {
        const unsigned char* e = p + i * kEntryBytes;
        const unsigned id = e[1];
        const unsigned room = (unsigned) e[0x18] << 8 | e[0x19];

        if (id > 0x60) return false;
        if (id == 0 || (e[0] & 1) == 0) continue;
        if (room == 0 || (room >> 8) > 4) return false;
    }
    return true;
}

static int failures;

static void check(bool ok, const char* what)
{
    printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) failures++;
}

int main(int argc, char** argv)
{
    /* the name gate: only the eleven lists, nothing else */
    check(isListName("etc/emleon00.esl"), "emleon00 is a list name");
    check(isListName("etc/omake02.esl"), "omake02 is a list name");
    check(!isListName("st1/r100.das"), "a room archive is NOT served");
    check(!isListName("etc/emleon08.esl"), "emleon08 does not exist");
    check(!isListName("movie/r100.sfd"), "a movie is NOT served");
    check(!isListName("etc/emleon00.esl.bak"), "a near-miss name is NOT served");
    check(!isListName(""), "empty name is NOT served");
    check(!isListName(0), "null name is NOT served");
    check(!isListName("../etc/emleon00.esl"), "a traversal name is NOT served");

    if (argc < 2) {
        printf("\n(no disc list given; name gate only)\n");
        return failures ? 1 : 0;
    }
    FILE* f = fopen(argv[1], "rb");
    if (!f) { printf("cannot open %s\n", argv[1]); return 1; }
    static unsigned char buf[kListBytes];
    size_t got = fread(buf, 1, sizeof(buf), f);
    fclose(f);
    check(got == (size_t) kMinListBytes || got == (size_t) kListBytes,
          "the real disc list is 8160 (255 entries) or 8192 bytes");
    check(sizeOk((long) got), "the real disc list passes the size gate");
    check(recordsSane(buf, (int) got), "the real disc list passes the record check");
    check(!sizeOk(8161), "a size that is not a whole entry is refused");
    check(!sizeOk(100), "a truncated file is refused");
    check(!sizeOk(0x4000), "an oversized file is refused");
    check(sizeOk(8192), "a full 256-entry list is accepted");

    /* a dealt list: change id/type/flag only, as the deal does */
    static unsigned char dealt[kListBytes];
    memcpy(dealt, buf, sizeof(dealt));
    int changed = 0;
    for (int i = 0; i < 256 && changed < 20; i++) {
        unsigned char* e = dealt + i * 32;
        if (e[0] & 1 && e[1] >= 0x10 && e[1] <= 0x17 && e[1] != 0x18) {
            e[1] = 0x12;      /* another village kind */
            e[2] = 4;         /* the chainsaw model type */
            e[7] |= 0x10;     /* flag bit 28 (byte 7 is the high byte of the u32) */
            changed++;
        }
    }
    check(changed > 0, "the rehearsal found village Ganados to deal");
    check(recordsSane(dealt, (int) got), "a dealt list (id/type/flag only) still passes");

    /* damaged files must be refused */
    static unsigned char bad[kListBytes];
    memcpy(bad, buf, sizeof(bad));
    bad[1] = 0xFF;                       /* an impossible id */
    check(!recordsSane(bad, (int) got), "an impossible enemy id is refused");

    memcpy(bad, buf, sizeof(bad));
    for (int i = 0; i < 256; i++) {
        if (bad[i * 32 + 1]) { bad[i * 32 + 0x18] = 9; break; }  /* stage 9 */
    }
    check(!recordsSane(bad, (int) got), "a record moved to a nonexistent stage is refused");

    memcpy(bad, buf, sizeof(bad));
    for (int i = 0; i < 256; i++) {
        if (bad[i * 32 + 1]) { bad[i * 32 + 0x18] = 0; bad[i * 32 + 0x19] = 0; break; }
    }
    check(!recordsSane(bad, (int) got), "a record with no room is refused");

    /* an all-zero file is a cleared list, which the game itself produces: allowed */
    memset(bad, 0, sizeof(bad));
    check(recordsSane(bad, kListBytes), "an empty (all-zero) list is allowed");

    printf("\n%s (%d failures)\n", failures ? "FAILED" : "ALL PASS", failures);
    return failures ? 1 : 0;
}
