package com.ast.re4decomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.ast.re4decomp.disc.DiscInstaller;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The randomizer's room collection (branch feature/offline-randomizer).
 *
 * A patcher cannot rebuild a room archive offline, because the room data is Yz2, an adaptive arithmetic coder
 * (src/game/yz2code.cpp hands over to the assembly decoder). So the decoded archive comes from a decoder that
 * agrees with the game: the app's own native one (NativeBridge.decodeRoom, android_bridge.cpp), whose output is
 * checked against the original PowerPC decoder's fingerprints (RoomCheckActivity; the catalogues carry every
 * room's decoded size and hash).
 *
 * Decoding every room of the imported discs into the sidecar folder the core reads
 * (randomizer/stN/rNNN.das.raw, runtime/room_raw.cpp) leaves the disc files untouched, so the patcher later
 * edits plain bytes while the discs keep their bytes and their hashes. Rooms already there are skipped, so an
 * interrupted collection resumes.
 *
 * Runs in a process of its own (":tour", manifest) to keep a long collection away from the launcher.
 */
public final class RoomTourActivity extends Activity {
  private static final String TAG = "RoomTour";
  /** Room archives as the catalogues name them ("St1/r100.das"). */
  private static final Pattern ROOM = Pattern.compile("St[1-4]/r[1-4][0-9a-f]{2}\\.das");

  /** One room of an imported disc. */
  private static final class Room {
    final int disc, stage;
    final String id, path;
    Room(int disc, String path, int stage, String id) {
      this.disc = disc; this.path = path; this.stage = stage; this.id = id;
    }
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    new Thread(this::collect, "room-collect").start();
  }

  /**
   * The rooms of one disc's catalogue. Both shapes are accepted: files.tsv (disc, path, bytes, sha256) and
   * rooms.tsv (disc, path, decoded size, decoded sha256, source sha256) — which one arrives depends on the
   * import mode (ImportManager.roomCatalog, RoomFileCheck reads both as well). Only the disc and the path
   * are needed here.
   */
  private static List<Room> rooms(InputStream in) throws IOException {
    List<Room> list = new ArrayList<>();
    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] row = line.split("\t", -1);
      if (row.length < 2) continue;
      // files.tsv names the paths below files/ ("files/St1/r100.das"), rooms.tsv without that prefix
      String path = row[1].startsWith("files/") ? row[1].substring(6) : row[1];
      if (!path.matches(ROOM.pattern())) continue;
      int disc;
      try {
        disc = Integer.parseInt(row[0]);
      } catch (NumberFormatException e) {
        continue;
      }
      String id = row[1].substring(4, 8);
      int stage = Integer.parseInt(id.substring(1, 2));
      list.add(new Room(disc, row[1], stage, id));
    }
    return list;
  }

  /** The sidecar of a room as runtime/room_raw.cpp looks for it: st<stage>/r<room>.das.raw. */
  private static File sidecar(File root, Room room) {
    return new File(root, "randomizer/st" + room.stage + "/" + room.id + ".das.raw");
  }

  private static void remove(File file) {
    try {
      file.delete();
    } catch (Exception ignored) {
    }
  }

  private void collect() {
    ImportManager imports = ((Re4Application) getApplication()).imports;
    File root = imports == null ? null : imports.root;
    if (root == null) {
      finish("No data folder");
      return;
    }
    // markers of the earlier, game-boot based collection: no longer used, so the game starts as usual
    remove(new File(root, "room-dump.on"));
    remove(new File(root, "room-start.on"));
    remove(new File(root, "game.pid"));

    List<Room> rooms = new ArrayList<>();
    for (int n = 1; n <= 2; n++) {
      File disc = new File(root, "disc" + n);
      if (!disc.isDirectory() || !DiscInstaller.ready(disc, n)) continue;
      try (InputStream in = imports.roomCatalog(disc)) {
        for (Room room : rooms(in)) if (room.disc == n) rooms.add(room);
      } catch (Exception e) {
        Log.i(TAG, "disc " + n + ": its room catalogue could not be read: " + e);
      }
    }
    if (rooms.isEmpty()) {
      finish("No imported disc with rooms found");
      return;
    }

    int done = 0, skipped = 0, failed = 0;
    for (Room room : rooms) {
      File out = sidecar(root, room);
      if (out.isFile() && out.length() > 0) {
        skipped++;
        continue;
      }
      File source = new File(root, "disc" + room.disc + "/files/" + room.path);
      if (!source.isFile()) {
        Log.i(TAG, room.id + ": " + source + " is missing");
        failed++;
        continue;
      }
      File parent = out.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
        Log.i(TAG, room.id + ": cannot make " + parent);
        failed++;
        continue;
      }
      try {
        NativeBridge.decodeRoom(source.getAbsolutePath(), out.getAbsolutePath());
        done++;
        if (done % 10 == 0) Log.i(TAG, "decoded " + done + " rooms so far (" + room.id + ")");
      } catch (Exception | LinkageError e) {
        Log.i(TAG, room.id + ": decode failed: " + e);
        remove(out);
        failed++;
      }
    }
    Log.i(TAG, "done: decoded " + done + ", already there " + skipped + ", failed " + failed
        + " of " + rooms.size() + " rooms");
    finish("Room collection: " + done + " decoded, " + skipped + " already there"
        + (failed > 0 ? ", " + failed + " failed" : ""));
  }

  private void finish(String message) {
    new Handler(Looper.getMainLooper()).post(() -> {
      Toast.makeText(this, message, Toast.LENGTH_LONG).show();
      startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.SHOW_LAUNCHER, true)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      finish();
    });
  }
}
