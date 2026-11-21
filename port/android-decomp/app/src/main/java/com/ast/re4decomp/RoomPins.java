package com.ast.re4decomp;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rooms the player wants left exactly as the disc has them (branch feature/offline-randomizer).
 *
 * One line per pin in &lt;data&gt;/randomizer/pins.txt, "&lt;kind&gt; &lt;room&gt;" with kind doors, items or enemies. The
 * shuffles read the set they need once per run, and the screen writes it through save(), which keeps the pins
 * of the other kinds intact. Deleting the file keeps every room in the pool.
 */
public final class RoomPins {
  private static final String TAG = "RoomPins";
  public static final String DOORS = "doors";
  public static final String ITEMS = "items";
  public static final String ENEMIES = "enemies";
  private static final String[] KINDS = {DOORS, ITEMS, ENEMIES};

  private RoomPins() {
  }

  static File file(File root) {
    return new File(root, "randomizer/pins.txt");
  }

  /** Every pin of `kind`; a missing or unreadable file counts as "nothing pinned". */
  public static Set<String> load(File root, String kind) {
    Set<String> rooms = new TreeSet<>();
    File file = file(root);
    if (!file.isFile()) return rooms;
    try {
      for (String line : new String(readAll(file), StandardCharsets.UTF_8).split("\n")) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 2 && parts[0].equals(kind)) rooms.add(parts[1]);
      }
    } catch (IOException | RuntimeException e) {
      Log.i(TAG, "the pins could not be read: " + e);
    }
    return rooms;
  }

  /** The collected room names, sorted: what the screen offers as check boxes. */
  public static List<String> rooms(File root) {
    List<String> names = new ArrayList<>();
    for (String stage : new String[]{"st1", "st2", "st3", "st4"}) {
      File[] files = new File(new File(root, "randomizer"), stage).listFiles();
      if (files == null) continue;
      for (File file : files) {
        String name = file.getName();
        if (!name.endsWith(".das.raw")) continue;
        names.add(name.substring(0, name.length() - 8));
      }
    }
    Collections.sort(names);
    return names;
  }

  /** Replaces this kind's pins; the other kinds are read back and written out unchanged. */
  public static void save(File root, String kind, Set<String> rooms) {
    Set<String> lines = new TreeSet<>();
    for (String other : KINDS) {
      if (other.equals(kind)) continue;
      for (String room : load(root, other)) lines.add(other + " " + room);
    }
    for (String room : rooms) lines.add(kind + " " + room);
    StringBuilder text = new StringBuilder();
    for (String line : lines) text.append(line).append('\n');
    File file = file(root);
    File parent = file.getParentFile();
    if (parent != null && !parent.isDirectory()) parent.mkdirs();
    try {
      writeAll(file, text.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the pins could not be written: " + e);
    }
  }

  private static byte[] readAll(File file) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
      byte[] b = new byte[(int) f.length()];
      f.readFully(b);
      return b;
    }
  }

  private static void writeAll(File file, byte[] b) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
      f.seek(0);
      f.write(b);
      f.setLength(b.length);
    }
  }
}
