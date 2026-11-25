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
 * Offline enemy randomizer for the four test rooms (r100, r101, r103, r104).
 *
 * <p>There are two pools. Elites are the powerful ordinary enemies (JJ, Dr. Salvador, Bella Sisters,
 * Regenerator/Iron Maiden, Armadura, Garrador); bosses are Krauser, El Gigante, Super Salvador,
 * Mendez and Verdugo. For each room the seed picks one member from each pool whose toggle is on and
 * whose module fits the room's four slots. A swap replaces one record one-for-one, so counts and
 * room geometry never change. The plan is built entirely in memory: the launcher can preview it
 * before writing, and a deal is refused unless every protected byte still matches the disc.
 */
final class EnemyShuffle {
  private static final String TAG = "EnemyShuffle";
  private EnemyShuffle() {}

  private static final int ENTRY = 32, ENTRIES = 256;
  private static final int LIST_BYTES = ENTRIES * ENTRY;
  private static final int MIN_BYTES = 255 * ENTRY;

  private static final int BE_FLAG = 0x00, ID = 0x01, TYPE = 0x02, SET = 0x03, FLAG = 0x04,
      HP = 0x08, ROOM = 0x18;

  static final int SAW = 1, GARRADOR = 1 << 1, JJ = 1 << 2, SUPER = 1 << 3, VARIETY = 1 << 4,
      HELMET = 1 << 5, SHIELD = 1 << 6, PLAGA = 1 << 7, DYNAMITE = 1 << 8, MIXED = 1 << 9,
      SAFE_CABIN = 1 << 10, REGEN = 1 << 11, IRON = 1 << 12, KRAUSER = 1 << 13,
      MUTATED = 1 << 14, GIGANTE = 1 << 15, ARMADURA = 1 << 16, SHARED_SET = 1 << 17,
      RESET_SET = 1 << 18, BELLA = 1 << 19, MENDEZ = 1 << 20, VERDUGO = 1 << 21;
  static final int DEFAULT_OPTIONS =
      SAW | JJ | GARRADOR | REGEN | VARIETY | SAFE_CABIN | VERDUGO;

  private static final int FLAG_SAW = 1 << 28, FLAG_BOMB = 1 << 17,
      FLAG_PLAGA = 1 << 20, FLAG_HELMET = 1 << 9, FLAG_SHIELD = 1 << 31;
  private static final int WEAPON_MASK =
      FLAG_SAW | FLAG_BOMB | (1 << 27) | (1 << 29) | (1 << 30) | (1 << 31) | (1 << 11);
  private static final int FLAG_BOWGUN = 1 << 15, FLAG_CLUB = 1 << 11;
  private static final int TEST_WEAPON_MASK = WEAPON_MASK | FLAG_BOWGUN;

  private static final int A_ID = 1, A_TYPE = 2, A_FLAG = 4, A_HP = 8, A_SET = 16;

  private static final Map<Integer, int[]> TYPES = new LinkedHashMap<>();
  static {
    TYPES.put(0x12, new int[] {0, 1, 3, 4});
    TYPES.put(0x13, new int[] {0, 1, 3, 4, 6});
    TYPES.put(0x15, new int[] {0, 3, 4, 11});
    TYPES.put(0x16, new int[] {0, 1, 3, 4, 11, 12});
    TYPES.put(0x1B, new int[] {7, 8, 9, 10, 13});
    TYPES.put(0x1D, new int[] {2, 14, 15, 16, 17, 18, 19, 20, 21});
    TYPES.put(0x20, new int[] {14, 15, 16, 22});
    TYPES.put(0x21, new int[] {0, 1});
    TYPES.put(0x23, new int[] {0});
    TYPES.put(0x26, new int[] {0, 1});
    TYPES.put(0x28, new int[] {0, 1});
    TYPES.put(0x2A, new int[] {0, 2});
    TYPES.put(0x2B, new int[] {0, 1, 2, 3});
    TYPES.put(0x2C, new int[] {0});
    TYPES.put(0x34, new int[] {0, 2, 3});
    TYPES.put(0x36, new int[] {0, 1, 2, 3});
    TYPES.put(0x39, new int[] {0, 1, 2});
    TYPES.put(0x3C, new int[] {0, 1, 2, 3});
  }

