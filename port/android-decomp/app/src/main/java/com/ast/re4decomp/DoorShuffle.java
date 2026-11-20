package com.ast.re4decomp;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 *
 * Stages are one pool: with crossStage on, a village door may lead into the castle or the island and back
 * again (the PC randomizer deals its doors the same way). Two things never move: a door whose payload is a
 * chapter's spawn (a script calls SceSetChapterEnd(chapter, door_area) and the chapter end reads that very
 * record afterwards)
 * and the opening, whose rooms may not be linked straight to the late ones.
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
  /**
   * Chapter endings that hand over to a door area of their own room, as "room/area".
   *
   * The scripts name them directly (SceSetChapterEnd(CHAPTER_4_2, 0xC) in src/st2/r21d.cpp, and so on), so the
   * next chapter spawns from that record's payload: shuffling it moves where the chapter puts the player, which
   * is how a seed ends up somewhere it has no business being yet. Their pair is pinned as a whole, because a
   * swap exchanges both ends of a link.
   *
   * The other ten chapters pass -1 and never fire a door, so nothing of theirs can move.
   */
  private static final String[] CHAPTER_DOORS = {
      "r106/3", "r10b/6", "r200/9", "r21d/12", "r225/1", "r22a/1", "r22b/0", "r31c/0"};
  /**
   * Rooms the pool keeps out: the shared shooting-range gallery and the five merchant rooms that unlock its
   * games.
   *
   * r22c (st2) is the one room every target-practice entrance uses; the minigame hands the player back to the
   * room it came from, so a moved door here returns the player to a stranger's room (seen on the phone: a farm
   * door in r103 led into r22c). r207, r211, r223, r303 and r31d are the merchant rooms that open games A-E,
   * which is why the PC randomizer files them as reserved rooms instead of pool rooms.
   */
  private static final String[] PIN_ROOMS = {"r22c", "r207", "r211", "r223", "r303", "r31d"};
  /** Vanilla door-graph distance: the first rooms of the game (0..2 hops from the start) and the closing ones. */
  private static final int OPENING_DIST = 2;
  private static final int LATE_DIST = 21;

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
        byte[] bytes;
        try {
          bytes = readAll(file);
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
    applySnapshot(root, rooms);
    return rooms;
  }

  /**
   * The vanilla record of everything this feature changes: about 30 kB instead of a copy of every room.
   *
   *   d <room> <record offset> <stage> <room> <part> <position+angle as hex>
   *   i <room> <record offset> <item id>
   *
   * Without it a second shuffle would shuffle the already shuffled map, which is how the reachable set
   * collapsed on the phone. It is written once, from the state the first accepted seed started from.
   */
  private static File snapshotFile(File root) {
    return new File(root, "randomizer/doors.orig.txt");
  }

  /** The marker patch 0240 looks for: written only when a seed is accepted, deleted by a refusal and by
   *  "Restore original rooms". While it exists the player may pass a door without Ashley. */
  private static File markerFile(File root) {
    return new File(root, "randomizer/doors.active");
  }

  private static void applySnapshot(File root, Map<String, Room> rooms) {
    File file = snapshotFile(root);
    if (!file.isFile()) return;
    try {
      for (String line : new String(readAll(file), StandardCharsets.UTF_8).split("\n")) {
        String[] parts = line.trim().split(" ");
        if (parts.length < 4) continue;
        Room room = rooms.get(parts[1]);
        if (room == null) continue;
        int at = Integer.parseInt(parts[2]);
        if ("d".equals(parts[0]) && parts.length >= 7) {
          for (Door door : room.doors) {
            if (door.at != at) continue;
            Dest d = new Dest();
            d.stage = Integer.parseInt(parts[3]);
            d.room = Integer.parseInt(parts[4]);
            d.part = Integer.parseInt(parts[5]);
            byte[] pos = hex(parts[6]);
            if (pos != null && pos.length == DOOR_POS_LEN) System.arraycopy(pos, 0, d.pos, 0, DOOR_POS_LEN);
            door.dest = d;
            door.orig = d;
          }
        } else if ("i".equals(parts[0]) && parts.length >= 4) {
          int id = Integer.parseInt(parts[3]);
          for (ItemArea item : room.items) if (item.at == at) item.id = id;
        }
      }
    } catch (IOException | RuntimeException e) {
      Log.i(TAG, "the vanilla record could not be read: " + e);
    }
  }

  private static void writeSnapshot(File root, Map<String, Room> rooms) {
    StringBuilder text = new StringBuilder();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        text.append("d ").append(room.name).append(' ').append(door.at).append(' ')
            .append(door.orig.stage).append(' ').append(door.orig.room).append(' ').append(door.orig.part).append(' ')
            .append(hex(door.orig.pos)).append('\n');
      }
    }
    try {
      writeAll(snapshotFile(root), text.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the vanilla record could not be written: " + e);
    }
  }

  private static byte[] hex(String text) {
    if (text.length() % 2 != 0) return null;
    byte[] out = new byte[text.length() / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = Character.digit(text.charAt(i * 2), 16);
      int lo = Character.digit(text.charAt(i * 2 + 1), 16);
      if (hi < 0 || lo < 0) return null;
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) text.append(String.format(Locale.US, "%02x", b & 0xFF));
    return text.toString();
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

  private static boolean chapterDoor(String room, int area) {
    String key = room + "/" + area;
    for (String pinned : CHAPTER_DOORS) {
      if (pinned.equals(key)) return true;
    }
    return false;
  }

  private static boolean roomPinned(String room) {
    for (String pinned : PIN_ROOMS) {
      if (pinned.equals(room)) return true;
    }
    return false;
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
    File marker = markerFile(root);
    if (marker.isFile() && !marker.delete()) Log.i(TAG, "the door marker could not be removed");
    StringBuilder log = new StringBuilder();
    log.append("RE4 offline randomizer - door and key shuffle\n");
    log.append("seed ").append(seed).append("   cross-stage ").append(allowCross ? "mixed, any stage" : "same stage only")
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

    int chapterPinned = 0;
    for (Room room : rooms.values()) {
      for (Door door : room.doors) if (chapterDoor(room.name, door.area)) chapterPinned++;
    }
    List<Door[]> pairs = new ArrayList<>();
    int roomPinned = 0;
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        if (door.pair == null || door.pair.room == door.room) continue;
        if (door.room.name.compareTo(door.pair.room.name) < 0) continue;
        // a chapter's spawn never moves, and neither does the link it belongs to
        if (chapterDoor(door.room.name, door.area) || chapterDoor(door.pair.room.name, door.pair.area)) continue;
        // the shooting range and its merchant entrances keep the doors they have
        if (roomPinned(door.room.name) || roomPinned(door.pair.room.name)) {
          roomPinned++;
          continue;
        }
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
        // Every stage may meet every stage: one pool for the whole game, as the PC randomizer deals it. The only
        // rule left protects the pacing at the two ends of the story: a door of the opening rooms may not be
        // wired straight into the closing ones. Everything in between is free, so the village doors shuffle too
        // (the old >= 3 rule froze the first three rooms and that is what left the village alone).
        int distA = dist.getOrDefault(a.room.name, 99), distB = dist.getOrDefault(b.room.name, 99);
        int distC = dist.getOrDefault(c.room.name, 99), distD = dist.getOrDefault(d.room.name, 99);
        // after the swap the links are a.room<->d.room and c.room<->b.room
        if ((distA <= OPENING_DIST && distD >= LATE_DIST) || (distC <= OPENING_DIST && distB >= LATE_DIST)) {
          rejected++;
          continue;
        }
      }
      a.dest = dc;
      c.dest = da;
      b.dest = dd;
      d.dest = db;
      boolean ok = a.dest.name().equals(d.room.name) && c.dest.name().equals(b.room.name);
      if (!ok || !reach(graph(rooms), rooms, null).containsAll(universe)) {
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

    List<String> head = new ArrayList<>();
    head.add(chapterPinned + " chapter doors kept in place (their payload is the next chapter's spawn)");
    head.add(roomPinned + " links kept for the shooting range and its merchant rooms: "
        + String.join(", ", PIN_ROOMS));
    head.add(ASHLEY_NOTE);
    head.add(stageLine(rooms));
    head.add(rooms.size() + " rooms, " + pairs.size() + " two-way doors, " + oneWay(rooms).size()
        + " one-way links, " + swaps + " door swaps accepted, " + rejected + " rejected, "
        + cross + " across stages");
    return settle(root, seed, allowCross, "RE4 offline randomizer - door and key shuffle", rooms, universe,
        head, rng, "Doors: " + swaps + " swaps in " + pairs.size() + " pairs (" + cross + " across stages), ");
  }

  /** The keys as dealt, and the ones the walk can actually hold. */
  private static final class KeyPlan {
    final Map<Integer, String> placement = new LinkedHashMap<>();
    final Set<Integer> held = new TreeSet<>();
  }

  /**
   * Places the progression items the rooms gate on, in the order the gate-aware walk can reach them.
   *
   * A room whose use area names an item you do not carry cannot be left, so a key must sit in a room that
   * is reachable *before* the gate that needs it. The disc's own room is kept whenever the walk reaches it
   * (usually it is), which is both the safest and the least writing; only a key whose room sits behind its
   * own gate moves to another room that carries its model.
   */
  private static KeyPlan placeKeys(Map<String, Room> rooms, Random rng) {
    KeyPlan plan = new KeyPlan();
    Map<String, Set<String>> adj = graph(rooms);
    Set<Integer> keys = new TreeSet<>();
    for (Room room : rooms.values()) keys.addAll(room.gates);
    for (int round = 0; round < 64; round++) {
      Set<String> reachable = reach(adj, rooms, plan.held);
      boolean progress = false;
      for (Integer key : keys) {
        if (plan.held.contains(key)) continue;
        boolean needed = false;
        for (String name : reachable) if (rooms.get(name).gates.contains(key)) needed = true;
        if (!needed) continue;                       // no gate for it has been reached yet
        String vanilla = whereIs(rooms, key);
        if (reachable.contains(vanilla)) {
          plan.placement.put(key, vanilla);          // the disc's own spot is reachable: leave it alone
          plan.held.add(key);
          progress = true;
          continue;
        }
        List<Room> candidates = new ArrayList<>();
        for (String name : reachable) {
          Room room = rooms.get(name);
          if (room.gates.contains(key)) continue;    // never behind the gate that needs it
          if (room.models.contains(key) && freeArea(room) >= 0) candidates.add(room);
        }
        if (candidates.isEmpty()) continue;          // maybe reachable next round, when more is open
        Room to = candidates.get(rng.nextInt(candidates.size()));
        Room old = rooms.get(vanilla);
        if (old != null) {
          for (ItemArea item : old.items) {
            if (item.id == key) {
              old.writes.put(item.at, new int[]{0, 0});
              old.dirty = true;
            }
          }
        }
        to.writes.put(freeArea(to), new int[]{key, 1});
        to.dirty = true;
        plan.placement.put(key, to.name);
        plan.held.add(key);
        progress = true;
      }
      if (!progress) break;
    }
    for (Integer key : keys) if (!plan.placement.containsKey(key)) plan.placement.put(key, whereIs(rooms, key));
    return plan;
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

  /** The same line appears in both logs: patch 0240 makes a shuffled map enterable without Ashley. */
  private static final String ASHLEY_NOTE =
      "Ashley's door check is off while randomizer/doors.active exists (port patch 0240): a shuffled map has no back room";
  /** Rooms on the main path the spine aims for, the smallest spine accepted, and how often a seed retries. */
  private static final int SPINE_TARGET = 24;
  private static final int SPINE_MIN = 14;
  private static final int SPINE_TRIES = 20;

  // ---- the spine (branch feature/offline-randomizer) --------------------------------------------------------

  /**
   * The PC randomizer's shape instead of pair swaps: pick an ordered main path first, hang branches on it, and
   * only then write the doors.
   *
   * Why: a swap keeps the map legal but says nothing about the order the player walks it in, so a chapter door
   * or a key can guard a room nobody is ready for. The spine decides the order first and writes the destinations
   * afterwards. Every link is proved against the whole map before it is kept: a link that would strand a room
   * is refused and another door combination is tried.
   */

  /** One candidate room of the spine with its score. */
  private static final class Pick {
    final String name;
    final double score;

    Pick(String name, double score) {
      this.name = name;
      this.score = score;
    }
  }

  /** What one spine attempt produced, for the log. */
  private static final class SpineBuild {
    final List<String> spine = new ArrayList<>();
    final Set<String> used = new TreeSet<>();
    int links;
    int branches;
  }

  /** The room classes the plan names, read from the disc instead of guessed. */
  private static String spineClass(Room room, Set<String> universe) {
    if (!universe.contains(room.name) || room.doors.isEmpty()) return "scripted";
    if (roomPinned(room.name) || chapterRoom(room.name) || "r120".equals(room.name)) return "reserved";
    boolean oneWay = false, locked = false;
    for (Door door : room.doors) {
      if (door.pair == null) oneWay = true;
      if (door.keyId != 0) locked = true;
    }
    if (oneWay) return "oneway";
    if (!room.gates.isEmpty()) return "gated";
    if (locked) return "locked";
    if (room.doors.size() == 1) return "single";
    return "regular";
  }

  /** True for the rooms that host a boundary chapter's ending. */
  private static boolean chapterRoom(String name) {
    for (String pinned : CHAPTER_DOORS) {
      if (pinned.startsWith(name + "/")) return true;
    }
    return false;
  }

  /** Doors the spine may re-point: reciprocal, known target, no chapter ending, no lock. */
  private static List<Door> spineDoors(Room room, Map<String, Room> rooms) {
    List<Door> out = new ArrayList<>();
    for (Door door : room.doors) {
      if (door.pair == null || door.pair.room == room) continue;
      if (!rooms.containsKey(door.dest.name())) continue;
      if (chapterDoor(room.name, door.area)) continue;
      if (door.keyId != 0) continue;
      out.add(door);
    }
    return out;
  }

  /** Every gate of a room must be placeable in a room the spine already visits before it. */
  private static boolean spineKeyHost(Room room, List<String> spine, Map<String, Room> rooms) {
    for (Integer gate : room.gates) {
      boolean host = false;
      for (String earlier : spine) {
        Room r = rooms.get(earlier);
        if (r == null) continue;
        for (ItemArea item : r.items) if (item.free()) host = true;
      }
      if (!host) return false;
    }
    return true;
  }

  /**
   * The arrival payload for a room: the vanilla payload of the partner of the door the player walks back
   * through. It is a point in front of that door in the room, with that room's part number.
   */
  private static Dest spineArrival(Door returnDoor) {
    Dest src = returnDoor.pair.orig;
    Dest d = new Dest();
    System.arraycopy(src.pos, 0, d.pos, 0, DOOR_POS_LEN);
    d.part = src.part;
    d.stage = stageOf(returnDoor.room.name);
    d.room = Integer.parseInt(returnDoor.room.name.substring(2), 16);
    return d;
  }

  /**
   * Write one link both ways and prove it: the whole vanilla-reachable set must still be reachable.
   * Every door combination at both ends is tried before the link is given up.
   */
  private static Door[] spineLink(String aName, String bName, Map<String, List<Door>> free,
                                  Map<String, Room> rooms, Set<String> universe) {
    List<Door> fa = free.get(aName);
    List<Door> fb = free.get(bName);
    if (fa == null || fb == null) return null;
    for (Door da : new ArrayList<>(fa)) {
      if (da.dest.name().equals(bName)) continue;
      for (Door db : new ArrayList<>(fb)) {
        if (db.dest.name().equals(aName)) continue;
        Dest oa = da.dest, ob = db.dest;
        da.dest = spineArrival(db);          // the player appears in b, in front of b's return door
        db.dest = spineArrival(da);          // and back in a, in front of a's door
        boolean ok = reach(graph(rooms), rooms, null).containsAll(universe);
        if (ok) {
          fa.remove(da);
          fb.remove(db);
          return new Door[]{da, db};
        }
        da.dest = oa;
        db.dest = ob;
      }
    }
    return null;
  }

  /** One attempt: main path first, then every room still free is hung somewhere on it. */
  private static SpineBuild spineAttempt(Map<String, Room> rooms, Set<String> universe, Random rng) {
    SpineBuild out = new SpineBuild();
    Map<String, List<Door>> free = new TreeMap<>();
    Map<String, String> cls = new TreeMap<>();
    for (Room room : rooms.values()) {
      cls.put(room.name, spineClass(room, universe));
      free.put(room.name, spineDoors(room, rooms));
    }
    out.spine.add(START);
    out.used.add(START);
    if (free.get(START).isEmpty()) return null;
    // r100 has exactly one door and the next room hangs on it: start on that vanilla link and continue from
    // r101, whose four doors are what makes a spine possible at all.
    String first = rooms.get(START).doors.get(0).dest.name();
    if (rooms.containsKey(first) && free.get(first).size() >= 3) {
      out.spine.add(first);
      out.used.add(first);
      // The opening link is vanilla and has to survive: if a branch took r100's only door later, the whole
      // first chapter lost its way in (seen on the phone: 5 of 97 rooms left). These two doors are reserved.
      List<Door> keep = new ArrayList<>();
      for (Door door : free.get(START)) if (!door.dest.name().equals(first)) keep.add(door);
      free.put(START, keep);
      List<Door> keepFirst = new ArrayList<>();
      for (Door door : free.get(first)) {
        if (!door.dest.name().equals(START) && !door.dest.name().equals(first)) keepFirst.add(door);
      }
      free.put(first, keepFirst);
    }
    while (out.spine.size() < SPINE_TARGET) {
      String tip = out.spine.get(out.spine.size() - 1);
      List<Pick> picks = new ArrayList<>();
      for (String name : universe) {
        if (out.used.contains(name)) continue;
        String c = cls.get(name);
        if ("reserved".equals(c) || "scripted".equals(c)) continue;
        if (free.get(name).size() < 2) continue;
        if ("gated".equals(c) && !spineKeyHost(rooms.get(name), out.spine, rooms)) continue;
        double score = rng.nextDouble();
        if (stageOf(name) != stageOf(tip)) score += 0.30;     // cross-stage steps are wanted, not mandatory
        if (free.get(name).size() >= 3) score += 0.25;        // room for a branch
        if ("gated".equals(c)) score -= 0.15;
        picks.add(new Pick(name, score));
      }
      Collections.sort(picks, (p, q) -> Double.compare(q.score, p.score));
      boolean linked = false;
      for (Pick pick : picks) {
        Door[] link = spineLink(tip, pick.name, free, rooms, universe);
        if (link == null) continue;
        out.links++;
        out.spine.add(pick.name);
        out.used.add(pick.name);
        linked = true;
        break;
      }
      if (!linked) break;
    }
    // branches: a room whose only door is the way in stays a tip; the rest is a path hung anywhere on the spine
    List<String> rest = new ArrayList<>();
    for (String name : universe) {
      if (out.used.contains(name)) continue;
      String c = cls.get(name);
      if ("reserved".equals(c) || "scripted".equals(c)) continue;
      rest.add(name);
    }
    Collections.shuffle(rest, rng);
    for (String room : rest) {
      if (free.get(room).isEmpty()) continue;
      List<String> parents = new ArrayList<>(out.used);
      Collections.shuffle(parents, rng);
      int tried = 0;
      for (String parent : parents) {
        if (free.get(parent).isEmpty()) continue;
        if (tried++ >= 6) break;
        if (spineLink(parent, room, free, rooms, universe) == null) continue;
        out.links++;
        out.branches++;
        out.used.add(room);
        break;
      }
    }
    return out.spine.size() >= SPINE_MIN ? out : null;
  }

  /** "door records that change stage: st1->st2=12 ..." for the log. */
  private static String stageLine(Map<String, Room> rooms) {
    Map<String, Integer> stagePairs = new TreeMap<>();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        int from = stageOf(room.name), to = stageOf(door.dest.name());
        if (from == to) continue;
        String edge = "st" + Math.min(from, to) + "->st" + Math.max(from, to);
        stagePairs.put(edge, stagePairs.getOrDefault(edge, 0) + 1);
      }
    }
    if (stagePairs.isEmpty()) return "door records that change stage: none";
    StringBuilder line = new StringBuilder("door records that change stage:");
    for (Map.Entry<String, Integer> e : stagePairs.entrySet()) {
      line.append(' ').append(e.getKey()).append('=').append(e.getValue());
    }
    return line.toString();
  }

  /** The last stage both shuffles share: place the keys, prove the map, write the log, then the rooms. */
  private static String settle(File root, long seed, boolean allowCross, String title, Map<String, Room> rooms,
                               Set<String> universe, List<String> head, Random rng, String summaryLead) {
    KeyPlan plan = placeKeys(rooms, rng);
    Map<Integer, String> placement = plan.placement;
    Map<String, Set<String>> adj = graph(rooms);
    // The door map itself must keep every room reachable; that is the hard rule. A gate whose item could not
    // be put in front of it (its model only exists in rooms behind the gate) is named in the log instead of
    // rejecting every seed: the PC randomizer reports the same class of problem and leaves it to play testing.
    Set<String> doorReach = reach(adj, rooms, null);
    boolean structure = doorReach.containsAll(universe);
    Set<Integer> unproven = new TreeSet<>();
    for (Integer key : plan.placement.keySet()) {
      if (plan.held.contains(key)) continue;
      for (Room room : rooms.values()) {
        if (room.gates.contains(key) && universe.contains(room.name)) unproven.add(key);
      }
    }
    boolean complete = structure && unproven.isEmpty();
    boolean write = structure;

    StringBuilder log = new StringBuilder();
    log.append(title).append('\n');
    log.append("verdict: ").append(complete ? "PLAYABLE"
        : structure ? "PLAYABLE - " + unproven.size() + " gate(s) unproven, see below"
        : "REJECTED - nothing was written").append('\n');
    log.append("seed ").append(seed).append("   cross-stage ")
        .append(allowCross ? "mixed, any stage" : "same stage only").append('\n');
    for (String line : head) log.append(line).append('\n');
    log.append("every room the vanilla doors reach is reachable: ")
        .append(structure ? "yes" : "NO - TELL THE DEVELOPER")
        .append(" (").append(doorReach.size()).append('/').append(universe.size()).append(" rooms)\n");
    if (!unproven.isEmpty()) {
      log.append("gates whose item could not be put in front of them (test these first):\n");
      for (Integer key : unproven) {
        Set<String> gates = new TreeSet<>();
        for (Room room : rooms.values()) if (room.gates.contains(key)) gates.add(room.name);
        log.append(String.format(Locale.US, "  0x%02x needed in %s, sits in %s%n", key, gates,
            placement.getOrDefault(key, "nowhere")));
      }
    }
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
    // the debug view: only the doors this seed actually moved, so a bad jump can be read against the vanilla map
    log.append('\n').append("doors this seed moved (vanilla -> now)\n");
    int moved = 0;
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        if (same(door.dest, door.orig)) continue;
        log.append(String.format(Locale.US, "  %-6s area %2d: %-8s -> %s%n", room.name, door.area,
            door.orig.name(), door.dest.name()));
        moved++;
      }
    }
    log.append("  ").append(moved).append(" door records written\n");

    if (!write) {
      try {
        writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        Log.i(TAG, "the log could not be written: " + e);
      }
      String why = "Seed " + seed + " rejected: only " + doorReach.size() + " of " + universe.size()
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
      writeAll(markerFile(root), Long.toString(seed).getBytes(StandardCharsets.UTF_8));   // patch 0240 reads this
      if (!snapshotFile(root).isFile()) writeSnapshot(root, rooms);   // the map this run started from
    } catch (IOException | OutOfMemoryError e) {
      Log.i(TAG, "the rooms could not be written: " + e);
      return "The shuffle could not be written: " + e;
    }
    String summary = summaryLead + changed + " rooms written; every room reachable: "
        + (structure ? "yes" : "NO")
        + (unproven.isEmpty() ? "" : ", " + unproven.size() + " gate(s) unproven")
        + "; the log is randomizer/doors.txt";
    Log.i(TAG, summary);
    return summary;
  }

  /** The PC-style shuffle: an ordered main path, branches hung on it, then the doors are written. */
  static String shuffleWithSpine(File root, long seed, boolean allowCross) {
    Map<String, Room> rooms = scan(root);
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
      String why = "Spine shuffle refused: only " + universe.size() + " rooms are reachable from " + START
          + " of " + rooms.size() + " collected (" + doors + " doors, " + orphan
          + " pointing outside the collection); nothing was written. Press \"Restore original rooms\" and "
          + "\"Collect rooms for the randomizer\" again, then retry.";
      Log.i(TAG, why);
      writeRefusal(root, seed, allowCross, rooms.size(), doors, orphan, universe.size(), why);
      return why;
    }
    // The vanilla destinations, so a failed attempt can put the map back exactly as it was.
    Map<Door, Dest> vanilla = new HashMap<>();
    for (Room room : rooms.values()) for (Door door : room.doors) vanilla.put(door, door.dest);

    Random rng = new Random(seed);
    SpineBuild build = null;
    for (int attempt = 0; attempt < SPINE_TRIES && build == null; attempt++) {
      for (Map.Entry<Door, Dest> e : vanilla.entrySet()) e.getKey().dest = e.getValue();
      SpineBuild candidate = spineAttempt(rooms, universe, rng);
      if (candidate == null) continue;
      // The whole map is checked once, after it is built: a link that passes on its own can still orphan a room
      // a later link depended on. A map that fails is thrown away and the next attempt starts from vanilla.
      if (!reach(graph(rooms), rooms, null).containsAll(universe)) continue;
      build = candidate;
    }
    if (build == null) {
      for (Map.Entry<Door, Dest> e : vanilla.entrySet()) e.getKey().dest = e.getValue();
      String why = "Seed " + seed + " refused: no main path of " + SPINE_MIN
          + " rooms keeps every room reachable; nothing was written. Try another seed.";
      StringBuilder log = new StringBuilder();
      log.append("RE4 offline randomizer - spine shuffle\n");
      log.append("verdict: REJECTED - nothing was written\n");
      log.append("seed ").append(seed).append('\n');
      log.append(why).append('\n');
      try {
        writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        Log.i(TAG, "the log could not be written: " + e);
      }
      Log.i(TAG, why);
      return why;
    }

    List<String> head = new ArrayList<>();
    head.add("spine (" + build.spine.size() + " rooms): " + String.join(" -> ", build.spine));
    head.add(build.links + " door links written, " + build.branches + " of them branches; "
        + build.used.size() + " of " + universe.size() + " reachable rooms are on the spine or a branch");
    head.add("chapter doors and the shooting range keep their payloads (pins are never a spine step)");
    head.add(ASHLEY_NOTE);
    head.add(stageLine(rooms));
    head.add(rooms.size() + " rooms, " + oneWay(rooms).size() + " one-way links");
    return settle(root, seed, allowCross, "RE4 offline randomizer - spine shuffle", rooms, universe,
        head, rng, "Spine: " + build.spine.size() + " rooms on the main path, ");
  }
}
