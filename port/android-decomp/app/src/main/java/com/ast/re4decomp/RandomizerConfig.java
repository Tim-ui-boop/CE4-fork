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
      this(id, label, on, chance, 0, 0);
    }

    boolean hasChance() {
      return id.equals("merchant");
    }

    boolean hasWeapons() {
      return id.equals("merchant");
    }

    /** The enemies feature has a kind pool and ratio mix instead (0.99.16); see ENEMY_POOL and packRatios. */
    int pool;

    /** The ratio mix packed one nibble each: plaga, helmet, shield, sub-boss; bits 16..17 the variety cap. */
    int ratios;

    boolean hasEnemyOptions() {
      return id.equals("enemies");
    }

    Feature(String id, String label, boolean on, int chance, int pool, int ratios) {
      this.id = id;
      this.label = label;
      this.on = on;
      this.chance = chance;
      this.weapons = 0;
      this.pool = pool;
      this.ratios = ratios;
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

  /**
   * The enemy kinds the enemy randomizer may turn an ordinary Ganado into, in the core's bit order
   * (runtime/enemy_random.cpp kPoolKinds). Bit i of the feature's `pool` answers "may the i-th kind appear".
   * The two Krausers (the owner's wish of 17.11.2026) are off by default: a boss in every room is a much
   * different game; the game's own bosses (Saddler, U3, Salazar) stay out — the scripts own them.
   */
  static final String[] ENEMY_POOL = {
      "Chainsaw Man", "Gatling Man", "Garrador", "Novistador", "Dog", "Crow", "Cow", "Armadura",
      "Regenerator", "Iron Maiden", "Krauser (knife)", "Krauser (mutated)",
  };
  /** The default pool: the PC randomizer's own defaults keep Novistador, Crow and Cow off (its help text). */
  static final int ENEMY_POOL_DEFAULT = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 4) | (1 << 7) | (1 << 8) | (1 << 9);
  /** The ratio sliders' shifts in the packed `ratios`: a step k means "one in 16 - k" (0 never, 15 every one). */
  static final String[] ENEMY_RATIOS = {"Plaga", "Helmet", "Shield", "Sub-boss"};
  static final int[] RATIO_SHIFT = {0, 4, 8, 12};
  static final int RATIO_OFF = 0, RATIO_MAX = 15;
  /** The "unsafe" switch of the enemy randomizer (the owner's name, 17.11.2026): bit 16 of `ratios` -- the
   *  cautious rooms open up (their unnamed enemies shuffle; boss rooms and narrow-counting rooms stay locked). */
  static final int UNSAFE_SHIFT = 16;
  /** The variety cap: pool kinds a room may roll (bits 17..18 of `ratios`; 0: the core's default of 4). */
  static final int VARIETY_MASK = 3, VARIETY_SHIFT = 17, VARIETY_DEFAULT = 3;

  /** Packs the four ratio steps (0..15 each), the unsafe switch and the variety cap into one int, as the core
   *  reads it. `variety` is the player's pick 1..4; the core's bits 17..18 take 1..3 as themselves and 4 as 0
   *  (its own "the default"), bit 16 is the unsafe switch. */
  static int packRatios(int plaga, int helmet, int shield, int subBoss, int variety, boolean unsafe) {
    int out = 0;
    int[] steps = {plaga, helmet, shield, subBoss};
    for (int i = 0; i < 4; i++) {
      out |= Math.max(RATIO_OFF, Math.min(RATIO_MAX, steps[i])) << RATIO_SHIFT[i];
    }
    out |= (unsafe ? 1 : 0) << UNSAFE_SHIFT;
    final int capBits = variety >= 4 ? 0 : Math.max(1, Math.min(3, variety));
    return out | (capBits << VARIETY_SHIFT);
  }

  /** The variety cap the player picked, from the packed `ratios` (the core's 0 = its default of 4). */
  static int varietyOf(int ratios) {
    final int bits = (ratios >> VARIETY_SHIFT) & VARIETY_MASK;
    return bits == 0 ? 4 : bits;
  }

  /** Whether the unsafe switch is on in the packed `ratios`. */
  static boolean unsafeOf(int ratios) {
    return (ratios >> UNSAFE_SHIFT & 1) != 0;
  }

  /** The mix the enemies feature starts with — the PC tool's Default profile flavor: plaga its 5, helmet its 1
   *  (rare), shield its 7, the sub-boss about its 1:10, the variety limit its 3, unsafe mode off (a switch of
   *  the owner's, 17.11.2026: the cautious rooms stay shut until they ask for it). One constant, so the page,
   *  the store and the seed file all agree on "the defaults". */
  static final int ENEMY_RATIOS_DEFAULT = packRatios(5, 1, 7, 6, VARIETY_DEFAULT, false);

  int seed;
  String name = "";

  /**
   * "Diagnostics log" of the randomizer page (wish of the project owner, 17.11.2026): keeps the in-depth log of the
   * randomizer's decisions (runtime/re4_diag.cpp) on for this run. Not a randomizer: it changes nothing in the game,
   * so it stays out of {@link #features} (it would count as one in {@link #summary}), and it is saved in the seed file
   * as one more key, which an older build ignores.
   */
  boolean diag;

  /**
   * The randomizers, in the order the page lists them. Adding a randomizer later means one entry here plus its
   * native setter in {@link RandomizerConfig#applyToCore()} (phase 1) and one row on the page (phase 2).
   * The ids are the file keys and must stay stable once a seed may exist on a player's device.
   */
  final Map<String, Feature> features = new LinkedHashMap<>();
  {
    features.put("merchant", new Feature("merchant", "Random merchant stock", false, 100));
    features.put("items", new Feature("items", "Random items", false, 0));
    // 0.99.16: the PC randomizer's "Enemy settings" (the owner's choice of 16.11.2026): its replacements pool,
    // its ratios and its variety limit; its HP tab stays out. Defaults are that tool's own defaults.
    features.put("enemies", new Feature("enemies", "Random enemies", false, 0, ENEMY_POOL_DEFAULT,
        ENEMY_RATIOS_DEFAULT));  // 0.99.16: unsafe off by default -- the cautious rooms stay shut
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

  /**
   * Takes another run's values into this object. The views of the page hold references to this run's Feature
   * objects, so the run must never be swapped for another instance: a new seed or a loaded file changes the values
   * here instead, and every switch keeps working.
   */
  void adopt(RandomizerConfig other) {
    seed = other.seed;
    name = other.name;
    for (Feature mine : features.values()) {
      Feature theirs = other.features.get(mine.id);
      if (theirs == null) continue;
      mine.on = theirs.on;
      mine.chance = theirs.chance;
      mine.weapons = theirs.weapons;
      mine.pool = theirs.pool;
      mine.ratios = theirs.ratios;
    }
    diag = other.diag;
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
    if (diag) out.append("diag on\n");
    for (Feature f : features.values()) {
      out.append(f.id).append(' ').append(f.on ? "on" : "off");
      if (f.hasChance() && f.chance != 100) out.append(' ').append(f.chance);
      if (f.hasWeapons() && f.weapons != 0) out.append(" w").append(f.weapons);
      if (f.hasEnemyOptions()) {
        if (f.pool != ENEMY_POOL_DEFAULT) out.append(" p").append(f.pool);
        if (f.ratios != ENEMY_RATIOS_DEFAULT) out.append(" r").append(f.ratios);
      }
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
      } else if (key.equals("diag")) {
        // a file from a build before this key simply has no line here: the switch stays off
        c.diag = part.length > 1 && (part[1].equalsIgnoreCase("on") || part[1].equals("1"));
      } else {
        Feature f = c.features.get(key);
        if (f == null) continue;  // a setting of a newer version: keep it out of the way rather than fail
        if (part.length > 1) f.on = part[1].equalsIgnoreCase("on") || part[1].equals("1");
        if (part.length > 2) {
          String third = part[2];
          if (third.startsWith("w")) f.weapons = parse(third.substring(1));
          else if (third.startsWith("p")) f.pool = parse(third.substring(1));
          else if (third.startsWith("r")) f.ratios = parse(third.substring(1));
          else f.chance = Math.max(1, Math.min(100, parse(third)));
        }
        if (part.length > 3) {
          // third and fourth column both carry an option: the merchant's weapon mask (older files), the
          // enemies' packed ratios (0.99.16)
          if (part[3].startsWith("w")) f.weapons = parse(part[3].substring(1));
          else if (part[3].startsWith("r")) f.ratios = parse(part[3].substring(1));
          else if (part[3].startsWith("p")) f.pool = parse(part[3].substring(1));
        }
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
   * change. The enemy and merchant randomizers keep their own core calls (each has its own seed handling in the
   * core); they all take this run's single seed, so one seed defines the whole run. The item randomizer stays the
   * cheat bit it always was (runtime/cheats.cpp: bit 1024, the run's seed in bits 11..30); its switch moved from the
   * in-game cheat menu to the page, and {@link SeedStore#setCurrent} keeps its bits with the run, so the core hears
   * it through the cheat bits it reads at its start.
   */
  void applyToCore() {
    if (!anyEnabled()) {
      CoreBridge.setEnemyRandom(0);
      CoreBridge.setMerchantRandom(0);
      return;
    }
    final int s = seed == 0 ? 0 : seed;
    // The enemy randomizer is a data rewrite since 18.11.2026 (EslRandomizer/EnemyRandomizer): one seed writes
    // patched enemy lists the loader prefers over the disc's own, so there is nothing to tell a running core.
    // The play-time swap (CoreBridge.setEnemyRandom) is retired in runtime/enemy_random.cpp -- it takes every
    // seed as 0 and plans nothing.
    final Feature merchant = feature("merchant");
    CoreBridge.setMerchantRandom(merchant.on ? s : 0);
    // phase 5: how often a merchant deals anew, and which weapons he may hand out
    if (merchant.on) CoreBridge.setMerchantOptions(merchant.chance <= 0 ? 100 : merchant.chance, merchant.weapons);
  }

  /**
   * The "Diagnostics log" switch as a cheat bit, the way the core hears it: bit 31 (CoreMenu.DIAG_BIT) is what
   * runtime/re4_diag.cpp asks for through re4_diag_cheat. `prefs` is CoreActivity's GAME_PREFS, which holds the bits
   * for the next core start, so this works both before a core runs and while one does. Called by
   * {@link SeedStore#setCurrent} when the switch is turned and by CoreActivity when the core starts (a saved seed's
   * "diag on" would otherwise only reach a core once the page had been opened).
   */
  void applyDiagToCore(android.content.SharedPreferences prefs) {
    if (prefs == null) return;
    final int bits = prefs.getInt(CoreActivity.CHEATS, 0);
    final int wanted = CoreMenu.withDiag(bits, diag);
    if (wanted != bits) prefs.edit().putInt(CoreActivity.CHEATS, wanted).apply();
    CoreBridge.setCheats(wanted);
  }
}