  /** One pool entry. {@code ids} are alternative bodies; the seed picks one that fits the room. */
  private static final class Member {
    final String name;
    final int bit;
    final int[] ids;
    final int[] types;
    final int flag;
    final int hp;
    final boolean massive;
    Member(String name, int bit, int[] ids, int[] types, int flag, int hp, boolean massive) {
      this.name = name; this.bit = bit; this.ids = ids; this.types = types;
      this.flag = flag; this.hp = hp; this.massive = massive;
    }
  }

  private static final Member[] ELITES = {
    new Member("JJ", JJ, new int[] {0x1D}, new int[] {2}, 0, 1000, false),
    new Member("Dr. Salvador", SAW, new int[] {0x12, 0x13, 0x15, 0x16, 0x17},
        new int[] {3, 4}, FLAG_SAW, 1200, false),
    new Member("Bella Sisters", BELLA, new int[] {0x16}, new int[] {11, 12},
        FLAG_SAW, 1200, false),
    new Member("Regenerator", REGEN, new int[] {0x36}, new int[] {0, 1}, 0, 1000, false),
    new Member("Iron Maiden", IRON, new int[] {0x36}, new int[] {2, 3}, 0, 1000, false),
    new Member("Armadura", ARMADURA, new int[] {0x3C}, new int[] {0, 1, 2, 3}, 0, 1000, false),
    new Member("Garrador", GARRADOR, new int[] {0x1B}, new int[] {10}, 0, 1200, false),
  };

  private static final Member[] BOSSES = {
    new Member("Krauser", KRAUSER, new int[] {0x39}, new int[] {0, 1, 2}, 0, 3000, false),
    new Member("El Gigante", GIGANTE, new int[] {0x2B}, new int[] {0, 1, 2, 3}, 0, 4000, true),
    new Member("Super Salvador", SUPER, new int[] {0x20}, new int[] {22}, FLAG_SAW, 3000, false),
    new Member("Mendez", MENDEZ, new int[] {0x34}, new int[] {0}, 0, 4000, true),
    new Member("Verdugo", VERDUGO, new int[] {0x2C}, new int[] {0}, 0, 3000, false),
  };

  private static final String LIST_NAME = "etc/emleon00.esl";
  private static final int[] ROOMS = {0x100, 0x101, 0x103, 0x104};

  static final class Result {
    boolean ok;
    String message = "";
  }

  /** The whole plan in memory. The launcher can show it without writing anything. */
  private static final class Plan {
    byte[] out;
    int[] allowed;
    Set<String> silent;
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

  private static int pick(long seed, int what, int n) {
    long x = mix(seed * 0x9E3779B1L + what * 0x85EBCA6BL);
    return n <= 0 ? 0 : (int) Math.floorMod(x, (long) n);
  }

