package com.ast.re4decomp;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import com.ast.re4decomp.disc.DiscInstaller;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One action prepares the verified room bundle and opens gameplay. */
public final class MainActivity extends Activity implements ImportManager.Listener {
  /** Opened from the game (LauncherRestartActivity: "Import other discs"): the launcher stays, the game does not
   *  start at once with the discs there; "Start game" starts it. */
  static final String SHOW_LAUNCHER = "showLauncher";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean destroyed;
  /** The seed of the item shuffle ("Shuffle all room items"), empty for a new one each time. */
  private android.widget.EditText seedField;
  /** The door shuffle's cross-stage switch (branch feature/offline-randomizer). */
  private android.widget.CheckBox crossStage;
  /** Roll a new seed on every shuffle click, so a test run does not need the number typed by hand. */
  private android.widget.CheckBox newSeedEach;
  private boolean loading, startupShown;
  /** Core flavor: the original game core runs the game (CoreActivity). */
  private boolean core;
  private ImportManager imports;
  private TextView status, discs, roomNote, disc1State, disc2State;
  private Button disc1, disc2, cancel, open, start;
  private LinearLayout importPanel, roomBrowser;
  private Spinner category, rooms, mode;
  private RoomCatalog catalog;
  private List<RoomCatalog.Entry> shown = Collections.emptyList();
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); startupShown=state!=null&&state.getBoolean("startupShown",false); imports = ((Re4Application)getApplication()).imports;
    if (getIntent().getBooleanExtra(SHOW_LAUNCHER, false)) startupShown = true;
    // the randomizer's room collection is started from this screen, and after an import the launcher must stay
    // reachable: the start screen always comes first instead of straight into the game
    // (branch feature/offline-randomizer; undo this line to get the automatic start back)
    startupShown = true;
    core = getResources().getBoolean(R.bool.core_mode);
    LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
    int p = dp(20); body.setPadding(p, core ? dp(12) : p, p, core ? dp(12) : p);
    if (core) {
      // the launcher of the core mode (project owner, 17.10.2026): title and author centred, a line,
      // Start game, then the disc import under a centred heading between lines (the controls left
      // the launcher, 27.10.2026; a swap of A/B and X/Y saved before stays in force)
      centered(body, getString(R.string.core_title), 28, true, 0);
      centered(body, getString(R.string.core_author), 15, false, dp(4));
      // under the name (CubeEvil4 since 18.10.2026): own discs needed, not the original's maker
      centered(body, getString(R.string.core_notice), 12, false, dp(2));
      line(body, dp(10));
      button(body, "Randomizer", this::openRandomizer);
      start = button(body, getString(R.string.core_start), this::startOriginalTitle);
      heading(body, getString(R.string.core_import_heading));
    } else {
      // the variant "classic" (own game modules): its name and real version, so it is not taken for
      // CubeEvil4 (the title said 0.37.0 for every version until 19.10.2026)
      TextView title = new TextView(this); title.setText(getString(R.string.app_title) + " · " + versionName()); title.setTextSize(25); body.addView(title);
      TextView note = new TextView(this); note.setText(R.string.browser_notice);
      note.setPadding(0, dp(8), 0, dp(12)); body.addView(note);
      button(body, "Randomizer", this::openRandomizer);
      start = button(body,"Start game",this::startOriginalTitle);
    }
    // The room browser opens the app's own game modules; the core mode has none.
    Button browse = button(body, getString(R.string.launch_rooms), () -> {
      roomBrowser.setVisibility(roomBrowser.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
      if (roomBrowser.getVisibility() == View.VISIBLE) rooms.requestFocus();
    });
    if (core) browse.setVisibility(View.GONE);
    roomBrowser = new LinearLayout(this); roomBrowser.setOrientation(LinearLayout.VERTICAL); roomBrowser.setVisibility(View.GONE); body.addView(roomBrowser);
    category = spinner(roomBrowser, Arrays.asList(getString(R.string.category_campaign), getString(R.string.category_extras), getString(R.string.category_all)));
    rooms = spinner(roomBrowser, Collections.emptyList()); mode = spinner(roomBrowser, Collections.singletonList(getString(R.string.mode_explore)));
    roomNote = new TextView(this); roomNote.setPadding(0, dp(8), 0, dp(8)); roomBrowser.addView(roomNote);
    open = button(roomBrowser, getString(R.string.room_open_selected), this::openSelected);
    status = new TextView(this); status.setPadding(0, dp(8), 0, dp(8)); body.addView(status);
    // the menu's only entry in the core mode was the disc import, which its launcher shows anyway
    if (!core) button(body, getString(R.string.browser_menu), this::showMenu);
    importPanel = new LinearLayout(this); importPanel.setOrientation(LinearLayout.VERTICAL);
    importPanel.setVisibility(core ? View.VISIBLE : View.GONE); body.addView(importPanel);
    discs = new TextView(this); importPanel.addView(discs);
    if (core) {
      // the core launcher: each import button over the left half, its disc's state right of it
      discs.setVisibility(View.GONE); status.setGravity(Gravity.CENTER_HORIZONTAL);
      disc1State = new TextView(this); disc2State = new TextView(this);
      disc1 = halfButton(importPanel, getString(R.string.import_disc1), () -> choose(1), disc1State);
      disc2 = halfButton(importPanel, getString(R.string.import_disc2), () -> choose(2), disc2State);
      cancel = halfButton(importPanel, getString(R.string.cancel_import), imports::cancel, null);
    } else {
      disc1 = button(importPanel, getString(R.string.import_disc1), () -> choose(1));
      disc2 = button(importPanel, getString(R.string.import_disc2), () -> choose(2));
      cancel = button(importPanel, getString(R.string.cancel_import), imports::cancel);
    }
    try (Reader reader = new InputStreamReader(getAssets().open("rooms.tsv"), StandardCharsets.UTF_8)) { catalog = new RoomCatalog(reader); }
    catch (IOException e) { status.setText(e.getMessage()); }
    category.setOnItemSelectedListener(selected(this::fillRooms)); rooms.setOnItemSelectedListener(selected(this::roomChanged));
    mode.setOnItemSelectedListener(selected(this::updateOpenState)); fillRooms();
    launcher = new ScrollView(this); launcher.addView(body); setContentView(launcher);
    if (state != null && state.getBoolean("randomizer")) {
      openRandomizer();
      pairs.setChecked(state.getBoolean("pairs"));
      newSeedEach.setChecked(state.getBoolean("fresh", true));
      seedField.setText(state.getString("seed", ""));
      crossStage.setChecked(state.getBoolean("cross", true));
      doorMix.setChecked(state.getBoolean("doorMix", true));
    }
    findViewById(android.R.id.content).setOnApplyWindowInsetsListener((v, insets) -> {
      v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
    });
  }
  private ScrollView launcher, setup;
  private RadioButton pairs;
  private CheckBox doorMix;
  private android.window.OnBackInvokedCallback setupBack;
  private TextView outcome;
  private static volatile boolean writing;
  private static volatile String lastResult = "";

  private void openRandomizer() {
    if (setup == null) buildRandomizer();
    setContentView(setup);
    if (android.os.Build.VERSION.SDK_INT >= 33 && setupBack == null) {
      setupBack = this::closeRandomizer;
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, setupBack);
    }
    refreshResult();
  }

  private void closeRandomizer() {
    if (writing) { Toast.makeText(this, "Wait for the current shuffle to finish.", Toast.LENGTH_SHORT).show(); return; }
    if (android.os.Build.VERSION.SDK_INT >= 33 && setupBack != null) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(setupBack);
      setupBack = null;
    }
    setContentView(launcher);
  }

  @Override public void onBackPressed() {
    if (setup != null && setup.isAttachedToWindow()) closeRandomizer(); else super.onBackPressed();
  }

  private void buildRandomizer() {
    setup = new ScrollView(this);
    LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(20), dp(12), dp(20), dp(24)); setup.addView(page);
    button(page, "Back to launcher", this::closeRandomizer);
    centered(page, "Randomizer", 28, true, dp(12));
    heading(page, "Room collection");
    note(page, "Collect editable room copies from your imported discs. Existing copies stay as they are.");
    action(page, "Collect rooms", this::startRoomTour);
    heading(page, "Route layout");
    RadioGroup layouts = new RadioGroup(this); page.addView(layouts);
    RadioButton path = new RadioButton(this); pairs = new RadioButton(this);
    for (RadioButton choice : new RadioButton[]{path, pairs}) { choice.setId(View.generateViewId()); layouts.addView(choice); }
    path.setText("Main path with branches"); pairs.setText("Swap door pairs"); path.setChecked(true);
    crossStage = new CheckBox(this); crossStage.setText("Mix village, castle and island");
    crossStage.setChecked(true); page.addView(crossStage); crossStage.setVisibility(View.GONE);
    layouts.setOnCheckedChangeListener((g, id) -> crossStage.setVisibility(pairs.isChecked() ? View.VISIBLE : View.GONE));
    newSeedEach = new CheckBox(this); newSeedEach.setText("New random seed"); newSeedEach.setChecked(true); page.addView(newSeedEach);
    seedField = new EditText(this); seedField.setSingleLine(true); seedField.setHint("Seed to reuse");
    seedField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
    page.addView(seedField); seedField.setVisibility(View.GONE);
    newSeedEach.setOnCheckedChangeListener((b, on) -> seedField.setVisibility(on ? View.GONE : View.VISIBLE));
    action(page, "Shuffle items and doors", () -> { if (pairs.isChecked()) dealAll(); else dealSpine(); });
    outcome = note(page, ""); outcome.setTextIsSelectable(true); outcome.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    note(page, "The route check follows doors and keys. It does not test drops, lifts or story events.");
    if (core) note(page, "Enemy settings are separate: open Cheats & Mods → Game during play.");
    LinearLayout extra = new LinearLayout(this); extra.setOrientation(LinearLayout.VERTICAL);
    Button toggle = button(page, "Show advanced options", () -> {});
    toggle.setOnClickListener(v -> { boolean show = extra.getVisibility() != View.VISIBLE; extra.setVisibility(show ? View.VISIBLE : View.GONE); toggle.setText(show ? "Hide advanced options" : "Show advanced options"); });
    extra.setVisibility(View.GONE); page.addView(extra);
    heading(extra, "Individual shuffles");
    action(extra, "Shuffle items only", this::shuffleRoomItems);
    doorMix = new CheckBox(this); doorMix.setText("Mix regions for doors and keys"); doorMix.setChecked(true); extra.addView(doorMix);
    action(extra, "Shuffle doors and keys", () -> shuffleDoors(doorMix.isChecked()));
    heading(extra, "Room exceptions");
    action(extra, "Keep original doors in selected rooms", () -> roomPinsDialog(RoomPins.DOORS));
    action(extra, "Keep original items in selected rooms", () -> roomPinsDialog(RoomPins.ITEMS));
    heading(extra, "Reports"); action(extra, "Door and route report", this::showDoorLog); action(extra, "Item placement report", this::showShuffleLog);
    if (core) {
      heading(extra, "Opening forest playtest");
      action(extra, "Seed 100: enemy, items and exit…", () -> {
        boolean active = ForestTrial.marker(imports.root).exists();
        new AlertDialog.Builder(this).setTitle(active ? "Turn off the forest trial?" : "Test all three changes in the forest?")
            .setMessage(active ? "Your existing room collection will be used on the next game start." : "This changes an ambush Ganado, exchanges the two crow rewards and connects the exit to r102. Start a new game with item and enemy mods off. Your shuffled rooms are ignored, not deleted, during the trial.")
            .setNegativeButton("Cancel", null).setPositiveButton(active ? "Turn off" : "Enable trial", (d,w) -> runWriter("Forest trial", () -> ForestTrial.toggle(imports.root, NativeBridge::decodeRoom))).show();
      });
    }
    heading(extra, "Manage collected rooms"); action(extra, "Delete collected rooms…", this::restoreRoomItems);
  }

  private TextView note(LinearLayout page, String message) {
    TextView t = new TextView(this); t.setText(message); t.setPadding(0, dp(6), 0, dp(6)); page.addView(t); return t;
  }

  private void action(LinearLayout page, String label, Runnable run) {
    Button b = button(page, label, () -> {
      if (writing) { refreshResult(); return; }
      if (!gameReady() || imports.busy || loading) {
        closeRandomizer(); importPanel.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Finish importing the required discs first.", Toast.LENGTH_LONG).show(); return;
      }
      run.run();
    }); b.setAllCaps(false);
  }

  private final Runnable resultRefresh = this::refreshResult;
  private void refreshResult() {
    main.removeCallbacks(resultRefresh);
    if (destroyed || outcome == null) return;
    boolean busy = writing;
    String text = busy ? "Working on the collected rooms…" : lastResult;
    outcome.setText(text); outcome.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    if (busy) main.postDelayed(resultRefresh, 350);
  }

  private void runWriter(String label, java.util.concurrent.Callable<String> operation) {
    if (writing) return;
    writing = true; refreshResult();
    new Thread(() -> {
      try { lastResult = label + "\n" + operation.call(); }
      catch (Exception | OutOfMemoryError e) { lastResult = label + " stopped: " + e; }
      finally { writing = false; }
    }, "randomizer-write").start();
  }

  /** The randomizer's first patch (branch feature/offline-randomizer): exchange the items lying in the rooms. */
  private void swapRoomItems() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    final java.io.File root = imports.root;
    new Thread(() -> {
      final String result = SidecarSwap.swapAll(root);
      runOnUiThread(() -> android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show());
    }, "sidecar-swap").start();
  }
  private Long seedForThisClick() {
    long seed;
    if (newSeedEach.isChecked()) seed = new Random().nextLong();
    else try { seed = Long.parseLong(seedField.getText().toString().trim()); }
    catch (NumberFormatException e) { seedField.setError("Enter a signed whole number or choose a new random seed."); return null; }
    seedField.setText(Long.toString(seed)); return seed;
  }

  private void shuffleRoomItems() {
    final File root = imports.root; final Long seed = seedForThisClick(); if (seed == null) return;
    runWriter("Items · seed " + seed, () -> ItemShuffle.shuffleAll(root, seed));
  }
  private void dealSpine() {
    final File root = imports.root; final Long seed = seedForThisClick(); if (seed == null) return;
    runWriter("Main path · seed " + seed, () -> DoorShuffle.dealSpine(root, seed, true));
  }
  private void dealAll() {
    final File root = imports.root; final boolean mix = crossStage.isChecked(); final Long seed = seedForThisClick(); if (seed == null) return;
    runWriter("Door pairs · seed " + seed, () -> DoorShuffle.dealAll(root, seed, mix));
  }
  private void shuffleDoors(boolean mix) {
    final File root = imports.root; final Long seed = seedForThisClick(); if (seed == null) return;
    runWriter("Doors and keys · seed " + seed, () -> DoorShuffle.shuffleAll(root, seed, mix));
  }

  /** The PC-style shuffle: a main path first, branches hung on it, doors written afterwards. */
  private void shuffleDoorsSpine() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    final File root = imports.root;
    final boolean cross = crossStage != null && crossStage.isChecked();
    final long seed = seedForThisClick();
    new Thread(() -> {
      String result;
      try {
        result = DoorShuffle.shuffleWithSpine(root, seed, cross);
      } catch (RuntimeException | OutOfMemoryError e) {
        result = "The spine shuffle stopped: " + e;
      }
      final String shown = result;
      runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
    }, "door-spine").start();
  }

  /** Tick the rooms a shuffle must leave exactly as the disc has them; the pins live in randomizer/pins.txt. */
  private void roomPinsDialog(String kind) {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    final String what = RoomPins.DOORS.equals(kind) ? "doors" : "items";
    final File root = imports.root;
    new Thread(() -> {
      final java.util.List<String> rooms = RoomPins.rooms(root);
      final java.util.Set<String> pinned = RoomPins.load(root, kind);
      runOnUiThread(() -> {
        if (rooms.isEmpty()) {
          Toast.makeText(this, "Collect the rooms for the randomizer first", Toast.LENGTH_LONG).show();
          return;
        }
        final boolean[] checked = new boolean[rooms.size()];
        for (int i = 0; i < rooms.size(); i++) checked[i] = pinned.contains(rooms.get(i));
        new android.app.AlertDialog.Builder(this)
            .setTitle("Rooms kept vanilla: " + what + " (" + pinned.size() + " ticked)")
            .setMultiChoiceItems(rooms.toArray(new String[0]), checked,
                (dialog, which, on) -> checked[which] = on)
            .setPositiveButton("Save", (dialog, which) -> {
              java.util.Set<String> now = new java.util.TreeSet<>();
              for (int i = 0; i < rooms.size(); i++) if (checked[i]) now.add(rooms.get(i));
              RoomPins.save(root, kind, now);
              Toast.makeText(this, now.size() + " room(s) kept vanilla for " + what,
                  Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Clear all", (dialog, which) -> {
              RoomPins.save(root, kind, new java.util.TreeSet<>());
              Toast.makeText(this, "nothing kept vanilla for " + what, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
      });
    }, "room-pins").start();
  }

  /** Every door as it now leads, plus where the progression items were put. */
  private void showDoorLog() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    android.widget.TextView text = new android.widget.TextView(this);
    text.setText(DoorShuffle.readLog(imports.root));
    text.setTextIsSelectable(true);
    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
    text.setTypeface(android.graphics.Typeface.MONOSPACE);
    text.setPadding(dp(12), dp(8), dp(12), dp(8));
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(text);
    new android.app.AlertDialog.Builder(this).setTitle("Door shuffle log").setView(scroll)
        .setPositiveButton("Close", null).show();
  }

  /** The readable log of the last shuffle: every room, every changed item area, where it is and what it became. */
  private void showShuffleLog() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    android.widget.TextView text = new android.widget.TextView(this);
    text.setText(ItemShuffle.readLog(imports.root));
    text.setTextIsSelectable(true);
    text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
    text.setTypeface(android.graphics.Typeface.MONOSPACE);
    text.setPadding(dp(12), dp(8), dp(12), dp(8));
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.addView(text);
    new android.app.AlertDialog.Builder(this).setTitle("Item shuffle log").setView(scroll)
        .setPositiveButton("Close", null).show();
  }

  /** Deletes the collected rooms, so the next collection writes the discs' own items again. */
  private void restoreRoomItems() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    new android.app.AlertDialog.Builder(this)
        .setTitle("Delete collected rooms?")
        .setMessage("This removes the editable room copies and their changes. Collect the rooms again to rebuild them. Imported discs and saves stay untouched.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Delete copies", (dialog, which) -> runWriter("Room collection", () -> ItemShuffle.restore(imports.root)))
        .show();
  }

  private void startRoomTour() {
    if (!gameReady()) { importPanel.setVisibility(View.VISIBLE); return; }
    startActivity(new Intent(this, RoomTourActivity.class));
  }
  private void startOriginalTitle(){if(!writing&&!loading&&!imports.busy&&gameReady()){startupShown=true;Intent start=new Intent(this,core?CoreActivity.class:StartupActivity.class);if(core)start.putExtra("coreSettings",getIntent().getStringExtra("coreSettings"));startActivity(start);}else importPanel.setVisibility(View.VISIBLE);}
  @Override protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("startupShown", startupShown);
    if (setup != null && setup.isAttachedToWindow()) {
      state.putBoolean("randomizer", true); state.putBoolean("pairs", pairs.isChecked());
      state.putBoolean("fresh", newSeedEach.isChecked()); state.putString("seed", seedField.getText().toString());
      state.putBoolean("cross", crossStage.isChecked()); state.putBoolean("doorMix", doorMix.isChecked());
    }
    super.onSaveInstanceState(state);
  }
  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
  private String versionName() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
    catch (android.content.pm.PackageManager.NameNotFoundException e) { return "?"; }
  }
  /** A centred line of text (the core launcher). */
  private TextView centered(LinearLayout body, String text, int sp, boolean bold, int top) {
    TextView t = new TextView(this); t.setText(text); t.setTextSize(sp); t.setGravity(Gravity.CENTER_HORIZONTAL);
    if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    t.setPadding(0, top, 0, 0); body.addView(t); return t;
  }
  /** A thin line in the text colour (the core launcher's separators). */
  private View rule(int color) {
    View v = new View(this); v.setBackgroundColor((color & 0x00FFFFFF) | 0x60000000); return v;
  }
  private void line(LinearLayout body, int margin) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
    lp.topMargin = margin; lp.bottomMargin = margin; body.addView(rule(new TextView(this).getCurrentTextColor()), lp);
  }
  /** A centred heading with a line on its left and right. */
  private void heading(LinearLayout body, String text) {
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    TextView t = new TextView(this); t.setText(text); t.setTextSize(16); t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    t.setGravity(Gravity.CENTER); t.setPadding(dp(12), 0, dp(12), 0);
    LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(0, Math.max(1, dp(1)), 1f);
    LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    row.addView(rule(t.getCurrentTextColor()), side); row.addView(t, mid); row.addView(rule(t.getCurrentTextColor()), new LinearLayout.LayoutParams(side));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(14); lp.bottomMargin = dp(2); body.addView(row, lp);
  }
  /** A button over the left half of a row, `beside` (or nothing) in the right half. */
  private Button halfButton(LinearLayout body, String label, Runnable action, TextView beside) {
    LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
    Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run());
    row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    View right = beside != null ? beside : new View(this);
    if (beside != null) beside.setPadding(dp(16), 0, 0, 0);
    row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    body.addView(row); return b;
  }
  private Button button(LinearLayout body, String label, Runnable action) {
    Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); body.addView(b); return b;
  }
  private Spinner spinner(LinearLayout body, List<String> labels) {
    Spinner s = new Spinner(this); body.addView(s); setChoices(s, labels); return s;
  }
  private void setChoices(Spinner spinner, List<String> labels) {
    ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
  }
  private AdapterView.OnItemSelectedListener selected(Runnable action) {
    return new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) { action.run(); }
      @Override public void onNothingSelected(AdapterView<?> p) { updateOpenState(); }
    };
  }
  private RoomCatalog.Entry selectedRoom() {
    int index = rooms.getSelectedItemPosition(); return index >= 0 && index < shown.size() ? shown.get(index) : null;
  }
  private void fillRooms() {
    if (catalog == null) { updateOpenState(); return; }
    String last = getPreferences(MODE_PRIVATE).getString("room", "r100");
    shown = catalog.filter(Math.max(0, category.getSelectedItemPosition())); List<String> labels = new ArrayList<>(); int selected = 0;
    for (int i = 0; i < shown.size(); ++i) { labels.add(shown.get(i).label()); if (shown.get(i).id.equals(last)) selected = i; }
    setChoices(rooms, labels); rooms.setSelection(selected); roomChanged();
  }
  private void roomChanged() {
    RoomCatalog.Entry entry = selectedRoom();
    setChoices(mode, entry != null && entry.combatTest() ? Arrays.asList(getString(R.string.mode_explore), getString(R.string.mode_combat_test))
        : Collections.singletonList(getString(entry != null && !entry.walkable() && entry.explorable() ? R.string.mode_scene_only : R.string.mode_explore)));
    updateOpenState();
  }
  /** The game may start: Disc 1, and in the core mode both discs (project owner, 17.10.2026). */
  private boolean gameReady() {
    // core mode: both discs of one release (port/docs/retail-eu-plan.md, retail-us-plan.md, retail-jp-plan.md:
    // debug build, European, US, Japanese or Korean discs)
    return discReady(1) && (!core || (discReady(2) && DiscInstaller.sameRelease(imports.root)));
  }
  /** "ready (European release)" ... in the core launcher, "missing" without the disc. */
  private String discState(int number) {
    if (!discReady(number)) return getString(R.string.missing);
    return getString(R.string.core_disc_ready, ImportManager.releaseName(DiscInstaller.release(new File(imports.root, "disc" + number))));
  }
  private boolean discReady(int number) {
    if (imports.root == null) return false;
    File disc = new File(imports.root, "disc" + number);
    // the core mounts the disc from sys/fst.bin: older imports must be repeated
    return core ? DiscInstaller.coreReady(disc, number) : DiscInstaller.ready(disc, number);
  }
  private void updateOpenState() {
    if (open == null) return;
    RoomCatalog.Entry entry = selectedRoom();
    boolean available = entry != null && entry.explorable() && discReady(1) && (discReady(entry.disc) ||
        (discReady(2) && new File(imports.root, "disc2/files/" + entry.path).isFile()));
    roomNote.setText(entry == null ? "" : !entry.explorable() ? getString(R.string.browser_room_unsupported)
        : !discReady(1) ? getString(R.string.browser_missing_disc, 1)
        : !available ? getString(R.string.browser_missing_disc, entry.disc)
        : !entry.walkable() ? getString(R.string.browser_room_dynamic_floor)
        : getString(R.string.browser_room_note, entry.area(), entry.id));
    open.setEnabled(available && !loading && !imports.busy); category.setEnabled(!loading && !imports.busy); rooms.setEnabled(!loading && !imports.busy);
    mode.setEnabled(!loading && !imports.busy && entry != null && entry.combatTest());
  }
  private void openSelected() {
    RoomCatalog.Entry entry = selectedRoom(); if (entry == null || !entry.explorable() || loading || imports.busy) return;
    openRoom(entry, mode.getSelectedItemPosition() != 1 || !entry.combatTest(), 0);
  }
  private void openTest(String id, boolean explore, int profile) {
    if (catalog == null || loading || imports.busy || !discReady(1)) return;
    for (RoomCatalog.Entry entry : catalog.filter(2)) if (id.equals(entry.id)) { openRoom(entry, explore, profile); return; }
    status.setText(getString(R.string.browser_load_error, "Test room is missing from the catalog"));
  }
  private void openRoom(RoomCatalog.Entry entry, final boolean explore, final int profile) {
    final File root = imports.root;
    loading = true; updateOpenState(); status.setText(getString(R.string.browser_loading, entry.id));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); getPreferences(MODE_PRIVATE).edit().putString("room", entry.id).apply();
    worker.execute(() -> {
      File file = null; String error = null;
      try (Reader rows = new InputStreamReader(getAssets().open("rooms.tsv"), StandardCharsets.UTF_8);
           Reader blocks = new InputStreamReader(getAssets().open("blocks.tsv"), StandardCharsets.UTF_8)) {
        RoomRepository repository = new RoomRepository(root, getCacheDir(), rows, blocks,
            (source, output) -> NativeBridge.decodeRoom(source.getAbsolutePath(), output.getAbsolutePath()));
        file = repository.prepare(entry.id);
        if (Thread.currentThread().isInterrupted()) throw new IOException("Loading canceled");
        NativeBridge.inspectScene(file.getAbsolutePath());
      } catch (Exception e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
      final File prepared = file; final String failure = error;
      main.post(() -> {
        if (destroyed) return; loading = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); changed();
        if (failure != null || prepared == null) { status.setText(getString(R.string.browser_load_error, failure)); return; }
        status.setText(""); startActivity(new Intent(this, ControllerActivity.class).putExtra("previewScene", prepared.getAbsolutePath())
            .putExtra("sceneRoom", entry.id).putExtra("exploreMode", explore).putExtra("testProfile", profile));
      });
    });
  }
  private void showMenu() {
    new AlertDialog.Builder(this).setTitle(R.string.browser_menu).setItems(new String[]{getString(R.string.browser_data), getString(R.string.browser_diagnostics)}, (dialog, which) -> {
      if (which == 0) { importPanel.setVisibility(importPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (importPanel.getVisibility() == View.VISIBLE) disc1.requestFocus(); }
      else new AlertDialog.Builder(this).setTitle(R.string.browser_diagnostics).setItems(new String[]{getString(R.string.open_test), getString(R.string.room_test)},
          (d, test) -> startActivity(new Intent(this, test == 0 ? ControllerActivity.class : RoomCheckActivity.class))).show();
    }).show();
  }
  private void choose(int disc) {
    if (loading) return; Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivityForResult(i, disc);
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if ((request == 1 || request == 2) && result == RESULT_OK && data != null && data.getData() != null) imports.start(data.getData(), request);
  }
  @Override protected void onResume() { super.onResume(); imports.attach(this); }
  @Override protected void onPause() { imports.detach(this); super.onPause(); }
  @Override public void changed() {
    // the core launcher shows the import prompt as its heading: the status line only for the rest
    if (!loading) status.setText(core && ImportManager.PROMPT.equals(imports.status) ? "" : imports.status);
    discs.setText(getString(R.string.disc_status, getString(discReady(1) ? R.string.ready : R.string.missing), getString(discReady(2) ? R.string.ready : R.string.missing)));
    disc1.setEnabled(imports.root != null && !imports.busy && !loading); disc2.setEnabled(imports.root != null && !imports.busy && !loading); cancel.setEnabled(imports.busy);
    if (core) {
      cancel.setVisibility(imports.busy ? View.VISIBLE : View.GONE);  // the launcher's two buttons, Cancel while importing
      disc1State.setText(getString(R.string.core_disc_state, 1, discState(1)));
      disc2State.setText(getString(R.string.core_disc_state, 2, discState(2)));
      // two discs of different releases: say so, whatever the last import said
      String mixed = imports.busy ? null : imports.mixedReleases();
      if (mixed != null && !loading) status.setText(mixed);
      // no empty line between the import heading and its buttons
      status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }
    if (!gameReady() || imports.busy) importPanel.setVisibility(View.VISIBLE);
    if (start != null) start.setEnabled(gameReady() && !imports.busy && !loading);
    if (imports.busy || loading) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    updateOpenState();
    if(!startupShown&&gameReady()&&!imports.busy&&!loading)startOriginalTitle();
  }
  @Override public boolean dispatchKeyEvent(KeyEvent e) {
    if ((e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD &&
        (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A || e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B))
      return super.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(),
          e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK,
          e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), InputDevice.SOURCE_DPAD));
    return super.dispatchKeyEvent(e);
  }
  @Override protected void onDestroy() { destroyed = true; worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
