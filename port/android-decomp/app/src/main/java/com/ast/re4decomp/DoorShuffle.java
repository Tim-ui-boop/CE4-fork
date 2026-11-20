package com.ast.re4decomp;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Doors and progression keys (branch feature/offline-randomizer), patched in the decoded room sidecar.
 *
 * AEV: the room's area records ("AEV" sub-file, u16 version 0x104, u16 count at +0x06, records at +0x10,
 * 0x9C bytes each; include/sce_at.h SCE_AT_DATA, src/game/sce_at.cpp SceAtInit). Record byte 0x35 is the
 * area type: 1 = door, 3 = item, 17 = use (a gate that names an item in a u16 at 0x5E).
 * Door payload: destination position + angle 0x5C..0x6B, stage 0x6C, room 0x6D, part 0x74. The lock is the
 * record's own (key_id 0x6E, key_flg 0x6F) and never travels.
 *
 * Memory: a room archive is up to several megabytes and there are more than a hundred of them, so nothing
 * keeps the room bytes. A scan reads one file, keeps the small metadata (door payloads, item areas, the
 * model ids) and drops the bytes again; the patching pass later reads and writes one room at a time.
 *
 * Geometry of a swap: the destination payload belongs to the room it leads into, so re-pairing A-B and C-D
 * into A-D and C-B is exactly "exchange the payloads of a and c, and of b and d". Every room keeps its
 * number of doors and the pairing stays reciprocal, so no door can lead into a wall.
 */
public final class DoorShuffle {
  private static final String TAG = "DoorShuffle";
  private static final int RECORD = 0x9C;
  private static final int TABLES = 0x10;
  private static final int TYPE = 0x35;
  private static final int TYPE_DOOR = 1;
  private static final int TYPE_ITEM = 3;
  private static final int TYPE_USE = 17;
  private static final int DOOR_POS = 0x5C;      // 12 bytes position + 4 bytes angle
  private static final int DOOR_POS_LEN = 16;
  private static final int DOOR_STAGE = 0x6C;
  private static final int DOOR_ROOM = 0x6D;
  private static final int DOOR_KEY = 0x6E;
  private static final int DOOR_FLG = 0x6F;
  private static final int DOOR_PART = 0x74;
  private static final int USE_ITEM = 0x5E;
  private static final int AREA_NO = 0x36;
  private static final int ITEM_ID = 0x78;
  private static final int ITEM_NUM = 0x7C;
  private static final int ITEM_EFF = 0x80;
  private static final int WAITING = 0x46;
  private static final int CTRL = 0x84;
  private static final int MODEL_ID = 8;
  private static final long MAX_ROOM = 64L * 1024 * 1024;
  /** Below this many reachable rooms the collection does not match the doors: refuse instead of writing. */
  private static final int MIN_UNIVERSE = 24;
  private static final String START = "r100";
  private static final String[] STORY = {"st1", "st2", "st3"};