  /** A seed-ordered pick from the pool, advancing per room so enabled enemies spread out. */
  private static Member chooseAt(Member[] pool, long seed, int options, Set<Integer> base,
      boolean massiveUsed, int roomIndex) {
    List<Member> ok = new ArrayList<>();
    for (Member m : pool) {
      if ((options & m.bit) == 0) continue;
      if (m.massive && massiveUsed) continue;
      int id = chooseId(m, base, seed, roomIndex * 100 + 1);
      if (!base.contains(id) && base.size() >= 4) continue;
      ok.add(m);
    }
    if (ok.isEmpty()) return null;
    ok.sort((a, b) -> Long.compare(mix(seed + a.name.hashCode() * 2654435761L),
                                  mix(seed + b.name.hashCode() * 2654435761L)));
    return ok.get(roomIndex % ok.size());
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

  /** Builds the exact same plan the deal writes, but never touches the filesystem. */
  static String preview(File discFiles, long seed, int options) {
    Plan p = makePlan(discFiles, seed, options);
    return p.error != null ? "Refused: " + p.error : p.report;
  }

  static Result dealRooms(File dataRoot, File discFiles, long seed, int options) {
    Result r = new Result();
    Plan p = makePlan(discFiles, seed, options);
    if (p.error != null) {
      r.message = p.error + " Nothing was written.";
      Log.i(TAG, r.message);
      return r;
    }
    byte[] out = p.out;
    File dir = outDir(dataRoot);
    File stage = new File(dir, "staging");
    deleteTree(stage);
    try {
      File staged = new File(stage, LIST_NAME);
      staged.getParentFile().mkdirs();
      writeAll(staged, out);
      byte[] back = read(staged);
      if (back == null || !Arrays.equals(back, out)) {
        r.message = "The dealt list did not read back as written. Nothing was activated.";
        Log.i(TAG, r.message);
        deleteTree(stage);
        return r;
      }
      marker(dataRoot).delete();
      File target = new File(dir, LIST_NAME);
      target.getParentFile().mkdirs();
      if (target.exists() && !target.delete()) {
        r.message = "Could not replace the previous enemy list. Nothing was activated.";
        Log.i(TAG, r.message);
        return r;
      }
      if (!staged.renameTo(target)) {
        r.message = "Could not put the dealt list in place. Nothing was activated.";
        Log.i(TAG, r.message);
        return r;
      }
      deleteTree(stage);
      writeAll(logFile(dataRoot), p.report.getBytes(StandardCharsets.UTF_8));
      writeAll(new File(dir, "no-sound.txt"), String.join("\n", p.silent).getBytes(StandardCharsets.UTF_8));
      new File(dir, "r100-no-sound").delete();
      new File(dir, "r101-no-sound").delete();
      writeAll(marker(dataRoot), String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
      r.ok = true;
      r.message = "Dealt r100, r101, r103 and r104. Seed " + seed + ". See enemies.txt for the changes.";
      Log.i(TAG, r.message);
    } catch (IOException ex) {
      r.message = "Could not write the dealt list: " + ex.getMessage() + ". Nothing was activated.";
      Log.e(TAG, r.message);
      deleteTree(stage);
    }
    return r;
  }

  private static Plan makePlan(File discFiles, long seed, int options) {
    Plan p = new Plan();
    byte[] src = read(new File(discFiles, LIST_NAME));
    if (src == null || src.length < MIN_BYTES || src.length > LIST_BYTES || src.length % ENTRY != 0) {
      p.error = "The imported disc has no usable " + LIST_NAME + ". Import both discs again, then try.";
      return p;
    }
    byte[] out = new byte[LIST_BYTES];
    System.arraycopy(src, 0, out, 0, src.length);
    int[] allowed = new int[ENTRIES];
    Set<String> silent = new LinkedHashSet<>();
    StringBuilder report = new StringBuilder();
    report.append("Enemy pools, seed ").append(seed).append(", options ").append(options).append('\n');

    applyPools(out, allowed, seed, options, silent, report);
    planR100(out, allowed, seed, options, silent, report);
    planR101(out, allowed, seed, options, silent, report);
    planR103(out, allowed, seed, options, silent, report);
    planR104(out, allowed, seed, options, silent, report);

    String why = kinds(src, out);
    if (why == null) why = verify(src, out, allowed);
    if (why != null) { p.error = why + "\nPlan: " + report.toString().replace('\n', ' '); return p; }
    for (String s : silent) report.append("Silent module: ").append(s).append('\n');
    p.out = out; p.allowed = allowed; p.silent = silent; p.report = report.toString();
    return p;
  }

  /** The rows a pool pick may replace: few, safe, and not needed by a script's kill count. */
  private static List<Integer> poolRows(byte[] out, int room) {
    List<Integer> rows = new ArrayList<>();
    int n = 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != room) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      int flag = u32(out, at + FLAG), set = u8(out, at + SET);
      if (room == 0x100 && id == 0x23 && (u8(out, at + BE_FLAG) & 1) != 0) { rows.add(no); continue; }
      if (room == 0x101 && (id == 0x15 || id == 0x26 || id == 0x28)) {
        if (n == 11 || n == 37) rows.add(no);
        n++;
        continue;
      }
      if (room == 0x103 && (id == 0x21 || id == 0x28)) { rows.add(no); continue; }
      if (room == 0x104 && set == 0 && (flag & FLAG_BOMB) == 0 && n < 2) { rows.add(no); n++; continue; }
    }
    return rows;
  }

  /** Modules the room will need after its ordinary restyling, plus the ones its events preload. */
  private static Set<Integer> baseKinds(int room) {
    Set<Integer> k = new LinkedHashSet<>();
    if (room == 0x100) { k.add(0x12); k.add(0x1D); k.add(0x21); }
    if (room == 0x101) { k.add(0x15); k.add(0x26); k.add(0x1B); }
    // r103 keeps em12 for its corpse models and uses em1d for the changed Ganados; two slots stay free
    if (room == 0x103) { k.add(0x12); k.add(0x1D); }
    if (room == 0x104) k.add(0x13);
    return k;
  }

