package com.ast.re4decomp;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline enemy randomizer for all story rooms.
 *
 * <p>The toggles define one pool: basic Ganado families, elites and bosses. Every ordinary record
 * in a dealt room is dealt from that pool using the seed and a per-enemy appearance ratio (1 in N).
 * The source-derived EnemyRooms table decides which rooms may change at all: untouched rooms and
 * rows the scripts drive by list number keep their exact disc bytes. The whole plan is built in
 * memory across all eight story enemy lists, so the launcher can preview it without writing
 * anything, and it is refused unless every protected byte and the four-module limit still hold.
 */
final class EnemyShuffle {
  private static final String TAG = "EnemyShuffle";
  private EnemyShuffle() {}

  private static final int ENTRY = 32, ENTRIES = 256;
  private static final int LIST_BYTES = ENTRIES * ENTRY;
  private static final int MIN_BYTES = 255 * ENTRY;

  private static final int BE_FLAG = 0x00, ID = 0x01, TYPE = 0x02, SET = 0x03, FLAG = 0x04,
      HP = 0x08, ROOM = 0x18;

  static final int VILLAGE = 1 << 22, CASTLE = 1 << 23, ISLAND = 1 << 24;
  static final int SAW = 1, GARRADOR = 1 << 1, JJ = 1 << 2, SUPER = 1 << 3, VARIETY = 1 << 4,
      HELMET = 1 << 5, SHIELD = 1 << 6, PLAGA = 1 << 7, DYNAMITE = 1 << 8, MIXED = 1 << 9,
      SAFE_CABIN = 1 << 10, REGEN = 1 << 11, IRON = 1 << 12, KRAUSER = 1 << 13,
      GIGANTE = 1 << 15, ARMADURA = 1 << 16, SHARED_SET = 1 << 17,
      RESET_SET = 1 << 18, BELLA = 1 << 19, VERDUGO = 1 << 21,
      ARMADURA_DARK = 1 << 25, KRAUSER_MUTANT = 1 << 26;

  /** The nine switches that are not pool members. */
  static final int[] MODIFIER_BITS = {VARIETY, HELMET, SHIELD, PLAGA, DYNAMITE, MIXED,
      SAFE_CABIN, SHARED_SET, RESET_SET};
  static final int DEFAULT_OPTIONS = VILLAGE | CASTLE | ISLAND | SAW | JJ | GARRADOR
      | REGEN | VARIETY | SAFE_CABIN | VERDUGO;

  private static final int FLAG_SAW = 1 << 28, FLAG_BOMB = 1 << 17,
      FLAG_PLAGA = 1 << 20, FLAG_HELMET = 1 << 9, FLAG_SHIELD = 1 << 31;
  private static final int WEAPON_MASK =
      FLAG_SAW | FLAG_BOMB | (1 << 27) | (1 << 29) | (1 << 30) | (1 << 31) | (1 << 11);

  private static final int A_ID = 1, A_TYPE = 2, A_FLAG = 4, A_HP = 8, A_SET = 16;

  private static final class Member {
    final String name;
    final int bit, hp, ratio;
    final int[] ids, types;
    final int flag;
    final boolean massive, basic;
    Member(String name, int bit, int[] ids, int[] types, int flag, int hp, int ratio,
        boolean massive, boolean basic) {
      this.name = name; this.bit = bit; this.ids = ids; this.types = types;
      this.flag = flag; this.hp = hp; this.ratio = ratio;
      this.massive = massive; this.basic = basic;
    }
    boolean counted() {
      for (int id : ids) if (id < 0x10 || id > 0x20) return false;
      return true;
    }
  }

