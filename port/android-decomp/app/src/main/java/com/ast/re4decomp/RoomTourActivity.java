package com.ast.re4decomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import com.ast.re4decomp.disc.DiscInstaller;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The randomizer's room collection (branch feature/offline-randomizer).
 *
 * A patcher cannot rebuild a room archive offline, because the room data is Yz2, an adaptive arithmetic coder
 * (src/game/yz2code.cpp hands over to the assembly decoder), so the decoded archive has to come from the game
 * itself. This activity therefore starts the game once per room and lets it decode that room: the core boots
 * into the room asked for in room-start.on (runtime/dolphin_os.cpp, the tour boot), and with room-dump.on there
 * it writes the decoded archive beside the room's .das as <name>.raw (runtime/room_raw.cpp). Once the file is
 * there the game's process is ended and the next room starts.
 *
 * It runs in a process of its own (":tour", manifest), the way LauncherRestartActivity does, because the core
 * cannot be restarted inside the process that ran it. Both marker files are removed at the end, so the game
 * starts as usual again; a tour that is interrupted leaves them, and the next run skips the rooms whose .raw
 * already exists instead of starting over.
 */
public final class RoomTourActivity extends Activity {
  /** How long one room may take to load and decode (a slow device on a cold start). */
  private static final int WAIT_MS = 120000;
  private static final int POLL_MS = 250;
  /** After the .raw appears: let the core close the file before its process is ended. */
  private static final int SETTLE_MS = 1500;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    new Thread(this::collect, "room-tour").start();
  }

  private ImportManager imports() {
    return ((Re4Application) getApplication()).imports;
  }

  /** stage << 8 | room, as runtime/dolphin_os.cpp's RE4_START_ROOM has it (r100 -> 0x100, r11a -> 0x11a). */
  private static int roomCode(RoomCatalog.Entry entry) {
    return (entry.stage << 8) | Integer.parseInt(entry.id.substring(2), 16);
  }

  /** The room's archive name as src/game/read.cpp builds it: st<stage>/r<room in 3 hex digits>.das. */
  private static String archiveName(int code) {
    return String.format("st%d/r%03x.das", code >> 8, code);
  }

  /** The decoded sidecar of a room, on either imported disc. */
  private static File sidecar(File root, int code) {
    String name = archiveName(code);
    for (int n = 1; n <= 2; n++) {
      File raw = new File(root, "disc" + n + "/files/" + name + ".raw");
      if (raw.isFile()) return raw;
    }
    return null;
  }

  private static void write(File file, String text) {
    try (FileWriter w = new FileWriter(file)) {
      w.write(text);
    } catch (Exception ignored) {
      // the core simply starts as usual when the marker cannot be written
    }
  }

  private static void remove(File file) {
    try {
      file.delete();
    } catch (Exception ignored) {
    }
  }

  /** Ends the game's process (CoreActivity writes its process id to game.pid at the start). */
  private static void endGame(File pidFile) {
    int pid = 0;
    try {
      String text = new java.util.Scanner(pidFile).useDelimiter("\\A").next().trim();
      pid = Integer.parseInt(text);
    } catch (Exception e) {
      return;
    }
    if (pid <= 0 || pid == Process.myPid()) return;
    Process.killProcess(pid);
    File proc = new File("/proc/" + pid);
    for (int i = 0; i < 600 && proc.exists(); i++) SystemClock.sleep(20);
    remove(pidFile);
  }

  private void collect() {
    ImportManager imports = imports();
    File root = imports == null ? null : imports.root;
    if (root == null) {
      finishTour();
      return;
    }

    List<Integer> rooms = new ArrayList<>();
    for (int n = 1; n <= 2; n++) {
      File disc = new File(root, "disc" + n);
      if (disc.isDirectory() && DiscInstaller.ready(disc, n)) {
        try (InputStream in = imports.roomCatalog(disc)) {
          RoomCatalog catalog = new RoomCatalog(new InputStreamReader(in));
          for (RoomCatalog.Entry entry : catalog.filter(RoomCatalog.CAMPAIGN)) rooms.add(roomCode(entry));
        } catch (Exception ignored) {
          // a disc whose catalogue cannot be read is left out of the tour
        }
      }
    }

    File dumpOn = new File(root, "room-dump.on");
    File startOn = new File(root, "room-start.on");
    File pidFile = new File(root, "game.pid");
    write(dumpOn, "1");

    int collected = 0, skipped = 0;
    for (int code : rooms) {
      if (sidecar(root, code) != null) {
        skipped++;
        continue;
      }
      write(startOn, String.format("%x", code));
      remove(pidFile);
      Intent game = new Intent(this, CoreActivity.class);
      game.putExtra("coreSettings", getIntent().getStringExtra("coreSettings"));
      game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(game);
      long until = SystemClock.elapsedRealtime() + WAIT_MS;
      while (sidecar(root, code) == null && SystemClock.elapsedRealtime() < until) {
        SystemClock.sleep(POLL_MS);
      }
      SystemClock.sleep(SETTLE_MS);
      endGame(pidFile);
      if (sidecar(root, code) != null) collected++;
    }

    remove(dumpOn);
    remove(startOn);
    android.util.Log.i("RoomTour", "concluded: collected " + collected + ", already there " + skipped
        + ", of " + rooms.size() + " rooms");
    finishTour();
  }

  private void finishTour() {
    startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.SHOW_LAUNCHER, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    finish();
    Runtime.getRuntime().exit(0);
  }
}