  private DoorShuffle() {
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

  private static int entry(byte[] b, String tag) {
    if (b.length < 0x20) return -1;
    int num = be32(b, 0);
    if (num <= 0 || num > 0x1000) return -1;
    int offs = 0x10, tags = offs + num * 4;
    if (tags + num * 4 > b.length) return -1;
    for (int i = 0; i < num; i++) {
      int t = tags + i * 4;
      if (b[t] == (byte) tag.charAt(0) && b[t + 1] == (byte) tag.charAt(1) && b[t + 2] == (byte) tag.charAt(2)) {
        int ofs = be32(b, offs + i * 4);
        return ofs >= 0 && ofs < b.length ? ofs : -1;
      }
    }
    return -1;
  }

  private static List<Integer> records(byte[] b, int base, int version) {
    List<Integer> out = new ArrayList<>();
    if (base < 0 || base + TABLES > b.length) return out;
    if (be16(b, base + 4) != version) return out;
    int count = be16(b, base + 6);
    if (count <= 0 || count > 512 || base + TABLES + count * RECORD > b.length) return out;
    for (int i = 0; i < count; i++) out.add(base + TABLES + i * RECORD);
    return out;
  }

  private static Set<Integer> models(byte[] b) {
    Set<Integer> ids = new TreeSet<>();
    int itm = entry(b, "ITM");
    if (itm < 0 || itm + 0x10 > b.length) return ids;
    int count = be32(b, itm + 4);
    int table = be32(b, itm + 8);
    if (count <= 0 || count > 0x100 || table <= 0 || itm + table + count * MODEL_ID > b.length) return ids;
    for (int i = 0; i < count; i++) ids.add(be16(b, itm + table + i * MODEL_ID));
    return ids;
  }

  // ---- metadata (small enough to keep for every room) ------------------------------------------------------

  private static final class Dest {
    final byte[] pos = new byte[DOOR_POS_LEN];
    int stage;
    int room;
    int part;

    static Dest of(byte[] b, int at) {
      Dest d = new Dest();
      System.arraycopy(b, at + DOOR_POS, d.pos, 0, DOOR_POS_LEN);
      d.stage = b[at + DOOR_STAGE] & 0xFF;
      d.room = b[at + DOOR_ROOM] & 0xFF;
      d.part = b[at + DOOR_PART] & 0xFF;
      return d;
    }

    void into(byte[] b, int at) {
      System.arraycopy(pos, 0, b, at + DOOR_POS, DOOR_POS_LEN);
      b[at + DOOR_STAGE] = (byte) stage;
      b[at + DOOR_ROOM] = (byte) room;
      b[at + DOOR_PART] = (byte) part;
    }

    String name() {
      return String.format(Locale.US, "r%d%02x", stage, room);
    }
  }

  private static boolean same(Dest a, Dest b) {
    return a.stage == b.stage && a.room == b.room && a.part == b.part && Arrays.equals(a.pos, b.pos);
  }

  private static final class Door {
    Room room;
    int at;
    int area;
    int keyId;
    int keyFlg;
    Dest dest;
    Dest orig;
    Door pair;
  }

  /** One item area, only what the key placement needs. */
  private static final class ItemArea {
    int at;
    int id;
    int waiting;
    int ctrl;

    boolean free() {
      return (id == 0 || id == 0xFFFF || id >= 0x1000) && waiting == 0 && (ctrl & 0x10) == 0;
    }
  }

  private static final class Room {
    String name;
    File file;
    boolean dirty;
    final List<Door> doors = new ArrayList<>();
    final List<ItemArea> items = new ArrayList<>();
    final List<Integer> gates = new ArrayList<>();
    Set<Integer> models = new TreeSet<>();
    /** Item areas this run writes: offset -> {id, num}. */
    final Map<Integer, int[]> writes = new LinkedHashMap<>();
  }

  // ---- scan: one room at a time, keep metadata only ---------------------------------------------------------

  private static Map<String, Room> scan(File root) {
    Map<String, Room> rooms = new TreeMap<>();
    for (String stage : STORY) {
      File[] files = new File(new File(root, "randomizer"), stage).listFiles();
      if (files == null) continue;
      for (File file : files) {
        if (!file.getName().endsWith(".das.raw")) continue;
        String name = file.getName().substring(0, file.getName().length() - 8);
        if (file.length() <= 0 || file.length() > MAX_ROOM) continue;
        File orig = new File(file.getPath() + ".orig");
        byte[] bytes;
        try {
          bytes = readAll(orig.isFile() ? orig : file);     // every run starts from the vanilla snapshot
        } catch (IOException | RuntimeException e) {
          Log.i(TAG, file.getName() + ": " + e);
          continue;
        }
        try {
          Room room = new Room();
          room.file = file;
          room.name = name;
          int aev = entry(bytes, "AEV");
          for (int at : records(bytes, aev, 0x104)) {
            int type = bytes[at + TYPE] & 0xFF;
            if (type == TYPE_DOOR) {
              Door door = new Door();
              door.room = room;
              door.at = at;
              door.area = bytes[at + AREA_NO] & 0xFF;
              door.keyId = bytes[at + DOOR_KEY] & 0xFF;
              door.keyFlg = bytes[at + DOOR_FLG] & 0xFF;
              door.dest = Dest.of(bytes, at);
              door.orig = Dest.of(bytes, at);
              if (door.dest.name().equals(name)) continue;     // a door into its own room stays as it is
              room.doors.add(door);
            } else if (type == TYPE_USE) {
              room.gates.add(be16(bytes, at + USE_ITEM));
            }
          }
          int ita = entry(bytes, "ITA");
          for (int at : records(bytes, ita, 0x105)) {
            if ((bytes[at + TYPE] & 0xFF) != TYPE_ITEM) continue;
            ItemArea item = new ItemArea();
            item.at = at;
            item.id = be16(bytes, at + ITEM_ID);
            item.waiting = bytes[at + WAITING] & 0xFF;
            item.ctrl = bytes[at + CTRL] & 0xFF;
            room.items.add(item);
          }
          room.models = models(bytes);
          rooms.put(name, room);
        } finally {
          bytes = null;                                        // the room bytes are never kept
        }
      }
    }
    return rooms;
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
      f.setLength(b.length);   // a shorter file must not keep the tail of the previous one
    }
  }

