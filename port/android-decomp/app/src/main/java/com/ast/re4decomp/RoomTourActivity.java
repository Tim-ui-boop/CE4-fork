package com.ast.re4decomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import com.ast.re4decomp.disc.DiscInstaller;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The randomizer's room collection (branch feature/offline-randomizer).
 *
 * A patcher cannot rebuild a room archive offline, because the room data is Yz2, an adaptive arithmetic coder
 * (src/game/yz2code.cpp hands over to the assembly decoder). So the decoded archive has to come from a decoder
 * that agrees with the game: the app's own native one (NativeBridge.decodeRoom, android_bridge.cpp), whose
 * output is checked against the original PowerPC decoder's fingerprints (RoomCheckActivity, rooms.tsv carries
 * the decoded size and hash of every room).
 *
 * This activity decodes every room of the imported discs into the sidecar folder the core reads
 * (randomizer/stN/rNNN.das.raw, runtime/room_raw.cpp), so the game itself never has to start for the
 * collection. Rooms that are already there are skipped, so an interrupted collection resumes, and the
 * patcher edits plain bytes afterwards — the disc files keep their bytes and their hashes.
 *
 * It runs in a process of its own (":tour", manifest) to keep a long collection away from the launcher.
 */
public final class RoomTourActivity extends Activity {
  private static final String TAG = "RoomTour";

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    new Thread(this::collect, "room-collect").start();
  }

  private ImportManager imports() {
    return ((Re4Application) getApplication()).imports;
  }

  /** The sidecar of a room as runtime/room_raw.cpp looks for it: st<stage>/r<room>.das.raw. */
  private static File sidecar(File root, RoomCatalog.Entry entry) {
    return new File(root, "randomizer/st" + entry.stage + "/" + entry.id + ".das.raw");
  }

  private static void remove(File file) {
    try {
      file.delete();
    } catch (Exception ignored) {
    }
  }

  private void collect() {
    ImportManager imports = imports();
    File root = imports == null ? null : imports.root;
    if (root == null) {
      finish("No data folder");
      return;
    }
    // markers of the earlier, game-boot based collection: no longer used, so the game starts as usual
    remove(new File(root, "room-dump.on"));
    remove(new File(root, "room-start.on"));
    remove(new File(root, "game.pid"));

    List<RoomCatalog.Entry> rooms = new ArrayList<>();
    for (int n = 1; n <= 2; n++) {
      File disc = new File(root, "disc" + n);
      if (disc.isDirectory() && DiscInstaller.ready(disc, n)) {
        try (InputStream in = imports.roomCatalog(disc)) {
          rooms.addAll(new RoomCatalog(new InputStreamReader(in)).filter(RoomCatalog.CAMPAIGN));
        } catch (Exception e) {
          Log.i(TAG, "disc " + n + ": its room catalogue could not be read: " + e);
        }
      }
    }
    if (rooms.isEmpty()) {
      finish("No imported disc with rooms found");
      return;
    }

    int done = 0, skipped = 0, failed = 0;
    for (RoomCatalog.Entry entry : rooms) {
      File out = sidecar(root, entry);
      if (out.isFile() && out.length() > 0) {
        skipped++;
        continue;
      }
      File source = new File(root, "disc" + entry.disc + "/files/" + entry.path);
      if (!source.isFile()) {
        Log.i(TAG, entry.id + ": " + source + " is missing");
        failed++;
        continue;
      }
      File parent = out.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
        Log.i(TAG, entry.id + ": cannot make " + parent);
        failed++;
        continue;
      }
      try {
        NativeBridge.decodeRoom(source.getAbsolutePath(), out.getAbsolutePath());
        done++;
        if (done % 10 == 0) Log.i(TAG, "decoded " + done + " rooms so far (" + entry.id + ")");
      } catch (Exception | LinkageError e) {
        Log.i(TAG, entry.id + ": decode failed: " + e);
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
