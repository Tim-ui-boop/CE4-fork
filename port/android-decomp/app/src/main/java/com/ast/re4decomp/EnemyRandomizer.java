package com.ast.re4decomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * The enemy randomizer, data-rewrite model: one seed turns the Story's enemy lists into a new set of
 * placements, written as sidecar files the loader prefers over the disc's own.
 *
 * This replaces the play-time randomizer that swapped enemies while the game ran. That one crashed
 * (17.11.2026): it asked a room's heap for more enemy modules than it had, the module failed to load,
 * and a room script used the missing enemy. Here nothing is asked of the running game: the lists are
 * edited before play, and the game loads exactly what the plan says.
 *
 * The rules, each from something measured (see port/tools/enemy_plan.py, the Python that proved them):
 *
 *  - a slot a room's own script spawns by number is never touched (10 Story entries do this;
 *    r100 marks 19 of its 25 slots that way -- the old randomizer changed 12 of them, which is
 *    exactly how it crashed);
 *  - only a record alive at room start (be_flag bit 0) may change: the script decides when, if
 *    ever, that entry appears;
 *  - a Crow or a Cow stays scenery, never becomes a fighter;
 *  - a new enemy kind costs about 0.70 of its module's .drs size in the room's heap, which measured
 *    14.5 MB with 1.8 MB spare in r100, so a room may only take kinds it can afford.
 *
 * The sidecar is a complete list, not a patch, because the loader swaps the whole file. The disc's
 * own file is never written to, so the app's hash check keeps passing and deleting the sidecars
 * restores vanilla with no backup and no defaults file.
 */
final class EnemyRandomizer {
  private static final String TAG = "EnemyRandomizer";

  static final int SIZE = 0x20;          // one record, include/global.h EM_LIST
  static final String SIDECAR = ".re4";  // runtime/dvd.cpp entryPath

  /** One record of a list file. Offsets are the struct's own. */
  static final class Record {
    final int slot;
    final int beFlag;   // 0x00 bit0 alive, bit1 set
    final int id;       // 0x01
    final int type;     // 0x02
    final int hp;       // 0x08
    final int room;     // 0x18

    Record(int slot, int beFlag, int id, int type, int hp, int room) {
      this.slot = slot;
      this.beFlag = beFlag;
      this.id = id;
      this.type = type;
      this.hp = hp;
      this.room = room;
    }

    boolean alive() {
      return (beFlag & 1) != 0;
    }

    boolean ganado() {
      return id >= 0x10 && id <= 0x20 && id != 0x18;   // 0x18 is the trader
    }

    boolean scenery() {
      return id == 0x23 || id == 0x26;                 // Crow, Cow
    }
  }

  /** A kind the randomizer deals, with its hp floor (0 = the game's own) and heap cost in KB. */
  static final class Kind {
    final int id, type, hp, costKb;
    final String name;

    Kind(int id, int type, int hp, int costKb, String name) {
      this.id = id;
      this.type = type;
      this.hp = hp;
      this.costKb = costKb;
      this.name = name;
    }
  }

  /** runtime/enemy_random.cpp kPoolKinds, with the heap cost measured from the modules themselves. */
  static final Kind[] POOL = {
      new Kind(0x16, 4, 0, 4048, "Chainsaw Man"),
      new Kind(0x1D, 2, 0, 4404, "Gatling Man"),
      new Kind(0x1B, 10, 0, 4624, "Garrador"),
      new Kind(0x2D, 0, 0, 3176, "Novistador"),
      new Kind(0x22, 0, 1000, 1298, "Dog"),
      new Kind(0x23, 0, 0, 280, "Crow"),
      new Kind(0x26, 0, 1000, 248, "Cow"),
      new Kind(0x3C, 0, 2000, 1598, "Armadura"),
      new Kind(0x36, 0, 3000, 4273, "Regenerator"),
      new Kind(0x36, 2, 3000, 4273, "Iron Maiden"),
  };

  /**
   * KB of new enemy modules a room may take. Heap 4 is 32.5 MB since 18.11.2026 (was 14.5 MB), where a
   * kind costs about 0.70 of its .drs size; r100 measured 1.8 MB spare of the old 14.5 MB, so the
   * bigger heap leaves it roughly 20 MB of room. 12000 KB keeps a wide margin for the rooms that use
   * more of their heap than the first village does (event archives, bigger geometry) while letting a
   * room hold several heavy kinds at once -- the point of the change.
   */
  static final int BUDGET_KB = 12000;

