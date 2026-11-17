package com.ast.re4decomp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The debug proof of concept of the owner's wish of 17.11.2026: randomize enemy data in the imported game data the
 * way a PC randomizer rewrites an ISO, instead of at play time.
 *
 * The enemy list of a stage is one file on the disc, <code>files/etc/emleonNN.esl</code>: 32-byte records in
 * big-endian (GameCube) order, read straight into the game's own <code>pG->Em_list</code> (src/game/stage.cpp
 * readEmList). So the file and the running game's list are the same structure, and patching the file is a real data
 * rewrite -- the run then plays out of it without the app being involved.
 *
 * Two things this gets for free, both from the game's own code:
 * <ul>
 *   <li>the file holds 255 records while the game's buffer holds 256 (global.h EM_LIST[256]), so a reader that stops
 *       at the file's end simply leaves the last slot as it was;</li>
 *   <li>EmSetFromList (src/game/em_set.cpp) creates only the records whose <code>room</code> is the room it is
 *       loading, so a record written for another room is inert there.</li>
 * </ul>
 *
 * Only emleon00.esl carries r100 (25 records) and r101; r120 has no record in any list file, so it is seeded into the
 * file's spare slot, which no room can lose.
 */
final class EslRandomizer {
  private static final String TAG = "EslRandomizer";

  /** One record of the list: the fields this patch touches, plus where the game expects them. */
  static final class Record {
    static final int SIZE = 0x20;

    int index;
    int beFlag;   // 0x00: bit0 alive, bit1 set (the game sets it after it created the enemy)
    int id;       // 0x01: enemy id, 0 = empty
    int type;     // 0x02
    int set;      // 0x03
    int hp;       // 0x08
    int x, y, z;  // 0x0C: signed, in the game's units
    int room;     // 0x18: stage << 8 | room

    boolean ganado() {
      return id >= 0x10 && id <= 0x20 && id != 0x18;   // 0x18 is the trader, not an enemy
    }

    boolean aliveFlag() {
      return (beFlag & 1) != 0;
    }
  }

  /** A kind out of the randomizer's own pool (runtime/enemy_random.cpp kPoolKinds), with its hp floor. */
  static final class Kind {
    final int id, type, hp;
    final String name;

    Kind(int id, int type, int hp, String name) {
      this.id = id;
      this.type = type;
      this.hp = hp;
      this.name = name;
    }
  }

  /**
   * The pool, in the randomizer's order and with its hp floors ("0" there means the game's own default, which for a
   * patched record is the hp it already carries, so the patch leaves those alone).
   */
  static final Kind[] POOL = {
      new Kind(0x16, 4, 0, "Chainsaw Man"),
      new Kind(0x1D, 2, 0, "Gatling Man"),
      new Kind(0x1B, 10, 0, "Garrador"),
      new Kind(0x2D, 0, 0, "Novistador"),
      new Kind(0x22, 0, 1000, "Dog"),
      new Kind(0x23, 0, 0, "Crow"),
      new Kind(0x26, 0, 1000, "Cow"),
      new Kind(0x3C, 0, 2000, "Armadura"),
      new Kind(0x36, 0, 3000, "Regenerator"),
      new Kind(0x36, 2, 3000, "Iron Maiden"),
      new Kind(0x39, 1, 1250, "Krauser (knife)"),
      new Kind(0x39, 2, 2500, "Krauser (mutated)"),
  };

  /** Rooms this proof of concept touches: the opening valley and the first village room. */
  static final int ROOM_R120 = 0x120;
  static final int ROOM_R100 = 0x100;

  private EslRandomizer() {}

  /** The data folder's copy of the disc 1 enemy list. */
  static File file(Context context) {
    return new File(new File(AppStorage.base(context), "game"),
        "disc1/files/etc/emleon00.esl");
  }

  /** Where the first patch keeps the file the game shipped with, so the rewrite can be undone. */
  static File backup(Context context) {
    return new File(new File(AppStorage.base(context), "esl-backup"), "emleon00.esl");
  }

  /** Reads every record of the file, in disk order. */
  static List<Record> read(File esl) throws IOException {
    List<Record> out = new ArrayList<>();
    try (RandomAccessFile in = new RandomAccessFile(esl, "r")) {
      int count = (int) (in.length() / Record.SIZE);
      byte[] raw = new byte[Record.SIZE];
      for (int i = 0; i < count; i++) {
        in.readFully(raw);
        out.add(decode(raw, i));
      }
    }
    return out;
  }

  private static Record decode(byte[] raw, int index) {
    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
    Record r = new Record();
    r.index = index;
    r.beFlag = b.get(0) & 0xFF;
    r.id = b.get(1) & 0xFF;
    r.type = b.get(2) & 0xFF;
    r.set = b.get(3) & 0xFF;
    r.hp = b.getShort(8) & 0xFFFF;
    r.x = b.getShort(12);
    r.y = b.getShort(14);
    r.z = b.getShort(16);
    r.room = b.getShort(24) & 0xFFFF;
    return r;
  }