  // ---- the map ---------------------------------------------------------------------------------------------

  private static void link(Map<String, Room> rooms) {
    Map<String, List<Door>> byEdge = new HashMap<>();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) byEdge.computeIfAbsent(room.name + ">" + door.dest.name(), k -> new ArrayList<>()).add(door);
    }
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        List<Door> candidates = byEdge.get(door.dest.name() + ">" + room.name);
        if (candidates == null) continue;
        for (Door c : candidates) {
          if (c.pair == null && c.room != door.room) {
            door.pair = c;
            c.pair = door;
            break;
          }
        }
      }
    }
  }

  private static Set<String> oneWay(Map<String, Room> rooms) {
    Set<String> fixed = new TreeSet<>();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        if (door.pair == null && rooms.containsKey(door.dest.name())) fixed.add(room.name + ">" + door.dest.name());
      }
    }
    return fixed;
  }

  private static Map<String, Set<String>> graph(Map<String, Room> rooms) {
    Map<String, Set<String>> adj = new HashMap<>();
    for (Room room : rooms.values()) {
      Set<String> next = adj.computeIfAbsent(room.name, k -> new TreeSet<>());
      for (Door door : room.doors) {
        String target = door.dest.name();
        if (rooms.containsKey(target) && !target.equals(room.name)) next.add(target);
      }
    }
    for (String edge : oneWay(rooms)) {
      String[] parts = edge.split(">");
      if (parts.length != 2) continue;
      adj.computeIfAbsent(parts[0], k -> new TreeSet<>()).add(parts[1]);
      adj.computeIfAbsent(parts[1], k -> new TreeSet<>()).add(parts[0]);
    }
    return adj;
  }

  private static Set<String> reach(Map<String, Set<String>> adj, Map<String, Room> rooms, Set<Integer> held) {
    Set<String> seen = new TreeSet<>();
    List<String> stack = new ArrayList<>();
    stack.add(START);
    while (!stack.isEmpty()) {
      String room = stack.remove(stack.size() - 1);
      if (seen.contains(room) || !rooms.containsKey(room)) continue;
      seen.add(room);
      if (held != null) {
        Room r = rooms.get(room);
        boolean blocked = false;
        for (Integer gate : r.gates) if (!held.contains(gate)) blocked = true;
        if (blocked) continue;
      }
      stack.addAll(adj.getOrDefault(room, new TreeSet<>()));
    }
    return seen;
  }

  private static int stageOf(String name) {
    return name.length() > 1 ? Character.digit(name.charAt(1), 10) : 0;
  }

  private static Map<String, Integer> distanceMap(Map<String, Set<String>> adj) {
    Map<String, Integer> dist = new HashMap<>();
    List<String> queue = new ArrayList<>();
    dist.put(START, 0);
    queue.add(START);
    for (int i = 0; i < queue.size(); i++) {
      String room = queue.get(i);
      for (String next : adj.getOrDefault(room, new TreeSet<>())) {
        if (!dist.containsKey(next)) {
          dist.put(next, dist.get(room) + 1);
          queue.add(next);
        }
      }
    }
    return dist;
  }

  private static int distance(Map<String, Set<String>> adj, String to) {
    return distanceMap(adj).getOrDefault(to, Integer.MAX_VALUE / 2);
  }

  // ---- the seed ---------------------------------------------------------------------------------------------

  private static File logFile(File root) {
    return new File(root, "randomizer/doors.txt");
  }

  private static void writeRefusal(File root, long seed, boolean allowCross, int rooms, int doors, int orphan,
                                   int reachable, String why) {
    StringBuilder log = new StringBuilder();
    log.append("RE4 offline randomizer - door and key shuffle\n");
    log.append("seed ").append(seed).append("   cross-stage ").append(allowCross ? "allowed" : "same stage only")
        .append('\n');
    log.append("verdict: REJECTED - nothing was written\n");
    log.append(rooms).append(" rooms, ").append(doors).append(" doors, ").append(orphan)
        .append(" doors whose target is not a collected room, ").append(reachable).append(" rooms reachable from ")
        .append(START).append('\n');
    log.append(why).append('\n');
    try {
      writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the log could not be written: " + e);
    }
  }

  /** Deals the doors with a seed, moves the keys, writes the rooms, and logs every change. */
  static String shuffleAll(File root, long seed, boolean allowCross) {
    Map<String, Room> rooms = scan(root);
    if (rooms.isEmpty()) return "No collected rooms yet: collect the rooms first";
    link(rooms);
    int doors = 0, orphan = 0;
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        doors++;
        if (!rooms.containsKey(door.dest.name())) orphan++;
      }
    }
    Set<String> universe = reach(graph(rooms), rooms, null);
    if (universe.isEmpty()) return "The rooms do not link up: no door leaves " + START;
    if (universe.size() < MIN_UNIVERSE || orphan > doors / 8) {
      String why = "Door shuffle refused: only " + universe.size() + " rooms are reachable from " + START
          + " of " + rooms.size() + " collected (" + doors + " doors, " + orphan
          + " pointing outside the collection); nothing was written. Press \"Restore original rooms\" and "
          + "\"Collect rooms for the randomizer\" again, then retry.";
      Log.i(TAG, why);
      writeRefusal(root, seed, allowCross, rooms.size(), doors, orphan, universe.size(), why);
      return why;
    }

    List<Door[]> pairs = new ArrayList<>();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        if (door.pair == null || door.pair.room == door.room) continue;
        if (door.room.name.compareTo(door.pair.room.name) < 0) continue;
        pairs.add(new Door[]{door, door.pair});
      }
    }
    if (pairs.size() < 2) return "Nothing to deal: only " + pairs.size() + " two-way doors in the collected rooms";

    Random rng = new Random(seed);
    Map<String, Integer> dist = distanceMap(graph(rooms));
    int rounds = pairs.size() * 40;
    int swaps = 0, rejected = 0, cross = 0;
    for (int n = 0; n < rounds; n++) {
      Door[] one = pairs.get(rng.nextInt(pairs.size()));
      Door[] two = pairs.get(rng.nextInt(pairs.size()));
      if (one == two) continue;
      Door a = one[0], b = one[1], c = two[0], d = two[1];
      if (a.room == c.room || b.room == d.room) continue;
      if (a.room == d.room || c.room == b.room) continue;      // never a door into its own room
      Dest da = a.dest, dc = c.dest, db = b.dest, dd = d.dest;
      int sa = stageOf(a.room.name), sd = stageOf(d.room.name);
      int sc = stageOf(c.room.name), sb = stageOf(b.room.name);
      if (!allowCross) {
        if (sa != sd || sc != sb) {
          rejected++;
          continue;
        }
      } else {
        // a village door straight into the island is what put the first test next to the last boss: only
        // neighbouring stages may meet, and never in the opening rooms of the game
        if (Math.abs(sa - sd) > 1 || Math.abs(sc - sb) > 1) {
          rejected++;
          continue;
        }
        if (dist.getOrDefault(a.room.name, 99) < 3 || dist.getOrDefault(d.room.name, 99) < 3) {
          rejected++;
          continue;
        }
      }
      a.dest = dc;
      c.dest = da;
      b.dest = dd;
      d.dest = db;
      boolean ok = a.dest.name().equals(d.room.name) && c.dest.name().equals(b.room.name);
      if (!ok || !universe.containsAll(reach(graph(rooms), rooms, null))) {
        a.dest = da;
        c.dest = dc;
        b.dest = db;
        d.dest = dd;
        rejected++;
        continue;
      }
      swaps++;
      if (sa != sd) cross++;
    }

    Map<Integer, String> placement = placeKeys(rooms, rng);
    Map<String, Set<String>> adj = graph(rooms);
    Set<Integer> held = new TreeSet<>();
    Set<String> got = reach(adj, rooms, held);
    for (int n = 0; n < 20; n++) {
      boolean more = false;
      for (Map.Entry<Integer, String> e : placement.entrySet()) {
        if (got.contains(e.getValue()) && !held.contains(e.getKey())) {
          held.add(e.getKey());
          more = true;
        }
      }
      if (!more) break;
      got = reach(adj, rooms, held);
    }
    Set<String> finalRooms = reach(adj, rooms, held);
    boolean complete = finalRooms.containsAll(universe);

    StringBuilder log = new StringBuilder();
    log.append("RE4 offline randomizer - door and key shuffle\n");
    log.append("verdict: ").append(complete ? "PLAYABLE" : "REJECTED - nothing was written").append('\n');
    log.append("seed ").append(seed).append("   cross-stage ").append(allowCross ? "allowed" : "same stage only").append('\n');
    log.append(rooms.size()).append(" rooms, ").append(pairs.size()).append(" two-way doors, ")
        .append(oneWay(rooms).size()).append(" one-way links, ").append(swaps).append(" door swaps accepted, ")
        .append(rejected).append(" rejected, ").append(cross).append(" across stages\n");
    log.append("every room the vanilla doors reach is reachable: ").append(complete ? "yes" : "NO - TELL THE DEVELOPER")
        .append(" (").append(finalRooms.size()).append('/').append(universe.size()).append(" rooms)\n");
    log.append('\n').append("room    area  leads to        lock\n");
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        String lock = door.keyId != 0 ? ("key_id " + door.keyId + " flg " + door.keyFlg) : "-";
        log.append(String.format(Locale.US, "%-7s %5d  %-13s %s%n", room.name, door.area, door.dest.name(), lock));
      }
    }
    log.append('\n').append("progression items: where they are now\n");
    for (Map.Entry<Integer, String> e : placement.entrySet()) {
      log.append(String.format(Locale.US, "  0x%02x in %s%n", e.getKey(), e.getValue()));
    }

    if (!complete) {
      try {
        writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        Log.i(TAG, "the log could not be written: " + e);
      }
      String why = "Seed " + seed + " rejected: only " + finalRooms.size() + " of " + universe.size()
          + " rooms stay reachable; nothing was written. The walk is in randomizer/doors.txt - try another seed.";
      Log.i(TAG, why);
      return why;
    }

    int changed = 0;
    try {
      for (Room room : rooms.values()) {
        boolean doorChanged = false;
        for (Door door : room.doors) if (!same(door.dest, door.orig)) doorChanged = true;
        if (!doorChanged && room.writes.isEmpty()) continue;
        File orig = new File(room.file.getPath() + ".orig");
        if (!orig.isFile()) {
          try {
            writeAll(orig, readAll(room.file));       // remember the map this run started from
          } catch (IOException e) {
            Log.i(TAG, room.name + ": no snapshot: " + e);
          }
        }
        byte[] b = readAll(room.file);               // one room at a time: a room can be several megabytes
        for (Door door : room.doors) if (!same(door.dest, door.orig)) door.dest.into(b, door.at);
        for (Map.Entry<Integer, int[]> w : room.writes.entrySet()) {
          put16(b, w.getKey() + ITEM_ID, w.getValue()[0]);
          put16(b, w.getKey() + ITEM_NUM, w.getValue()[1]);
          b[w.getKey() + ITEM_EFF] = 0;
        }
        writeAll(room.file, b);
        b = null;
        changed++;
      }
      writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException | OutOfMemoryError e) {
      Log.i(TAG, "the rooms could not be written: " + e);
      return "The shuffle could not be written: " + e;
    }
    String summary = "Doors: " + swaps + " swaps in " + pairs.size() + " pairs (" + cross + " across stages), "
        + changed + " rooms written; every room reachable: " + (complete ? "yes" : "NO")
        + "; the log is randomizer/doors.txt";
    Log.i(TAG, summary);
    return summary;
  }

  /**
   * Moves the progression items the rooms gate on. A key may only go to a room that (a) already carries its
   * model, so it can be seen, and (b) comes before the room whose gate needs it. The writes are recorded and
   * applied with the patching pass, so no room bytes are held here.
   */
  private static Map<Integer, String> placeKeys(Map<String, Room> rooms, Random rng) {
    Map<Integer, String> placement = new LinkedHashMap<>();
    Map<String, Set<String>> adj = graph(rooms);
    Set<Integer> keys = new TreeSet<>();
    for (Room room : rooms.values()) keys.addAll(room.gates);
    for (Integer key : keys) {
      int gateDist = Integer.MAX_VALUE;
      for (Room room : rooms.values()) if (room.gates.contains(key)) gateDist = Math.min(gateDist, distance(adj, room.name));
      List<Room> candidates = new ArrayList<>();
      for (Room room : rooms.values()) {
        if (!room.models.contains(key)) continue;                 // the room could not show it
        if (room.gates.contains(key)) continue;                   // never behind its own gate
        if (distance(adj, room.name) >= gateDist) continue;       // must come before it
        if (freeArea(room) >= 0) candidates.add(room);
      }
      if (candidates.isEmpty()) {
        placement.put(key, whereIs(rooms, key));
        continue;
      }
      Room to = candidates.get(rng.nextInt(candidates.size()));
      String was = whereIs(rooms, key);
      Room old = rooms.get(was);
      if (old != null) {
        for (ItemArea item : old.items) {
          if (item.id == key) {
            old.writes.put(item.at, new int[]{0, 0});
            old.dirty = true;
          }
        }
      }
      int at = freeArea(to);
      if (at < 0) {
        placement.put(key, was);
        continue;
      }
      to.writes.put(at, new int[]{key, 1});
      to.dirty = true;
      placement.put(key, to.name);
    }
    return placement;
  }

  private static int freeArea(Room room) {
    for (ItemArea item : room.items) if (item.free()) return item.at;
    return -1;
  }

  private static String whereIs(Map<String, Room> rooms, int id) {
    for (Room room : rooms.values()) {
      for (ItemArea item : room.items) if (item.id == id) return room.name;
    }
    return "nowhere";
  }

  /** The last door log, or a line saying there is none yet. */
  static String readLog(File root) {
    File file = logFile(root);
    if (!file.isFile()) return "No door shuffle log yet: shuffle the doors once.";
    try {
      return new String(readAll(file), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      return "The log could not be read: " + e;
    }
  }
}