  /** Three basic families, eight elites and five bosses. The first three are the fallback pool. */
  private static final Member[] POOL = {
    new Member("Village Ganado", VILLAGE, new int[] {0x12, 0x13},
        new int[] {0, 1, 3}, 0, 700, 2, false, true),
    new Member("Castle Zealot", CASTLE, new int[] {0x1B, 0x1C},
        new int[] {7, 8, 9}, 0, 1000, 2, false, true),
    new Member("Island Soldier", ISLAND, new int[] {0x1D},
        new int[] {14, 15, 16, 17, 18, 19, 20, 21}, 0, 1000, 2, false, true),
    new Member("Dr. Salvador", SAW, new int[] {0x12, 0x13, 0x15, 0x16, 0x17},
        new int[] {3, 4}, FLAG_SAW, 1200, 12, false, false),
    new Member("JJ", JJ, new int[] {0x1D}, new int[] {2}, 0, 1000, 2, false, false),
    new Member("Garrador", GARRADOR, new int[] {0x1B}, new int[] {10}, 0, 1200, 8, false, false),
    new Member("Bella Sisters", BELLA, new int[] {0x16}, new int[] {11, 12},
        FLAG_SAW, 1200, 12, false, false),
    new Member("Regenerator", REGEN, new int[] {0x36}, new int[] {0, 1},
        0, 1000, 8, false, false),
    new Member("Iron Maiden", IRON, new int[] {0x36}, new int[] {2, 3},
        0, 1000, 12, false, false),
    new Member("Armadura (white/grey)", ARMADURA, new int[] {0x3C}, new int[] {0, 1},
        0, 1000, 8, false, false),
    new Member("Krauser (human)", KRAUSER, new int[] {0x39}, new int[] {0, 1},
        0, 3000, 12, false, false),
    new Member("El Gigante", GIGANTE, new int[] {0x2B}, new int[] {0, 1, 2, 3},
        0, 4000, 12, true, false),
    new Member("Super Salvador", SUPER, new int[] {0x20}, new int[] {22},
        FLAG_SAW, 3000, 8, false, false),
    new Member("Verdugo", VERDUGO, new int[] {0x2C}, new int[] {0},
        0, 3000, 12, false, false),
    // New variants are appended so the ratio steppers keep their old positions.
    new Member("Armadura (black)", ARMADURA_DARK, new int[] {0x3C}, new int[] {2, 3},
        0, 1000, 8, false, false),
    new Member("Krauser (mutated)", KRAUSER_MUTANT, new int[] {0x39}, new int[] {2},
        0, 3000, 12, false, false),
  };

  /** What the launcher draws: a checkbox and a 1-in-N stepper for each pool entry. */
  static final String[] POOL_NAMES = new String[POOL.length];
  static final int[] POOL_BITS = new int[POOL.length];
  static final int[] DEFAULT_RATIOS = new int[POOL.length];
  static final int[] RATIO_STEPS = {0, 2, 4, 8, 12, 20};
  static {
    for (int i = 0; i < POOL.length; i++) {
      POOL_NAMES[i] = POOL[i].name;
      POOL_BITS[i] = POOL[i].bit;
      DEFAULT_RATIOS[i] = POOL[i].ratio;
    }
  }

  private static final Map<Integer, int[]> TYPES = new LinkedHashMap<>();
  static {
    for (Member m : POOL) for (int id : m.ids) {
      Set<Integer> all = new LinkedHashSet<>();
      int[] old = TYPES.get(id);
      if (old != null) for (int v : old) all.add(v);
      for (int v : m.types) all.add(v);
      int[] merged = new int[all.size()];
      int at = 0;
      for (int v : all) merged[at++] = v;
      TYPES.put(id, merged);
    }
    TYPES.put(0x21, new int[] {0, 1});
    TYPES.put(0x23, new int[] {0});
    TYPES.put(0x26, new int[] {0, 1});
    TYPES.put(0x28, new int[] {0, 1});
    TYPES.put(0x2A, new int[] {0, 2});
    TYPES.put(0x2B, new int[] {0, 1, 2, 3});
    TYPES.put(0x39, new int[] {0, 1, 2});
  }

  /** The eight story enemy lists, in the order stage.cpp asks for them. */
  private static final String[] LISTS = {
      "etc/emleon00.esl", "etc/emleon01.esl", "etc/emleon02.esl", "etc/emleon03.esl",
      "etc/emleon04.esl", "etc/emleon05.esl", "etc/emleon06.esl", "etc/emleon07.esl"};

  static final class Result {
    boolean ok;
    String message = "";
  }

  private static final class ListPlan {
    String name;
    byte[] out;
    int[] allowed;
  }

  private static final class Plan {
    final List<ListPlan> lists = new ArrayList<>();
    final Set<String> silent = new LinkedHashSet<>();
    String report;
    String error;
  }

