package com.ast.re4decomp;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The offline randomizer's item shuffle (branch feature/offline-randomizer).
 *
 * Where the pair swap ({@link SidecarSwap}) exchanged two items inside one room, this deals the whole game anew:
 * every item lying free in the story's rooms takes a place from one pool, so the pool's contents keep their number
 * but not their room. A seed makes the same roll every time; the log line names the seed and a fingerprint of the
 * mapping.
 *
 * The sidecars are the original big-endian room archives (see 0238-room-raw-sidecar.patch), so reading and writing
 * them is plain bytes:
 *
 *   archive  0x00 count, 0x04 REL word, 0x08/0x0C pads, 0x10 count offsets, then count 4-byte tags, then entries
 *            (each 32-byte aligned; see port/android-decomp/docs/ARCHIVE.md)
 *   ITA      "ITA", u16 version 0x105, u16 count at +0x06, records at +0x10, 0x9C bytes each (sce_at.h SCE_AT_ITEM)
 *   item id  record + 0x78, count + 0x7C, big endian
 *   ITM      item model pack: 0x00/0x04/0x08/0x0C are offsets to the id, BIN and TPL tables (item_model.cpp
 *            ItemModelPack); each id has one BIN and one TPL blob, both big endian and 32-byte aligned
 *
 * The models are the reason a full shuffle needs this class at all: the game only draws an item whose model the
 * room's ITM pack carries (ItemGetBinTplAddr, sce_at.cpp sceAtSetItem), so a room that receives items it never had
 * gets their models added to its own pack. That is the CE4 equivalent of the PC randomizer's merged RandoITM_Full.ITM,
 * done per room so every archive stays small (a model is about 5..30 KB, a room's pack about 250 KB).
 *
 * What moves: the items the port's own cheat pool already deals with (runtime/cheats.cpp kAmmoIds, kHealIds,
 * kMoneyIds, kGrenadeIds and the treasure types 5 and 12 minus the three a room script handles by its id). Keys,
 * files, maps, weapons and weapon parts keep their place: a key can still open its door from anywhere, but a key
 * behind a door that needs it, or a weapon nobody owns yet, is how a seed breaks (the PC randomizer has the same two
 * switches off by default).
 */
public final class ItemShuffle {
  private static final String TAG = "ItemShuffle";
  /** One ITA item area (sce_at.h SCE_AT_ITEM). */
  private static final int RECORD = 0x9C;
  /** The item records start after the ITA block's 0x10-byte header (sce_at.cpp SceAtInit). */
  private static final int ITA_RECORDS = 0x10;
  private static final int ITEM_ID = 0x78;
  /** SCE_AT_UNIT: 1 = held by an enemy list entry (a crate, a Ganado), 2 = etc model. */
  private static final int WAITING_TYPE = 0x46;
  /** SCE_AT_DATA_ITEM.ctrl_flag (0x5C + 0x28): bit0 = handed to an enemy, bit3 = save work. */
  private static final int CTRL_FLAG = 0x84;
  /** Bytes per entry of the model pack's id table. */
  private static final int ITM_ID = 8;
  private static final int MAX_ARCHIVE = 64 * 1024 * 1024;

  /**
   * The ids a room slot may not deal freely, from src/game/item.cpp itemInfo's types and the survey of the discs:
   * type 7 is the default branch, the key / quest / misc class (the ids the port's own key gates read), type 9 the
   * weapon parts, type 10 the files (their models are megabytes). 0x1000.. are the game's own random drops.
   * Keys are dealt by a rule of their own (see the key rules); the rest of the room items move anywhere.
   */
  private static final int[] KEYS_AND_FILES = {0x0F, 0x1D, 0x1E, 0x1F, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x47,
      0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x69, 0x6E, 0x6F, 0x74, 0x7A, 0x7B, 0x80, 0x82,
      0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x8B, 0x8C, 0x8D, 0x92, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xAE, 0xAF,
      0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xC3, 0xC4, 0xC5, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9,
      0xFA, 0xFB, 0x1000, 0x1001, 0x1002, 0x1003, 0x1004, 0x1005};
  /** No model bigger than this goes into a room: a merged pack has to stay small (a room's own is about 250 KB). */
  private static final int MODEL_CAP = 512 * 1024;

  private ItemShuffle() {
  }

  // ---- bytes ----------------------------------------------------------------------------------------------------

  private static int be32(byte[] b, int at) {
    return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
  }

