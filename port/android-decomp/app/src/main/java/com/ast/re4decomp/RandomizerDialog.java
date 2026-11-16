package com.ast.re4decomp;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.IOException;

/**
 * The randomizer page, opened from the title screen before the game starts (port/docs randomizer plan, phase 2).
 *
 * Everything a randomizer needs lives here, as in a Ocarina of Time randomizer: one seed defines the run, the
 * randomizers are switched on or off, and a seed can be kept as a named file to come back to or to reproduce.
 * The in-game menu keeps its own switches until phase 3; both act on the same run (RandomizerConfig), so they
 * cannot disagree.
 */
final class RandomizerDialog extends Dialog {
  private final SeedStore store;
  private final TextView seedLine, status;
  private final LinearLayout savedList;
  private final EditText nameField;
  private LinearLayout features;
  /** The switch of each randomizer, by feature id: the container also holds the chance slider and the weapon button. */
  private final java.util.Map<String, CheckBox> boxes = new java.util.HashMap<>();
  private RandomizerConfig run;
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

    LinearLayout body = new LinearLayout(context);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(18);
    body.setPadding(pad, pad, pad, pad);

    TextView title = new TextView(context);
    title.setText(R.string.randomizer_title);
    title.setTextSize(22);
    title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    title.setPadding(0, 0, 0, dp(10));
    body.addView(title);

    TextView intro = new TextView(context);
    intro.setText(R.string.randomizer_intro);
    intro.setTextSize(13);
    intro.setPadding(0, 0, 0, dp(12));
    body.addView(intro);

