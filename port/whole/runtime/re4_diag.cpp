// The randomizer's diagnostic log (0.99.17, the owner's wish of 17.11.2026: "I think I should have told
// you to make incredibly in-depth logs so we could debug this better").
//
// Why it exists: every round of this feature was debugged from a handful of lines. The room's line named
// counts, but never WHY a given entry was left alone -- named, waited for by an area, kind not loaded,
// already created, dead -- and never where a chosen place sat relative to anything the player recognises.
// Guesses filled the gap, and several shipped bugs came from exactly those guesses (the fifth module, the
// stale player position, the spawn table read at the wrong stride).
//
// The switch is a cheat bit (re4_diag_wanted, the app's Diagnostics toggle) so it needs no environment
// variable on a phone. With it on, the randomizer prints a decision per entry and a placement proof per
// chosen place. With it off it prints what it always did: one line a room, so the log stays small.
#include "re4_runtime.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

// runtime/cheats.cpp: the app's Diagnostics switch (a cheat bit, so a phone can turn it on).
extern "C" int re4_diag_cheat(void);

namespace {

// The lines a session may print with the diagnostics on. The old cap was 300 for the whole session,
// which went silent in a long test exactly when the log was needed; this one fits a full playthrough
// and still bounds a runaway loop.
const int kDiagLines = 4000;
int gLines;
int gEnabled = -1;  // -1 not asked yet: RE4_DIAG may switch it on for a PC run

}  // namespace

extern "C" {

// 1 with the diagnostics on. RE4_DIAG=1 works on the PC; the app's cheat bit (runtime/cheats.cpp) on
// the phone; either one is enough.
int re4_diag_on(void)
{
    if (gEnabled < 0) {
        const char* v = getenv("RE4_DIAG");
        gEnabled = v && *v && *v != '0' ? 1 : 0;
    }
    return gEnabled || (re4_diag_cheat() != 0) ? 1 : 0;
}

// A diagnostic line: printed when the switch is on (and counted against its cap), nothing otherwise.
void re4_diag(const char* fmt, ...)
{
    if (!re4_diag_on() || gLines >= kDiagLines) {
        return;
    }
    gLines++;
    char text[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    re4_log("%s", text);
}

// How many diagnostic lines went out (the app's "the log is full" hint).
int re4_diag_lines(void)
{
    return gLines;
}

}  // extern "C"