  /**
   * The Story's list files. emleon00..07 are the chapters (stage.cpp checkEmListNo picks one by
   * progress); emleon08/09 and omake* belong to the bonus modes and are not this randomizer's
   * business (the owner's decision, 18.11.2026).
   */
  static final String[] STORY_LISTS = {
      "emleon00.esl", "emleon01.esl", "emleon02.esl", "emleon03.esl",
      "emleon04.esl", "emleon05.esl", "emleon06.esl", "emleon07.esl",
  };

  /**
   * The slots a room's own script spawns by number (tools/room_db.py: the EmSetFromList2 calls of
   * src/st1..st3). Room 0x100's 19 slots are the crash of 17.11.2026 in one line.
   */
  private static final String SPAWN_SLOTS =
      "0x100:3,4,5,6,7,8,9,10,11,12,13,14,18,19,29,37,38,39,122;"
      + "0x10b:161,162,163;"
      + "0x10c:2,3,4;"
      + "0x10e:13,14,15,16;"
      + "0x10f:50,53;"
      + "0x118:121,122,123,124,125,126,127;"
      + "0x119:40,41;"
      + "0x11b:60,62,63,64,65,66,67,68,69,70;"
      + "0x11c:200,201,202,203,204,205,206,207,208,209";

  private EnemyRandomizer() {}

  /** The slots a room's script spawns by number, empty when it does not do that. */
  static Set<Integer> protectedSlots(int room) {
    Set<Integer> out = new LinkedHashSet<>();
    for (String group : SPAWN_SLOTS.split(";")) {
      int colon = group.indexOf(':');
      if (colon < 0) continue;
      if (Integer.decode(group.substring(0, colon)) != room) continue;
      for (String n : group.substring(colon + 1).split(",")) {
        if (!n.trim().isEmpty()) out.add(Integer.parseInt(n.trim()));
      }
    }
    return out;
  }

  /** Where a disc list lives, and where its sidecar goes. */
  static File listFile(Context context, String name) {
    return new File(new File(importRoot(context), "disc1/files/etc"), name);
  }

  static File sidecar(Context context, String name) {
    return new File(listFile(context, name).getAbsolutePath() + SIDECAR);
  }

  /** The imported data folder (ImportManager.root is the same place: <data>/game). */
  static File importRoot(Context context) {
    return new File(AppStorage.base(context), "game");
  }

  /** Reads every record of a list file. */
  static List<Record> read(File esl) throws IOException {
    List<Record> out = new ArrayList<>();
    byte[] data = new byte[(int) esl.length()];
    try (RandomAccessFile in = new RandomAccessFile(esl, "r")) {
      in.readFully(data);
    }
    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    final int n = data.length / SIZE;
    for (int i = 0; i < n; i++) {
      final int o = i * SIZE;
      out.add(new Record(i, b.get(o) & 0xFF, b.get(o + 1) & 0xFF, b.get(o + 2) & 0xFF,
          b.getShort(o + 8) & 0xFFFF, b.getShort(o + 24) & 0xFFFF));
    }
    return out;
  }

  /** What one run did: how many records changed per list, and why anything was left alone. */
  static final class Result {
    final Map<String, Integer> perList = new TreeMap<>();
    int changed;
    int lists;

    String summary() {
      return changed + " enemies over " + lists + (lists == 1 ? " list" : " lists");
    }
  }

  /**
   * Writes the sidecar lists for `seed`: the whole pipeline, as the Python tools prove it offline
   * (tools/enemy_plan.py and enemy_write.py, measured against the debug disc: 274 records over 8
   * lists at seed 12345).
   *
   * The random generator is seeded once per run and walks the rooms in a fixed order, so the same
   * seed always writes the same files -- a randomizer seed must be reproducible.
   */
  static Result apply(Context context, int seed) throws IOException {
    return apply(context, seed, RandomizerConfig.ENEMY_POOL_DEFAULT);
  }

