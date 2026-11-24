package com.ast.re4decomp;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The offline enemy randomizer: deals the enemy lists BEFORE play and writes private copies.
 *
 * <p>The player picks a seed in the launcher, this reads the imported disc's enemy lists
 * ({@code game/disc1/files/etc/emleon0N.esl}), deals them, and writes the result to
 * {@code randomizer/enemies/etc/emleon0N.esl} beside an {@code active} marker. The core's loader
 * serves those copies instead of the disc's ({@code runtime/enemy_sidecar.cpp}); the import and the
 * saves are never written. Deleting the randomizer folder plays the game as the disc has it.
 *
 * <p>Why offline: the deal can be checked before anyone plays it. Every written list is re-read and
 * compared with its source, and the whole deal is refused unless every record kept its room, its
 * place, its health and its scripted entrance. That is the same "prove it before you play" property
 * the door randomizer has, and it is achievable here because the invariant is simple: same enemies,
 * same number, same places — different faces and weapons.
 *
 * <p>What changes: {@code id} (only within the Ganado family 0x10..0x20), {@code type} (the model
 * variant) and {@code flag} (the weapon and clothing bits). What never changes: {@code set},
 * {@code room}, {@code pos}, {@code rot}, {@code hp}, {@code Character}, {@code Guard_r},
 * {@code be_flag}. See port/docs/enemy-types-user-guide.md for the measurements behind those rules.
 *
 * <p>The records are BIG ENDIAN, as on the GameCube disc (the PC randomizer's own lists are the same
 * layout little endian; they are a design reference, not interchangeable bytes).
 */
final class EnemyShuffle {
  private static final String TAG = "EnemyShuffle";

  private EnemyShuffle() {}

  /** One enemy list entry: 32 bytes, big endian (EM_LIST, include/global.h). */
  private static final int ENTRY = 32;
  private static final int ENTRIES = 256;
  private static final int LIST_BYTES = ENTRIES * ENTRY;  // 0x2000, what the game reads
  /** The story lists on the disc hold 255 entries; the drive zero-fills the last one. */
  private static final int MIN_BYTES = 255 * ENTRY;

  private static final int BE_FLAG = 0x00, ID = 0x01, TYPE = 0x02, SET = 0x03, FLAG = 0x04,
      HP = 0x08, CHARACTER = 0x0B, POS = 0x0C, ROT = 0x12, ROOM = 0x18, GUARD = 0x1A;

  /** The option bits, shared with the core (runtime/enemy_types.cpp) and CoreActivity. */
  static final int SAW = 1, GARRADOR = 1 << 1, JJ = 1 << 2, SUPER = 1 << 3, VARIETY = 1 << 4,
      HELMET = 1 << 5, SHIELD = 1 << 6, PLAGA = 1 << 7, DYNAMITE = 1 << 8, MIXED = 1 << 9,
      SAFE_CABIN = 1 << 10;
  static final int DEFAULT_OPTIONS = SAW | VARIETY | SAFE_CABIN;

  private static final int FLAG_SAW = 1 << 28, FLAG_PLAGA = 1 << 20, FLAG_BOMB = 1 << 17,
      FLAG_HELMET = 1 << 9, FLAG_SHIELD = 1 << 31;
  private static final int WEAPON_MASK =
      FLAG_SAW | FLAG_BOMB | (1 << 27) | (1 << 29) | (1 << 30) | (1 << 31) | (1 << 11);
  // The r100 test uses two weapons the island module supports: crossbow and the big club.
  private static final int FLAG_BOWGUN = 1 << 15, FLAG_CLUB = 1 << 11;
  private static final int TEST_WEAPON_MASK = WEAPON_MASK | FLAG_BOWGUN;

