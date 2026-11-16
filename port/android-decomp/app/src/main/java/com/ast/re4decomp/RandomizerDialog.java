package com.ast.re4decomp;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.IOException;
import java.util.List;

/**
 * The randomizer page (randomizer plan, phases 2-5): everything a randomizer needs, laid out the way a Ocarina of
 * Time randomizer does it. One seed defines the run, a switch starts each randomizer, and its options sit beside it.
 *
 * The layout is horizontal wherever it helps: the seed and its buttons share a line, each randomizer is one row of
 * switch + options, and a saved seed is one line with its two buttons. Only the page itself scrolls.
 *
 * The run is never swapped for another object: a new seed or a loaded file adopts its values (RandomizerConfig.adopt),
 * so every switch and slider keeps pointing at the run in use. Swapping the object was why the switches stopped
 * responding after "New seed".
 */
final class RandomizerDialog extends Dialog {
  private final SeedStore store;
  private final TextView seedLine, status;
  private final LinearLayout savedList;
  private final EditText nameField;
  /** Set while the page redraws a switch from the run itself: the echo must not act as a tap. */
  private boolean silent;
  /** The switch of each randomizer, by feature id. */
  private final java.util.Map<String, CheckBox> boxes = new java.util.HashMap<>();
  /** The chance slider's label, so the row can show its value without a second redraw path. */
  private final java.util.Map<String, TextView> chanceLabels = new java.util.HashMap<>();
  /** The chance buttons as one group, so they can be switched off with the randomizer itself. */
  private final java.util.Map<String, View> chanceGroups = new java.util.HashMap<>();
  /** The weapon button of each randomizer that has a pool, to relabel it when the choice changes. */
  private final java.util.Map<String, Button> weaponButtons = new java.util.HashMap<>();
  private final RandomizerConfig run;
  /** The seed file this run came from, "" when it has none yet. */
  private String activeFile;

  private int dp(float value) {
    return Math.round(value * getContext().getResources().getDisplayMetrics().density);
  }

  RandomizerDialog(Context context, SeedStore store) {
    super(context);
    this.store = store;
    this.run = store.current();
    this.activeFile = context.getSharedPreferences(CoreActivity.GAME_PREFS, Context.MODE_PRIVATE)
        .getString(SeedStore.PREF_ACTIVE, "");
    requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

    ScrollView page = new ScrollView(context);
    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(16);
    body.setPadding(pad, pad, pad, pad);
    page.addView(body);

    TextView title = new TextView(context);
    title.setText(R.string.randomizer_title);
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    body.addView(title);

    TextView intro = new TextView(context);
    intro.setText(R.string.randomizer_intro);
    intro.setTextSize(12);
    intro.setPadding(0, dp(4), 0, dp(10));
    body.addView(intro);

    // ---- one line: the seed and its two buttons ---------------------------------------------------------------
    LinearLayout seedRow = new LinearLayout(context);
    seedRow.setGravity(Gravity.CENTER_VERTICAL);
    seedLine = new TextView(context);
    seedLine.setTextSize(16);
    seedLine.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    seedRow.addView(seedLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    seedRow.addView(compactButton(context, R.string.randomizer_new_seed, v -> newSeed()));
    body.addView(seedRow);

    // ---- one row per randomizer -------------------------------------------------------------------------------
    for (final RandomizerConfig.Feature f : run.features.values()) {
      body.addView(featureRow(context, f));
    }

    // ---- saved seeds: one line each, with Load and Delete -----------------------------------------------------
    TextView savedHeading = new TextView(context);
    savedHeading.setText(R.string.randomizer_saved_seeds);
    savedHeading.setTextSize(13);
    savedHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    savedHeading.setPadding(0, dp(12), 0, dp(2));
    body.addView(savedHeading);

    savedList = new LinearLayout(context);
    savedList.setOrientation(LinearLayout.VERTICAL);
    body.addView(savedList);

    // ---- one line: a name and Save ----------------------------------------------------------------------------
    LinearLayout saveRow = new LinearLayout(context);
    saveRow.setGravity(Gravity.CENTER_VERTICAL);
    saveRow.setPadding(0, dp(6), 0, 0);
    nameField = new EditText(context);
    nameField.setHint(R.string.randomizer_name_hint);
    nameField.setTextSize(13);
    nameField.setSingleLine(true);
    nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    saveRow.addView(nameField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    saveRow.addView(compactButton(context, R.string.randomizer_save, v -> saveRun()));
    body.addView(saveRow);

    status = new TextView(context);
    status.setTextSize(12);
    status.setPadding(0, dp(6), 0, dp(4));
    body.addView(status);

    LinearLayout bottom = new LinearLayout(context);
    bottom.setGravity(Gravity.END);
    bottom.addView(compactButton(context, R.string.randomizer_close, v -> dismiss()));
    body.addView(bottom);

    setContentView(page);
    refresh();
    hintNoneOn();
  }

  /** A small button, so a row of them fits beside a label on a phone. */
  private Button compactButton(Context context, int label, View.OnClickListener click) {
    Button b = new Button(context);
    b.setText(label);
    b.setTextSize(12);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(10), dp(2), dp(10), dp(2));
    b.setOnClickListener(click);
    return b;
  }

  /**
   * One randomizer as a single row: its switch, then its own options beside it (the merchant's chance and weapon
   * pool). The options are disabled while the switch is off, so the row reads as one thing.
   */
  private View featureRow(Context context, final RandomizerConfig.Feature f) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(4), 0, dp(4));