  private static void encode(Record r, byte[] raw) {
    ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
    b.put(0, (byte) r.beFlag);
    b.put(1, (byte) r.id);
    b.put(2, (byte) r.type);
    b.put(3, (byte) r.set);
    b.putShort(8, (short) r.hp);
    b.putShort(12, (short) r.x);
    b.putShort(14, (short) r.y);
    b.putShort(16, (short) r.z);
    b.putShort(24, (short) r.room);
  }

  /** Copies the untouched file aside once; later patches never overwrite that copy. */
  static boolean keepOriginal(Context context) {
    File source = file(context);
    File target = backup(context);
    if (target.isFile() || !source.isFile()) return target.isFile();
    try {
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
      // plain streams rather than java.nio.file.Files: no API level question, and this is a small file
      try (java.io.InputStream in = new java.io.FileInputStream(source);
           java.io.OutputStream out = new java.io.FileOutputStream(target)) {
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
      }
      return true;
    } catch (IOException e) {
      Log.w(TAG, "cannot keep the original list: " + e);
      return false;
    }
  }

  /** The patch itself, for the diagnostics log and for the page's status line. */
  static final class Result {
    final List<String> lines = new ArrayList<>();
    int changed, added;

    String summary() {
      return changed + " changed, " + added + " added";
    }
  }

  /**
   * Patches emleon00.esl: r100's entries take a pool kind, and r120 gets the file's spare slot.
   *
   * `seed` picks everything (the same seed rewrites the same way), so a data patch is reproducible like the in-game
   * randomizer's own runs.
   */
  static Result patch(Context context, int seed) throws IOException {
    File esl = file(context);
    if (!esl.isFile()) throw new IOException("The enemy list is missing: " + esl);
    keepOriginal(context);

    List<Record> records = read(esl);
    Result result = new Result();
    java.util.Random rnd = new java.util.Random(seed);

    // R100: the room's own Ganado spots take a pool kind. The records the game already marks alive are the ones
    // that stand there at the start; the rest are left as the room wrote them, so the script's casts stay.
    int r100 = 0;
    for (Record r : records) {
      if (r.room != ROOM_R100) continue;
      r100++;
      if (!r.ganado()) {
        result.lines.add(String.format(Locale.ROOT, "r100 #%d id 0x%02x: not a Ganado spot, left alone", r.index, r.id));
        continue;
      }
      // a record already standing here was rewritten by an earlier patch: leave it, so a second press reaches the
      // room's wave entries instead of rolling the same standing ones again and again
      if (r.aliveFlag()) {
        result.lines.add(String.format(Locale.ROOT, "r100 #%d id 0x%02x: already standing, left alone", r.index, r.id));
        continue;
      }
      Kind k = POOL[rnd.nextInt(POOL.length)];
      int before = r.id;
      r.id = k.id;
      r.type = k.type;
      if (k.hp > 0) r.hp = k.hp;
      // alive, and not yet "set": the game creates it at room start, exactly like a list entry it shipped with
      r.beFlag = (r.beFlag & ~2) | 1;
      result.changed++;
      result.lines.add(String.format(Locale.ROOT,
          "r100 #%d id 0x%02x -> 0x%02x type %d hp %d (%s)", r.index, before, r.id, r.type, r.hp, k.name));
    }

    // R120: the file holds 255 records and the game's buffer 256, so appending one record is the one slot no room
    // can lose. The room has no record of its own in any list file, so without this it stays empty.
    if (r100 == 0) {
      result.lines.add("r100 has no records in this list file");
    }
    boolean r120Present = false;
    for (Record r : records) {
      if (r.room == ROOM_R120) { r120Present = true; break; }
    }
    if (!r120Present) {
      Kind k = POOL[rnd.nextInt(POOL.length)];
      Record add = new Record();
      add.index = records.size();
      add.beFlag = 1;                 // alive, not yet set: created at room start
      add.id = k.id;
      add.type = k.type;
      add.set = 0;
      add.hp = k.hp > 0 ? k.hp : 1000;
      // Leon starts at the room's origin (his spawn in the diagnostics log reads (0, 0, 0)), so a short step away
      // keeps the enemy off his back at the very start
      add.x = 400;
      add.y = 0;
      add.z = 400;
      add.room = ROOM_R120;
      records.add(add);
      result.added++;
      result.lines.add(String.format(Locale.ROOT,
          "r120 #%d added id 0x%02x type %d hp %d (%s) at (%d, %d, %d)",
          add.index, add.id, add.type, add.hp, k.name, add.x, add.y, add.z));
    }

    write(esl, records);
    return result;
  }

  private static void write(File esl, List<Record> records) throws IOException {
    byte[] raw = new byte[Record.SIZE];
    try (RandomAccessFile out = new RandomAccessFile(esl, "rw")) {
      for (Record r : records) {
        encode(r, raw);
        out.write(raw);
      }
      out.setLength((long) records.size() * Record.SIZE);
      out.getFD().sync();
    }
  }

  /** Puts back the file the game shipped with; false when no original was kept or nothing needed undoing. */
  static boolean restore(Context context) throws IOException {
    File source = backup(context);
    if (!source.isFile()) return false;
    try (java.io.InputStream in = new java.io.FileInputStream(source);
         java.io.OutputStream out = new java.io.FileOutputStream(file(context))) {
      byte[] buffer = new byte[8192];
      for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
    }
    return true;
  }
}