    // ---- the seed -----------------------------------------------------------------------------------------------
    LinearLayout seedRow = new LinearLayout(context);
    seedRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    seedLine = new TextView(context);
    seedLine.setTextSize(16);
    seedRow.addView(seedLine, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    Button fresh = button(context, R.string.randomizer_new_seed, v -> {
      run = new RandomizerConfig(RandomizerConfig.freshSeed(), "");
      // the switches keep their state: a new seed is a new roll of the same rules
      RandomizerConfig previous = store.current();
      for (RandomizerConfig.Feature f : run.features.values()) {
        RandomizerConfig.Feature old = previous.features.get(f.id);
        if (old != null) {
          f.on = old.on;
          f.chance = old.chance;
        }
      }
      activeFile = "";
      store.setCurrent(run, "");
      refresh();
    });
    seedRow.addView(fresh);
    body.addView(seedRow);

    // ---- the randomizers ----------------------------------------------------------------------------------------
    TextView heading = new TextView(context);
    heading.setText(R.string.randomizer_features);
    heading.setTextSize(15);
    heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    heading.setPadding(0, dp(14), 0, dp(6));
    body.addView(heading);

    features = new LinearLayout(context);
    features.setOrientation(LinearLayout.VERTICAL);
    for (final RandomizerConfig.Feature f : run.features.values()) {
      CheckBox box = new CheckBox(context);
      box.setText(f.label);
      box.setTextSize(15);
      box.setChecked(f.on);
      box.setOnCheckedChangeListener((v, on) -> {
        f.on = on;
        if (on && run.seed == 0) run.seed = RandomizerConfig.freshSeed();
        store.setCurrent(run, activeFile);
        refresh();
      });
      features.addView(box);
      boxes.put(f.id, box);
      // phase 5: the merchant's options, shown under its switch while it is on
      if (f.hasChance()) features.addView(chanceRow(f));
      if (f.hasWeapons()) features.addView(weaponButton(f));
    }
    body.addView(features);

    // ---- saved seeds --------------------------------------------------------------------------------------------
    TextView savedHeading = new TextView(context);
    savedHeading.setText(R.string.randomizer_saved_seeds);
    savedHeading.setTextSize(15);
    savedHeading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    savedHeading.setPadding(0, dp(14), 0, dp(6));
    body.addView(savedHeading);

    ScrollView scroll = new ScrollView(context);
    savedList = new LinearLayout(context);
    savedList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(savedList);
    scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
    body.addView(scroll);

    // ---- save the run under a name ------------------------------------------------------------------------------
    LinearLayout saveRow = new LinearLayout(context);
    saveRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    saveRow.setPadding(0, dp(10), 0, 0);
    nameField = new EditText(context);
    nameField.setHint(R.string.randomizer_name_hint);
    nameField.setTextSize(14);
    nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    saveRow.addView(nameField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    saveRow.addView(button(context, R.string.randomizer_save, v -> saveRun()));
    body.addView(saveRow);

    status = new TextView(context);
    status.setTextSize(13);
    status.setPadding(0, dp(8), 0, dp(8));
    body.addView(status);

    LinearLayout bottom = new LinearLayout(context);
    bottom.setGravity(android.view.Gravity.END);
    bottom.addView(button(context, R.string.randomizer_close, v -> dismiss()));
    body.addView(bottom);

    ScrollView page = new ScrollView(context);
    page.addView(body);
    setContentView(page);
    refresh();
  }

  private Button button(Context context, int label, View.OnClickListener click) {
    Button b = new Button(context);
    b.setText(label);
    b.setTextSize(14);
    b.setOnClickListener(click);
    return b;
  }

  /** The chance option (phase 5): how often a merchant offers new goods. A slider, 10..100 in steps of 10. */
  private View chanceRow(final RandomizerConfig.Feature f) {
    LinearLayout row = new LinearLayout(getContext());
    row.setOrientation(LinearLayout.VERTICAL);
    row.setPadding(dp(28), 0, 0, dp(4));
    final TextView label = new TextView(getContext());
    label.setTextSize(13);
    label.setText(getContext().getString(R.string.randomizer_chance,
        f.chance <= 0 ? 100 : f.chance));
    final SeekBar bar = new SeekBar(getContext());
    bar.setMax(9);
    bar.setProgress(Math.max(0, Math.min(9, (f.chance <= 0 ? 100 : f.chance) / 10 - 1)));
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        f.chance = (progress + 1) * 10;
        label.setText(getContext().getString(R.string.randomizer_chance, f.chance));
        if (fromUser) store.setCurrent(run, activeFile);
      }
      @Override public void onStartTrackingTouch(SeekBar seekBar) {}
      @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    });
    row.addView(label);
    row.addView(bar);
    row.setTag("chance:" + f.id);
    return row;
  }

  /** The weapon pool (phase 5): which weapons the merchant may hand out. A dialog with one switch per weapon. */
  private View weaponButton(final RandomizerConfig.Feature f) {
    Button b = new Button(getContext());
    b.setTextSize(13);
    b.setText(getContext().getString(R.string.randomizer_weapons, weaponCount(f)));
    b.setPadding(dp(28), 0, 0, dp(4));
    b.setOnClickListener(v -> {
      final boolean[] chosen = new boolean[RandomizerConfig.WEAPONS.length];
      final int mask = f.weapons == 0 ? RandomizerConfig.WEAPONS_DEFAULT : f.weapons;
      for (int i = 0; i < chosen.length; i++) chosen[i] = (mask >> i & 1) != 0;
      new android.app.AlertDialog.Builder(getContext())
          .setTitle(R.string.randomizer_weapons_title)
          .setMultiChoiceItems(RandomizerConfig.WEAPONS, chosen,
              (d, which, checked) -> chosen[which] = checked)
          .setPositiveButton(R.string.randomizer_ok, (d, w) -> {
            int result = 0;
            for (int i = 0; i < chosen.length; i++) if (chosen[i]) result |= 1 << i;
            f.weapons = result;
            store.setCurrent(run, activeFile);
            b.setText(getContext().getString(R.string.randomizer_weapons, weaponCount(f)));
            status.setText("");
          })
          .setNegativeButton(R.string.randomizer_cancel, null)
          .show();
    });
    return b;
  }

  private static int weaponCount(RandomizerConfig.Feature f) {
    int mask = f.weapons == 0 ? RandomizerConfig.WEAPONS_DEFAULT : f.weapons;
    int n = 0;
    for (int i = 0; i < RandomizerConfig.WEAPONS.length; i++) if ((mask >> i & 1) != 0) n++;
    return n;
  }

  /** Redraws everything from the current run and the seed files. */
  private void refresh() {
    boolean any = run.anyEnabled();
    String where = activeFile.isEmpty() ? "" : " (" + activeFile + ")";
    seedLine.setText(run.seed == 0 ? getContext().getString(R.string.randomizer_no_seed)
        : getContext().getString(R.string.randomizer_seed, run.seed) + where);
    // only the switches: the chance slider and the weapon button sit in the same container
    for (RandomizerConfig.Feature f : run.features.values()) {
      CheckBox box = boxes.get(f.id);
      if (box != null) box.setChecked(f.on);
    }

    savedList.removeAllViews();
    java.util.List<String> names = store.names();
    if (names.isEmpty()) {
      TextView empty = new TextView(getContext());
      empty.setText(R.string.randomizer_no_files);
      empty.setTextSize(13);
      savedList.addView(empty);
    }
    for (final String name : names) {
      LinearLayout row = new LinearLayout(getContext());
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      TextView label = new TextView(getContext());
      label.setText(name);
      label.setTextSize(14);
      row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
      row.addView(button(getContext(), R.string.randomizer_load, v -> loadRun(name)));
      row.addView(button(getContext(), R.string.randomizer_delete, v -> deleteRun(name)));
      savedList.addView(row);
    }
    status.setText(any ? "" : getContext().getString(R.string.randomizer_none_on));
  }

  private void loadRun(String name) {
    try {
      RandomizerConfig loaded = store.load(name);
      run = loaded;
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
      if (activeFile.isEmpty()) activeFile = wrote;
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