  private static int chooseId(Member m, Set<Integer> base, long seed, int salt) {
    for (int id : m.ids) if (base.contains(id)) return id;   // reuse a module already loaded
    return m.ids[pick(seed, salt + 31, m.ids.length)];
  }

  private static Member choose(Member[] pool, long seed, int salt, int options,
      Set<Integer> base, boolean massiveUsed) {
    List<Member> ok = new ArrayList<>();
    for (Member m : pool) {
      if ((options & m.bit) == 0) continue;
      if (m.massive && massiveUsed) continue;
      int id = chooseId(m, base, seed, salt);
      if (!base.contains(id) && base.size() >= 4) continue;  // no free module slot
      ok.add(m);
    }
    return ok.isEmpty() ? null : ok.get(pick(seed, salt + 7000, ok.size()));
  }

  /** r101's bell counts ids 0x10..0x20, so an elite there must stay inside that range. */
  private static boolean counted(Member m) {
    for (int id : m.ids) if (id < 0x10 || id > 0x20) return false;
    return true;
  }

  private static Member chooseCounted(int options, long seed, int salt, Set<Integer> base) {
    List<Member> ok = new ArrayList<>();
    for (Member m : ELITES) {
      if ((options & m.bit) == 0 || !counted(m)) continue;
      int id = chooseId(m, base, seed, salt);
      if (!base.contains(id) && base.size() >= 4) continue;
      ok.add(m);
    }
    return ok.isEmpty() ? null : ok.get(pick(seed, salt + 7000, ok.size()));
  }

  private static Member chooseCountedAt(int options, long seed, Set<Integer> base, int roomIndex) {
    List<Member> ok = new ArrayList<>();
    for (Member m : ELITES) {
      if ((options & m.bit) == 0 || !counted(m)) continue;
      int id = chooseId(m, base, seed, roomIndex * 100 + 1);
      if (!base.contains(id) && base.size() >= 4) continue;
      ok.add(m);
    }
    if (ok.isEmpty()) return null;
    ok.sort((a, b) -> Long.compare(mix(seed + a.name.hashCode() * 2654435761L),
                                  mix(seed + b.name.hashCode() * 2654435761L)));
    return ok.get(roomIndex % ok.size());
  }

  private static void writeMember(byte[] out, int[] allowed, int no, Member m, long seed, int salt,
      int options, Set<String> silent, Set<Integer> base) {
    int at = no * ENTRY;
    int id = chooseId(m, base, seed, salt);
    int type = m.name.equals("Krauser") && (options & MUTATED) != 0
        ? 2 : m.types[pick(seed, salt + 13, m.types.length)];
    putU8(out, at + ID, id);
    putU8(out, at + TYPE, type);
    putU32(out, at + FLAG, (u32(out, at + FLAG) & ~TEST_WEAPON_MASK) | m.flag);
    putU16(out, at + HP, m.hp);
    putU8(out, at + SET, 0);
    allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
    silent.add(moduleName(id));
    base.add(id);
  }

  /** Writes a whole group of rows. A massive enemy may only take the first row; the rest fall back. */
  private static void writeRows(byte[] out, int[] allowed, List<Integer> rows, Member m, long seed,
      int salt, int options, int fallback, Set<String> silent, Set<Integer> base) {
    for (int k = 0; k < rows.size(); k++) {
      int no = rows.get(k);
      if (m.massive && k > 0) {
        int at = no * ENTRY;
        int[] ft = TYPES.get(fallback);
        putU8(out, at + ID, fallback);
        putU8(out, at + TYPE, ft[(k + 1) % ft.length]);
        putU32(out, at + FLAG, u32(out, at + FLAG) & ~TEST_WEAPON_MASK);
        putU8(out, at + SET, 0);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_SET);
        base.add(fallback);
      } else {
        writeMember(out, allowed, no, m, seed, salt, options, silent, base);
      }
    }
  }

