package com.ast.re4decomp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.ast.re4decomp.EnemyRandomizer
import com.ast.re4decomp.RandomizerConfig
import com.ast.re4decomp.SeedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The randomizer page's host (Jetpack Compose, 18.11.2026), standing in for RandomizerDialog.
 *
 * It owns one [RandomizerUiState] and is the only thing that touches the seed or the files. The
 * write runs on Dispatchers.IO and reports its steps back to the state, which is what the progress
 * bar draws -- on the Java page the write froze the screen with no sign of life, and a run could not
 * be told apart from a failure.
 */
class RandomizerActivity : ComponentActivity() {
    private var state by mutableStateOf(RandomizerUiState())
    private lateinit var store: SeedStore
    private var run: RandomizerConfig = RandomizerConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SeedStore(filesDir, getSharedPreferences("core_game", MODE_PRIVATE))
        run = store.current()

        // the switches start from the run's own pool: an empty pool means "the default set", which is
        // every kind on, exactly as the writer reads it
        val allowed = EnemyRandomizer.allowedKinds(run.feature("enemies").pool)
        state = state.copy(
            seed = run.seed,
            kinds = allowed.toList(),
            activeSummary = summary(),
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RandomizerScreen(
                        state = state,
                        onSeedChange = { seed ->
                            run.seed = seed
                            store.setCurrent(run, activeFile())
                            state = state.copy(seed = seed, message = "", failed = false)
                        },
                        onKindToggle = { index, on ->
                            val kinds = state.kinds.toMutableList()
                            kinds[index] = on
                            state = state.copy(kinds = kinds, message = "", failed = false)
                            // the pool is kept with the run, so a saved seed brings its switches back
                            run.feature("enemies").pool = maskOf(kinds)
                            store.setCurrent(run, activeFile())
                        },
                        onWrite = { write(store, run, state.kinds) },
                        onRestore = { restore() },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun activeFile(): String =
        getSharedPreferences("core_game", MODE_PRIVATE).getString(SeedStore.PREF_ACTIVE, "") ?: ""

    private fun maskOf(kinds: List<Boolean>): Int {
        var mask = 0
        kinds.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
        return mask
    }

    private fun summary(): String {
        val active = EnemyRandomizer.active(this)
        return if (active == 0) "No written enemy data is in place."
        else "$active list${if (active == 1) "" else "s"} written and in use."
    }

    /** Writes the enemy lists, one list at a time, with the bar following the work. */
    private fun write(store: SeedStore, run: RandomizerConfig, kinds: List<Boolean>) {
        val seed = if (run.seed == 0) RandomizerConfig.freshSeed().also { run.seed = it } else run.seed
        state = state.copy(seed = seed, busy = true, done = 0, total = EnemyRandomizer.STORY_LISTS.size,
            step = "Starting", message = "", failed = false)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    EnemyRandomizer.apply(applicationContext, seed, maskOf(kinds)) { done, total, what ->
                        // the writer calls this from the IO thread; the bar is drawn on the main one
                        runOnUiThread {
                            state = state.copy(done = done, total = total,
                                step = if (what.isEmpty()) "Finishing" else "Writing $what")
                        }
                    }
                }
                state = state.copy(
                    busy = false,
                    done = state.total,
                    step = "",
                    message = "Enemies written: ${result.summary()} (seed $seed). They appear from the next room on.",
                    failed = false,
                    activeSummary = summary(),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state = state.copy(
                    busy = false,
                    step = "",
                    message = e.message ?: e.toString(),
                    failed = true,
                )
            }
        }
    }

    /** Removes the written lists; fast enough for no bar, but still off the main thread. */
    private fun restore() {
        state = state.copy(busy = true, done = 0, total = 0, step = "Removing the written lists",
            message = "", failed = false)
        CoroutineScope(Dispatchers.Main).launch {
            val removed = withContext(Dispatchers.IO) { EnemyRandomizer.restore(applicationContext) }
            state = state.copy(
                busy = false,
                step = "",
                message = if (removed > 0) "Enemy data restored to the disc's own."
                else "No written enemy data is in place.",
                failed = false,
                activeSummary = summary(),
            )
        }
    }
}
