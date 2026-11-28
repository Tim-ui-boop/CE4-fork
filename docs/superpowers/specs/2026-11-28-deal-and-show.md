# Deal-and-show launcher result — design

Date: 2026-11-28
Status: design, awaiting approval
Scope: `port/android-decomp/app/src/main/java/com/ast/re4decomp/MainActivity.java`, `res/values/strings.xml`

## Problem

The launcher has a read-only **Preview seed rolls** button that shows a planned report before
anything is written, and a separate **Deal test rooms** button whose result is only a one-line
status. The player asked for the display to show the *actual* randomized enemies after dealing,
"so it's not a preview".

Prior offline evidence (`check_preview_deal.py`, 120 seeds) shows the current report matches the
bytes actually written, but two separate buttons still invite the player to trust a plan rather
than the deal. The fix is to remove the preview and show the real result from the deal.

## Design

1. Remove the read-only Preview button (`MainActivity.java:279-308`) and its unused string
   `launch_rand_preview`.
2. Keep the single **Deal enemies** button. When its worker returns `r.ok`, read the already-written
   result file `<data>/randomizer/enemies/enemies.txt` — the text written at
   `EnemyShuffle.java:314` from the final plan — and show it in the same scrollable dialog the
   Preview button used. Prepend the existing pool note and the seed. Keep the short status line as
   well.
3. If the result file cannot be read, show `r.message` instead; never block the deal on the dialog.
4. Rename the string `launch_rand_deal` to "Deal enemies and show result" so the button says what it
   does. Add a dialog title string.

No change to the dealer, the RNG, the pool rules, or the sidecars. The dialog text is the final
result, not a simulation.

## Verification

- `javac` check of `MainActivity.java` with the Android stubs used by the project.
- `python3 .harness/analysis/check_preview_deal.py` (report matches written rows).
- Full offline gate + GitHub Actions build and a user playtest of the button.

## Explicitly not in this change

- The codec-cutscene model issue: research shows Leon's cutscene body is loaded from the player
  archive, not the four enemy module slots; freeing those slots cannot restore him. That needs a
  fresh logcat capture from the failing scene.
