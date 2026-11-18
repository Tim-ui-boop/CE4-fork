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

  /**
   * The enemy kinds a VILLAGER may become, and the only thing the first chapter needs. This is the same insight the
   * r100 hook uses: every kind here is the same villager module with another variant checked, so a room can show a
   * chainsaw man, a woman or an armed villager without loading one extra module, sound bank or ARAM region.
   *
   * `id` stays 0x12 (or whichever villager the entry already was) and only `type` changes, exactly like em10_set.cpp
   * and em12_set.cpp switch their own villagers:
   *
   *   0  default male villager   1  young villager   3  armed villager   4  chainsaw man
   *
   * That is what made r100 crash-free while the module-swapping randomizer crashed: no new module is ever requested.
   */
  static final class Form {
    final int type;
    final String name;

    Form(int type, String name) {
      this.type = type;
      this.name = name;
    }
  }

  /**
   * The forms em10_set.cpp and em12_set.cpp themselves switch on: the default villager, the young one, the armed one
   * and the chainsaw man. Any other `type` is a special form a room's script picked on purpose (r101's siege uses
   * type 11), and it is left exactly as the disc wrote it.
   */
  static boolean plainForm(int type) {
    return type == 0 || type == 1 || type == 3 || type == 4;
  }

  /** The villager forms, in EM_LIST.type order (em10_set.cpp / em12_set.cpp). */
  static final Form[] VILLAGER_FORMS = {
      new Form(0, "villager"),
      new Form(1, "young villager"),
      new Form(3, "armed villager"),
      new Form(4, "Chainsaw Man"),
  };

  /**
   * Story rooms that may only become another kind of VILLAGER, never another enemy. The first chapter is one long
   * scripted village: its rooms hold the crows Leon walks past, the first zombie at the house, the ambush and the
   * bingo village, and every one of them is cut into events that expect a villager body. Loading a Garrador or a
   * Regenerator into those rooms is what crashed r101 on 18.11.2026 -- the room ran out of enemy sound blocks
   * (SndEmDataReadCheck: six) and module space, lost its own em15, and panicked in the sound driver. A villager
   * variant costs nothing, so variety there is free.
   */
  static boolean villagersOnly(int room) {
    return (room & 0xF00) == 0x100 && room >= 0x100 && room <= 0x11F;
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
   * KB of new enemy modules a room may take in total. The r101 crash of 18.11.2026 came from 9 MB of extra modules
   * (em1b Garrador 4589 KB + em36 Regenerator 4476 KB) whose sound banks and ARAM loads then took the room's own
   * em15 down with them -- the log ends in "SND SEQ data error" / OSPanic in snd_seq2 right after the last bank.
   * 6000 KB admits one heavy kind (a Garrador fits, two do not) and all the light ones at once.
   */
  static final int BUDGET_KB = 6000;

  /**
   * The engine's enemy module slots (port patch 0235: EmReadModule[8]). A room's own disc kinds hold their slots
   * for the whole room, so a new kind may only take one of what is left.
   */
  static final int MODULE_SLOTS = 8;

  /**
   * New enemy kinds one room may receive. One keeps the room recognisable and leaves every remaining slot, sound
   * bank and ARAM region to the enemies the disc put there. Two is what crashed r101.
   */
  static final int MAX_NEW_KINDS_PER_ROOM = 1;

  /**
   * What the first chapter's scenery (a Crow or a Cow) may become: only a light enemy. A dog (1298 KB) or an
   * armoured villager (1598 KB) fits the scripted approach; a Garrador, a Regenerator or a Gatling man does not, and
   * the crows Leon walks past are part of a cutscene the room was built around.
   */
  static final int LIGHT_KIND_KB = 1600;

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
    /** Records that became another villager form: the first chapter's change, and it costs no enemy module. */
    int forms;

    String summary() {
      final StringBuilder out = new StringBuilder();
      out.append(changed).append(" enemies over ").append(lists).append(lists == 1 ? " list" : " lists");
      if (forms > 0) out.append(", ").append(forms).append(" of them villager forms");
      return out.toString();
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
    final File etc = new File(importRoot(context), "disc1/files/etc");
    // A run that writes nothing used to look exactly like a run that worked: the page said "Enemies written" and the
    // player saw no change. Fail loudly instead, and name the folder, because the three plausible causes (no import
    // yet, disc 2 only, a different data folder) all show up here.
    if (!etc.isDirectory()) {
      throw new IOException("No imported disc at " + etc.getAbsolutePath() + " -- import the disc first.");
    }
    int found = 0;
    for (String name : STORY_LISTS) {
      File src = listFile(context, name);
      if (!src.isFile()) continue;
      found++;
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
      final Map<Integer, Integer> roomNewKinds = new LinkedHashMap<>();
      int changedHere = 0;
      ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
      for (Record r : records) {
        Set<Integer> prot = protectedSlots(r.room);
        if (prot.contains(r.slot)) continue;   // the room's script spawns it by number
        if (r.id == 0) continue;
        final int o = r.slot * SIZE;
        // The first chapter: another kind of VILLAGER, never another enemy module, and the alive-at-start rule does
        // not apply here. The debug disc's first chapter has NO villager alive at room start -- r100's 12 villagers
        // are be_flag 0 because every one of them is spawned by the script (slot 0 by EmSetEvent, slots 3-8 and the
        // rest by EmSetFromList2) -- so requiring be_flag bit 0 left the village untouched. A form byte is only read
        // when the script creates the enemy, so writing it early is exactly what the script would have done.
        if (r.ganado() && villagersOnly(r.room)) {
          // Only a PLAIN villager is re-rolled. A type outside 0/1/3/4 is a special form the script chose on purpose
          // (r101's slot 21 is type 11, a villager with a weapon), and the bingo village's siege expects exactly
          // those, so they keep the form the disc gave them.
          if (!plainForm(r.type)) continue;
          Form form = VILLAGER_FORMS[rng.nextInt(VILLAGER_FORMS.length)];
          if (form.type == r.type) continue;   // the form it already has: no visible change
          b.put(o + 2, (byte) form.type);
          changedHere++;
          result.forms++;
          continue;
        }
        // An enemy the room's script creates later (be_flag bit 0 clear) may change its kind too. It is not skipped
        // any more: on the debug disc only 262 of 992 Ganados are alive at room start, so requiring that bit kept
        // three quarters of the game unchanged -- the reason whole chapters looked randomizer-free. Nothing here
        // asks the room to create anything; it only rewrites what the script would create anyway.
        List<Kind> candidates = new ArrayList<>();
        if (r.ganado()) {
          for (int i = 0; i < POOL.length; i++) if (allowed[i]) candidates.add(POOL[i]);
        } else if (r.scenery()) {
          // A Crow or a Cow may become a real enemy, as the PC randomizer does; the old code offered only Crow and
          // Cow, both excluded by the default pool, so neither ever changed. In the first chapter the scenery stays
          // lightweight on purpose: the approach is a scripted walk past those crows, and turning them into
          // Garradors there would load modules a cutscene room cannot afford (the r101 crash).
          for (int i = 0; i < POOL.length; i++) {
            if (!allowed[i]) continue;
            if (villagersOnly(r.room) && POOL[i].costKb > LIGHT_KIND_KB) continue;
            candidates.add(POOL[i]);
          }
        } else {
          continue;                            // the room's own business
        }
        Set<Integer> loaded = roomKinds.getOrDefault(r.room, new LinkedHashSet<>());
        int spent = roomSpend.getOrDefault(r.room, 0);
        int added = roomNewKinds.getOrDefault(r.room, 0);
        // Everywhere else the entry takes another kind's module, so the room must be able to hold it: the engine has
        // six enemy sound blocks (SndEmDataReadCheck) and eight module slots, and the room's own disc kinds come
        // first -- the r101 crash happened when a Garrador and a Regenerator crowded em15 out.
        List<Kind> affordable = new ArrayList<>();
        for (Kind k : candidates) {
          if (loaded.contains(k.id)) {
            affordable.add(k);
            continue;
          }
          if (added >= MAX_NEW_KINDS_PER_ROOM) continue;
          if (loaded.size() + 1 >= MODULE_SLOTS) continue;
          if (spent + k.costKb > BUDGET_KB) continue;
          affordable.add(k);
        }
        if (affordable.isEmpty()) continue;
        Kind kind = affordable.get(rng.nextInt(affordable.size()));
        if (!loaded.contains(kind.id)) {
          roomSpend.put(r.room, spent + kind.costKb);
          roomNewKinds.put(r.room, added + 1);
        }
        loaded.add(kind.id);
        if (kind.id == r.id && (kind.hp == 0 || kind.hp == r.hp)) continue;  // no change
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
    if (found == 0) {
      throw new IOException("No enemy lists in " + etc.getAbsolutePath() + " -- the import looks incomplete.");
    }
    Log.i(TAG, "seed " + seed + ", pool 0x" + Integer.toHexString(pool) + ", " + found + " list(s) read: "
        + result.summary());
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