  /**
   * The same write, restricted to the kinds the player's enemy-kind switches allow (`pool`, a bit per entry of
   * {@link #POOL}; 0 means the default set). Before this the switch was drawn on the page and then ignored here,
   * which is why Cows kept appearing after the owner had switched them off.
   */
  static Result apply(Context context, int seed, int pool) throws IOException {
    final boolean[] allowed = allowedKinds(pool);
    Result result = new Result();
    final Random rng = new Random(seed);
    for (String name : STORY_LISTS) {
      File src = listFile(context, name);
      if (!src.isFile()) continue;
      byte[] data = new byte[(int) src.length()];
      try (RandomAccessFile in = new RandomAccessFile(src, "r")) {
        in.readFully(data);
      }
      final List<Record> records = read(src);
      // what this room already loads decides what a new kind costs
      final Map<Integer, Set<Integer>> roomKinds = new LinkedHashMap<>();
      for (Record r : records) {
        if (!r.alive()) continue;
        roomKinds.computeIfAbsent(r.room, k -> new LinkedHashSet<>()).add(r.id);
      }
      final Map<Integer, Integer> roomSpend = new LinkedHashMap<>();
      int changedHere = 0;
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
      for (Record r : records) {
        Set<Integer> prot = protectedSlots(r.room);
        if (prot.contains(r.slot)) continue;   // the room's script spawns it by number
        if (!r.alive()) continue;              // the script decides when it appears
        if (r.id == 0) continue;
        List<Kind> candidates = new ArrayList<>();
        if (r.ganado()) {
          for (int i = 0; i < POOL.length; i++) if (allowed[i]) candidates.add(POOL[i]);
        } else if (r.scenery()) {
          for (int i = 0; i < POOL.length; i++) {
            if (allowed[i] && (POOL[i].id == 0x23 || POOL[i].id == 0x26)) candidates.add(POOL[i]);
          }
        } else {
          continue;                            // the room's own business
        }
        Set<Integer> loaded = roomKinds.getOrDefault(r.room, new LinkedHashSet<>());
        int spent = roomSpend.getOrDefault(r.room, 0);
        List<Kind> affordable = new ArrayList<>();
        for (Kind k : candidates) {
          if (loaded.contains(k.id) || spent + k.costKb <= BUDGET_KB) affordable.add(k);
        }
        if (affordable.isEmpty()) continue;
        Kind kind = affordable.get(rng.nextInt(affordable.size()));
        if (!loaded.contains(kind.id)) roomSpend.put(r.room, spent + kind.costKb);
        loaded.add(kind.id);
        if (kind.id == r.id && (kind.hp == 0 || kind.hp == r.hp)) continue;  // no change
        final int o = r.slot * SIZE;
        b.put(o + 1, (byte) kind.id);
        b.put(o + 2, (byte) kind.type);
        if (kind.hp != 0) b.putShort(o + 8, (short) kind.hp);
        b.put(o, (byte) ((b.get(o) & ~0x02) | 0x01));   // alive, not yet set
        changedHere++;
      }
      if (changedHere == 0) continue;
      writeSidecar(sidecar(context, name), data);
      result.perList.put(name, changedHere);
      result.changed += changedHere;
      result.lists++;
    }
    Log.i(TAG, "seed " + seed + ": " + result.summary());
    return result;
  }

  /** Writes a sidecar through a temp file: a half-written one must never be read. */
  private static void writeSidecar(File dst, byte[] data) throws IOException {
    File tmp = new File(dst.getAbsolutePath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      out.write(data);
      out.getFD().sync();
    }
    if (!tmp.renameTo(dst)) {
      if (!tmp.delete()) Log.w(TAG, "cannot remove " + tmp);
      throw new IOException("Could not write " + dst.getName());
    }
  }

  /** The pool mask as one boolean per entry of {@link #POOL}; 0 means the default set. */
  static boolean[] allowedKinds(int pool) {
    final int mask = pool == 0 ? RandomizerConfig.ENEMY_POOL_DEFAULT : pool;
    final boolean[] out = new boolean[POOL.length];
    for (int i = 0; i < POOL.length; i++) out[i] = (mask >> i & 1) != 0;
    return out;
  }

  /** Removes every sidecar, which restores the disc's own lists. Returns how many were removed. */
  static int restore(Context context) {
    int removed = 0;
    for (String name : STORY_LISTS) {
      File f = sidecar(context, name);
      if (f.isFile() && f.delete()) removed++;
    }
    Log.i(TAG, "restored: " + removed + " sidecar(s) removed");
    return removed;
  }

  /** How many sidecars are in place, so the page can say whether a patched run is active. */
  static int active(Context context) {
    int n = 0;
    for (String name : STORY_LISTS) if (sidecar(context, name).isFile()) n++;
    return n;
  }
}