  private static int u8(byte[] b, int at) { return b[at] & 0xFF; }
  private static int u16(byte[] b, int at) { return (u8(b, at) << 8) | u8(b, at + 1); }
  private static int u32(byte[] b, int at) {
    return (u8(b, at) << 24) | (u8(b, at + 1) << 16) | (u8(b, at + 2) << 8) | u8(b, at + 3);
  }
  private static void putU8(byte[] b, int at, int v) { b[at] = (byte) v; }
  private static void putU16(byte[] b, int at, int v) {
    b[at] = (byte) (v >>> 8); b[at + 1] = (byte) v;
  }
  private static void putU32(byte[] b, int at, int v) {
    b[at] = (byte) (v >>> 24); b[at + 1] = (byte) (v >>> 16);
    b[at + 2] = (byte) (v >>> 8); b[at + 3] = (byte) v;
  }
  private static void allow(int[] allowed, int no, int bits) { allowed[no] |= bits; }

  private static long mix(long x) {
    x ^= x >>> 33; x *= 0xFF51AFD7ED558CCDL; x ^= x >>> 33;
    return x;
  }

  private static int hash(long seed, int room, int no, int salt) {
    return (int) Math.floorMod(mix(seed * 0x9E3779B1L + ((long) room << 40)
        + ((long) no << 18) + salt * 0x85EBCA6BL), (1L << 40));
  }

  private static String moduleName(int id) { return String.format(Locale.ROOT, "em%02x", id); }

  private static File outDir(File dataRoot) { return new File(dataRoot, "randomizer/enemies"); }
  private static File marker(File dataRoot) { return new File(outDir(dataRoot), "active"); }
  private static File logFile(File dataRoot) { return new File(outDir(dataRoot), "enemies.txt"); }

  static boolean active(File dataRoot) { return marker(dataRoot).isFile(); }

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

  static String preview(File discFiles, long seed, int options, int[] ratios) {
    Plan p = makePlan(discFiles, seed, options, ratios);
    return p.error != null ? "Refused: " + p.error : p.report;
  }

