package com.ast.re4decomp;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * The first patch of the randomizer (branch feature/offline-randomizer): exchange the items lying in the rooms.
 *
 * A decoded room archive is a small table of sub-files (src/game/read.cpp GetDataExt): a count at 0x00, an offset
 * table at 0x10 (count u32 entries, relative to the archive start) and a 4-byte tag per entry right after it. The
 * items of the room are the "ITA" sub-file: the 4 bytes "ITA", a u16 version, a u16 count, then one record of
 * 0x9C bytes per item area (include/sce_at.h, SCE_AT_ITEM); item id at +0x78 and its count at +0x7C, both big endian
 * as the GameCube has them (the port converts them when it loads the room).
 *
 * Two items of the same room are exchanged, which keeps every model available: each item that appears is one the
 * room already showed. That is the safe way round the model limit until a merged model pack exists.
 */
public final class SidecarSwap {
  private static final String TAG = "SidecarSwap";
  /** One ITA item area (include/sce_at.h: SCE_AT_ITEM, 0x9C bytes). */
  private static final int RECORD = 0x9C;
  private static final int ITEM_ID = 0x78;
  private static final int ITEM_NUM = 0x7C;

  private SidecarSwap() {
  }

  private static int be32(byte[] b, int at) {
    return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
  }

  private static int be16(byte[] b, int at) {
    return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
  }

  private static void put16(byte[] b, int at, int v) {
    b[at] = (byte) (v >> 8);
    b[at + 1] = (byte) v;
  }

  /** Offset of the ITA sub-file in a decoded room archive, -1 when there is none. */
  private static int itaOffset(byte[] b) {
    if (b.length < 0x14) return -1;
    int num = be32(b, 0);
    if (num <= 0 || num > 128) return -1;
    int offsets = 0x10;
    int tags = offsets + num * 4;
    if (tags + num * 4 > b.length) return -1;
    for (int i = 0; i < num; i++) {
      int t = tags + i * 4;
      if (b[t] == 'I' && b[t + 1] == 'T' && b[t + 2] == 'A') {
        int ofs = be32(b, offsets + i * 4);
        return ofs >= 0 && ofs < b.length ? ofs : -1;
      }
    }
    return -1;
  }

  /** The item areas of the room as archive offsets, empty when the ITA block does not look right. */
  private static List<Integer> areas(byte[] b, int ita) {
    List<Integer> list = new ArrayList<>();
    if (ita + 8 > b.length) return list;
    if (!(b[ita] == 'I' && b[ita + 1] == 'T' && b[ita + 2] == 'A')) return list;
    int count = be16(b, ita + 6);
    if (count <= 0 || count > 512) return list;
    if (ita + 8 + count * RECORD > b.length) return list;
    for (int i = 0; i < count; i++) list.add(ita + 8 + i * RECORD);
    return list;
  }

  /** What the first file holds, so a wrong assumption is named instead of guessed at. */
  private static void diagnose(File file, byte[] b) {
    StringBuilder t = new StringBuilder();
    t.append(file.getName()).append(": ").append(b.length).append(" bytes, starts");
    for (int i = 0; i < Math.min(16, b.length); i++) t.append(' ').append(String.format("%02x", b[i] & 0xFF));
    t.append(", count at 0x00 ").append(be32(b, 0));
    if (b.length >= 0x14) {
      int num = be32(b, 0);
      if (num > 0 && num <= 128 && 0x10 + num * 8 <= b.length) {
        t.append(", tags");
        for (int i = 0; i < num; i++) {
          int at = 0x10 + num * 4 + i * 4;
          String tag = new String(new byte[]{b[at], b[at + 1], b[at + 2], b[at + 3]});
          t.append(' ').append(tag.isEmpty() ? "?" : tag);
        }
      }
    }
    int found = -1;
    for (int i = 0; i + 2 < b.length && i < 200000; i++) {
      if (b[i] == 'I' && b[i + 1] == 'T' && b[i + 2] == 'A') {
        found = i;
        break;
      }
    }
    t.append(", first ITA in the bytes at ").append(found);
    Log.i(TAG, t.toString());
  }

  /**
   * Exchanges the items of every collected room in pairs and writes the sidecars back. Returns how many rooms
   * changed and how many item pairs were exchanged.
   */
  static String swapAll(File root) {
    File folder = new File(root, "randomizer");
    String[] stages = {"st1", "st2", "st3", "st4"};
    int rooms = 0, swaps = 0, skipped = 0;
    boolean described = false;
    for (String stage : stages) {
      File[] files = new File(folder, stage).listFiles();
      if (files == null) continue;
      for (File file : files) {
        if (!file.getName().endsWith(".das.raw")) continue;
        try {
          if (!described) {
            described = true;
            byte[] head = new byte[(int) Math.min(file.length(), 64L * 1024 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
              in.read(head);
            }
            diagnose(file, head);
          }
          int n = swap(file);
          if (n < 0) skipped++;
          else if (n == 0) skipped++;
          else {
            rooms++;
            swaps += n;
          }
        } catch (IOException | RuntimeException e) {
          Log.i(TAG, file.getName() + ": " + e);
          skipped++;
        }
      }
    }
    String result = rooms + " rooms changed, " + swaps + " item pairs exchanged, " + skipped + " left as they were";
    Log.i(TAG, result);
    return result;
  }

  /** Swaps the items of one room file; -1 when it has no usable item block, else the pairs exchanged. */
  private static int swap(File file) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
      long size = f.length();
      if (size <= 0 || size > 64L * 1024 * 1024) return -1;
      byte[] b = new byte[(int) size];
      f.readFully(b);
      int ita = itaOffset(b);
      if (ita < 0) return -1;
      List<Integer> areas = areas(b, ita);
      if (areas.isEmpty()) return -1;

      int pairs = 0;
      for (int i = 0; i + 1 < areas.size(); i += 2) {
        int a = areas.get(i), c = areas.get(i + 1);
        int idA = be16(b, a + ITEM_ID), idC = be16(b, c + ITEM_ID);
        // 0 and 0xFFFF are "nothing here": an item area without an item stays as it is
        if (idA == 0 || idA == 0xFFFF || idC == 0 || idC == 0xFFFF) continue;
        int numA = be16(b, a + ITEM_NUM), numC = be16(b, c + ITEM_NUM);
        put16(b, a + ITEM_ID, idC);
        put16(b, a + ITEM_NUM, numC);
        put16(b, c + ITEM_ID, idA);
        put16(b, c + ITEM_NUM, numA);
        pairs++;
      }
      if (pairs == 0) return 0;
      f.seek(0);
      f.write(b);
      Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": " + pairs + " pairs exchanged");
      return pairs;
    }
  }
}