    final CheckBox box = new CheckBox(context);
    box.setText(f.label);
    box.setTextSize(14);
    box.setChecked(f.on);
    box.setOnCheckedChangeListener((v, on) -> {
      if (silent) return;  // the redraw echoes the run's own state; only a tap reaches here
      f.on = on;
      if (on && run.seed == 0) run.seed = RandomizerConfig.freshSeed();
      store.setCurrent(run, activeFile);
      refresh();
      hintNoneOn();
    });
    boxes.put(f.id, box);
    row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

    if (f.hasChance()) row.addView(chanceOption(context, f));
    if (f.hasWeapons()) row.addView(weaponOption(context, f));
    return row;
  }

  /** The chance, as a compact "+/-" pair rather than a slider: it reads at a glance and fits a row. 10..100 in tens. */
  private View chanceOption(Context context, final RandomizerConfig.Feature f) {
    LinearLayout group = new LinearLayout(context);
    group.setGravity(Gravity.CENTER_VERTICAL);

    final TextView label = new TextView(context);
    label.setTextSize(12);
    label.setGravity(Gravity.CENTER);
    label.setPadding(dp(4), 0, dp(4), 0);
    chanceLabels.put(f.id, label);

    final Button less = compactButton(context, R.string.randomizer_minus, v -> changeChance(f, -10));
    final Button more = compactButton(context, R.string.randomizer_plus, v -> changeChance(f, 10));
    group.addView(less);
    group.addView(label);
    group.addView(more);
    group.setTag("chance:" + f.id);
    chanceGroups.put(f.id, group);
    updateChanceLabel(f);
    return group;
  }

  private void changeChance(RandomizerConfig.Feature f, int delta) {
    int now = f.chance <= 0 ? 100 : f.chance;
    f.chance = Math.max(10, Math.min(100, now + delta));
    updateChanceLabel(f);
    store.setCurrent(run, activeFile);
  }

  private void updateChanceLabel(RandomizerConfig.Feature f) {
    TextView label = chanceLabels.get(f.id);
    if (label != null) label.setText(getContext().getString(R.string.randomizer_chance_short, f.chance <= 0 ? 100 : f.chance));
  }

  /** The weapon pool, as one small button that says how many are chosen and opens the list. */
  private View weaponOption(Context context, final RandomizerConfig.Feature f) {
    Button b = compactButton(context, R.string.randomizer_weapons_short, v -> chooseWeapons(f));
    weaponButtons.put(f.id, b);
    updateWeaponButton(f);
    return b;
  }

  private void chooseWeapons(final RandomizerConfig.Feature f) {
    final boolean[] chosen = new boolean[RandomizerConfig.WEAPONS.length];
    final int mask = f.weapons == 0 ? RandomizerConfig.WEAPONS_DEFAULT : f.weapons;
    for (int i = 0; i < chosen.length; i++) chosen[i] = (mask >> i & 1) != 0;
    new AlertDialog.Builder(getContext())
        .setTitle(R.string.randomizer_weapons_title)
        .setMultiChoiceItems(RandomizerConfig.WEAPONS, chosen, (d, which, checked) -> chosen[which] = checked)
        .setPositiveButton(R.string.randomizer_ok, (d, w) -> {
          int result = 0;
          for (int i = 0; i < chosen.length; i++) if (chosen[i]) result |= 1 << i;
          f.weapons = result;
          store.setCurrent(run, activeFile);
          updateWeaponButton(f);
          status.setText("");
        })
        .setNegativeButton(R.string.randomizer_cancel, null)
        .show();
  }

  private void updateWeaponButton(RandomizerConfig.Feature f) {
    Button b = weaponButtons.get(f.id);
    if (b != null) b.setText(getContext().getString(R.string.randomizer_weapons_short, weaponCount(f)));
  }

  private static int weaponCount(RandomizerConfig.Feature f) {
    int mask = f.weapons == 0 ? RandomizerConfig.WEAPONS_DEFAULT : f.weapons;
    int n = 0;
    for (int i = 0; i < RandomizerConfig.WEAPONS.length; i++) if ((mask >> i & 1) != 0) n++;
    return n;
  }

  /**
   * A new seed: the same rules, a new roll. The run object stays the same one the page was built from (adopt), so
   * every switch keeps working afterwards.
   */
  private void newSeed() {
    // only the seed changes: the switches and options are this run's rules, and stay as they are
    run.seed = RandomizerConfig.freshSeed();
    activeFile = "";
    store.setCurrent(run, "");
    refresh();
    hintNoneOn();
  }

  /** Redraws the values the page shows. The views themselves stay; only their state is updated. */
  private void refresh() {
    seedLine.setText(run.seed == 0 ? getContext().getString(R.string.randomizer_no_seed)
        : getContext().getString(R.string.randomizer_seed, run.seed)
            + (activeFile.isEmpty() ? "" : "  \u00b7  " + activeFile));
    for (RandomizerConfig.Feature f : run.features.values()) {
      CheckBox box = boxes.get(f.id);
      if (box != null) {
        silent = true;
        box.setChecked(f.on);
        silent = false;
      }
      if (f.hasChance()) {
        updateChanceLabel(f);
        setOptionsEnabled(chanceGroups.get(f.id), f.on);
      }
      if (f.hasWeapons()) {
        updateWeaponButton(f);
        setOptionsEnabled(weaponButtons.get(f.id), f.on);
      }
    }
    rebuildSavedList();
  }

  /** Options only work while their randomizer is on: the row greys them out and takes their clicks. */
  private void setOptionsEnabled(View option, boolean on) {
    if (option == null) return;
    option.setEnabled(on);
    option.setAlpha(on ? 1.0f : 0.45f);
    if (option instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) option;
      for (int i = 0; i < group.getChildCount(); i++) group.getChildAt(i).setEnabled(on);
    }
  }

  /** The one thing the status line says while the page is used: that no randomizer is on. */
  private void hintNoneOn() {
    status.setText(run.anyEnabled() ? "" : getContext().getString(R.string.randomizer_none_on));
  }

  private void rebuildSavedList() {
    savedList.removeAllViews();
    List<String> names = store.names();
    if (names.isEmpty()) {
      TextView empty = new TextView(getContext());
      empty.setText(R.string.randomizer_no_files);
      empty.setTextSize(12);
      savedList.addView(empty);
      return;
    }
    for (final String name : names) {
      LinearLayout row = new LinearLayout(getContext());
      row.setGravity(Gravity.CENTER_VERTICAL);
      TextView label = new TextView(getContext());
      label.setTextSize(13);
      label.setText((name.equals(activeFile) ? "\u25cf " : "") + name);
      row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
      row.addView(compactButton(getContext(), R.string.randomizer_load, v -> loadRun(name)));
      row.addView(compactButton(getContext(), R.string.randomizer_delete, v -> deleteRun(name)));
      savedList.addView(row);
    }
  }

  private void loadRun(String name) {
    try {
      RandomizerConfig loaded = store.load(name);
      run.adopt(loaded);  // the page keeps its own object, so every switch stays live
      activeFile = name;
      store.setCurrent(run, name);
      status.setText(getContext().getString(R.string.randomizer_loaded, name));
      refresh();
    } catch (IOException e) {
      status.setText(e.getMessage());
    }
  }

  private void saveRun() {
    try {
      String wrote = store.save(nameField.getText().toString(), run);
      activeFile = wrote;  // the run now lives in the file just written (save-as switches to it)
      store.setCurrent(run, activeFile);
      nameField.setText("");
      status.setText(getContext().getString(R.string.randomizer_saved, wrote));
      refresh();
    } catch (IOException e) {
      status.setText(e.getMessage());
    }
  }

  private void deleteRun(String name) {
    try {
      store.delete(name);
      if (name.equals(activeFile)) {
        // the run in use keeps its settings; only its file is gone
        activeFile = "";
        store.setCurrent(run, "");
      }
      status.setText(getContext().getString(R.string.randomizer_deleted, name));
      refresh();
    } catch (IOException e) {
      status.setText(e.getMessage());
    }
  }
}
