package com.ast.re4decomp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * One randomizer run: the seed every randomizer derives from, plus the randomizers' settings.
 *
 * The model is the one a Ocarina of Time randomizer uses (Ship of Harkinian): the player creates a seed before the
 * game starts, everything in that run follows from it, and the same seed with the same settings always gives the
 * same run. A seed is therefore a small text file, readable and reproducible.
 *
 * A file is a short list of "key value" lines so that a later version can add settings without breaking older files
 * (unknown keys are ignored, missing ones take their default):
 *
 *     re4seed 1
 *     seed 482130
 *     name Randomizer Shop
 *     merchant on 25
 *     items on
 *     enemies off
 *
 * Kept in the app's private storage (files/seeds), so nothing needs a permission and no seed leaves the device.
 * Seeds are tiny (a few hundred bytes each); the page that lists them offers a delete, to keep the folder tidy.
 */
final class RandomizerConfig {
  static final String DIRECTORY = "seeds";
  static final String SUFFIX = ".re4seed";
  static final int FORMAT = 1;
  /** The seed is 20 bits everywhere in the core (re4_core_set_enemy_random, re4_core_set_merchant_random). */
  static final int SEED_MAX = (1 << 20) - 1;

  /** A randomizer the player can switch on before the game starts. `chance` is 0 when the feature has no such setting. */
  static final class Feature {
    final String id;
    final String label;
    boolean on;
    /** Merchant only for now: the percent of merchants that offer new goods. 100 = every one. */
    int chance;
    /** Merchant only: a bit per weapon of the core's list, 0 = its default set (RandomizerConfig.WEAPONS). */
    int weapons;

    Feature(String id, String label, boolean on, int chance) {
      this.id = id;
      this.label = label;
      this.on = on;
      this.chance = chance;
      this.weapons = 0;
    }

    boolean hasChance() {
      return id.equals("merchant");
    }

    boolean hasWeapons() {
      return id.equals("merchant");
    }
  }

  /**
   * The weapons the merchant randomizer may hand out, in the core's order (runtime/merchant_random.cpp kWeapons).
   * The bit of an entry is its weapon bit; the names are the app's own.
   */
  static final String[] WEAPONS = {
      "Handgun", "Punisher", "Red9", "Blacktail", "Matilda", "Shotgun", "Riot Gun", "Striker", "Rifle",
      "Rifle (semi-auto)", "TMP", "Chicago Typewriter", "Mine Thrower", "Broken Butterfly", "Killer7",
      "Handcannon", "Rocket Launcher",
  };
  /** The core's default set: everything but Chicago Typewriter, Handcannon and the Rocket Launcher. */
  static final int WEAPONS_DEFAULT = ~((1 << 11) | (1 << 15) | (1 << 16)) & ((1 << 17) - 1);

  int seed;
  String name = "";

  /**
   * The randomizers, in the order the page lists them. Adding a randomizer later means one entry here plus its
   * native setter in {@link RandomizerConfig#applyToCore()} (phase 1) and one row on the page (phase 2).
   * The ids are the file keys and must stay stable once a seed may exist on a player's device.
   */
  final Map<String, Feature> features = new LinkedHashMap<>();
  {
    features.put("merchant", new Feature("merchant", "Random merchant stock", false, 100));
    features.put("items", new Feature("items", "Random items", false, 0));
    features.put("enemies", new Feature("enemies", "Random enemies", false, 0));
  }

  RandomizerConfig() {}

  RandomizerConfig(int seed, String name) {
    this.seed = seed;
    this.name = name == null ? "" : name;
  }

  Feature feature(String id) {
    Feature f = features.get(id);
    if (f == null) throw new IllegalArgumentException("unknown randomizer: " + id);
    return f;
  }

  /** A new seed for a new run, 1..SEED_MAX (0 means "off" in the core, so it is never handed out). */
  static int freshSeed() {
    return 1 + new java.util.Random().nextInt(SEED_MAX);
  }

