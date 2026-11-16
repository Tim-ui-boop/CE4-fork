package com.ast.re4decomp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.List;

/**
 * The screen shown before the game starts (randomizer plan, phase 6).
 *
 * The core app boots straight into the game: MainActivity opens CoreActivity, which starts the core at once, so there
 * was nowhere "before the game" to put the randomizer settings. This screen is that place. It shows the run in use,
 * opens the randomizer page, and starts the game; it appears once per app start and never during play, and a restored
 * activity (a rotation, a return from the game) skips straight through.
 *
 * The page itself is {@link RandomizerDialog}, the one the title screen of the classic flavour also opens; both act on
 * the same {@link RandomizerConfig}, so there is one place the settings live whatever opened them.
 */
public final class RandomizerStart extends Activity {
  /** The page is already open (a configuration change while it showed): the new activity opens it again. */
  private static final String EXTRA_OPEN = "openRandomizer";
  private RandomizerDialog page;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (getIntent().getBooleanExtra("skip", false)) {
      startGame();
      return;
    }
    setContentView(build());
    // a rotation rebuilds the screen above; a page that was open is opened again on the new activity
    if (state != null && state.getBoolean(EXTRA_OPEN, false)) openPage();
  }

  private SeedStore store() {
    return new SeedStore(getFilesDir(), getSharedPreferences(CoreActivity.GAME_PREFS, MODE_PRIVATE));
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private View build() {
    final SeedStore store = store();
    final RandomizerConfig run = store.current();

    ScrollView scroll = new ScrollView(this);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(22), dp(26), dp(22), dp(22));
    scroll.addView(body);

    TextView title = new TextView(this);
    title.setText(R.string.randomizer_title);
    title.setTextSize(26);
    title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    body.addView(title);

    TextView intro = new TextView(this);
    intro.setText(R.string.randomizer_intro);
    intro.setTextSize(14);
    intro.setPadding(0, dp(8), 0, dp(18));
    body.addView(intro);

    // the run in use: the seed and what it switches on
    TextView line = new TextView(this);
    line.setTextSize(18);
    line.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
    line.setText(run.anyEnabled()
        ? getString(R.string.randomizer_seed, run.seed)
        : getString(R.string.randomizer_no_seed));
    body.addView(line);

    TextView detail = new TextView(this);
    detail.setTextSize(14);
    detail.setPadding(0, dp(4), 0, dp(16));
    detail.setText(summary(run));
    body.addView(detail);

    Button page = new Button(this);
    page.setText(R.string.randomizer_open_page);
    page.setTextSize(16);
    page.setOnClickListener(v -> openPage());
    body.addView(page);

    Button start = new Button(this);
    start.setText(R.string.randomizer_start);
    start.setTextSize(18);
    start.setPadding(dp(8), dp(14), dp(8), dp(14));
    start.setOnClickListener(v -> startGame());
    LinearLayout.LayoutParams startParams =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    startParams.topMargin = dp(10);
    body.addView(start, startParams);

    // the seed files, so a run can be picked without opening the page about it
    List<String> names = store.names();
    if (!names.isEmpty()) {
      TextView heading = new TextView(this);
      heading.setText(R.string.randomizer_saved_seeds);
      heading.setTextSize(15);
      heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
      heading.setPadding(0, dp(20), 0, dp(6));
      body.addView(heading);
      for (final String name : names) {
        Button pick = new Button(this);
        pick.setText(name);
        pick.setTextSize(14);
        pick.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pick.setOnClickListener(v -> {
          try {
            RandomizerConfig loaded = store.load(name);
            store.setCurrent(loaded, name);
            setContentView(build());  // redraw from the loaded run
          } catch (java.io.IOException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
          }
        });
        body.addView(pick);
      }
    }
    return scroll;
  }

  /** "Random merchant stock, random items" from the run's own switches. */
  private String summary(RandomizerConfig run) {
    StringBuilder out = new StringBuilder();
    int on = 0;
    for (RandomizerConfig.Feature f : run.features.values()) {
      if (!f.on) continue;
      if (out.length() > 0) out.append(", ");
      out.append(f.label);
      on++;
    }
    if (on == 0) return getString(R.string.randomizer_none_on);
    return out.toString();
  }

  private void openPage() {
    if (page != null) return;
    page = new RandomizerDialog(this, store());
    page.setOnDismissListener(d -> {
      page = null;
      // the page may have changed the run: draw this screen again from it
      setContentView(build());
    });
    page.show();
  }

  /** On to the game, which starts the core with the run's settings (CoreActivity.applyToCore). */
  private void startGame() {
    Intent game = new Intent(this, CoreActivity.class);
    game.putExtra("coreSettings", getIntent().getStringExtra("coreSettings"));
    startActivity(game);
    finish();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean(EXTRA_OPEN, page != null);
  }
}