  /** The model types each Ganado module can show (the case labels of its EmNNSet switch). */
  private static final Map<Integer, int[]> TYPES = new LinkedHashMap<>();
  static {
    TYPES.put(0x10, new int[] {0, 1, 3, 4});
    TYPES.put(0x11, new int[] {7, 8, 9});
    TYPES.put(0x12, new int[] {0, 1, 3, 4});
    TYPES.put(0x13, new int[] {0, 1, 3, 4, 6});
    TYPES.put(0x14, new int[] {6, 7, 8, 9});
    TYPES.put(0x15, new int[] {0, 3, 4, 11});
    TYPES.put(0x16, new int[] {3, 4, 11, 12});
    TYPES.put(0x17, new int[] {0, 1, 3, 12});
    TYPES.put(0x19, new int[] {7, 8, 9});
    TYPES.put(0x1A, new int[] {7, 8, 9});
    TYPES.put(0x1B, new int[] {7, 8, 10});
    TYPES.put(0x1C, new int[] {7, 9, 10, 13});
    TYPES.put(0x1D, new int[] {2, 14, 15, 16, 17, 18, 19, 20, 21});
    TYPES.put(0x1E, new int[] {6, 14, 15, 23, 25});
    TYPES.put(0x1F, new int[] {14, 15, 16, 24});
    TYPES.put(0x20, new int[] {14, 15, 16, 22});
  }

  private static final String[] LISTS = {
      "etc/emleon00.esl", "etc/emleon01.esl", "etc/emleon02.esl", "etc/emleon03.esl",
      "etc/emleon04.esl", "etc/emleon05.esl", "etc/emleon06.esl", "etc/emleon07.esl"};

