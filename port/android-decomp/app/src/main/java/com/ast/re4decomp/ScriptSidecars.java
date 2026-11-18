package com.ast.re4decomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the sidecar enemy lists in step with the run that is being played.
 *
 * Why this exists: the randomizer's list rewrite only reaches the game through the `*.esl.re4` files beside the
 * imported disc. Until now those were written only when the player pressed "Write enemy data" on the page, so a
 * run that skipped that button had a seed, an engine hook that changed the one script-built Ganado, and nothing
 * else -- exactly the report of 19.11.2026 ("no enemies randomized all the way to the next chapter, but the house
 * Ganado has a different villager model"). The log confirmed it: r101's villagers and r103's cows were byte for
 * byte what the disc holds.
 *
 * The rules here:
 *
 *  - the files are written when the run's seed or kind pool differs from the ones already on disk;
 *  - a stamp file records which seed and pool are in place, so an unchanged run does no work and logs nothing;
 *  - switching the randomizer off deletes them, so the disc's own lists come back and a stale run cannot haunt the
 *    next one;
 *  - everything is logged under the tag RE4Randomizer, so a log proves what happened.
 */
final class ScriptSidecars {
  private static final String TAG = "RE4Randomizer";
  private static final String STAMP = "enemy-lists.stamp";

  /** The application context, set once by Re4Application so the callers need none. */
  private static Context app;

  private ScriptSidecars() {}

  static synchronized void init(Context context) {
    if (context != null) app = context.getApplicationContext();
  }

  private static File stampFile() {
    return new File(EnemyRandomizer.importRoot(app), STAMP);
  }

  /** "seed pool" of the lists on disk, or null when there are none. */
  private static String current() {
    File f = stampFile();
    if (!f.isFile()) return null;
    try {
      byte[] b = new byte[(int) f.length()];
      try (FileInputStream in = new FileInputStream(f)) {
        if (in.read(b) != b.length) return null;
      }
      return new String(b, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Makes the on-disk lists match this run. `seed` 0 means the randomizer is off, which removes them.
   *
   * Called from {@link RandomizerConfig#applyToCore()}: once before a core starts and once shortly after, and when
   * the page closes. A disk write there is a handful of 8 KB files, and only when the run actually changed.
   */
  static synchronized void sync(int seed, int pool) {
    if (app == null) return;
    if (seed == 0) {
      syncOff();
      return;
    }
    final String wanted = seed + " " + pool;
    final String have = current();
    if (wanted.equals(have)) {
      Log.i(TAG, "enemy lists: already in place for seed " + seed + ", pool 0x" + Integer.toHexString(pool));
      return;
    }
    try {
      EnemyRandomizer.Result result = EnemyRandomizer.apply(app, seed, pool);
      writeStamp(wanted);
      Log.i(TAG, "enemy lists: wrote " + result.summary() + " for seed " + seed + " (pool 0x"
          + Integer.toHexString(pool) + "), " + EnemyRandomizer.active(app) + " file(s) in place");
    } catch (IOException e) {
      // A missing import is not an error the player needs to see here; the lists simply were not written.
      Log.w(TAG, "enemy lists: not written (" + e.getMessage() + ")");
    }
  }

  /** Removes every sidecar so the game reads the disc's own lists again. */
  static synchronized void syncOff() {
    if (app == null) return;
    if (current() == null && EnemyRandomizer.active(app) == 0) return;  // nothing to undo, nothing to log
    final int n = EnemyRandomizer.restore(app);
    final File stamp = stampFile();
    if (stamp.isFile() && !stamp.delete()) Log.w(TAG, "enemy lists: cannot remove " + stamp.getName());
    Log.i(TAG, "enemy lists: randomizer off, " + n + " written list(s) removed");
  }

  private static void writeStamp(String stamp) throws IOException {
    File f = stampFile();
    File tmp = new File(f.getAbsolutePath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(stamp.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    if (!tmp.renameTo(f)) {
      tmp.delete();
      throw new IOException("could not write " + f.getName());
    }
  }
}
