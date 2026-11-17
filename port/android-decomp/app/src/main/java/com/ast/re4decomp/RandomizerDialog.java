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
  /** The "Diagnostics log" switch: not a randomizer, so it is kept out of {@link #boxes}. */
  private CheckBox diagBox;
  /** The chance slider's label, so the row can show its value without a second redraw path. */
  private final java.util.Map<String, TextView> chanceLabels = new java.util.HashMap<>();
  /** The chance buttons as one group, so they can be switched off with the randomizer itself. */
  private final java.util.Map<String, View> chanceGroups = new java.util.HashMap<>();
  /** The weapon button of each randomizer that has a pool, to relabel it when the choice changes. */
  private final java.util.Map<String, Button> weaponButtons = new java.util.HashMap<>();
  /** The enemy options button (pool + mix) of the enemies feature, relabelled as the settings change. */
  private final java.util.Map<String, Button> enemyButtons = new java.util.HashMap<>();
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
    body.addView(diagRow(context));

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

  /**
   * The "Diagnostics log" switch: sits after the randomizers because it is not one (it changes nothing in the game),
   * and it keeps its own state with the run, so a saved seed brings it back. A line under it says what it does and
   * where the log ends up, because the switch alone would not tell a tester where to look.
   */
  private View diagRow(Context context) {
    LinearLayout group = new LinearLayout(context);
    group.setOrientation(LinearLayout.VERTICAL);
    group.setPadding(0, dp(10), 0, 0);

    final CheckBox box = new CheckBox(context);
    box.setText(R.string.randomizer_diag);
    box.setTextSize(14);
    box.setChecked(run.diag);
    box.setOnCheckedChangeListener((v, on) -> {
      if (silent) return;  // the redraw echoes the run's own state; only a tap reaches here
      run.diag = on;
      store.setCurrent(run, activeFile);  // keeps the bit with the run and tells a running core
      status.setText(getContext().getString(on ? R.string.randomizer_diag_on : R.string.randomizer_diag_off));
    });
    group.addView(box);
    diagBox = box;

    TextView hint = new TextView(context);
    hint.setText(R.string.randomizer_diag_hint);
    hint.setTextSize(11);
    hint.setPadding(dp(34), 0, 0, 0);
    group.addView(hint);

    // ---- the enemy data rewrite (18.11.2026) -------------------------------------------------------------------
    // The enemy randomizer is a data rewrite now, not a play-time swap: one seed writes patched enemy lists the
    // loader prefers over the disc's own (runtime/dvd.cpp entryPath looks for <name>.re4 first). Nothing on the
    // disc is written, so removing the files restores vanilla.
    LinearLayout patchRow = new LinearLayout(context);
    patchRow.setGravity(Gravity.CENTER_VERTICAL);
    patchRow.setPadding(dp(34), dp(6), 0, 0);
    patchRow.addView(compactButton(context, R.string.randomizer_esl_patch, v -> patchEsl()));
    patchRow.addView(compactButton(context, R.string.randomizer_esl_restore, v -> restoreEsl()));
    group.addView(patchRow);

    TextView patchHint = new TextView(context);
    patchHint.setText(R.string.randomizer_esl_hint);
    patchHint.setTextSize(11);
    patchHint.setPadding(dp(34), 0, 0, 0);
    group.addView(patchHint);
    return group;
  }

  /**
   * Writes this run's enemy lists: the Story's lists take the seed's kinds, checked against each room's script and
   * its heap. Takes effect on the next room load, so a run already in progress hears it too.
   */
  private void patchEsl() {
    try {
      final int seed = run.seed == 0 ? RandomizerConfig.freshSeed() : run.seed;
      if (run.seed == 0) run.seed = seed;
      EnemyRandomizer.Result result = EnemyRandomizer.apply(getContext(), seed);
      status.setText(getContext().getString(R.string.randomizer_esl_patched, result.summary(), seed));
    } catch (java.io.IOException e) {
      status.setText(e.getMessage());
    }
  }

  /** Removes the patched lists, so the disc's own enemy data is what the game reads again. */
  private void restoreEsl() {
    int removed = EnemyRandomizer.restore(getContext());
    status.setText(getContext().getString(removed > 0
        ? R.string.randomizer_esl_restored : R.string.randomizer_esl_no_backup));
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
    if (f.hasEnemyOptions()) row.addView(enemyOption(context, f));
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

  // ---- the enemies feature's options (0.99.16: the PC randomizer's "Enemy settings") --------------------------------

  /** The enemies' options as one small button (like the merchant's weapon pool button). */
  private View enemyOption(Context context, final RandomizerConfig.Feature f) {
    Button b = compactButton(context, R.string.randomizer_enemy_options_short, v -> chooseEnemyOptions(f));
    enemyButtons.put(f.id, b);  // greyed out with the switch through refresh()
    updateEnemyButton(f);
    return b;
  }

  private static int poolCount(RandomizerConfig.Feature f) {
    int mask = f.pool == 0 ? RandomizerConfig.ENEMY_POOL_DEFAULT : f.pool;
    int n = 0;
    for (int i = 0; i < RandomizerConfig.ENEMY_POOL.length; i++) if ((mask >> i & 1) != 0) n++;
    return n;
  }

  private void updateEnemyButton(RandomizerConfig.Feature f) {
    Button b = enemyButtons.get(f.id);
    if (b != null) b.setText(getContext().getString(R.string.randomizer_enemy_options_short, poolCount(f)));
  }

  /**
   * The pool, the mix and the variety cap. The pool is a checklist like the merchant's weapons; the rest are four
   * "one in N" choices and a cap 1..4, one row each, built by hand for want of a stock layout that fits.
   */
  private void chooseEnemyOptions(final RandomizerConfig.Feature f) {
    final boolean[] chosen = new boolean[RandomizerConfig.ENEMY_POOL.length];
    final int startPool = f.pool == 0 ? RandomizerConfig.ENEMY_POOL_DEFAULT : f.pool;
    for (int i = 0; i < chosen.length; i++) chosen[i] = (startPool >> i & 1) != 0;
    final int[] steps = new int[RandomizerConfig.ENEMY_RATIOS.length];
    for (int i = 0; i < steps.length; i++) steps[i] = (f.ratios >> RandomizerConfig.RATIO_SHIFT[i]) & 0xF;
    final int[] variety = {RandomizerConfig.varietyOf(f.ratios)};
    final boolean[] unsafe = {RandomizerConfig.unsafeOf(f.ratios)};

    LinearLayout list = new LinearLayout(getContext());
    list.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(12);
    list.setPadding(pad, pad, pad, pad);
    TextView poolTitle = new TextView(getContext());
    poolTitle.setText(R.string.randomizer_enemy_pool_title);
    poolTitle.setTextSize(13);
    poolTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    list.addView(poolTitle);
    for (int i = 0; i < chosen.length; i++) {
      final int bit = i;
      CheckBox kind = new CheckBox(getContext());
      kind.setText(RandomizerConfig.ENEMY_POOL[i]);
      kind.setTextSize(13);
      kind.setChecked(chosen[i]);
      kind.setOnCheckedChangeListener((v, on) -> chosen[bit] = on);
      list.addView(kind);
    }
    TextView mixTitle = new TextView(getContext());
    mixTitle.setText(R.string.randomizer_enemy_mix_title);
    mixTitle.setTextSize(13);
    mixTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    mixTitle.setPadding(0, dp(10), 0, 0);
    list.addView(mixTitle);
    for (int i = 0; i < RandomizerConfig.ENEMY_RATIOS.length; i++) {
      list.addView(ratioRow(RandomizerConfig.ENEMY_RATIOS[i], steps, i, false));
    }
    list.addView(ratioRow(getContext().getString(R.string.randomizer_enemy_variety), variety, 0, true));
    // the unsafe switch (the owner's name): the cautious rooms open up; the per-enemy protections never turn off
    CheckBox unsafeBox = new CheckBox(getContext());
    unsafeBox.setText(R.string.randomizer_enemy_unsafe);
    unsafeBox.setTextSize(13);
    unsafeBox.setChecked(unsafe[0]);
    unsafeBox.setOnCheckedChangeListener((v, on) -> unsafe[0] = on);
    list.addView(unsafeBox);

    ScrollView scroll = new ScrollView(getContext());
    scroll.addView(list);
    new AlertDialog.Builder(getContext())
        .setTitle(R.string.randomizer_enemy_options_title)
        .setView(scroll)
        .setPositiveButton(R.string.randomizer_ok, (d, w) -> {
          int pool = 0;
          for (int i = 0; i < chosen.length; i++) if (chosen[i]) pool |= 1 << i;
          f.pool = pool;
          f.ratios = RandomizerConfig.packRatios(steps[0], steps[1], steps[2], steps[3], variety[0], unsafe[0]);
          store.setCurrent(run, activeFile);
          updateEnemyButton(f);
          status.setText("");
        })
        .setNegativeButton(R.string.randomizer_cancel, null)
        .show();
  }

  /** One "one in N" line: the label, the step, minus/plus buttons. `slot` indexes the value array (the variety's
   *  own array, when `variety`). */
  private View ratioRow(String label, final int[] values, final int slot, final boolean variety) {
    LinearLayout rowIn = new LinearLayout(getContext());
    rowIn.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = new TextView(getContext());
    name.setText(label);
    name.setTextSize(12);
    rowIn.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    final TextView value = new TextView(getContext());
    value.setTextSize(12);
    value.setGravity(Gravity.CENTER);
    value.setMinWidth(dp(44));
    final Button less = compactButton(getContext(), R.string.randomizer_minus, v -> changeStep(values, slot, -1, variety, value));
    final Button more = compactButton(getContext(), R.string.randomizer_plus, v -> changeStep(values, slot, 1, variety, value));
    rowIn.addView(less);
    rowIn.addView(value);
    rowIn.addView(more);
    stepLabel(value, values[slot], variety);
    return rowIn;
  }

  private void changeStep(int[] values, int slot, int delta, boolean variety, TextView value) {
    if (variety) {
      values[slot] = Math.max(1, Math.min(4, values[slot] + delta));
    } else {
      values[slot] = Math.max(RandomizerConfig.RATIO_OFF, Math.min(RandomizerConfig.RATIO_MAX, values[slot] + delta));
    }
    stepLabel(value, values[slot], variety);
  }

  /** The step as it reads on the page: "off", "1 in 2", "1 in 3", ...; the variety cap shown as its plain number. */
  private void stepLabel(TextView value, int step, boolean variety) {
    if (variety) {
      value.setText(String.valueOf(step));
      return;
    }
    if (step <= 0) {
      value.setText(R.string.ingame_off);
      return;
    }
    value.setText(getContext().getString(R.string.randomizer_one_in, 16 - step));
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
      if (f.hasEnemyOptions()) {
        updateEnemyButton(f);
        setOptionsEnabled(enemyButtons.get(f.id), f.on);
      }
    }
    // the diagnostics switch belongs to the run too: a loaded seed turns it on or off with the rest
    if (diagBox != null) {
      silent = true;
      diagBox.setChecked(run.diag);
      silent = false;
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