  // --- the record readers (big endian) ---
  private static int u8(byte[] b, int at) { return b[at] & 0xFF; }
  private static int u16(byte[] b, int at) { return (u8(b, at) << 8) | u8(b, at + 1); }
  private static int u32(byte[] b, int at) {
    return (u8(b, at) << 24) | (u8(b, at + 1) << 16) | (u8(b, at + 2) << 8) | u8(b, at + 3);
  }
  private static void putU8(byte[] b, int at, int v) { b[at] = (byte) v; }
  private static void putU16(byte[] b, int at, int v) { b[at] = (byte) (v >>> 8); b[at + 1] = (byte) v; }
  private static void putU32(byte[] b, int at, int v) {
    b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16);
    b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v;
  }

  private static boolean ganado(int id) { return id >= 0x10 && id <= 0x20 && id != 0x18; }

  private static int family(int id) {
    switch (id) {
      case 0x10: case 0x12: case 0x13: case 0x15: case 0x16: case 0x17: return 0;  // village
      case 0x11: case 0x14: case 0x19: case 0x1A: case 0x1B: case 0x1C: return 1;  // castle
      case 0x1D: case 0x1E: case 0x1F: case 0x20: return 2;                        // island
      default: return -1;
    }
  }

  private static boolean sawCapable(int id) {
    return id == 0x10 || id == 0x12 || id == 0x13 || id == 0x15 || id == 0x16 || id == 0x17;
  }

  /** A special form the room's script or its own module drives: never touched. */
  private static boolean special(int id, int type, int flag) {
    if (id <= 0x17 && type == 4) return true;
    if (type == 6 && (id == 0x13 || id == 0x14 || id == 0x1E)) return true;
    if ((id == 0x1B && type == 10) || (id == 0x1C && (type == 10 || type == 13))) return true;
    if (id == 0x1D && type == 2) return true;
    return id == 0x20 && (type == 22 || (flag & FLAG_SAW) != 0);
  }

  private static boolean cabin(int room) { return room == 0x11C || room == 0x11D; }

  private static boolean typeOk(int id, int type) {
    int[] t = TYPES.get(id);
    if (t == null) return false;
    for (int v : t) if (v == type) return true;
    return false;
  }

  // --- the same hash the core uses, so a seed means one thing everywhere ---
  private static int mix(int x) {
    x ^= x >>> 16; x *= 0x7feb352d;
    x ^= x >>> 15; x *= 0x846ca68b;
    x ^= x >>> 16;
    return x;
  }

  private static int hashOf(int seed, int list, int room, int what) {
    return mix(seed * 0x9E3779B1 ^ mix((list << 24) ^ (room << 8) ^ mix(what + 0x51)));
  }

  private static boolean oneIn(int h, int den) {
    return den > 0 && Integer.remainderUnsigned(h, den) == 0;
  }

  /** What one entry becomes; null leaves it exactly as the disc has it. */
  private static int[] dealFor(int seed, int list, int room, int no, byte[] src, int at,
      int options, List<Integer> kinds) {
    int id = u8(src, at + ID), type = u8(src, at + TYPE), flag = u32(src, at + FLAG);
    int fam = family(id);
    int h = hashOf(seed, list, room, 0x900 + no);
    boolean cab = cabin(room);
    int newId = id, newType = type, set = 0, clear = 0;
    String tag = null;

    if (!cab || (options & SAFE_CABIN) == 0) {
      if (fam == 0 && (options & SAW) != 0 && sawCapable(id) && oneIn(h, 12)) {
        set = FLAG_SAW; clear = WEAPON_MASK & ~FLAG_SAW; newType = 4; tag = "saw";
      } else if (fam == 1 && (options & GARRADOR) != 0 && (id == 0x1B || id == 0x1C) && oneIn(h, 8)) {
        newType = 10; clear = WEAPON_MASK; tag = "garrador";
      } else if (fam == 2 && (options & JJ) != 0 && id != 0x1D && oneIn(h, 2)) {
        if (!budget(kinds, 0x1D)) return null;
        newId = 0x1D; newType = 2; clear = WEAPON_MASK; tag = "jj";
      } else if (fam == 2 && (options & SUPER) != 0 && id != 0x20 && oneIn(h, 8)) {
        if (!budget(kinds, 0x20)) return null;
        newId = 0x20; newType = 22; set = FLAG_SAW; clear = WEAPON_MASK & ~FLAG_SAW; tag = "super";
      }
    }
    if ((options & MIXED) != 0 && newId == id && set == 0 && fam != -1 && !cab
        && sawCapable(id) && (options & SAW) != 0 && oneIn(h ^ 0x5A, 20)) {
      set = FLAG_SAW; clear = WEAPON_MASK & ~FLAG_SAW; newType = 4; tag = "saw";
    }
    if ((options & VARIETY) != 0 && newId == id && set == 0 && clear == 0 && newType == type) {
      int[] sup = TYPES.get(id);
      if (sup != null && sup.length > 1) {
        List<Integer> pick = new ArrayList<>();
        for (int t : sup) {
          if (t != type && !special(id, t, flag)) pick.add(t);
        }
        if (!pick.isEmpty() && oneIn(h ^ 0x11, 2)) {
          newType = pick.get(Integer.remainderUnsigned(h >>> 8, pick.size()));
          tag = "variant";
        }
      }
    }
    if (newId == id && set == 0) {
      if ((options & HELMET) != 0 && oneIn(h ^ 0x21, 10)) { set |= FLAG_HELMET; tag = "helmet"; }
      if ((options & SHIELD) != 0 && (fam == 1 || fam == 2) && oneIn(h ^ 0x31, 7)) {
        set |= FLAG_SHIELD; tag = "shield";
      }
      if ((options & PLAGA) != 0 && oneIn(h ^ 0x41, 5) && (set & FLAG_HELMET) == 0) {
        set |= FLAG_PLAGA; tag = "plaga";
      }
      if ((options & DYNAMITE) != 0 && fam == 0 && oneIn(h ^ 0x51, 12)) {
        set |= FLAG_BOMB; tag = "dynamite";
      }
    }
    if (newId == id && newType == type && set == 0 && clear == 0) return null;
    if (!ganado(newId) || !typeOk(newId, newType)) return null;
    return new int[] {newId, newType, set, clear, tag == null ? 0 : 1};
  }

  /** The room may load at most 4 enemy modules; 3 leaves a slot for Ashley or Luis. */
  private static boolean budget(List<Integer> kinds, int id) {
    if (kinds.contains(id)) return true;
    if (kinds.size() >= 3) return false;
    kinds.add(id);
    return true;
  }

  /** The deal's own report, written beside the lists so a refusal can be read on the phone. */
  static final class Result {
    boolean ok;
    String message = "";
    int dealt, rooms, saws, garradors, jjs, supers, variants;
  }

  private static File outDir(File dataRoot) { return new File(dataRoot, "randomizer/enemies"); }
  private static File marker(File dataRoot) { return new File(outDir(dataRoot), "active"); }
  private static File logFile(File dataRoot) { return new File(outDir(dataRoot), "enemies.txt"); }

  /** True when a dealt set is currently active. */
  static boolean active(File dataRoot) { return marker(dataRoot).isFile(); }

  /** Removes the dealt set: the game reads the disc again. The import and the saves stay. */
  static String clear(File dataRoot) {
    File dir = outDir(dataRoot);
    int n = deleteTree(dir);
    Log.i(TAG, "cleared the dealt enemy lists (" + n + " files)");
    return "Enemy randomizer off. The game reads the disc again (" + n + " files removed).";
  }

  private static int deleteTree(File f) {
    if (f == null || !f.exists()) return 0;
    int n = 0;
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) n += deleteTree(k);
    return f.delete() ? n + 1 : n;
  }

  /**
   * Deals every story enemy list with {@code seed} and writes the private copies. Refuses the whole
   * deal (writing nothing, leaving any previous set alone) when a source is missing or a written list
   * does not verify. Returns a report for the launcher.
   */
  static Result deal(File dataRoot, File discFiles, long seed, int options) {
    Result r = new Result();
    StringBuilder log = new StringBuilder();
    log.append("Enemy randomizer, seed ").append(seed).append(", options ").append(options).append('\n');

    Map<String, byte[]> written = new LinkedHashMap<>();
    Map<String, byte[]> sources = new LinkedHashMap<>();
    for (int li = 0; li < LISTS.length; li++) {
      String name = LISTS[li];
      File src = new File(discFiles, name);
      byte[] raw = read(src);
      if (raw == null) {
        r.message = "The imported disc has no " + name + ". Import the disc again, then deal.";
        Log.i(TAG, r.message);
        return r;
      }
      if (raw.length < MIN_BYTES || raw.length > LIST_BYTES || raw.length % ENTRY != 0) {
        r.message = name + " is " + raw.length + " bytes, not an enemy list. Nothing was written.";
        Log.i(TAG, r.message);
        return r;
      }
      byte[] out = new byte[LIST_BYTES];
      System.arraycopy(raw, 0, out, 0, raw.length);  // the last entry stays zero, as the drive gives
      sources.put(name, raw);

      // the modules this list's rooms already need
      Map<Integer, List<Integer>> roomKinds = new LinkedHashMap<>();
      for (int no = 0; no < ENTRIES; no++) {
        int at = no * ENTRY;
        int id = u8(out, at + ID);
        if (id == 0 || (u8(out, at + BE_FLAG) & 1) == 0) continue;
        int room = u16(out, at + ROOM);
        List<Integer> k = roomKinds.get(room);
        if (k == null) { k = new ArrayList<>(); roomKinds.put(room, k); }
        if (!k.contains(id) && k.size() < 4) k.add(id);
      }
      int touchedRooms = 0;
      for (int room : new ArrayList<>(roomKinds.keySet())) {
        boolean any = false;
        List<Integer> kinds = roomKinds.get(room);
        for (int no = 0; no < ENTRIES; no++) {
          int at = no * ENTRY;
          int id = u8(out, at + ID);
          if (!ganado(id) || u16(out, at + ROOM) != room) continue;
          if ((u8(out, at + BE_FLAG) & 1) == 0 || (u8(out, at + BE_FLAG) & 2) != 0) continue;
          if (u8(out, at + SET) != 0) continue;               // a scripted entrance
          if (special(id, u8(out, at + TYPE), u32(out, at + FLAG))) continue;
          int[] d = dealFor((int) seed, li, room, no, out, at, options, kinds);
          if (d == null) continue;
          putU8(out, at + ID, d[0]);
          putU8(out, at + TYPE, d[1]);
          putU32(out, at + FLAG, (u32(out, at + FLAG) & ~d[3]) | d[2]);
          r.dealt++;
          any = true;
          if ((d[2] & FLAG_SAW) != 0 && d[0] != 0x20) r.saws++;
          else if (d[0] == 0x1D && id != 0x1D) r.jjs++;
          else if (d[0] == 0x20) r.supers++;
          else if (d[1] == 10 && (id == 0x1B || id == 0x1C)) r.garradors++;
          else if (d[0] == id && d[2] == 0) r.variants++;
        }
        if (any) touchedRooms++;
      }
      r.rooms += touchedRooms;
      written.put(name, out);
      log.append(name).append(": ").append(touchedRooms).append(" rooms touched\n");
    }

    // verify BEFORE anything is written: every record keeps everything but id, type and flag
    for (Map.Entry<String, byte[]> e : written.entrySet()) {
      byte[] src = sources.get(e.getKey()), out = e.getValue();
      String why = verify(src, out);
      if (why != null) {
        r.message = e.getKey() + ": " + why + " Nothing was written.";
        Log.i(TAG, r.message);
        return r;
      }
    }
    // write to a staging folder, then re-read each file and compare, then activate
    File dir = outDir(dataRoot);
    File stage = new File(dir, "staging");
    deleteTree(stage);
    try {
      for (Map.Entry<String, byte[]> e : written.entrySet()) {
        File f = new File(stage, e.getKey());
        f.getParentFile().mkdirs();
        writeAll(f, e.getValue());
        byte[] back = read(f);
        if (back == null || !java.util.Arrays.equals(back, e.getValue())) {
          r.message = e.getKey() + " did not read back as written. Nothing was activated.";
          Log.i(TAG, r.message);
          deleteTree(stage);
          return r;
        }
      }
      // promote: remove the previous set's lists, move the staged ones in, then the marker
      marker(dataRoot).delete();
      for (String name : LISTS) {
        File old = new File(dir, name);
        File now = new File(stage, name);
        old.getParentFile().mkdirs();
        if (old.exists() && !old.delete()) {
          r.message = "Could not replace " + name + ". The previous set is unchanged.";
          Log.i(TAG, r.message);
          return r;
        }
        if (!now.renameTo(old)) {
          r.message = "Could not put " + name + " in place. Nothing was activated.";
          Log.i(TAG, r.message);
          return r;
        }
      }
      deleteTree(stage);
      r.ok = true;
      r.message = String.format(Locale.ROOT,
          "Dealt %d enemies in %d rooms: %d chainsaw, %d Garrador, %d JJ, %d super Salvador, "
              + "%d other looks. Seed %d.",
          r.dealt, r.rooms, r.saws, r.garradors, r.jjs, r.supers, r.variants, seed);
      log.append(r.message).append('\n');
      writeAll(logFile(dataRoot), log.toString().getBytes(StandardCharsets.UTF_8));
      writeAll(marker(dataRoot), String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
      Log.i(TAG, r.message);
    } catch (IOException ex) {
      r.message = "Could not write the dealt lists: " + ex.getMessage() + ". Nothing was activated.";
      Log.e(TAG, r.message);
      deleteTree(stage);
    }
    return r;
  }

  /** Everything but id, type and flag must be identical, and every dealt record must be sane. */
  /**
   * Fixed test for room 1-00. Rewrites only r100's records of emleon00: villagers become island
   * soldiers (crossbows / big clubs / plain), the five crows become JJ and regenerators, and the
   * bear traps become island soldiers so the room never needs a fifth enemy module. The hardcoded
   * officer-killer (0x12) and the dog (0x21) keep their kinds, giving exactly four loaded kinds.
   */
  static Result dealR100Test(File dataRoot, File discFiles) {
    Result r = new Result();
    final String name = "etc/emleon00.esl";
    byte[] src = read(new File(discFiles, name));
    if (src == null || src.length < MIN_BYTES || src.length > LIST_BYTES || src.length % ENTRY != 0) {
      r.message = "The imported disc has no usable " + name + ". Import both discs again, then try.";
      Log.i(TAG, r.message);
      return r;
    }
    byte[] out = new byte[LIST_BYTES];
    System.arraycopy(src, 0, out, 0, src.length);

    int villagers = 0, archers = 0, clubs = 0, plain = 0, jjs = 0, regs = 0, traps = 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (at + ENTRY > src.length) break;
      if (u16(out, at + ROOM) != 0x0100) continue;
      int id = u8(out, at + ID);
      int flag = u32(out, at + FLAG);
      if (id == 0x12) {
        int weapon;
        if (villagers % 3 == 0) { weapon = FLAG_BOWGUN; archers++; }
        else if (villagers % 3 == 1) { weapon = FLAG_CLUB; clubs++; }
        else { weapon = 0; plain++; }
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14 + (villagers % 8));
        putU32(out, at + FLAG, (flag & ~TEST_WEAPON_MASK) | weapon);
        villagers++;
      } else if (id == 0x23 && (u8(out, at + BE_FLAG) & 1) != 0) {
        // The crow's 10 HP would make a JJ or regenerator die in one shot.
        if (jjs + regs < 2) {
          putU8(out, at + ID, 0x1D);
          putU8(out, at + TYPE, 2);
          jjs++;
        } else {
          putU8(out, at + ID, 0x36);
          putU8(out, at + TYPE, 0);
          regs++;
        }
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        putU16(out, at + HP, 1000);
      } else if (id == 0x2a) {
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14 + (traps % 8));
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        traps++;
      }
    }
    if (villagers != 12 || jjs != 2 || regs != 3 || traps != 7) {
      r.message = "r100 is not the disc layout this test expects (" + villagers + " villagers, "
          + (jjs + regs) + " crows, " + traps + " traps). Nothing was written.";
      Log.i(TAG, r.message);
      return r;
    }
    String why = verifyTest(src, out);
    if (why != null) {
      r.message = why + " Nothing was written.";
      Log.i(TAG, r.message);
      return r;
    }

    File dir = outDir(dataRoot);
    File stage = new File(dir, "staging");
    deleteTree(stage);
    try {
      File staged = new File(stage, name);
      staged.getParentFile().mkdirs();
      writeAll(staged, out);
      byte[] back = read(staged);
      if (back == null || !java.util.Arrays.equals(back, out)) {
        r.message = "The test list did not read back as written. Nothing was activated.";
        Log.i(TAG, r.message);
        deleteTree(stage);
        return r;
      }
      marker(dataRoot).delete();
      File target = new File(dir, name);
      target.getParentFile().mkdirs();
      if (target.exists() && !target.delete()) {
        r.message = "Could not replace the previous " + name + ". Nothing was activated.";
        Log.i(TAG, r.message);
        return r;
      }
      if (!staged.renameTo(target)) {
        r.message = "Could not put the test list in place. Nothing was activated.";
        Log.i(TAG, r.message);
        return r;
      }
      deleteTree(stage);
      r.ok = true;
      r.message = "r100 test ready: 12 island soldiers (4 crossbows, 4 big clubs, 4 plain), "
          + "2 JJ, 3 regenerators; traps reused. Start the game and go to r100.";
      writeAll(logFile(dataRoot), (r.message + "\n").getBytes(StandardCharsets.UTF_8));
      writeAll(marker(dataRoot), "r100-test".getBytes(StandardCharsets.UTF_8));
      // The core sees this and drops the sound-bank parts of em1d/em36: their banks would
      // otherwise run into the music area in ARAM and crash on entering r100.
      writeAll(new File(dir, "r100-no-sound"), new byte[] {1});
      Log.i(TAG, "r100 test files: " + target + ", marker " + marker(dataRoot));
      Log.i(TAG, r.message);
    } catch (IOException ex) {
      r.message = "Could not write the test list: " + ex.getMessage() + ". Nothing was activated.";
      Log.e(TAG, r.message);
      deleteTree(stage);
    }
    return r;
  }

  /** The test's own check: only r100 records, only the fields the test claims to change. */
  private static String verifyTest(byte[] src, byte[] out) {
    java.util.Set<Integer> kinds = new java.util.HashSet<>();
    kinds.add(0x12);  // the hardcoded officer-killer is always loaded
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (at + ENTRY > src.length) {
        if (u8(out, at + ID) != 0) return "entry " + no + " was invented past the disc's end";
        continue;
      }
      if (u16(out, at + ROOM) != 0x0100) {
        for (int k = 0; k < ENTRY; k++) {
          if (src[at + k] != out[at + k]) return "entry " + no + " outside r100 changed";
        }
        continue;
      }
      if (u8(src, at + BE_FLAG) != u8(out, at + BE_FLAG)
          || u8(src, at + SET) != u8(out, at + SET)
          || u8(src, at + CHARACTER) != u8(out, at + CHARACTER)
          || u8(src, at + 0x0A) != u8(out, at + 0x0A)
          || u16(src, at + ROOM) != u16(out, at + ROOM)
          || u16(src, at + GUARD) != u16(out, at + GUARD)) {
        return "entry " + no + " changed a protected field";
      }
      for (int k = POS; k < ROOM; k++) {
        if (src[at + k] != out[at + k]) return "entry " + no + " moved or was retargeted";
      }
      if (u8(src, at + ID) != 0x23 && u16(src, at + HP) != u16(out, at + HP)) {
        return "entry " + no + " changed health";
      }
      int id = u8(out, at + ID);
      if (id != 0 && id != 0x12 && id != 0x1D && id != 0x36 && id != 0x21) {
        return "entry " + no + " has an unexpected kind " + id;
      }
      if (id != 0) kinds.add(id);
    }
    if (kinds.contains(0x2A) || kinds.size() > 4) {
      return "the test would need " + kinds.size() + " enemy modules in r100";
    }
    return null;
  }

  private static String verify(byte[] src, byte[] out) {
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (at + ENTRY > src.length) {
        // the source's missing last entry: the deal must leave it empty
        if (u8(out, at + ID) != 0) return "entry " + no + " was invented past the disc's end";
        continue;
      }
      for (int k = 0; k < ENTRY; k++) {
        if (k == ID || k == TYPE || k == FLAG || k == FLAG + 1 || k == FLAG + 2 || k == FLAG + 3) {
          continue;
        }
        if (src[at + k] != out[at + k]) {
          return "entry " + no + " changed byte " + k + " (only id, type and flag may change)";
        }
      }
      int id = u8(out, at + ID);
      if (id == 0) continue;
      /* An unused slot (be_flag bit0 clear) may carry a leftover id with no room: emleon07
         entry 155 is one on the retail disc. Only a LIVE record needs a valid room. */
      if ((u8(out, at + BE_FLAG) & 1) == 0) continue;
      if (!ganado(id) && id != u8(src, at + ID)) return "entry " + no + " left the Ganado family";
      if (!typeOk(id, u8(out, at + TYPE)) && id != u8(src, at + ID)) {
        return "entry " + no + " has a model its module cannot show";
      }
      int room = u16(out, at + ROOM);
      if (room == 0 || (room >>> 8) > 4) return "entry " + no + " has no valid room";
    }
    return null;
  }

  private static byte[] read(File f) {
    if (f == null || !f.isFile()) return null;
    try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
      long n = r.length();
      if (n <= 0 || n > LIST_BYTES) return null;
      byte[] b = new byte[(int) n];
      r.readFully(b);
      return b;
    } catch (IOException ex) {
      Log.e(TAG, "cannot read " + f + ": " + ex.getMessage());
      return null;
    }
  }

  private static void writeAll(File f, byte[] b) throws IOException {
    f.getParentFile().mkdirs();
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
      r.setLength(0);
      r.write(b);
    }
  }
}
