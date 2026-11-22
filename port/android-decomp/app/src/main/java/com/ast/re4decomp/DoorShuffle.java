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
  /**
   * Rooms whose script places a one-off prop and reads it straight afterwards, so the room it is entered
   * from decides whether the prop exists at all.
   *
   * r11b is the lake: R11bInit creates the boat and places it at one of two piers depending on
   * `room_id_prev == 0x10D` (the vanilla dock), and patch 0241 turns the missing boat into no boat instead of
   * a crash. A shuffled door into the lake from a room the script never expects loses the boat, and the
   * player cannot row back. Its doors stay exactly as the disc has them.
   *
   * r10f is the cable car and the castle gate: the gondola get-on/get-off areas (7, 8, 9) drive a scripted
   * ride whose state is room flag 0, and the door handlers are told which neighbour they belong to
   * (`r10f_DoorClose(0x11D)` / `0x11E`). Arriving through a shuffled door leaves the ride half-done - played
   * on the phone: the gondola had to be ridden twice before the Mendez-eye door to the castle answered.
   *
   * r208 is the water hall: its script asks `room_id_prev == 0xFFF` (entered from a chapter, not from a door)
   * before it runs the crank/moat/bridge sequence for Ashley, so a shuffled arrival drops the player at the
   * far end and skips it - played on the phone.
   *
   * r30a (the island lift) and r309 are deliberately NOT in this list any more (player's decision, 22.11.2026):
   * one-way and sequence-prone rooms are not to be solved by growing a list of vanilla rooms. That belongs to
   * the item+door deal - place what is needed before the place that needs it - so they keep their doors.
   */
  private static final String[] KEEP_VANILLA_ROOMS = {"r11b", "r10f", "r208"};
  /** Rooms the player ticked on the launcher's "rooms kept vanilla" screen; reloaded at every shuffle. */
  private static Set<String> userPinned = new TreeSet<>();
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

  /** One item area, only what the key placement and the deal need. */
  private static final class ItemArea {
    int at;
    int id;
    int num;
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
            item.num = be16(bytes, at + ITEM_NUM);
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

  /** A use area names a real item: 0 and the "nothing" markers are not gates, they must never block a room. */
  private static boolean realGate(int id) {
    return id > 0 && id < 0x1000;
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
        for (Integer gate : r.gates) if (realGate(gate) && !held.contains(gate)) blocked = true;
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
    if (userPinned.contains(room)) return true;
    for (String pinned : PIN_ROOMS) {
      if (pinned.equals(room)) return true;
    }
    for (String pinned : KEEP_VANILLA_ROOMS) {
      if (pinned.equals(room)) return true;
    }
    return false;
  }

  /** The pinned rooms as one line for the log: the range, its merchants, and the lake boat. */
  private static String pinnedNames() {
    return String.join(", ", PIN_ROOMS) + ", " + String.join(", ", KEEP_VANILLA_ROOMS);
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
    userPinned = RoomPins.load(root, RoomPins.DOORS);
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
    head.add(roomPinned + " links kept for the shooting range, its merchant rooms and the lake boat: "
        + pinnedNames());
    if (!userPinned.isEmpty()) {
      head.add(userPinned.size() + " room(s) kept vanilla by you: " + String.join(", ", userPinned));
    }
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
          if (userPinned.contains(name)) continue;   // a room the player keeps vanilla takes no key
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
    userPinned = RoomPins.load(root, RoomPins.DOORS);
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
    head.add("chapter doors, the shooting range, its merchants and the lake boat keep their payloads");
    if (!userPinned.isEmpty()) {
      head.add(userPinned.size() + " room(s) kept vanilla by you: " + String.join(", ", userPinned));
    }
    head.add(ASHLEY_NOTE);
    head.add(stageLine(rooms));
    head.add(rooms.size() + " rooms, " + oneWay(rooms).size() + " one-way links");
    return settle(root, seed, allowCross, "RE4 offline randomizer - spine shuffle", rooms, universe,
        head, rng, "Spine: " + build.spine.size() + " rooms on the main path, ");
  }
  // ---- the combined deal: doors and items in one pass ---------------------------------------------------

  /** Every item a room's use area names: holding it is what lets you leave that room. */
  private static Set<Integer> gateIds(Map<String, Room> rooms) {
    Set<Integer> ids = new TreeSet<>();
    for (Room room : rooms.values()) {
      for (Integer gate : room.gates) if (realGate(gate)) ids.add(gate);
    }
    return ids;
  }

  /** The door graph as it points, with no reverse help: the only walk this deal trusts. */
  private static Map<String, Set<String>> directed(Map<String, Room> rooms) {
    Map<String, Set<String>> adj = new HashMap<>();
    for (Room room : rooms.values()) {
      Set<String> next = adj.computeIfAbsent(room.name, k -> new TreeSet<>());
      for (Door door : room.doors) {
        String target = door.dest.name();
        if (rooms.containsKey(target) && !target.equals(room.name)) next.add(target);
      }
    }
    return adj;
  }

  /** A door in `from` leading to `to`: a pair must be found by where a door points now, not by an old link. */
  private static Door leadsBack(Map<String, Room> rooms, String from, String to) {
    Room room = rooms.get(from);
    if (room == null) return null;
    for (Door door : room.doors) if (door.dest.name().equals(to)) return door;
    return null;
  }

  /** The rooms of the reachable set that cannot be left yet: their use area needs an item not held. */
  private static Set<String> cannotLeave(Map<String, Room> rooms, Map<String, Set<String>> adj,
      Set<Integer> held) {
    Set<String> out = new TreeSet<>();
    for (String name : reach(adj, rooms, held)) {
      Room room = rooms.get(name);
      for (Integer gate : room.gates) {
        if (realGate(gate) && !held.contains(gate)) {
          out.add(name);
          break;
        }
      }
    }
    return out;
  }

  /** An item of the world (not the "nothing" marker and not a scene marker). */
  private static boolean itemId(int id) {
    return id > 0 && id < 0x1000;
  }

  /** One item area the deal may write, and what it held before and after. */
  private static final class Piece {
    Room room;
    ItemArea area;
    int oldId;
    int oldNum;
    int nowId;
    int nowNum;

    boolean empty() {
      return nowId == 0 || nowId >= 0x1000;
    }

    /** A crate/enemy record or a flag-gated one: the item there may not be lying on the floor to pick up. */
    boolean hidden() {
      return area.waiting != 0 || (area.ctrl & 0x50) != 0;
    }
  }

  /**
   * Deals the doors and the whole item pool in one pass, then proves the result by walking it.
   *
   * Why not the spine: the spine wired doors one socket at a time, so repointing A's door to B left the door
   * that used to lead back to A pointing at a room A no longer leads to. Each of those leftovers is a one-way
   * link, and the reach guard "fixed" them by walking one-way links backwards - so a map the guard called
   * complete could in fact be walked forwards only 10 of 97 rooms. This deals whole door *pairs* instead
   * (A<->B and C<->D become A<->D and C<->B), so every link stays two-way, and the guard now walks the map
   * strictly forwards: a swap is kept only when the rooms reachable afterwards are exactly the rooms the disc
   * itself reaches forwards. Nothing opens that the disc keeps closed.
   *
   * Then the items: the whole pool is dealt over those rooms, and every gate item is pulled to a room the
   * same forward walk reaches before its gate. Model merging is on, which is what lets an item the disc never
   * put in a room appear there (its model is copied into that room's own pack, not a master pack).
   *
   * Finally the written rooms are read back and walked again: the verdict in randomizer/deal.txt comes from
   * that walk, not from the plan.
   */
  static String dealAll(File root, long seed, boolean allowCross) {
    userPinned = RoomPins.load(root, RoomPins.DOORS);
    Set<String> itemPinned = RoomPins.load(root, RoomPins.ITEMS);
    Map<String, Room> rooms = scan(root);
    if (rooms.isEmpty()) return refuse("No collected rooms yet: collect the rooms first");
    link(rooms);
    Set<Integer> allGates = gateIds(rooms);
    Map<String, Set<String>> adj = directed(rooms);
    Set<String> forward = reach(adj, rooms, allGates);      // every gate in hand: pure geometry, forwards only
    if (forward.size() < MIN_UNIVERSE) {
      String why = "The deal refused: only " + forward.size() + " rooms are reachable from " + START
          + " walking the doors as they point, so the collected rooms do not form the map; nothing was "
          + "written. Collect the rooms again and retry.";
      Log.i(TAG, why);
      return why;
    }
    // rooms the disc only reaches by crossing one of its own one-way links backwards: kept, never claimed
    Set<String> bridgeOnly = new TreeSet<>(reach(graph(rooms), rooms, allGates));
    bridgeOnly.removeAll(forward);
    Set<Integer> gates = new TreeSet<>();
    for (String name : forward) for (Integer gate : rooms.get(name).gates) if (realGate(gate)) gates.add(gate);

    // ---- 1. the doors: whole pairs, guarded by the forward walk ------------------------------------------
    Random rng = new Random(seed);
    Map<String, Integer> dist = distanceMap(adj);
    int swaps = 0, rejected = 0, cross = 0;
    int rounds = Math.max(2000, rooms.size() * 32);
    for (int n = 0; n < rounds; n++) {
      Door a = randomDoor(rooms, rng);
      Door c = randomDoor(rooms, rng);
      if (a == null || c == null || a == c || a.room == c.room) continue;
      String ar = a.room.name, br = a.dest.name(), cr = c.room.name, dr = c.dest.name();
      if (!rooms.containsKey(br) || !rooms.containsKey(dr) || br.equals(dr)) continue;
      if (chapterDoor(ar, a.area) || chapterDoor(cr, c.area)) continue;
      if (roomPinned(ar) || roomPinned(cr) || roomPinned(br) || roomPinned(dr)) continue;
      Door b = leadsBack(rooms, br, ar);
      Door d = leadsBack(rooms, dr, cr);
      if (b == null || d == null || b.room == d.room) continue;
      if (!allowCross) {
        if (stageOf(ar) != stageOf(dr) || stageOf(cr) != stageOf(br)) {
          rejected++;
          continue;
        }
      } else {
        int distA = dist.getOrDefault(ar, 99), distD = dist.getOrDefault(dr, 99);
        int distC = dist.getOrDefault(cr, 99), distB = dist.getOrDefault(br, 99);
        if ((distA <= OPENING_DIST && distD >= LATE_DIST) || (distC <= OPENING_DIST && distB >= LATE_DIST)) {
          rejected++;
          continue;
        }
      }
      Dest da = a.dest, db = b.dest, dc = c.dest, dd = d.dest;
      a.dest = dc;      // A -> D
      d.dest = db;      // D -> A
      b.dest = dd;      // B -> C
      c.dest = da;      // C -> B
      boolean ok = a.dest.name().equals(dr) && c.dest.name().equals(br);
      if (!ok || !reach(directed(rooms), rooms, allGates).equals(forward)) {
        a.dest = da;
        b.dest = db;
        c.dest = dc;
        d.dest = dd;
        rejected++;
        continue;
      }
      swaps++;
      if (stageOf(ar) != stageOf(dr)) cross++;
    }
    if (swaps == 0) {
      String why = "Seed " + seed + " refused: no door swap keeps the disc's own forward-walkable rooms"
          + " walkable; nothing was written. Try another seed.";
      Log.i(TAG, why);
      return why;
    }
    adj = directed(rooms);          // the doors moved: every later walk has to see the map as it now points

    // ---- 2. the items: the whole pool, every gate item in front of its gate ---------------------------
    Set<Integer> modelled = ItemShuffle.modelIds(root);
    List<Piece> pieces = new ArrayList<>();
    for (String name : forward) {
      if (itemPinned.contains(name) || userPinned.contains(name)) continue;
      Room room = rooms.get(name);
      for (ItemArea area : room.items) {
        if (area.id == 0xFFFF) continue;
        Piece piece = new Piece();
        piece.room = room;
        piece.area = area;
        piece.oldId = area.id;
        piece.oldNum = area.num;
        piece.nowId = area.id;
        piece.nowNum = area.num;
        if (itemId(area.id) && !modelled.contains(area.id)) continue;                    // no model to show it
        pieces.add(piece);
      }
    }
    List<Piece> filled = new ArrayList<>();
    for (Piece piece : pieces) if (itemId(piece.nowId)) filled.add(piece);
    if (filled.isEmpty()) return refuse("Nothing to deal: the reachable rooms hold no item the deal can move");
    for (int k = filled.size() * 8; k > 0; k--) {
      Piece one = filled.get(rng.nextInt(filled.size()));
      Piece two = filled.get(rng.nextInt(filled.size()));
      if (one == two) continue;
      int id = one.nowId, num = one.nowNum;
      one.nowId = two.nowId;
      one.nowNum = two.nowNum;
      two.nowId = id;
      two.nowNum = num;
    }
    Set<Integer> held = new TreeSet<>();
    Set<Integer> unmodelled = new TreeSet<>();
    Map<Integer, String> gateRoom = new LinkedHashMap<>();
    for (int round = 0; round < gates.size() + 6; round++) {
      Set<String> seen = reach(adj, rooms, held);
      Set<String> stuck = cannotLeave(rooms, adj, held);
      Set<Integer> wanted = new TreeSet<>();
      for (String name : seen) {
        for (Integer gate : rooms.get(name).gates) if (realGate(gate) && !held.contains(gate)) wanted.add(gate);
      }
      if (wanted.isEmpty()) break;
      boolean progress = false;
      for (Integer gate : wanted) {
        if (!modelled.contains(gate)) {
          unmodelled.add(gate);          // the discs never place it: a script or a shop hands it over
          held.add(gate);
          progress = true;
          continue;
        }
        Piece holder = null, free = null, trade = null;
        for (Piece piece : pieces) {
          if (!seen.contains(piece.room.name) || stuck.contains(piece.room.name)) continue;
          // only a slot lying on the floor counts: an item on an enemy or behind a flag may never drop, and the
          // proof must not call that obtainable. Such a gate item is moved to a floor slot below.
          if (piece.nowId == gate && holder == null && !piece.hidden()) holder = piece;
          if (piece.hidden()) continue;
          if (free == null && piece.empty()) free = piece;
          if (trade == null && itemId(piece.nowId) && piece.nowId != gate) trade = piece;
        }
        if (holder != null) {
          held.add(gate);
          progress = true;
          continue;
        }
        Piece from = null;
        for (Piece piece : filled) if (piece.nowId == gate) { from = piece; break; }
        if (from == null) {
          unmodelled.add(gate);   // nothing on the discs puts it in a room: a script or a shop hands it over
          held.add(gate);
          progress = true;
          continue;
        }
        if (free != null) {
          free.nowId = gate;
          free.nowNum = from.nowNum;
          from.nowId = 0;                                // the item moved: its old area is empty now
          from.nowNum = 0;
        } else if (trade != null) {
          int id = trade.nowId, num = trade.nowNum;
          trade.nowId = gate;
          trade.nowNum = from.nowNum;
          from.nowId = id;
          from.nowNum = num;
        } else {
          continue;
        }
        gateRoom.put(gate, free != null ? free.room.name : trade.room.name);
        held.add(gate);
        progress = true;
      }
      if (!progress) break;
    }

    Map<String, Map<Integer, int[]>> plan = new LinkedHashMap<>();
    int areasPlanned = 0;
    for (Piece piece : pieces) {
      if (piece.nowId == piece.oldId && piece.nowNum == piece.oldNum) continue;
      plan.computeIfAbsent(piece.room.name, k -> new LinkedHashMap<>())
          .put(piece.area.at, new int[]{piece.nowId, piece.nowNum});
      areasPlanned++;
    }

    // ---- 3. write the doors, then the items (ItemShuffle owns the model packs) -----------------------
    int roomsWritten = 0;
    try {
      for (Room room : rooms.values()) {
        boolean doorChanged = false;
        for (Door door : room.doors) if (!same(door.dest, door.orig)) doorChanged = true;
        if (!doorChanged) continue;
        byte[] b = readAll(room.file);
        for (Door door : room.doors) if (!same(door.dest, door.orig)) door.dest.into(b, door.at);
        writeAll(room.file, b);
        b = null;
        roomsWritten++;
      }
      if (!snapshotFile(root).isFile()) writeSnapshot(root, rooms);
    } catch (IOException | OutOfMemoryError e) {
      Log.i(TAG, "the doors could not be written: " + e);
      return "The deal stopped before it wrote the rooms: " + e;
    }
    String itemLine = ItemShuffle.writeDeal(root, plan);
    try {
      writeAll(markerFile(root), Long.toString(seed).getBytes(StandardCharsets.UTF_8));   // patch 0240 reads this
    } catch (IOException e) {
      Log.i(TAG, "the marker could not be written: " + e);
    }

    // ---- 4. the proof: read the written rooms back and walk them ------------------------------------
    Map<String, Room> after = scan(root);
    link(after);
    Map<String, Set<String>> afterAdj = directed(after);
    Set<Integer> got = new TreeSet<>();
    Set<String> proven = new TreeSet<>();
    int proofRounds = 0;
    while (proofRounds < 400) {
      proofRounds++;
      proven = reach(afterAdj, after, got);
      boolean progress = false;
      for (String name : proven) {
        Room room = after.get(name);
        if (room == null) continue;
        for (ItemArea area : room.items) if (itemId(area.id) && got.add(area.id)) progress = true;
      }
      if (!progress) break;
    }
    List<String> lost = new ArrayList<>(forward);
    lost.removeAll(proven);
    List<Integer> gateShort = new ArrayList<>();
    for (Integer gate : gates) if (!got.contains(gate) && !unmodelled.contains(gate)) gateShort.add(gate);
    boolean provenOk = lost.isEmpty() && gateShort.isEmpty();

    StringBuilder log = new StringBuilder();
    log.append("RE4 offline randomizer - item and door deal (one pass)\n");
    log.append("seed ").append(seed).append('\n');
    log.append("verdict: ").append(provenOk ? "PROVEN - every room is reached and every gate is satisfiable"
        : "NOT PROVEN - do not play this seed").append('\n');
    log.append("proof: walk ").append(proven.size()).append(" rooms of ").append(forward.size())
        .append(", ").append(gates.size()).append(" gates, ").append(proofRounds)
        .append(" collection rounds\n");
    if (!lost.isEmpty()) log.append("rooms the walk never reached: ").append(String.join(", ", lost)).append('\n');
    if (!gateShort.isEmpty()) {
      log.append("gates still unsatisfied: ");
      for (Integer gate : gateShort) log.append(String.format(Locale.US, "0x%02x ", gate));
      log.append('\n');
    }
    if (!unmodelled.isEmpty()) {
      log.append("gate items the discs never place (script or shop, not modelled): ");
      for (Integer gate : unmodelled) log.append(String.format(Locale.US, "0x%02x ", gate));
      log.append('\n');
    }
    log.append('\n').append("doors: ").append(swaps).append(" pair swaps kept of ")
        .append(swaps + rejected).append(" tried, ").append(cross).append(" across stages\n");
    log.append("rooms: ").append(rooms.size()).append(" collected, ").append(forward.size())
        .append(" walkable forwards, ").append(oneWay(after).size()).append(" one-way links; ");
    if (bridgeOnly.isEmpty()) {
      log.append("no room of this map needs one walked backwards\n");
    } else {
      log.append("only reached by walking a one-way link backwards: ")
          .append(String.join(", ", bridgeOnly)).append('\n');
    }
    log.append("items: ").append(itemLine).append('\n');
    log.append("pool: ").append(filled.size()).append(" movable items over ").append(pieces.size())
        .append(" areas, ").append(areasPlanned).append(" areas written, ").append(roomsWritten)
        .append(" rooms touched\n");
    if (!userPinned.isEmpty()) {
      log.append("rooms you keep vanilla (doors): ").append(String.join(", ", userPinned)).append('\n');
    }
    if (!itemPinned.isEmpty()) {
      log.append("rooms you keep vanilla (items): ").append(String.join(", ", itemPinned)).append('\n');
    }
    log.append('\n').append("gate items, where they now sit:\n");
    for (Integer gate : gates) {
      String where = gateRoom.get(gate);
      if (where == null) {
        String at = null;
        for (Piece piece : filled) if (piece.nowId == gate) at = piece.room.name;
        where = at == null ? (unmodelled.contains(gate) ? "handed over by a script" : "nowhere") : at;
      }
      log.append(String.format(Locale.US, "  0x%02x  %s%n", gate, where));
    }
    log.append('\n').append("not modelled by this proof (so not claimed):\n");
    log.append("  locked doors: key_id 1/2 is a lock kind and key_flg is a script flag, not an item\n");
    log.append("  one-way geometry: a drop or a lift the records do not describe\n");
    log.append("  scripted room jumps (SceAtExecRoomJump) and chapter spawns\n");
    log.append("  Ashley: her door check is off while randomizer/doors.active exists (port patch 0240)\n");
    log.append("  enemy-carried items are dealt with the pool, but a gate item is never left on a holder\n");
    try {
      writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the deal log could not be written: " + e);
    }
    String summary = (provenOk ? "Proven: " : "NOT proven: ") + proven.size() + " of " + forward.size()
        + " rooms walkable, " + gates.size() + " gates; " + swaps + " door swaps, " + itemLine
        + ". The walk is in randomizer/doors.txt";
    Log.i(TAG, summary);
    return summary;
  }

  /**
   * A refusal the player sees and logcat keeps.
   *
   * Every refusal used to be returned without a log line, so "I keep getting refused seeds" arrived with an
   * empty log: the reason existed only in a toast that had already faded. A refusal is the most useful thing
   * this feature can report, so it goes to logcat under the same tag as the rest.
   */
  private static String refuse(String why) {
    Log.i(TAG, why);
    return why;
  }

  private static Door randomDoor(Map<String, Room> rooms, Random rng) {
    int pick = rng.nextInt(Math.max(1, rooms.size()));
    for (Room room : rooms.values()) {
      if (pick-- > 0) continue;
      if (room.doors.isEmpty()) return null;
      return room.doors.get(rng.nextInt(room.doors.size()));
    }
    return null;
  }

  // ---- the PC randomizer's shape: a main path, then branches --------------------------------------------

  /** The PC tool's room classes, read from what the disc wires. See RE4RND_v2's DoorRandomizerData. */
  private static final int KIND_RESERVED = 0;    // chapter spawns, pinned puzzle rooms: payloads untouched
  private static final int KIND_NEED_KEY = 1;    // TwoDoorOneWayRooms/NeedKey
  private static final int KIND_NEED_ITEM = 2;   // a use area: the room cannot be left without an item
  private static final int KIND_SINGLE = 3;      // one socket: can only end a path
  private static final int KIND_REGULAR = 4;     // two sockets: a corridor
  private static final int KIND_HUB = 5;         // more than two: a junction
  /** A room the player keeps vanilla: one entrance only, and its own doors are never written. */
  private static final int KIND_KEEP = 6;

  /** How many rooms the main path aims for (the PC list has 25) and the shortest that is still a spine. */
  private static final int PATH_TARGET = 22;
  private static final int PATH_MIN = 12;

  private static int kindOf(Room room, Map<String, Room> rooms) {
    int sockets = 0;
    boolean key = false;
    for (Door door : room.doors) {
      if (!rooms.containsKey(door.dest.name())) continue;
      sockets++;
      if (door.keyId != 0) key = true;
    }
    if (roomPinned(room.name)) return KIND_KEEP;      // a kept room is a leaf, never a source
    if (chapterRoom(room.name)) return KIND_RESERVED;
    if (key) return KIND_NEED_KEY;
    for (Integer gate : room.gates) if (realGate(gate)) return KIND_NEED_ITEM;
    if (sockets <= 1) return KIND_SINGLE;
    if (sockets == 2) return KIND_REGULAR;
    return KIND_HUB;
  }

  private static int socketsOf(Room room, Map<String, Room> rooms, int kind) {
    if (kind == KIND_KEEP) return 1;      // one entrance: the room is reached, its own doors stay vanilla
    int n = 0;
    for (Door door : room.doors) if (rooms.containsKey(door.dest.name())) n++;
    return n;
  }

  /** Where the disc's own doors already arrive: the legal arrival payloads for each target room. */
  private static Map<String, List<Dest>> arrivals(Map<String, Room> rooms) {
    Map<String, List<Dest>> into = new HashMap<>();
    for (Room room : rooms.values()) {
      for (Door door : room.doors) {
        if (!rooms.containsKey(door.dest.name())) continue;
        into.computeIfAbsent(door.dest.name(), k -> new ArrayList<>()).add(door.orig);
      }
    }
    for (Room room : rooms.values()) {
      if (into.containsKey(room.name) || room.doors.isEmpty()) continue;
      // a room the disc never arrives into (a one-way drop): its own door position is still a legal place
      into.put(room.name, new ArrayList<>(Collections.singletonList(room.doors.get(0).orig)));
    }
    return into;
  }

  /** A wired main path plus the branches hung on it. */
  private static final class Spine {
    List<String> path = new ArrayList<>();
    Map<String, List<String>> plan = new LinkedHashMap<>();
    Set<String> used = new TreeSet<>();
    Map<String, Integer> spare = new LinkedHashMap<>();
    int links;
  }

  /**
   * Picks the ordered main path the way the PC tool's MainPathRooms.txt does, then hangs the rest on it.
   *
   * Each step spends one free socket of the room the path is standing in and one of the next room's, which is
   * why a two-socket room is a corridor and a one-socket room can only end the path. The path prefers the
   * disc's own neighbours and may otherwise hop anywhere (the PC list hops between areas freely).
   */
  private static Spine buildSpine(Map<String, Room> rooms, Set<String> universe, Map<String, Integer> kinds,
      Random rng) {
    Spine spine = new Spine();
    Map<String, Integer> free = new LinkedHashMap<>();
    for (String name : universe) {
      free.put(name, socketsOf(rooms.get(name), rooms, kinds.getOrDefault(name, KIND_REGULAR)));
    }
    if (!universe.contains(START) || free.getOrDefault(START, 0) <= 0) return null;
    spine.used.add(START);
    spine.path.add(START);

    while (spine.path.size() < PATH_TARGET) {
      String here = spine.path.get(spine.path.size() - 1);
      if (free.getOrDefault(here, 0) <= 0) break;
      boolean longEnough = spine.path.size() >= PATH_MIN;
      List<String> near = new ArrayList<>();
      for (Door door : rooms.get(here).doors) {
        String target = door.dest.name();
        if (universe.contains(target) && !spine.used.contains(target) && free.getOrDefault(target, 0) > 0) {
          near.add(target);
        }
      }
      List<String> any = new ArrayList<>();
      for (String name : universe) {
        if (spine.used.contains(name) || free.getOrDefault(name, 0) <= 0) continue;
        int kind = kinds.getOrDefault(name, KIND_REGULAR);
        if (!longEnough && (kind == KIND_SINGLE || kind == KIND_KEEP)) continue;
        any.add(name);
      }
      List<String> pick;
      if (!near.isEmpty()) {
        pick = new ArrayList<>();
        for (String name : near) {
          int kind = kinds.getOrDefault(name, KIND_REGULAR);
          if (longEnough || (kind != KIND_SINGLE && kind != KIND_KEEP)) pick.add(name);
        }
      } else {
        pick = any;
      }
      if (pick.isEmpty()) pick = any;
      if (pick.isEmpty()) break;
      String next = pick.get(rng.nextInt(pick.size()));
      spine.plan.computeIfAbsent(here, k -> new ArrayList<>()).add(next);
      spine.plan.computeIfAbsent(next, k -> new ArrayList<>()).add(here);
      free.put(here, free.get(here) - 1);
      free.put(next, free.get(next) - 1);
      spine.used.add(next);
      spine.path.add(next);
      spine.links++;
    }

    // branches: always grow from the host with the most spare sockets to the unwired room with the most
    // sockets of its own, so the frontier cannot dry up with rooms still unwired
    List<String> rest = new ArrayList<>();
    for (String name : universe) if (!spine.used.contains(name)) rest.add(name);
    while (!rest.isEmpty()) {
      String host = null;
      for (String name : spine.used) {
        if (free.getOrDefault(name, 0) <= 0) continue;
        if (host == null || free.get(name) > free.get(host)) host = name;
      }
      if (host == null) break;
      final Map<String, Integer> f = free;
      rest.sort((a, b) -> {
        int sa = f.getOrDefault(a, 0) <= 1 ? 0 : 1, sb = f.getOrDefault(b, 0) <= 1 ? 0 : 1;
        if (sa != sb) return sa - sb;                       // a one-socket room ends a branch, so it goes last
        return f.getOrDefault(b, 0) - f.getOrDefault(a, 0);
      });
      String next = rest.remove(0);
      spine.plan.computeIfAbsent(host, k -> new ArrayList<>()).add(next);
      spine.plan.computeIfAbsent(next, k -> new ArrayList<>()).add(host);
      free.put(host, free.get(host) - 1);
      free.put(next, free.get(next) - 1);
      spine.used.add(next);
      spine.links++;
    }

    // leftover sockets duplicate a link that already exists, so no new shortcut appears
    for (String name : spine.used) {
      List<String> links = spine.plan.get(name);
      while (free.getOrDefault(name, 0) > 0 && links != null && !links.isEmpty()) {
        links.add(links.get(rng.nextInt(links.size())));
        free.put(name, free.get(name) - 1);
        spine.links++;
      }
    }
    spine.spare = free;
    return spine;
  }

  /**
   * Gives every wired socket an arrival payload that already arrives in its target room.
   *
   * The payload (position, stage, room, part) travels as one unit, exactly as the PC tool's Return/ files
   * precompute it, so a door never lands somewhere illegal. Reserved rooms keep the disc's own payloads.
   */
  private static int applySpine(Map<String, Room> rooms, Spine spine, Map<String, Integer> kinds, Random rng) {
    Map<String, List<Dest>> into = arrivals(rooms);
    int wired = 0;
    for (Map.Entry<String, List<String>> entry : spine.plan.entrySet()) {
      Room room = rooms.get(entry.getKey());
      if (room == null) continue;
      boolean reserved = kinds.getOrDefault(room.name, KIND_REGULAR) == KIND_RESERVED;
      boolean keep = kinds.getOrDefault(room.name, KIND_REGULAR) == KIND_KEEP;
      List<Door> doors = new ArrayList<>();
      for (Door door : room.doors) if (rooms.containsKey(door.dest.name())) doors.add(door);
      List<String> targets = entry.getValue();
      for (int i = 0; i < doors.size() && i < targets.size(); i++) {
        if (reserved || keep) continue;
        List<Dest> pool = into.get(targets.get(i));
        if (pool == null || pool.isEmpty()) continue;
        doors.get(i).dest = pool.get(rng.nextInt(pool.size()));
        wired++;
      }
    }
    return wired;
  }

  /** Whether the map, walked forwards, reaches exactly the rooms the disc reaches forwards. */
  private static boolean walkable(Map<String, Room> rooms, Set<String> forward) {
    return reach(directed(rooms), rooms, gateIds(rooms)).equals(forward);
  }

  /**
   * The PC randomizer's shape, then the same proven deal on top: a main path with branches, every socket given
   * an arrival payload the disc already uses, the whole item pool dealt along that walk, and the result
   * re-read and walked again before it is called proven.
   */
  static String dealSpine(File root, long seed, boolean allowCross) {
    userPinned = RoomPins.load(root, RoomPins.DOORS);
    Set<String> itemPinned = RoomPins.load(root, RoomPins.ITEMS);
    Map<String, Room> rooms = scan(root);
    if (rooms.isEmpty()) return refuse("No collected rooms yet: collect the rooms first");
    link(rooms);
    Set<Integer> allGates = gateIds(rooms);
    Set<String> forward = reach(directed(rooms), rooms, allGates);
    if (forward.size() < MIN_UNIVERSE) {
      return refuse("The deal refused: only " + forward.size() + " rooms are reachable from " + START
          + " walking the doors as they point; collect the rooms again and retry.");
    }
    Set<String> bridgeOnly = new TreeSet<>(reach(graph(rooms), rooms, allGates));
    bridgeOnly.removeAll(forward);

    Random rng = new Random(seed);
    Map<String, Integer> kinds = new LinkedHashMap<>();
    for (String name : forward) kinds.put(name, kindOf(rooms.get(name), rooms));

    Spine best = null;
    Map<String, Room> bestRooms = null;
    int bestWired = 0;
    for (int attempt = 0; attempt < 40; attempt++) {
      Map<String, Room> trial = copyOf(rooms);
      Spine spine = buildSpine(trial, forward, kinds, rng);
      if (spine == null || spine.path.size() < PATH_MIN) continue;
      int wired = applySpine(trial, spine, kinds, rng);
      if (!walkable(trial, forward)) continue;
      if (best == null || spine.path.size() > best.path.size()) {
        best = spine;
        bestRooms = trial;
        bestWired = wired;
      }
    }
    if (best == null || bestRooms == null) {
      return refuse("Seed " + seed + " refused: no main path of " + PATH_MIN
          + " rooms keeps the disc's own rooms walkable; nothing was written. Try another seed.");
    }
    rooms = bestRooms;
    Map<String, Set<String>> adj = directed(rooms);

    // the items, on this door map: the same rule as dealAll
    Set<Integer> modelled = ItemShuffle.modelIds(root);
    List<Piece> pieces = new ArrayList<>();
    for (String name : forward) {
      if (itemPinned.contains(name) || userPinned.contains(name)) continue;
      Room room = rooms.get(name);
      for (ItemArea area : room.items) {
        if (area.id == 0xFFFF) continue;
        if (itemId(area.id) && !modelled.contains(area.id)) continue;
        Piece piece = new Piece();
        piece.room = room;
        piece.area = area;
        piece.oldId = area.id;
        piece.oldNum = area.num;
        piece.nowId = area.id;
        piece.nowNum = area.num;
        pieces.add(piece);
      }
    }
    List<Piece> filled = new ArrayList<>();
    for (Piece piece : pieces) if (itemId(piece.nowId)) filled.add(piece);
    if (filled.isEmpty()) return refuse("Nothing to deal: the reachable rooms hold no item the deal can move");
    for (int k = filled.size() * 8; k > 0; k--) {
      Piece one = filled.get(rng.nextInt(filled.size()));
      Piece two = filled.get(rng.nextInt(filled.size()));
      if (one == two) continue;
      int id = one.nowId, num = one.nowNum;
      one.nowId = two.nowId;
      one.nowNum = two.nowNum;
      two.nowId = id;
      two.nowNum = num;
    }
    Set<Integer> gates = new TreeSet<>();
    for (String name : forward) for (Integer gate : rooms.get(name).gates) if (realGate(gate)) gates.add(gate);
    Set<Integer> held = new TreeSet<>();
    Set<Integer> unmodelled = new TreeSet<>();
    Map<Integer, String> gateRoom = new LinkedHashMap<>();
    for (int round = 0; round < gates.size() + 6; round++) {
      Set<String> seen = reach(adj, rooms, held);
      Set<String> stuck = cannotLeave(rooms, adj, held);
      Set<Integer> wanted = new TreeSet<>();
      for (String name : seen) {
        for (Integer gate : rooms.get(name).gates) if (realGate(gate) && !held.contains(gate)) wanted.add(gate);
      }
      if (wanted.isEmpty()) break;
      boolean progress = false;
      for (Integer gate : wanted) {
        if (!modelled.contains(gate)) {
          unmodelled.add(gate);
          held.add(gate);
          progress = true;
          continue;
        }
        Piece holder = null, free = null, trade = null;
        for (Piece piece : pieces) {
          if (!seen.contains(piece.room.name) || stuck.contains(piece.room.name)) continue;
          if (piece.nowId == gate && holder == null && !piece.hidden()) holder = piece;
          if (piece.hidden()) continue;
          if (free == null && piece.empty()) free = piece;
          if (trade == null && itemId(piece.nowId) && piece.nowId != gate) trade = piece;
        }
        if (holder != null) {
          held.add(gate);
          progress = true;
          continue;
        }
        Piece from = null;
        for (Piece piece : filled) if (piece.nowId == gate) { from = piece; break; }
        if (from == null) {
          unmodelled.add(gate);
          held.add(gate);
          progress = true;
          continue;
        }
        if (free != null) {
          free.nowId = gate;
          free.nowNum = from.nowNum;
          from.nowId = 0;
          from.nowNum = 0;
        } else if (trade != null) {
          int id = trade.nowId, num = trade.nowNum;
          trade.nowId = gate;
          trade.nowNum = from.nowNum;
          from.nowId = id;
          from.nowNum = num;
        } else {
          continue;
        }
        gateRoom.put(gate, free != null ? free.room.name : trade.room.name);
        held.add(gate);
        progress = true;
      }
      if (!progress) break;
    }

    Map<String, Map<Integer, int[]>> plan = new LinkedHashMap<>();
    int areasPlanned = 0;
    for (Piece piece : pieces) {
      if (piece.nowId == piece.oldId && piece.nowNum == piece.oldNum) continue;
      plan.computeIfAbsent(piece.room.name, k -> new LinkedHashMap<>())
          .put(piece.area.at, new int[]{piece.nowId, piece.nowNum});
      areasPlanned++;
    }

    int roomsWritten = 0;
    try {
      for (Room room : rooms.values()) {
        boolean doorChanged = false;
        for (Door door : room.doors) if (!same(door.dest, door.orig)) doorChanged = true;
        if (!doorChanged) continue;
        byte[] b = readAll(room.file);
        for (Door door : room.doors) if (!same(door.dest, door.orig)) door.dest.into(b, door.at);
        writeAll(room.file, b);
        b = null;
        roomsWritten++;
      }
      if (!snapshotFile(root).isFile()) writeSnapshot(root, rooms);
    } catch (IOException | OutOfMemoryError e) {
      Log.i(TAG, "the doors could not be written: " + e);
      return "The deal stopped before it wrote the rooms: " + e;
    }
    String itemLine = ItemShuffle.writeDeal(root, plan);
    try {
      writeAll(markerFile(root), Long.toString(seed).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the marker could not be written: " + e);
    }

    Map<String, Room> after = scan(root);
    link(after);
    Set<Integer> got = new TreeSet<>();
    Set<String> proven = new TreeSet<>();
    int proofRounds = 0;
    while (proofRounds < 400) {
      proofRounds++;
      proven = reach(directed(after), after, got);
      boolean progress = false;
      for (String name : proven) {
        Room room = after.get(name);
        if (room == null) continue;
        for (ItemArea area : room.items) if (itemId(area.id) && got.add(area.id)) progress = true;
      }
      if (!progress) break;
    }
    List<String> lost = new ArrayList<>(forward);
    lost.removeAll(proven);
    List<Integer> gateShort = new ArrayList<>();
    for (Integer gate : gates) if (!got.contains(gate) && !unmodelled.contains(gate)) gateShort.add(gate);
    boolean provenOk = lost.isEmpty() && gateShort.isEmpty();

    Map<String, Integer> kindCount = new LinkedHashMap<>();
    for (Integer kind : kinds.values()) {
      String label = kind == KIND_RESERVED ? "reserved" : kind == KIND_NEED_KEY ? "need key"
          : kind == KIND_NEED_ITEM ? "need item" : kind == KIND_SINGLE ? "single door"
          : kind == KIND_REGULAR ? "two doors" : "junction";
      kindCount.merge(label, 1, Integer::sum);
    }
    StringBuilder log = new StringBuilder();
    log.append("RE4 offline randomizer - PC-style spine, items and doors in one pass\n");
    log.append("seed ").append(seed).append('\n');
    log.append("verdict: ").append(provenOk ? "PROVEN - every room is reached and every gate is satisfiable"
        : "NOT PROVEN - do not play this seed").append('\n');
    log.append("main path (").append(best.path.size()).append(" rooms): ").append(String.join(" -> ", best.path))
        .append('\n');
    log.append("branches: ").append(best.used.size() - best.path.size()).append(" rooms hung on ")
        .append(best.links).append(" links, ").append(bestWired).append(" sockets given an arrival payload\n");
    log.append("room classes: ").append(kindCount).append('\n');
    log.append("proof: walk ").append(proven.size()).append(" rooms of ").append(forward.size()).append(", ")
        .append(gates.size()).append(" gates, ").append(proofRounds).append(" collection rounds\n");
    if (!lost.isEmpty()) log.append("rooms the walk never reached: ").append(String.join(", ", lost)).append('\n');
    if (!gateShort.isEmpty()) {
      log.append("gates still unsatisfied: ");
      for (Integer gate : gateShort) log.append(String.format(Locale.US, "0x%02x ", gate));
      log.append('\n');
    }
    if (!unmodelled.isEmpty()) {
      log.append("gate items the discs never place (script or shop, not modelled): ");
      for (Integer gate : unmodelled) log.append(String.format(Locale.US, "0x%02x ", gate));
      log.append('\n');
    }
    log.append("items: ").append(itemLine).append('\n');
    log.append("pool: ").append(filled.size()).append(" movable items over ").append(pieces.size())
        .append(" areas, ").append(areasPlanned).append(" areas written, ").append(roomsWritten)
        .append(" rooms touched\n");
    log.append("rooms: ").append(rooms.size()).append(" collected, ").append(forward.size())
        .append(" walkable forwards, ").append(oneWay(after).size()).append(" one-way links; ");
    if (bridgeOnly.isEmpty()) {
      log.append("no room of this map needs one walked backwards\n");
    } else {
      log.append("only reached by walking a one-way link backwards: ")
          .append(String.join(", ", bridgeOnly)).append('\n');
    }
    if (!userPinned.isEmpty()) {
      log.append("rooms you keep vanilla (doors): ").append(String.join(", ", userPinned)).append('\n');
    }
    if (!itemPinned.isEmpty()) {
      log.append("rooms you keep vanilla (items): ").append(String.join(", ", itemPinned)).append('\n');
    }
    log.append('\n').append("gate items, where they now sit:\n");
    for (Integer gate : gates) {
      String where = gateRoom.get(gate);
      if (where == null) {
        for (Piece piece : filled) if (piece.nowId == gate) where = piece.room.name;
        if (where == null) where = unmodelled.contains(gate) ? "handed over by a script" : "nowhere";
      }
      log.append(String.format(Locale.US, "  0x%02x  %s%n", gate, where));
    }
    log.append('\n').append("not modelled by this proof (so not claimed):\n");
    log.append("  locked doors: key_id 1/2 is a lock kind and key_flg is a script flag, not an item\n");
    log.append("  one-way geometry: a drop or a lift the records do not describe\n");
    log.append("  scripted room jumps (SceAtExecRoomJump) and chapter spawns\n");
    log.append("  Ashley: her door check is off while randomizer/doors.active exists (port patch 0240)\n");
    log.append("  enemy-carried items are dealt with the pool, but a gate item is never left on a holder\n");
    log.append("  the PC tool can also inject return triggers; this port cannot, so both ends of a link are real\n");
    try {
      writeAll(logFile(root), log.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      Log.i(TAG, "the deal log could not be written: " + e);
    }
    String summary = (provenOk ? "Proven: " : "NOT proven: ") + proven.size() + " of " + forward.size()
        + " rooms, " + gates.size() + " gates; main path " + best.path.size() + " rooms, " + best.links
        + " links, " + itemLine + ". The walk is in randomizer/doors.txt";
    Log.i(TAG, summary);
    return summary;
  }

  /** A copy of the room metadata, so a refused attempt can be thrown away without rescanning the files. */
  private static Map<String, Room> copyOf(Map<String, Room> rooms) {
    Map<String, Room> out = new TreeMap<>();
    for (Room room : rooms.values()) {
      Room copy = new Room();
      copy.name = room.name;
      copy.file = room.file;
      copy.models = room.models;
      copy.gates.addAll(room.gates);
      for (Door door : room.doors) {
        Door d = new Door();
        d.room = copy;
        d.at = door.at;
        d.area = door.area;
        d.keyId = door.keyId;
        d.keyFlg = door.keyFlg;
        d.orig = door.orig;
        d.dest = door.dest;
        copy.doors.add(d);
      }
      for (ItemArea area : room.items) {
        ItemArea a = new ItemArea();
        a.at = area.at;
        a.id = area.id;
        a.num = area.num;
        a.waiting = area.waiting;
        a.ctrl = area.ctrl;
        copy.items.add(a);
      }
      out.put(room.name, copy);
    }
    // door.dest and door.orig are immutable records (a fresh Dest per door), so sharing them is safe
    for (Room room : out.values()) for (Door door : room.doors) door.room = room;
    return out;
  }

}
