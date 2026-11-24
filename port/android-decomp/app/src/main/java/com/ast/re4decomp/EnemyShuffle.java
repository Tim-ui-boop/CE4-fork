package com.ast.re4decomp;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Offline enemy swap engine for the four test rooms (r100, r101, r103, r104).
 *
 * <p>The player picks a seed and the enemy toggles in the launcher. This reads the imported disc's
 * {@code etc/emleon00.esl}, replaces enemy records one-for-one from a per-room pool, writes a
 * private copy, re-reads it and refuses the whole deal unless every protected byte is unchanged.
 * The core serves that copy instead of the disc's; discs and saves are never written.
 *
 * <p>A swap keeps the record count and the module count stable: a Verdugo becomes a Krauser by
 * changing its module from em2c to em39, not by adding a fifth kind. Rows that a counting script
 * needs ({@code SceCountEmAlive(0x10, 0x20)}) stay inside that id range; bosses go only on rows
 * that are not counted. New kinds get {@code set=0}, high health and their sound bank skipped.
 */
final class EnemyShuffle {
  private static final String TAG = "EnemyShuffle";
  private EnemyShuffle() {}

  private static final int ENTRY = 32, ENTRIES = 256;
  private static final int LIST_BYTES = ENTRIES * ENTRY;
  private static final int MIN_BYTES = 255 * ENTRY;

  private static final int BE_FLAG = 0x00, ID = 0x01, TYPE = 0x02, SET = 0x03, FLAG = 0x04,
      HP = 0x08, ROOM = 0x18;

  /** The option bits. The first eleven are the PC-style toggles; the rest are this engine's. */
  static final int SAW = 1, GARRADOR = 1 << 1, JJ = 1 << 2, SUPER = 1 << 3, VARIETY = 1 << 4,
      HELMET = 1 << 5, SHIELD = 1 << 6, PLAGA = 1 << 7, DYNAMITE = 1 << 8, MIXED = 1 << 9,
      SAFE_CABIN = 1 << 10, REGEN = 1 << 11, IRON = 1 << 12, KRAUSER = 1 << 13,
      MUTATED = 1 << 14, GIGANTE = 1 << 15, ARMADURA = 1 << 16, SHARED_SET = 1 << 17,
      RESET_SET = 1 << 18;
  /** Defaults reproduce the two proven room tests. */
  static final int DEFAULT_OPTIONS = SAW | JJ | GARRADOR | REGEN | VARIETY | SAFE_CABIN;

  private static final int FLAG_SAW = 1 << 28, FLAG_BOMB = 1 << 17;
  private static final int WEAPON_MASK =
      FLAG_SAW | FLAG_BOMB | (1 << 27) | (1 << 29) | (1 << 30) | (1 << 31) | (1 << 11);
  private static final int FLAG_BOWGUN = 1 << 15, FLAG_CLUB = 1 << 11;
  private static final int TEST_WEAPON_MASK = WEAPON_MASK | FLAG_BOWGUN;

  private static final int A_ID = 1, A_TYPE = 2, A_FLAG = 4, A_HP = 8, A_SET = 16;

  /** Model types each module can actually show, from its own switch table. */
  private static final Map<Integer, int[]> TYPES = new LinkedHashMap<>();
  static {
    TYPES.put(0x12, new int[] {0, 1, 3, 4});
    TYPES.put(0x13, new int[] {0, 1, 3, 4, 6});
    TYPES.put(0x15, new int[] {0, 3, 4, 11});
    TYPES.put(0x1B, new int[] {7, 8, 9, 10, 13});
    TYPES.put(0x1D, new int[] {2, 14, 15, 16, 17, 18, 19, 20, 21});
    TYPES.put(0x21, new int[] {0, 1});
    TYPES.put(0x23, new int[] {0});
    TYPES.put(0x26, new int[] {0, 1});
    TYPES.put(0x28, new int[] {0, 1});
    TYPES.put(0x2A, new int[] {0, 2});
    TYPES.put(0x2B, new int[] {0, 1, 2, 3});
    TYPES.put(0x2C, new int[] {0});
    TYPES.put(0x36, new int[] {0, 1, 2, 3});
    TYPES.put(0x39, new int[] {0, 1, 2});
    TYPES.put(0x3C, new int[] {0, 1, 2, 3});
  }