  static Result dealRooms(File dataRoot, File discFiles, long seed, int options, int[] ratios) {
    Result r = new Result();
    Plan p = makePlan(discFiles, seed, options, ratios);
    if (p.error != null) {
      r.message = p.error + " Nothing was written.";
      Log.i(TAG, r.message);
      return r;
    }
    File dir = outDir(dataRoot);
    File stage = new File(dir, "staging");
    deleteTree(stage);
    try {
      for (ListPlan lp : p.lists) {
        File staged = new File(stage, lp.name);
        staged.getParentFile().mkdirs();
        writeAll(staged, lp.out);
        byte[] back = read(staged);
        if (back == null || !Arrays.equals(back, lp.out)) {
          r.message = "The dealt list " + lp.name + " did not read back as written. Nothing was activated.";
          Log.i(TAG, r.message);
          deleteTree(stage);
          return r;
        }
      }
      marker(dataRoot).delete();
      for (ListPlan lp : p.lists) {
        File target = new File(dir, lp.name);
        target.getParentFile().mkdirs();
        if (target.exists() && !target.delete()) {
          r.message = "Could not replace the previous enemy list " + lp.name
              + ". Nothing was activated.";
          Log.i(TAG, r.message);
          return r;
        }
        if (!new File(stage, lp.name).renameTo(target)) {
          r.message = "Could not put the dealt list " + lp.name + " in place. Nothing was activated.";
          Log.i(TAG, r.message);
          return r;
        }
      }
      deleteTree(stage);
      writeAll(logFile(dataRoot), p.report.getBytes(StandardCharsets.UTF_8));
      writeAll(new File(dir, "no-sound.txt"), String.join("\n", p.silent)
          .getBytes(StandardCharsets.UTF_8));
      new File(dir, "r100-no-sound").delete();
      new File(dir, "r101-no-sound").delete();
      writeAll(marker(dataRoot), String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
      r.ok = true;
      r.message = "Dealt all story rooms from the pool. Seed " + seed
          + ". See enemies.txt for the details.";
      Log.i(TAG, r.message);
    } catch (IOException ex) {
      r.message = "Could not write the dealt lists: " + ex.getMessage() + ". Nothing was activated.";
      Log.e(TAG, r.message);
      deleteTree(stage);
    }
    return r;
  }

  private static boolean basicId(int id) {
    return id == 0x12 || id == 0x13 || id == 0x1B || id == 0x1C || id == 0x1D;
  }

  /** True when the pool can actually write this id; other ids can only be kept or reused. */
  private static boolean poolId(int id) {
    for (Member m : POOL) for (int x : m.ids) if (x == id) return true;
    return false;
  }

  /** Model variants the pool can show for an id, with no id change. */
  private static int[] variantTypes(int id) {
    Set<Integer> all = new LinkedHashSet<>();
    for (Member m : POOL) for (int x : m.ids) if (x == id) {
      for (int t : m.types) all.add(t);
    }
    int[] result = new int[all.size()];
    int at = 0;
    for (int t : all) result[at++] = t;
    return result;
  }

  /**
   * A row the script owns keeps its id, hp, set, position and room. Only the look and non-weapon
   * extras may change; room scripts never read the type or flag fields.
   */
  private static void appearanceOnly(byte[] out, int[] allowed, long seed, int options,
      int room, int no) {
    int at = no * ENTRY;
    int[] types = variantTypes(u8(out, at + ID));
    if ((options & VARIETY) != 0 && types.length > 0 && TYPES.containsKey(u8(out, at + ID))) {
      putU8(out, at + TYPE, types[Math.floorMod(hash(seed, room, no, 903), types.length)]);
      allow(allowed, no, A_TYPE);
    }
    int flag = u32(out, at + FLAG), extra = 0;
    if ((options & HELMET) != 0 && hash(seed, room, no, 904) % 10 == 0) extra |= FLAG_HELMET;
    if ((options & SHIELD) != 0 && hash(seed, room, no, 905) % 10 == 0) extra |= FLAG_SHIELD;
    if ((options & PLAGA) != 0 && hash(seed, room, no, 906) % 10 == 0) extra |= FLAG_PLAGA;
    if (extra != 0) {
      putU32(out, at + FLAG, flag | extra);  // every vanilla weapon bit stays exactly as it was
      allow(allowed, no, A_FLAG);
    }
  }

  /**
   * Ordinary Ganados keep the weapon the disc gave them (sickle, stun rod, torch...). A special
   * enemy brings its own flag instead. The Dynamite switch adds a bomb to a seeded sixth of the
   * ordinary records; the disc's own dynamite rows are script rows and are never rewritten.
   */
  private static int keptWeapons(int srcId, int srcFlag, boolean basicPick, long seed,
      int room, int no, int options) {
    int keep = basicPick && basicId(srcId) && (srcFlag & FLAG_SAW) == 0
        ? srcFlag & WEAPON_MASK : 0;
    if ((options & DYNAMITE) != 0 && basicPick && (keep & FLAG_BOMB) == 0
        && hash(seed, room, no, 907) % 6 == 0) keep |= FLAG_BOMB;
    return keep;
  }

  private static Plan makePlan(File discFiles, long seed, int options, int[] ratios) {
    Plan p = new Plan();
    int[] ratio = new int[POOL.length];
    for (int i = 0; i < POOL.length; i++) {
      ratio[i] = ratios != null && i < ratios.length ? ratios[i] : POOL[i].ratio;
    }
    StringBuilder report = new StringBuilder();
    report.append("Enemy pool, seed ").append(seed).append(", options ").append(options).append('\n');

    for (String name : LISTS) {
      byte[] src = read(new File(discFiles, name));
      if (src == null || src.length < MIN_BYTES || src.length > LIST_BYTES || src.length % ENTRY != 0) {
        p.error = "The imported disc has no usable " + name + ". Import both discs again, then try.";
        return p;
      }
      byte[] out = new byte[LIST_BYTES];
      System.arraycopy(src, 0, out, 0, src.length);
      int[] allowed = new int[ENTRIES];
      Set<Integer> rooms = new LinkedHashSet<>();
      for (int no = 0; no < ENTRIES; no++) {
        int at = no * ENTRY;
        if (u8(out, at + ID) != 0) rooms.add(u16(out, at + ROOM));
      }
      ListPlan lp = new ListPlan();
      lp.name = name; lp.out = out; lp.allowed = allowed;
      report.append("List ").append(name).append('\n');
      Set<Integer> dealt = new LinkedHashSet<>();
      for (int room : rooms) {
        int kind = EnemyRooms.kind(room);
        if (kind == EnemyRooms.UNTOUCHED || kind < 0) continue;
        dealRoom(out, allowed, seed, options, ratio, room, p.silent, report,
            kind == EnemyRooms.PLACES_ONLY);
        dealt.add(room);
      }
      String why = kinds(out, dealt);
      if (why != null) {
        // A room can already be at its four-module limit because of script-owned rows. Drop the
        // whole room back to the disc rather than refusing every seed in the import.
        for (int room : dealt) restoreRoom(out, src, room);
        report.append("Kept rooms with a full module budget: ").append(dealt.size()).append('\n');
        why = null;
      }
      if (why == null) why = verify(src, out, allowed);
      if (why != null) {
        p.error = why + " in " + name + "\nPlan: " + report.toString().replace('\n', '|');
        return p;
      }
      p.lists.add(lp);
    }
    for (String s : p.silent) report.append("Silent module: ").append(s).append('\n');
    p.report = report.toString();
    return p;
  }

  /** Deals every ordinary record in one room; script rows and the hand-made officer are skipped. */
  private static void dealRoom(byte[] out, int[] allowed, long seed, int options, int[] ratio,
      int room, Set<String> silent, StringBuilder report, boolean placesOnly) {
    Set<Integer> kinds = new LinkedHashSet<>();
    for (int id : EnemyRooms.forced(room)) kinds.add(id);  // modules the room preloads itself
    if (room == 0x100) kinds.add(0x12);                    // the officer-killer is built by r100.cpp
    if (room == 0x101) { kinds.add(0x15); kinds.add(0x26); } // r101 preloads these for events
    if (room == 0x103) kinds.add(0x12);                    // r103's corpse models
    int[] tally = new int[POOL.length];
    boolean massiveUsed = false;
    // Only modules that survive the deal (hand-made actors and script rows) occupy a slot up front.
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != room) continue;
      int id = u8(out, at + ID);
      if (id != 0 && ((u32(out, at + FLAG) & FLAG_BOMB) != 0 || u8(out, at + SET) == 0x25
          || EnemyRooms.driven(room, no) || !poolId(id))) {
        kinds.add(id);
      }
    }
    int n = 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != room) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      if (EnemyRooms.driven(room, no)) {
        appearanceOnly(out, allowed, seed, options, room, no);
        continue;
      }
      int flag = u32(out, at + FLAG), set = u8(out, at + SET);
      if ((flag & FLAG_BOMB) != 0 || set == 0x25) continue;  // dynamite and named event motions
      boolean counted;
      if (room == 0x101) counted = n != 11 && n != 37;   // two dormant rows: bell count unchanged
      else if (room == 0x104) counted = n != 0;          // one boss row for the waves
      else counted = placesOnly;                        // open rooms have no counting script
      n++;

      List<Member> hits = new ArrayList<>();
      for (int i = 0; i < POOL.length; i++) {
        Member m = POOL[i];
        if (m.basic || (options & m.bit) == 0 || ratio[i] <= 0) continue;
        if (m.massive && massiveUsed) continue;
        if (counted && !m.counted()) continue;
        if (hash(seed, room, no, i) % ratio[i] != 0) continue;
        int candidate = m.ids[Math.floorMod(hash(seed, room, no, 902), m.ids.length)];
        if (!fits(kinds, candidate)) continue;
        hits.add(m);
      }
      Member pick = null;
      if (!hits.isEmpty()) {
        pick = hits.get(Math.floorMod(hash(seed, room, no, 900), hits.size()));
      } else {
        List<Member> bases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          if ((options & POOL[i].bit) == 0) continue;
          int candidate = POOL[i].ids[Math.floorMod(hash(seed, room, no, 902), POOL[i].ids.length)];
          if (fits(kinds, candidate)) bases.add(POOL[i]);
        }
        if (!bases.isEmpty()) {
          pick = bases.get(Math.floorMod(hash(seed, room, no, 901), bases.size()));
        }
      }
      if (pick == null) {
        // No member fits the four slots: reuse a module the room already loads. A room with no
        // reserved module and no eligible pick stays exactly as on the disc.
        if (kinds.isEmpty()) continue;
        int reuse = kinds.iterator().next();
        int[] rt = TYPES.get(reuse);
        if (rt == null) { kinds.add(id); continue; }  // no model table: leave the row, reserve its module
        putU8(out, at + ID, reuse);
        putU8(out, at + TYPE, rt[0]);
        putU32(out, at + FLAG,
            (flag & ~WEAPON_MASK) | keptWeapons(id, flag, basicId(reuse), seed, room, no, options));
        putU16(out, at + HP, 1000);
        putU8(out, at + SET, 0);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
        continue;
      }

      int chosen = pick.ids[Math.floorMod(hash(seed, room, no, 902), pick.ids.length)];
      int type = pick.types[Math.floorMod(hash(seed, room, no, 903), pick.types.length)];
      int extra = 0;
      if ((options & HELMET) != 0 && hash(seed, room, no, 904) % 10 == 0) extra |= FLAG_HELMET;
      if ((options & SHIELD) != 0 && hash(seed, room, no, 905) % 10 == 0) extra |= FLAG_SHIELD;
      if ((options & PLAGA) != 0 && hash(seed, room, no, 906) % 10 == 0) extra |= FLAG_PLAGA;
      if ((options & VARIETY) == 0) type = pick.types[0];
      putU8(out, at + ID, chosen);
      putU8(out, at + TYPE, type);
      putU32(out, at + FLAG, (flag & ~WEAPON_MASK)
          | keptWeapons(id, flag, pick.basic, seed, room, no, options) | pick.flag | extra);
      putU16(out, at + HP, pick.hp);
      putU8(out, at + SET, 0);
      allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
      kinds.add(chosen);
      silent.add(moduleName(chosen));
      if (pick.massive) massiveUsed = true;
      for (int i = 0; i < POOL.length; i++) if (POOL[i] == pick) tally[i]++;
    }
    report.append(String.format(Locale.ROOT, "r%03x dealt %d enemies: ", room, n));
    for (int i = 0; i < POOL.length; i++) {
      if (tally[i] == 0) continue;
      report.append(POOL[i].name).append(' ').append(tally[i]).append(", ");
    }
    report.append('\n');
  }

  private static boolean fits(Set<Integer> kinds, int id) {
    return kinds.contains(id) || kinds.size() < 4;
  }

  private static void restoreRoom(byte[] out, byte[] src, int room) {
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (at + ENTRY > src.length || u16(out, at + ROOM) != room) continue;
      System.arraycopy(src, at, out, at, ENTRY);
    }
  }

  private static String kinds(byte[] out, Set<Integer> rooms) {
    for (int room : rooms) {
      Set<Integer> k = new LinkedHashSet<>();
      for (int no = 0; no < ENTRIES; no++) {
        int at = no * ENTRY;
        if (u16(out, at + ROOM) != room) continue;
        int id = u8(out, at + ID);
        if (id == 0) continue;
        // Only rows the room actually spawns occupy a module: active rows, script-driven rows,
        // dynamite rows and named-motion rows. Dormant decorative records do not.
        if ((u8(out, at + BE_FLAG) & 1) == 0 && (u32(out, at + FLAG) & FLAG_BOMB) == 0
            && u8(out, at + SET) != 0x25 && !EnemyRooms.driven(room, no)) continue;
        k.add(id);
      }
      for (int id : EnemyRooms.forced(room)) k.add(id);
        if (room == 0x100) k.add(0x12);
      if (room == 0x101) { k.add(0x15); k.add(0x26); }
      if (room == 0x103) k.add(0x12);
      if (k.size() > 4) {
        return "the deal would need " + k.size() + " enemy modules in room "
            + Integer.toHexString(room) + " " + k + " (the game has four)";
      }
    }
    return null;
  }

  private static String verify(byte[] src, byte[] out, int[] allowed) {
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (at + ENTRY > src.length) {
        if (u8(out, at + ID) != 0) return "entry " + no + " was invented past the disc's end";
        continue;
      }
      boolean changed = false;
      for (int b = 0; b < ENTRY; b++) {
        if (src[at + b] == out[at + b]) continue;
        changed = true;
        int bit = b == ID ? A_ID : b == TYPE ? A_TYPE
            : (b == FLAG || b == FLAG + 1 || b == FLAG + 2 || b == FLAG + 3) ? A_FLAG
            : (b == HP || b == HP + 1) ? A_HP : b == SET ? A_SET : 0;
        if (bit == 0 || (allowed[no] & bit) == 0) return "entry " + no + " changed protected byte " + b;
      }
      if (!changed) continue;  // an untouched record needs no model-table check, even when restored
      if ((allowed[no] & A_TYPE) != 0) {
        int id = u8(out, at + ID), ty = u8(out, at + TYPE);
        int[] ts = TYPES.get(id);
        if (ts == null) return "entry " + no + " has an unsupported kind " + id;
        boolean ok = false;
        for (int v : ts) if (v == ty) { ok = true; break; }
        if (!ok) return "entry " + no + " has model " + ty + " its module cannot show";
      }
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