  private static int be16(byte[] b, int at) {
    return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
  }

  private static void put16(byte[] b, int at, int value) {
    b[at] = (byte) (value >> 8);
    b[at + 1] = (byte) value;
  }

  private static void put32(byte[] b, int at, int value) {
    b[at] = (byte) (value >> 24);
    b[at + 1] = (byte) (value >> 16);
    b[at + 2] = (byte) (value >> 8);
    b[at + 3] = (byte) value;
  }

  private static byte[] readAll(File file) throws IOException {
    try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
      long size = handle.length();
      if (size <= 0 || size > MAX_ARCHIVE) throw new IOException(file.getName() + ": bad size " + size);
      byte[] bytes = new byte[(int) size];
      handle.readFully(bytes);
      return bytes;
    }
  }

  private static void writeAll(File file, byte[] bytes) throws IOException {
    File temp = new File(file.getParentFile(), file.getName() + ".tmp");
    try (RandomAccessFile handle = new RandomAccessFile(temp, "rw")) {
      handle.setLength(0);
      handle.write(bytes);
    }
    if (!temp.renameTo(file)) {
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
      throw new IOException("cannot replace " + file.getName());
    }
  }

  // ---- the archive table ---------------------------------------------------------------------------------------

  /** An archive's table, or null when the bytes are not one. */
  private static final class Arc {
    final int num;
    final int[] offsets;
    final int[] tagAt;

    Arc(int num, int[] offsets, int[] tagAt) {
      this.num = num;
      this.offsets = offsets;
      this.tagAt = tagAt;
    }

    static Arc of(byte[] b) {
      if (b.length < 0x20) return null;
      int num = be32(b, 0);
      if (num <= 0 || num > 0x1000 || 0x10 + num * 8 > b.length) return null;
      if (be32(b, 8) != 0 || be32(b, 12) != 0) return null;
      int[] offsets = new int[num];
      int[] tagAt = new int[num];
      for (int i = 0; i < num; i++) {
        int at = be32(b, 0x10 + i * 4);
        if (at <= 0 || at >= b.length) return null;
        offsets[i] = at;
        tagAt[i] = 0x10 + num * 4 + i * 4;
      }
      return new Arc(num, offsets, tagAt);
    }

    int end(byte[] b, int index) {
      int best = b.length;
      for (int i = 0; i < num; i++) {
        if (offsets[i] > offsets[index] && offsets[i] < best) best = offsets[i];
      }
      return best;
    }

    /** Index of the `nth` entry tagged `tag`, -1 when absent. */
    int find(byte[] b, String tag, int nth) {
      int seen = 0;
      for (int i = 0; i < num; i++) {
        int at = tagAt[i];
        if (b[at] == tag.charAt(0) && b[at + 1] == tag.charAt(1) && b[at + 2] == tag.charAt(2)) {
          if (seen == nth) return i;
          seen++;
        }
      }
      return -1;
    }
  }

  /** One item model: where its BIN and TPL blobs lie in a sidecar. */
  private static final class Model {
    File file;
    int binAt;
    int binLen;
    int tplAt;
    int tplLen;

    int bytes() {
      return binLen + tplLen;
    }
  }

  private static Model[] models(byte[] b, Arc arc, int entry) {
    int base = arc.offsets[entry];
    int blockEnd = arc.end(b, entry);
    int ofsId = be32(b, base + 4);
    int ofsBin = be32(b, base + 8);
    int ofsTpl = be32(b, base + 12);
    int count = be32(b, base + ofsId);
    if (count <= 0 || count > 0x100) return null;
    if (base + ofsId + 4 + count * ITM_ID > blockEnd) return null;
    if (base + ofsBin + 4 + count * 4 > blockEnd || base + ofsTpl + 4 + count * 4 > blockEnd) return null;
    int[] bins = new int[count];
    int[] tpls = new int[count];
    for (int i = 0; i < count; i++) {
      int binAt = base + ofsBin + be32(b, base + ofsBin + 4 + i * 4);
      int tplAt = base + ofsTpl + be32(b, base + ofsTpl + 4 + i * 4);
      if (binAt <= 0 || binAt >= blockEnd || tplAt <= 0 || tplAt >= blockEnd) return null;
      bins[i] = binAt;
      tpls[i] = tplAt;
    }
    Model[] result = new Model[count];
    for (int i = 0; i < count; i++) {
      Model model = new Model();
      model.binAt = bins[i];
      model.binLen = clampSpan(bins[i], i + 1 < count ? bins[i + 1] : blockEnd, blockEnd);
      model.tplAt = tpls[i];
      model.tplLen = clampSpan(tpls[i], i + 1 < count ? tpls[i + 1] : blockEnd, blockEnd);
      result[i] = model;
    }
    return result;
  }

  /** A blob's length: up to the next blob or the end of the block, never past it. */
  private static int clampSpan(int from, int next, int end) {
    int to = next;
    if (to <= from || to > end) to = end;
    return Math.max(0, to - from);
  }

  // ---- pass 1: what the rooms hold -----------------------------------------------------------------------------

  /** An item area: which sidecar, where, and which item lies there. */
  private static final class Slot {
    File file;
    int at;
    int id;
  }

  /** The model of every item id anywhere on the discs, the smallest variant (keeps a room's pack small). */
  private static Map<Integer, Model> modelIndex(List<File> sidecars) {
    Map<Integer, Model> index = new HashMap<>();
    for (File file : sidecars) {
      try {
        byte[] b = readAll(file);
        Arc arc = Arc.of(b);
        if (arc == null) continue;
        int entry = arc.find(b, "ITM", 0);
        if (entry < 0) continue;
        Model[] models = models(b, arc, entry);
        if (models == null) continue;
        for (int i = 0; i < models.length; i++) {
          int id = be16(b, arc.offsets[entry] + be32(b, arc.offsets[entry] + 4) + 4 + i * ITM_ID);
          Model model = models[i];
          model.file = file;
          Model known = index.get(id);
          if (known == null || model.bytes() < known.bytes()) index.put(id, model);
        }
      } catch (IOException | RuntimeException e) {
        Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": reading models failed: " + e);
      }
    }
    return index;
  }

  /** Every item area of every collected room, and the room's own pack ids. */
  private static List<Slot> slots(List<File> sidecars) {
    List<Slot> result = new ArrayList<>();
    for (File file : sidecars) {
      try {
        byte[] b = readAll(file);
        Arc arc = Arc.of(b);
        if (arc == null) continue;
        int entry = arc.find(b, "ITA", 0);
        if (entry < 0) continue;
        int base = arc.offsets[entry];
        if (b[base] != 'I' || b[base + 1] != 'T' || b[base + 2] != 'A') continue;
        int version = be16(b, base + 4);
        if (version != 0x105) {
          Log.i(TAG, file.getName() + ": ITA version 0x" + Integer.toHexString(version) + ", left as it is");
          continue;
        }
        int count = be16(b, base + 6);
        int end = arc.end(b, entry);
        if (count <= 0 || base + ITA_RECORDS + count * RECORD > end) {
          Log.i(TAG, file.getName() + ": ITA of " + count + " areas does not fit its block, left as it is");
          continue;
        }
        for (int i = 0; i < count; i++) {
          int at = base + ITA_RECORDS + i * RECORD;
          int id = be16(b, at + ITEM_ID);
          if (id == 0 || id == 0xFFFF) continue;   // "nothing here"
          // a held record belongs to its holder: the item there is placed by a crate/enemy, with that holder's
          // effect and flags, so it keeps it (port/docs, cheats.cpp re4_item_random leaves them alone too)
          if ((b[at + WAITING_TYPE] & 0xFF) != 0 || (b[at + CTRL_FLAG] & 0xFF) != 0) continue;
          Slot slot = new Slot();
          slot.file = file;
          slot.at = at;
          slot.id = id;
          result.add(slot);
        }
      } catch (IOException | RuntimeException e) {
        Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": reading items failed: " + e);
      }
    }
    return result;
  }

  /** The ids that may move: every room item that is not a key, a file or a part, and that a room has a model for. */
  private static boolean inPool(int id, Map<Integer, Model> index) {
    if (has(KEYS_AND_FILES, id)) return false;
    Model model = index.get(id);
    return model != null && model.bytes() <= MODEL_CAP;
  }

  private static boolean has(int[] list, int id) {
    for (int value : list) if (value == id) return true;
    return false;
  }

  // ---- the seed -----------------------------------------------------------------------------------------------

  private static long next(long[] state) {
    long z = (state[0] += 0x9E3779B97F4A7C15L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  private static int below(long[] state, int bound) {
    return (int) ((next(state) >>> 1) % bound);
  }

  // ---- pass 2: rewrite one room --------------------------------------------------------------------------------

  private static final class Sources extends LinkedHashMap<File, byte[]> {
    @Override
    protected boolean removeEldestEntry(Map.Entry<File, byte[]> eldest) {
      return size() > 3;
    }
  }

  /** Copies the models of every id it can resolve into one pack: this room's own blobs where it has the id, the
   *  smallest variant elsewhere. An id nothing can supply is dropped (the room keeps its original pack for it).
   *  All BIN blobs first, then all TPL blobs, each 32-byte aligned (GX data needs it). Null when nothing resolves. */
  private static byte[] buildPack(byte[] own, Arc arc, int ownEntry, int[] ids, Map<Integer, Model> index,
      Sources sources) throws IOException {
    Model[] ownModels = ownEntry < 0 ? null : models(own, arc, ownEntry);
    int[] ownIds = ownEntry < 0 ? new int[0] : ownIds(own, arc, ownEntry);
    List<Integer> kept = new ArrayList<>();
    List<byte[]> bins = new ArrayList<>();
    List<byte[]> tpls = new ArrayList<>();
    for (int id : ids) {
      byte[] bin = null;
      byte[] tpl = null;
      int seen = ownIndex(ownIds, id);
      if (ownModels != null && seen >= 0 && seen < ownModels.length) {
        Model model = ownModels[seen];
        bin = Arrays.copyOfRange(own, model.binAt, model.binAt + model.binLen);
        tpl = Arrays.copyOfRange(own, model.tplAt, model.tplAt + model.tplLen);
      } else {
        Model model = index.get(id);
        if (model != null && model.file != null) {
          byte[] from = load(sources, model.file);
          bin = Arrays.copyOfRange(from, model.binAt, model.binAt + model.binLen);
          tpl = Arrays.copyOfRange(from, model.tplAt, model.tplAt + model.tplLen);
        }
      }
      if (bin == null || tpl == null) continue;   // nothing can show it: leave this id out
      kept.add(id);
      bins.add(bin);
      tpls.add(tpl);
    }
    int count = kept.size();
    if (count == 0) return null;
    int ofsId = 0x10;
    int ofsBin = ofsId + 4 + count * ITM_ID;
    int ofsTpl = ofsBin + 4 + count * 4;
    int dataAt = align32(ofsTpl + 4 + count * 4);
    int at = dataAt;
    int[] binRel = new int[count];
    int[] tplRel = new int[count];
    for (int i = 0; i < count; i++) {
      at = align32(at);
      binRel[i] = at - ofsBin;
      at += bins.get(i).length;
    }
    for (int i = 0; i < count; i++) {
      at = align32(at);
      tplRel[i] = at - ofsTpl;
      at += tpls.get(i).length;
    }
    byte[] pack = new byte[align32(at)];
    put32(pack, 0, 0);
    put32(pack, 4, ofsId);
    put32(pack, 8, ofsBin);
    put32(pack, 12, ofsTpl);
    put32(pack, ofsId, count);
    put32(pack, ofsBin, count);
    put32(pack, ofsTpl, count);
    for (int i = 0; i < count; i++) {
      put16(pack, ofsId + 4 + i * ITM_ID, kept.get(i));
      put32(pack, ofsBin + 4 + i * 4, binRel[i]);
      put32(pack, ofsTpl + 4 + i * 4, tplRel[i]);
    }
    int pos = dataAt;
    for (int i = 0; i < count; i++) {
      pos = align32(pos);
      System.arraycopy(bins.get(i), 0, pack, pos, bins.get(i).length);
      pos += bins.get(i).length;
    }
    for (int i = 0; i < count; i++) {
      pos = align32(pos);
      System.arraycopy(tpls.get(i), 0, pack, pos, tpls.get(i).length);
      pos += tpls.get(i).length;
    }
    return pack;
  }

  /** A sidecar's bytes, kept for the last three files read: the models of other rooms come from there. */
  private static byte[] load(Sources sources, File file) throws IOException {
    byte[] cached = sources.get(file);
    if (cached == null) {
      cached = readAll(file);
      sources.put(file, cached);
    }
    return cached;
  }

  private static int align32(int value) {
    return (value + 31) & ~31;
  }

  private static int[] ownIds(byte[] b, Arc arc, int entry) {
    if (entry < 0) return new int[0];
    int base = arc.offsets[entry];
    int ofsId = be32(b, base + 4);
    int count = be32(b, base + ofsId);
    int[] ids = new int[Math.max(0, Math.min(count, 0x100))];
    for (int i = 0; i < ids.length; i++) ids[i] = be16(b, base + ofsId + 4 + i * ITM_ID);
    return ids;
  }

  private static int ownIndex(int[] ids, int id) {
    for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
    return -1;
  }

  /** The archive with entry `itmEntry` replaced by `pack`: offsets, tags and 32-byte alignment rebuilt. */
  private static byte[] rebuild(byte[] b, Arc arc, int itmEntry, byte[] pack) {
    int header = 0x10 + arc.num * 8;
    int[] offsets = new int[arc.num];
    int[] lengths = new int[arc.num];
    int at = align32(header);
    for (int i = 0; i < arc.num; i++) {
      offsets[i] = at;
      lengths[i] = i == itmEntry ? pack.length : arc.end(b, i) - arc.offsets[i];
      at = align32(at + lengths[i]);
    }
    byte[] out = new byte[at];
    put32(out, 0, arc.num);
    put32(out, 4, be32(b, 4));          // REL word: rooms carry 0
    put32(out, 8, 0);
    put32(out, 12, 0);
    for (int i = 0; i < arc.num; i++) {
      put32(out, 0x10 + i * 4, offsets[i]);
      System.arraycopy(b, arc.tagAt[i], out, 0x10 + arc.num * 4 + i * 4, 4);
    }
    for (int i = 0; i < arc.num; i++) {
      byte[] data = i == itmEntry ? pack : b;
      int from = i == itmEntry ? 0 : arc.offsets[i];
      System.arraycopy(data, from, out, offsets[i], lengths[i]);
    }
    return out;
  }

  // ---- the two public steps ------------------------------------------------------------------------------------

  /** The story's stages. St4 is the extra games (Mercenaries, Ada's assignment): its rooms hold items the story
   *  never shows (0x0C, the Mercenaries time hourglass) and are left out of the deal and of the model index. */
  private static final String[] STORY = {"st1", "st2", "st3"};

  private static List<File> sidecars(File root) {
    List<File> files = new ArrayList<>();
    for (String stage : STORY) {
      File[] found = new File(root, "randomizer/" + stage).listFiles();
      if (found == null) continue;
      for (File file : found) if (file.getName().endsWith(".das.raw")) files.add(file);
    }
    return files;
  }

  /**
   * Deals every pool item of the collected rooms anew. Returns the summary the launcher shows and the log keeps.
   */
  static String shuffleAll(File root, long seed) {
    List<File> files = sidecars(root);
    if (files.isEmpty()) return "No collected rooms yet: collect the rooms first";
    Map<Integer, Model> index = modelIndex(files);
    List<Slot> slots = slots(files);
    List<Slot> movable = new ArrayList<>();
    for (Slot slot : slots) if (inPool(slot.id, index)) movable.add(slot);
    if (movable.isEmpty()) return "Nothing to shuffle: no item of the pool in the collected rooms";

    int[] dealt = movable.stream().mapToInt(s -> s.id).toArray();
    long[] state = {seed};
    for (int i = dealt.length - 1; i > 0; i--) {
      int j = below(state, i + 1);
      int swap = dealt[i];
      dealt[i] = dealt[j];
      dealt[j] = swap;
    }
    Map<String, Integer> changed = new HashMap<>();
    for (int i = 0; i < movable.size(); i++) {
      Slot slot = movable.get(i);
      if (dealt[i] != slot.id) changed.put(key(slot.file, slot.at), dealt[i]);
    }

    int rooms = 0, areas = 0, added = 0;
    long grew = 0;
    Sources sources = new Sources();
    StringBuilder mapping = new StringBuilder();
    for (File file : files) {
      try {
        byte[] original = readAll(file);
        Arc arc = Arc.of(original);
        if (arc == null) continue;
        int itaEntry = arc.find(original, "ITA", 0);
        if (itaEntry < 0) continue;
        int itmEntry = arc.find(original, "ITM", 0);
        byte[] patched = original.clone();
        int[] ownAll = itmEntry < 0 ? new int[0] : ownIds(original, arc, itmEntry);
        int base = arc.offsets[itaEntry];
        int count = be16(patched, base + 6);
        int[] needed = new int[count];
        int neededCount = 0;
        boolean touched = false;
        List<String> missing = new ArrayList<>();   // ids the deal moved here that nothing can show
        for (int i = 0; i < count; i++) {
          int at = base + ITA_RECORDS + i * RECORD;
          int want = changed.containsKey(key(file, at)) ? changed.get(key(file, at)) : be16(patched, at + ITEM_ID);
          if (want != be16(patched, at + ITEM_ID)) touched = true;
          mapping.append(file.getParentFile().getName()).append('/').append(file.getName()).append(':').append(at)
              .append(' ').append(be16(patched, at + ITEM_ID)).append("->").append(want).append('\n');
          put16(patched, at + ITEM_ID, want);
          // the room's own pack already carries it, or nothing on the discs can supply its model: leave it alone
          if (ownIndex(ownAll, want) >= 0 || ownIndex(needed, want) >= 0) continue;
          if (!index.containsKey(want)) {
            // only a id the deal brought here is a surprise; an untouched marker (0x1002) needs no model
            if (changed.containsKey(key(file, at)) && missing.size() < 8) {
              missing.add(String.format("0x%02x", want));
            }
            continue;
          }
          needed[neededCount++] = want;
        }
        if (!missing.isEmpty()) {
          Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": no model for " + missing);
        }
        if (!touched) continue;
        // the room's own models first, then whatever its new items need
        int[] all = new int[neededCount + ownAll.length];
        int used = 0;
        for (int id : ownAll) all[used++] = id;
        for (int i = 0; i < neededCount; i++) {
          if (ownIndex(all, needed[i]) >= 0) continue;
          all[used++] = needed[i];
        }
        all = Arrays.copyOf(all, used);
        byte[] pack = itmEntry < 0 ? null : buildPack(original, arc, itmEntry, all, index, sources);
        byte[] out = pack == null ? patched : rebuild(patched, arc, itmEntry, pack);
        Arc after = Arc.of(out);
        int beforeItm = itmEntry < 0 ? 0 : arc.end(original, itmEntry) - arc.offsets[itmEntry];
        int afterItm = itmEntry < 0 ? 0 : after.end(out, itmEntry) - after.offsets[itmEntry];
        if (after == null || after.num != arc.num || after.find(out, "ITA", 0) < 0
            || (itmEntry >= 0 && after.find(out, "ITM", 0) < 0)) {
          Log.i(TAG, file.getName() + ": rebuilt archive does not parse, kept as it was");
          continue;
        }
        if (pack != null) added += afterItm - beforeItm;
        grew += out.length - original.length;
        writeAll(file, out);
        areas += count;
        rooms++;
        Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": " + count + " areas, pack "
            + (itmEntry < 0 ? "none" : beforeItm + " -> " + afterItm + " bytes") + ", room "
            + original.length + " -> " + out.length + " bytes");
      } catch (IOException | RuntimeException e) {
        Log.i(TAG, file.getParentFile().getName() + "/" + file.getName() + ": " + e);
      }
    }
    String fingerprint = fingerprint(mapping.toString());
    String summary = "Shuffled " + areas + " item areas in " + rooms + " rooms (seed " + seed + ", fingerprint "
        + fingerprint + "), models +" + Math.max(0, added) + " bytes, rooms +" + Math.max(0, grew) + " bytes";
    Log.i(TAG, summary);
    return summary;
  }

  private static String key(File file, int at) {
    return file.getAbsolutePath() + ":" + at;
  }

  private static String fingerprint(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return String.format("%02x%02x%02x%02x%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3],
          digest[4], digest[5], digest[6], digest[7]);
    } catch (Exception e) {
      return "?";
    }
  }

  /** Removes the collected rooms, so the next collection writes them as the discs have them again. */
  static String restore(File root) {
    File folder = new File(root, "randomizer");
    int files = 0;
    List<File> all = new ArrayList<>();
    for (String stage : new String[] {"st1", "st2", "st3", "st4"}) {
      File[] found = new File(folder, stage).listFiles();
      if (found == null) continue;
      for (File file : found) if (file.getName().endsWith(".das.raw")) all.add(file);
    }
    for (File file : all) {
      if (file.delete()) files++;
    }
    for (String stage : new String[] {"st1", "st2", "st3", "st4"}) {
      File dir = new File(folder, stage);
      //noinspection ResultOfMethodCallIgnored
      dir.delete();   // only when empty
    }
    //noinspection ResultOfMethodCallIgnored
    folder.delete();
    Log.i(TAG, "restore: removed " + files + " collected rooms");
    return files == 0 ? "There were no collected rooms to remove" : "Removed " + files
        + " rooms. Collect the rooms again for the discs' own items";
  }
}
