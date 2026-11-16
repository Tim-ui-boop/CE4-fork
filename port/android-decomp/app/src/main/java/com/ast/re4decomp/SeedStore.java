package com.ast.re4decomp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The seed files (files/seeds/*.re4seed) and the one the app is playing right now.
 *
 * A seed file is a whole run: the seed, its name and the randomizers' settings (RandomizerConfig). The active seed
 * is remembered in the app's preferences by file name, so a restart continues the same run.
 *
 * A save stamped with a seed writes a small sidecar next to the game's own save (bh4_dataNN.re4seed). The save
 * itself is never touched: the game checks its data with a CRC (OriginalSaveHeader), so writing into it would
 * corrupt it. The sidecar is what the load screen reads to offer "this save was made with seed ...".
 */
final class SeedStore {
  static final String PREF_ACTIVE = "activeSeed";      // file name without the suffix, "" = none
  static final String PREF_CURRENT = "currentSeed";    // the run in use, kept even when it has no file yet

  private final File directory;
  private final android.content.SharedPreferences prefs;

  SeedStore(File filesDir, android.content.SharedPreferences prefs) {
    this.directory = new File(filesDir, RandomizerConfig.DIRECTORY);
    this.prefs = prefs;
  }

  File directory() {
    return directory;
  }

  private File file(String name) {
    return new File(directory, name + RandomizerConfig.SUFFIX);
  }

  /** Seed names on disk, sorted, without the suffix. */
  List<String> names() {
    List<String> out = new ArrayList<>();
    File[] files = directory.listFiles();
    if (files != null) {
      for (File f : files) {
        String n = f.getName();
        if (n.endsWith(RandomizerConfig.SUFFIX)) out.add(n.substring(0, n.length() - RandomizerConfig.SUFFIX.length()));
      }
    }
    Collections.sort(out);
    return out;
  }

  /** A legal file name from what the player typed: letters, digits, space, dash, underscore; never empty. */
  static String cleanName(String wanted) {
    if (wanted == null) return "seed";
    StringBuilder out = new StringBuilder();
    for (char c : wanted.trim().toCharArray()) {
      if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') out.append(c);
    }
    String name = out.toString().trim();
    if (name.isEmpty()) name = "seed";
    return name.length() > 48 ? name.substring(0, 48) : name;
  }

  RandomizerConfig load(String name) throws IOException {
    File source = file(name);
    if (!source.isFile()) throw new IOException("Seed not found: " + name);
    StringBuilder text = new StringBuilder();
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
      for (String line; (line = in.readLine()) != null; ) {
        text.append(line).append('\n');
      }
    }
    RandomizerConfig config = RandomizerConfig.fromText(text.toString());
    if (sniffSeed(text.toString()) != config.seed) throw new IOException("This seed file is damaged");
    return config;
  }

  private static int sniffSeed(String text) {
    for (String raw : text.split("\n")) {
      String line = raw.trim();
      if (line.startsWith("seed ")) {
        try {
          return Integer.parseInt(line.substring(5).trim());
        } catch (NumberFormatException e) {
          return -1;
        }
      }
    }
    return -1;
  }

  /** Writes the seed, replacing a file of that name (the caller asks first). Returns the name used. */
  String save(String wanted, RandomizerConfig config) throws IOException {
    String name = cleanName(wanted);
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create the seeds folder");
    File target = file(name);
    File pending = new File(directory, target.getName() + ".pending");
    try {
      try (FileOutputStream out = new FileOutputStream(pending)) {
        out.write(config.toText().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!pending.renameTo(target)) throw new IOException("Could not write the seed file");
    } finally {
      if (pending.exists()) pending.delete();
    }
    return name;
  }

  void delete(String name) throws IOException {
    File target = file(name);
    if (target.exists() && !target.delete()) throw new IOException("Could not delete the seed file");
  }

  // ---- the run in use --------------------------------------------------------------------------------------------

  /** The seed the app plays now, from the active file; a seed with no file is kept in the preferences. */
  RandomizerConfig current() {
    String active = prefs.getString(PREF_ACTIVE, "");
    if (!active.isEmpty() && file(active).isFile()) {
      try {
        return load(active);
      } catch (IOException ignored) {
        // a damaged file falls through to the last run kept in the preferences
      }
    }
    RandomizerConfig config = new RandomizerConfig();
    config.seed = prefs.getInt(PREF_CURRENT, 0);
    for (RandomizerConfig.Feature f : config.features.values()) {
      f.on = prefs.getBoolean("seedOn_" + f.id, false);
      f.chance = prefs.getInt("seedChance_" + f.id, 100);
      f.weapons = prefs.getInt("seedWeapons_" + f.id, 0);
    }
    return config;
  }

  /** Makes `config` the run in use; `activeFile` "" when it was never saved to a file. */
  void setCurrent(RandomizerConfig config, String activeFile) {
    android.content.SharedPreferences.Editor edit = prefs.edit();
    edit.putString(PREF_ACTIVE, activeFile == null ? "" : activeFile);
    edit.putInt(PREF_CURRENT, config.seed);
    boolean any = false;
    for (RandomizerConfig.Feature f : config.features.values()) {
      edit.putBoolean("seedOn_" + f.id, f.on);
      edit.putInt("seedChance_" + f.id, f.chance);
      edit.putInt("seedWeapons_" + f.id, f.weapons);
      any |= f.on;
    }
    // the item randomizer stays the cheat bit it always was in the core (runtime/cheats.cpp, patch 0218): bit 1024,
    // the run's seed in bits 11..30. Its switch moved from the in-game cheat menu to the page (phase 2), so the
    // run keeps that bit with it -- otherwise the switch would say "on" while the core never hears it. The core
    // reads the bits when it starts (CoreActivity); the cheat menu leaves this randomizer's bits alone.
    final boolean items = config.feature("items").on && config.seed != 0;
    final int bits = prefs.getInt(CoreActivity.CHEATS, 0);
    final int withItems = CoreMenu.withRandomItems(bits, items, config.seed);
    if (withItems != bits) edit.putInt(CoreActivity.CHEATS, withItems);
    edit.apply();
    // hand it to the running core too; before the core starts this is remembered by CoreActivity
    config.applyToCore();
  }

  // ---- the stamp beside a save -----------------------------------------------------------------------------------

  private static File stampOf(File save) {
    return new File(save.getParentFile(), save.getName() + RandomizerConfig.SUFFIX);
  }

  /** Records which seed created save `slot` in `savesDir`; written after the save itself is in place. */
  static void stamp(File savesDir, int slot, int seed) {
    File target = stampOf(new File(savesDir, String.format(Locale.ROOT, "bh4_data%02d", slot)));
    if (seed == 0) {
      if (target.exists()) target.delete();
      return;
    }
    try (OutputStream out = new FileOutputStream(target)) {
      out.write(("seed " + seed + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException ignored) {
      // a missing stamp only means the load screen cannot offer the seed; the save itself stays valid
    }
  }

  /** The seed a save was made with, 0 when it carries none. */
  static int stampedSeed(File savesDir, int slot) {
    File source = stampOf(new File(savesDir, String.format(Locale.ROOT, "bh4_data%02d", slot)));
    if (!source.isFile()) return 0;
    try (BufferedReader in = new BufferedReader(
        new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8))) {
      for (String line; (line = in.readLine()) != null; ) {
        line = line.trim();
        if (line.startsWith("seed ")) {
          try {
            return Integer.parseInt(line.substring(5).trim());
          } catch (NumberFormatException e) {
            return 0;
          }
        }
      }
    } catch (IOException ignored) {
    }
    return 0;
  }
}