  int enabledCount() {
    int n = 0;
    for (Feature f : features.values()) if (f.on) n++;
    return n;
  }

  boolean anyEnabled() {
    return enabledCount() > 0;
  }

  /** The settings as one line for the in-game menu: "seed 482130, 2 randomizers on". */
  String summary() {
    final int n = enabledCount();
    if (seed == 0 || n == 0) return "no seed";
    return "seed " + seed + ", " + n + (n == 1 ? " randomizer on" : " randomizers on");
  }

  // ---- the file format -------------------------------------------------------------------------------------------

  String toText() {
    StringBuilder out = new StringBuilder();
    out.append("re4seed ").append(FORMAT).append('\n');
    out.append("seed ").append(seed).append('\n');
    if (!name.isEmpty()) out.append("name ").append(name).append('\n');
    for (Feature f : features.values()) {
      out.append(f.id).append(' ').append(f.on ? "on" : "off");
      if (f.hasChance() && f.chance != 100) out.append(' ').append(f.chance);
      if (f.hasWeapons() && f.weapons != 0) out.append(" w").append(f.weapons);
      out.append('\n');
    }
    return out.toString();
  }

  static RandomizerConfig fromText(String text) throws IOException {
    RandomizerConfig c = new RandomizerConfig();
    int format = 0;
    for (String raw : text.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] part = line.split("\\s+");
      String key = part[0].toLowerCase(Locale.ROOT);
      if (key.equals("re4seed")) {
        format = parse(part, 1, 0);
      } else if (key.equals("seed")) {
        c.seed = Math.max(0, Math.min(SEED_MAX, parse(part, 1, 0)));
      } else if (key.equals("name")) {
        int at = line.indexOf(' ');
        c.name = at < 0 ? "" : line.substring(at + 1).trim();
      } else {
        Feature f = c.features.get(key);
        if (f == null) continue;  // a setting of a newer version: keep it out of the way rather than fail
        if (part.length > 1) f.on = part[1].equalsIgnoreCase("on") || part[1].equals("1");
        if (part.length > 2) {
          String third = part[2];
          if (third.startsWith("w")) f.weapons = parse(third.substring(1));
          else f.chance = Math.max(1, Math.min(100, parse(third)));
        }
        if (part.length > 3 && part[3].startsWith("w")) f.weapons = parse(part[3].substring(1));
      }
    }
    if (format > FORMAT) throw new IOException("This seed comes from a newer version (" + format + ")");
    if (c.seed == 0 && c.anyEnabled()) c.seed = freshSeed();
    return c;
  }

  private static int parse(String[] part, int at, int fallback) {
    if (at >= part.length) return fallback;
    return parse(part[at], fallback);
  }

  private static int parse(String value) {
    return parse(value, 0);
  }

  private static int parse(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  // ---- what the core hears ---------------------------------------------------------------------------------------

  /**
   * Hands the run to the game core. Called once, when CoreActivity starts the core, and again whenever the settings
   * change. The three randomizers keep their own core calls (each has its own seed handling in the core); they all
   * take this run's single seed, so one seed defines the whole run. The cheats bitfield no longer carries "random
   * items": that randomizer moves out of it with this class (phase 1), its seed in the cheats bits is cleared.
   */
  void applyToCore() {
    if (!anyEnabled()) {
      CoreBridge.setEnemyRandom(0);
      CoreBridge.setMerchantRandom(0);
      return;
    }
    final int s = seed == 0 ? 0 : seed;
    CoreBridge.setEnemyRandom(feature("enemies").on ? s : 0);
    final Feature merchant = feature("merchant");
    CoreBridge.setMerchantRandom(merchant.on ? s : 0);
    // phase 5: how often a merchant deals anew, and which weapons he may hand out
    if (merchant.on) CoreBridge.setMerchantOptions(merchant.chance <= 0 ? 100 : merchant.chance, merchant.weapons);
  }
}