  private static final String LIST_NAME = "etc/emleon00.esl";
  private static final int[] ROOMS = {0x100, 0x101, 0x103, 0x104};

  static final class Result {
    boolean ok;
    String message = "";
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

  /** A little hash so the seed changes which variants are picked, the same way for every player. */
  private static int pick(long seed, int what, int n) {
    long x = seed * 0x9E3779B1L + what * 0x85EBCA6BL;
    x ^= x >>> 33; x *= 0xFF51AFD7ED558CCDL; x ^= x >>> 33;
    return n <= 0 ? 0 : (int) Math.floorMod(x, (long) n);
  }

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

  /** Deals the four test rooms. Refuses the whole deal unless every byte verifies. */
  static Result dealRooms(File dataRoot, File discFiles, long seed, int options) {
    Result r = new Result();
    byte[] src = read(new File(discFiles, LIST_NAME));
    if (src == null || src.length < MIN_BYTES || src.length > LIST_BYTES || src.length % ENTRY != 0) {
      r.message = "The imported disc has no usable " + LIST_NAME + ". Import both discs again, then try.";
      Log.i(TAG, r.message);
      return r;
    }
    byte[] out = new byte[LIST_BYTES];
    System.arraycopy(src, 0, out, 0, src.length);
    int[] allowed = new int[ENTRIES];
    Set<String> silent = new LinkedHashSet<>();
    StringBuilder report = new StringBuilder();
    report.append("Enemy swaps, seed ").append(seed).append(", options ").append(options).append('\n');

    planR100(out, allowed, seed, options, silent, report);
    planR101(out, allowed, seed, options, silent, report);
    planR103(out, allowed, seed, options, silent, report);
    planR104(out, allowed, seed, options, silent, report);

    String why = kinds(src, out);
    if (why == null) why = verify(src, out, allowed);
    if (why != null) {
      r.message = why + " Nothing was written.";
      Log.i(TAG, r.message);
      return r;
    }
    for (String s : silent) report.append("Silent module: ").append(s).append('\n');

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
      writeAll(logFile(dataRoot), report.toString().getBytes(StandardCharsets.UTF_8));
      writeAll(new File(dir, "no-sound.txt"), String.join("\n", silent).getBytes(StandardCharsets.UTF_8));
      // the two fixed-test markers are gone for good
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

  /** r100: villagers and traps become island soldiers; crows become JJ, or a boss and JJ. */
  private static void planR100(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int villagers = 0, crows = 0, traps = 0, jjs = 0, regs = 0, bosses = 0;
    boolean bossHere = (options & (KRAUSER | GIGANTE)) != 0;
    int bossId = (options & KRAUSER) != 0 ? 0x39 : 0x2B;
    int bossType = bossId == 0x39 ? ((options & MUTATED) != 0 ? 2 : 0) : 0;
    int off = pick(seed, 0x100, 3);
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x100) continue;
      int id = u8(out, at + ID);
      int flag = u32(out, at + FLAG);
      if (id == 0x12) {
        int i = villagers++;
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14 + ((i + off) % 8));
        int weapon = ((i + off) % 3 == 0) ? FLAG_BOWGUN : ((i + off) % 3 == 1) ? FLAG_CLUB : 0;
        putU32(out, at + FLAG, (flag & ~TEST_WEAPON_MASK) | weapon);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG);
      } else if (id == 0x23 && (u8(out, at + BE_FLAG) & 1) != 0) {
        int c = crows++;
        int newId = 0x1D, newType = 2, hp = 1000;
        if (bossHere && c == 0) {
          newId = bossId; newType = bossType; hp = bossId == 0x39 ? 3000 : 4000;
          putU8(out, at + SET, 0);
          allow(allowed, no, A_SET);
          silent.add(bossId == 0x39 ? "em39" : "em2b");
          bosses++;
        } else if (!bossHere && (options & REGEN) != 0 && c >= 2) {
          newId = 0x36; newType = (options & IRON) != 0 ? 2 + (regs % 2) : (regs % 2);
          silent.add("em36"); regs++;
        } else {
          jjs++;
        }
        putU8(out, at + ID, newId);
        putU8(out, at + TYPE, newType);
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        putU16(out, at + HP, hp);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP);
      } else if (id == 0x2A) {
        int i = traps++;
        putU8(out, at + ID, 0x1D);
        putU8(out, at + TYPE, 14 + ((i + off) % 8));
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG);
      }
    }
    if (villagers != 12 || crows != 5 || traps != 7) {
      report.append("r100: SKIPPED, disc layout was not the expected 12 villagers / 5 crows / 7 traps.\n");
      return;
    }
    silent.add("em1d");
    report.append("r100: ").append(villagers).append(" island soldiers, ").append(jjs)
        .append(" JJ, ").append(regs).append(" regenerators, ").append(bosses).append(" boss.\n");
  }

  /** r101: the two dormant rows hold one boss; everything else is a zealot or Garrador. */
  private static void planR101(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int n = 0, zealots = 0, garradors = 0, special = 0;
    boolean krauser = (options & KRAUSER) != 0, gigante = (options & GIGANTE) != 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x101) continue;
      int id = u8(out, at + ID);
      if (id != 0x15 && id != 0x26 && id != 0x28) continue;
      int flag = u32(out, at + FLAG);
      int idx = n++;
      int newId = 0x1B, newType;
      if (idx == 11 && krauser) {
        newId = 0x39; newType = (options & MUTATED) != 0 ? 2 : 0; silent.add("em39"); special++;
      } else if (idx == 37 && gigante && !krauser) {
        newId = 0x2B; newType = 0; silent.add("em2b"); special++;
      } else if (idx == 11 || idx == 37) {
        // With a boss in one dormant row the other must give up em2c, or r101 has five modules.
        newId = (krauser || gigante) ? 0x1B : 0x2C;
        newType = (krauser || gigante) ? 7 + (idx & 1) : 0;
        if (newId == 0x2C) silent.add("em2c");
        special++;
      } else if ((options & GARRADOR) != 0
          && (idx == 3 || idx == 12 || idx == 19 || idx == 27 || idx == 35 || idx == 43)) {
        newType = 10; garradors++;
      } else {
        newType = 7 + (idx & 1); zealots++;
      }
      putU8(out, at + ID, newId);
      putU8(out, at + TYPE, newType);
      putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
      putU16(out, at + HP, 1200);
      putU8(out, at + SET, 0);
      allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
    }
    if (n != 46) {
      report.append("r101: SKIPPED, disc layout was not the expected 46 records.\n");
      return;
    }
    silent.add("em1b");
    silent.add("em26");
    report.append("r101: ").append(zealots).append(" zealots, ").append(garradors)
        .append(" Garradors, ").append(special).append(" special; sets cleared.\n");
  }

  /** r103: whole animal kinds are swapped one-for-one; the five Ganados get new looks. */
  private static void planR103(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int cows = 0, chickens = 0, ganados = 0;
    boolean basicSwap = (options & (SHARED_SET | RESET_SET)) != 0;
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x103) continue;
      int id = u8(out, at + ID);
      int flag = u32(out, at + FLAG);
      if (id == 0x26 && (options & GIGANTE) != 0) {
        int i = cows++;
        putU8(out, at + ID, 0x2B); putU8(out, at + TYPE, i % 4);
        putU16(out, at + HP, 4000); putU8(out, at + SET, 0);
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
        silent.add("em2b");
      } else if (id == 0x28 && (options & (ARMADURA | KRAUSER)) != 0) {
        int i = chickens++;
        boolean armor = (options & ARMADURA) != 0;
        putU8(out, at + ID, armor ? 0x3C : 0x39);
        putU8(out, at + TYPE, armor ? (i % 4) : ((options & MUTATED) != 0 ? 2 : 0));
        putU16(out, at + HP, armor ? 1000 : 3000);
        putU8(out, at + SET, 0);
        putU32(out, at + FLAG, flag & ~TEST_WEAPON_MASK);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
        silent.add(armor ? "em3c" : "em39");
      } else if (id == 0x12) {
        int i = ganados++;
        int newId = basicSwap ? 0x1D : 0x12;
        int[] ts = basicSwap ? new int[] {14, 15, 16, 17, 18} : new int[] {0, 1, 3, 4};
        int ty = ts[(i + pick(seed, 0x103, ts.length)) % ts.length];
        int weapon = 0;
        if ((options & SAW) != 0 && i == 0) {
          weapon = FLAG_SAW;
          ty = basicSwap ? 14 : 4;
        }
        putU8(out, at + ID, newId);
        putU8(out, at + TYPE, ty);
        putU32(out, at + FLAG, (flag & ~WEAPON_MASK) | weapon);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG);
        if (basicSwap && (options & RESET_SET) != 0) {
          putU8(out, at + SET, 0);
          allow(allowed, no, A_SET);
        }
        if (basicSwap) silent.add("em1d");
      }
    }
    report.append("r103: ").append(cows).append(" cows and ").append(chickens)
        .append(" chickens swapped, ").append(ganados).append(" Ganados restyled.\n");
  }

  /** r104: type/flag swaps on the script rows, with a few boss rows kept out of the count. */
  private static void planR104(byte[] out, int[] allowed, long seed, int options,
      Set<String> silent, StringBuilder report) {
    int n = 0, variants = 0, bosses = 0, skipped = 0;
    int off = pick(seed, 0x104, 5);
    for (int no = 0; no < ENTRIES; no++) {
      int at = no * ENTRY;
      if (u16(out, at + ROOM) != 0x104) continue;
      int id = u8(out, at + ID);
      if (id == 0) continue;
      int set = u8(out, at + SET), flag = u32(out, at + FLAG);
      // dynamite throwers and named preset rows drive the waves: leave them alone
      if (set != 0 || (flag & FLAG_BOMB) != 0) { skipped++; continue; }
      int idx = n++;
      int newId = 0x13, newType, hp = 0;
      boolean boss = false;
      if ((options & KRAUSER) != 0 && idx == 0) {
        newId = 0x39; newType = (options & MUTATED) != 0 ? 2 : 0; hp = 3000;
        silent.add("em39"); boss = true;
      } else if ((options & GIGANTE) != 0 && idx == 1 && (options & KRAUSER) == 0) {
        newId = 0x2B; newType = 0; hp = 4000; silent.add("em2b"); boss = true;
      } else if ((options & ARMADURA) != 0 && idx == 2
          && (options & KRAUSER) == 0 && (options & GIGANTE) == 0) {
        newId = 0x3C; newType = idx % 4; hp = 1000; silent.add("em3c"); boss = true;
      } else {
        int[] ts = {0, 1, 3, 4, 6};
        newType = ts[(idx + off) % ts.length];
        if ((options & SAW) != 0 && idx % 12 == 0) newType = 4;
      }
      putU8(out, at + ID, newId);
      putU8(out, at + TYPE, newType);
      putU32(out, at + FLAG, flag & ~(WEAPON_MASK & ~FLAG_SAW));
      if (boss) {
        putU8(out, at + SET, 0);
        putU16(out, at + HP, hp);
        allow(allowed, no, A_ID | A_TYPE | A_FLAG | A_HP | A_SET);
        bosses++;
      } else {
        allow(allowed, no, A_ID | A_TYPE | A_FLAG);
        variants++;
      }
    }
    report.append("r104: ").append(variants).append(" restyled, ").append(bosses)
        .append(" boss rows, ").append(skipped).append(" event rows untouched.\n");
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
      if (room == 0x100) k.add(0x12);          // the officer-killer is built by r100.cpp
      if (room == 0x101) { k.add(0x15); k.add(0x26); }  // r101 preloads these for events
      if (k.size() > 4) {
        return "the deal would need " + k.size() + " enemy modules in room "
            + Integer.toHexString(room) + " (the game has four)";
      }
    }
    return null;
  }

  /** Only the fields the plans marked may differ; every other byte must match the disc. */
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
        if (bit == 0 || (allowed[no] & bit) == 0) {
          return "entry " + no + " changed protected byte " + b;
        }
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