  /** The farm: all six chickens and all six cows change kind; the five Ganados become island soldiers. */
  private static void applyFarm(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report, int i) {
    List<Integer> cows = new ArrayList<>(), chickens = new ArrayList<>();
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x103) continue;
      int id = u8(out, at + ID);
      if (id == 0x21) {
        // The single dog becomes a Ganado, freeing em21 for the two pool picks.
        putU8(out, at + ID, 0x12);
        putU8(out, at + TYPE, 0);
        putU32(out, at + FLAG, u32(out, at + FLAG) & ~TEST_WEAPON_MASK);
        putU8(out, at + SET, 0);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_SET);
      } else if (id == 0x26) cows.add(no);
      else if (id == 0x28) chickens.add(no);
    }
    Set<Integer> base = baseKinds(0x103);
    int s1 = i * 100 + 1, s2 = i * 100 + 2;
    Member elite = chooseAt(ELITES, seed, options, base, false, i);
    Member boss = chooseAt(BOSSES, seed, options, base, elite != null && elite.massive, i);
    if (elite != null && !chickens.isEmpty()) {
      writeRows(out, allowed, chickens, elite, seed, s1, options, 0x1D, silent, base);
    } else if (!chickens.isEmpty()) {
      // Even with every elite toggle off, the chickens still change kind.
      for (int no : chickens) {
        int at = no * ENTRY;
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14);
        putU8(out, at + SET, 0);
        allow(allowed, no, A_ID | A_TYPE | A_SET);
      }
      base.add(0x1D);
    }
    if (boss != null && !cows.isEmpty()) {
      writeRows(out, allowed, cows, boss, seed, s2, options, 0x1D, silent, base);
    }
    report.append("r103: ").append(chickens.size()).append(" chickens as ")
        .append(elite == null ? "none" : elite.name).append(", ").append(cows.size())
        .append(" cows as ").append(boss == null ? "none" : boss.name).append('\n');
  }

  /** The village square: the plain Ganado rows are split between the two pool picks. */
  private static void applyVillage(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report, int i) {
    List<Integer> rows = new ArrayList<>();
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x104 || u8(out, at + ID) == 0) continue;
      if (u8(out, at + SET) == 0 && (u32(out, at + FLAG) & FLAG_BOMB) == 0) rows.add(no);
    }
    Set<Integer> base = baseKinds(0x104);
    int s1 = i * 100 + 1, s2 = i * 100 + 2;
    Member elite = chooseAt(ELITES, seed, options, base, false, i);
    Member boss = chooseAt(BOSSES, seed, options, base, elite != null && elite.massive, i);
    int half = rows.size() / 2;
    if (elite != null && boss != null && half > 0) {
      writeRows(out, allowed, rows.subList(0, half), elite, seed, s1, options, 0x13, silent, base);
      writeRows(out, allowed, rows.subList(half, rows.size()), boss, seed, s2, options, 0x13, silent, base);
    } else if (elite != null) {
      writeRows(out, allowed, rows, elite, seed, s1, options, 0x13, silent, base);
    } else if (boss != null) {
      writeRows(out, allowed, rows, boss, seed, s2, options, 0x13, silent, base);
    }
    report.append("r104: ").append(rows.size()).append(" Ganados: ")
        .append(elite == null ? "none" : elite.name).append(" and ")
        .append(boss == null ? "none" : boss.name).append('\n');
  }

  /** r100: the boss takes two crow rows, the elite takes the rest; traps keep their normal deal. */
  private static void applyVillage100(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report, int i) {
    List<Integer> crows = new ArrayList<>();
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) == 0x100 && u8(out, at + ID) == 0x23
          && (u8(out, at + BE_FLAG) & 1) != 0) crows.add(no);
    }
    Set<Integer> base = baseKinds(0x100);
    int s1 = i * 100 + 1, s2 = i * 100 + 2;
    Member boss = chooseAt(BOSSES, seed, options, base, false, i);
    int given = Math.min(2, crows.size());
    if (boss != null) writeRows(out, allowed, crows.subList(0, given),
        boss, seed, s2, options, 0x1D, silent, base);
    Member elite = chooseAt(ELITES, seed, options, base, boss != null && boss.massive, i);
    if (elite != null && crows.size() > given) {
      writeRows(out, allowed, crows.subList(given, crows.size()), elite, seed, s1, options, 0x1D, silent, base);
    }
    report.append("r100: ").append(crows.size()).append(" crows: ")
        .append(boss == null ? "none" : boss.name).append(" and ")
        .append(elite == null ? "none" : elite.name).append('\n');
  }

  /** r101: the boss takes the two dormant rows; the elite takes the other rows, inside the count range. */
  private static void applySquare101(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report, int i) {
    List<Integer> dormant = new ArrayList<>(), rest = new ArrayList<>();
    int n = 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x101 || u8(out, at + ID) == 0) continue;
      if (n == 11 || n == 37) dormant.add(no); else rest.add(no);
      n++;
    }
    Set<Integer> base = baseKinds(0x101);
    int s1 = i * 100 + 1, s2 = i * 100 + 2;
    Member boss = chooseAt(BOSSES, seed, options, base, false, i);
    if (boss != null) writeRows(out, allowed, dormant, boss, seed, s2, options, 0x1B, silent, base);
    Member elite = chooseCountedAt(options, seed, base, i);
    if (elite != null) writeRows(out, allowed, rest, elite, seed, s1, options, 0x1B, silent, base);
    report.append("r101: ").append(rest.size()).append(" zealots as ")
        .append(elite == null ? "none" : elite.name).append(", ")
        .append(dormant.size()).append(" as ").append(boss == null ? "none" : boss.name).append('\n');
  }

  private static void applyPools(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    for (int i = 0; i < ROOMS.length; i++) {
      int room = ROOMS[i];
      if (room == 0x100) { applyVillage100(out, allowed, seed, options, silent, report, i); continue; }
      if (room == 0x101) { applySquare101(out, allowed, seed, options, silent, report, i); continue; }
      if (room == 0x103) { applyFarm(out, allowed, seed, options, silent, report, i); continue; }
      if (room == 0x104) { applyVillage(out, allowed, seed, options, silent, report, i); continue; }
      List<Integer> rows = poolRows(out, room);
      if (rows.size() < 2) {
        report.append(String.format(Locale.ROOT, "r%03x: no safe pool rows\n", room));
        continue;
      }
      Set<Integer> base = baseKinds(room);
      Member elite = chooseAt(ELITES, seed, options, base, false, i);
      if (elite != null) writeMember(out, allowed, rows.get(0), elite, seed, i * 100 + 1,
          options, silent, base);
      Member boss = chooseAt(BOSSES, seed, options, base, elite != null && elite.massive, i);
      if (boss != null) writeMember(out, allowed, rows.get(1), boss, seed, i * 100 + 2,
          options, silent, base);
      report.append(String.format(Locale.ROOT, "r%03x: elite %s, boss %s\n", room,
          elite == null ? "none" : elite.name, boss == null ? "none" : boss.name));
    }
  }

  /** Baseline looks for the ordinary enemies. Rows already written by a pool pick are skipped. */
  private static void planR100(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int villagers = 0, traps = 0;
    boolean jjOn = (options & JJ) != 0, plaga = (options & PLAGA) != 0;
    int off = pick(seed, 0x100, 3);
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x100 || (allowed[no] & A_ID) != 0) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      int flag = u32(out, at + FLAG);
      if (id == 0x12 || id == 0x2A) {
        if (id == 0x12) villagers++; else traps++;
        int i = villagers + traps;
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14 + ((i + off) % 8));
        int weapon = ((i + off) % 3 == 0) ? FLAG_BOWGUN : ((i + off) % 3 == 1) ? FLAG_CLUB : 0;
        if (plaga && i % 4 == 0) weapon |= FLAG_PLAGA;
        putU32(out, at + FLAG, (flag & ~TEST_WEAPON_MASK) | weapon);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG);
      } else if (id == 0x23 && (u8(out, at + BE_FLAG) & 1) != 0) {
        int newType = jjOn ? 2 : 14 + off;
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, newType);
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        putU16(out, at + HP, 1000);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP);
      }
    }
    silent.add("em1d");
    report.append("r100: ").append(villagers).append(" villagers and ").append(traps)
        .append(" traps restyled.\n");
  }

  private static void planR101(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int zealots = 0, garradors = 0, n = 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x101 || (allowed[no] & A_ID) != 0) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      int flag = u32(out, at + FLAG);
      int idx = n++;
      int extra = (options & SHIELD) != 0 && idx % 8 == 0 ? FLAG_SHIELD : 0;
      if ((options & GARRADOR) != 0 && (idx == 3 || idx == 12 || idx == 19 || idx == 27 || idx == 35)) {
        putU8(out, at + TYPE, 10); garradors++;
      } else {
        putU8(out, at + TYPE, 7 + (idx & 1)); zealots++;
      }
      putU8(out, at + ID, 0x1B);
      putU32(out, at + FLAG, (flag & ~TEST_WEAPON_MASK) | extra);
      putU16(out, at + HP, 1200);
      putU8(out, at + SET, 0);
      allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
    }
    silent.add("em1b");
    silent.add("em26");
    report.append("r101: ").append(zealots).append(" zealots, ").append(garradors)
        .append(" Garradors; sets cleared.\n");
  }

  private static void planR103(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int ganados = 0;
    // Every ordinary Ganado becomes an island soldier: a real kind change, not just a model variant.
    boolean basicSwap = true;
    boolean variety = (options & VARIETY) != 0, helmet = (options & HELMET) != 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x103 || (allowed[no] & A_ID) != 0) continue;
      if (u8(out, at + ID) != 0x12) continue;
      int i = ganados++;
      int newId = basicSwap ? 0x1D : 0x12;
      int[] ts = basicSwap ? new int[] {14, 15, 16, 17, 18} : new int[] {0, 1, 3, 4};
      int ty = variety ? ts[(i + pick(seed, 0x103, ts.length)) % ts.length] : ts[0];
      int weapon = 0;
      if ((options & SAW) != 0 && i == 0) { weapon = FLAG_SAW; ty = basicSwap ? 14 : 4; }
      if (helmet && i % 3 == 0) weapon |= FLAG_HELMET;
      putU8(out, at + ID, newId);
      putU8(out, at + TYPE, ty);
      putU32(out, at + FLAG, (u32(out, at + FLAG) & ~WEAPON_MASK) | weapon);
      allow(allowed, no, A_ID | A_TYPE | A_FLAG);
      if (basicSwap && (options & RESET_SET) != 0) { putU8(out, at + SET, 0); allow(allowed, no, A_SET); }
      if (basicSwap) silent.add("em1d");
    }
    report.append("r103: ").append(ganados).append(" Ganados restyled.\n");
  }

  private static void planR104(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int n = 0, variants = 0, skipped = 0;
    int off = pick(seed, 0x104, 5);
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x104 || (allowed[no] & A_ID) != 0) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      int set = u8(out, at + SET), flag = u32(out, at + FLAG);
      if (set != 0 || (flag & FLAG_BOMB) != 0) { skipped++; continue; }
      int idx = n++;
      int[] ts = {0, 1, 3, 4, 6};
      int newType = (options & VARIETY) != 0 ? ts[(idx + off) % ts.length] : ts[0];
      if ((options & SAW) != 0 && idx % 12 == 0) newType = 4;
      int extra = 0;
      if ((options & HELMET) != 0 && idx % 3 == 0) extra |= FLAG_HELMET;
      if ((options & DYNAMITE) != 0 && idx % 7 == 0) extra |= FLAG_BOMB;
      putU8(out, at + ID, 0x13);
      putU8(out, at + TYPE, newType);
      putU32(out, at + FLAG, (flag & ~(WEAPON_MASK & ~FLAG_SAW)) | extra);
      allow(allowed, no, A_ID | A_TYPE | A_FLAG);
      variants++;
    }
    report.append("r104: ").append(variants).append(" restyled, ").append(skipped)
        .append(" event rows untouched.\n");
  }

  /** Four module slots per room visit, with the modules each room already loads for its events. */
  private static String kinds(byte[] src, byte[] out) {
    for (int room : ROOMS) {
      Set<Integer> k = new LinkedHashSet<>();
      for (int no = 0; no < ENTRIES; no++) {
        int at = no * ENTRY;
        if (u16(out, at + ROOM) != room) continue;
        int id = u8(out, at + ID);
        if (id != 0) k.add(id);
      }
      if (room == 0x100) k.add(0x12);
      if (room == 0x101) { k.add(0x15); k.add(0x26); }
      if (room == 0x103) k.add(0x12);
      if (k.size() > 4) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int no = 0; no < ENTRIES; no++) {
          int at = no * ENTRY;
          if (u16(out, at + ROOM) == room && u8(out, at + ID) != 0) {
            int id = u8(out, at + ID);
            counts.put(id, counts.getOrDefault(id, 0) + 1);
          }
        }
        return "the deal would need " + k.size() + " enemy modules in room "
            + Integer.toHexString(room) + " " + counts + " (the game has four)";
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
      for (int b = 0; b < ENTRY; b++) {
        if (src[at + b] == out[at + b]) continue;
        int bit = b == ID ? A_ID : b == TYPE ? A_TYPE
            : (b == FLAG || b == FLAG + 1 || b == FLAG + 2 || b == FLAG + 3) ? A_FLAG
            : (b == HP || b == HP + 1) ? A_HP : b == SET ? A_SET : 0;
        if (bit == 0 || (allowed[no] & bit) == 0) return "entry " + no + " changed protected byte " + b;
      }
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
