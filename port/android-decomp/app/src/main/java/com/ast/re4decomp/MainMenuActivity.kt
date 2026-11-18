package com.ast.re4decomp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ast.re4decomp.disc.DiscInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The main menu's host (Jetpack Compose, 18.11.2026). One activity for the whole pre-game screen:
 * the disc import, the randomizer settings and Start game, as the owner asked.
 *
 * It replaces three screens that used to stack (MainActivity's launcher, RandomizerStart and the
 * randomizer dialog/page), which is why a player met a menu, then another menu, then a page.
 *
 * Every change is written through SeedStore.setCurrent, so the run the game starts with is the run
 * this page shows -- the failure of the old page was a switch that was drawn but never handed to the
 * writer.
 */
class MainMenuActivity : ComponentActivity() {
    private var state by mutableStateOf(MainMenuUiState())
    private lateinit var imports: ImportManager
    private lateinit var store: SeedStore
    private var run: RandomizerConfig = RandomizerConfig()
    private var queue: List<Uri> = emptyList()
    private var next = 0

    private companion object {
        const val TAG = "MainMenu"
    }

    private val importListener = ImportManager.Listener {
        runOnUiThread {
            state = state.copy(
                importBusy = imports.busy,
                importDone = imports.progressDone,
                importTotal = imports.progressTotal,
                importStatus = imports.status,
                discReady = discReady(),
            )
            if (!imports.busy && next < queue.size) startNextDisc()
        }
    }

    private val pickDiscs = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        queue = uris
        next = 0
        startNextDisc()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imports = (application as Re4Application).imports
        imports.attach(importListener)
        store = SeedStore(filesDir, getSharedPreferences(CoreActivity.GAME_PREFS, MODE_PRIVATE))
        run = store.current()

        val allowed = EnemyRandomizer.allowedKinds(run.feature("enemies").pool)
        Log.i(TAG, "menu: seed=${run.seed} pool=0x${Integer.toHexString(run.feature("enemies").pool)} " +
            "kinds=${allowed.count { it }}/${allowed.size} written=${EnemyRandomizer.active(this)}")
        state = state.copy(
            discReady = discReady(),
            importStatus = if (discReady()) "" else ImportManager.PROMPT,
            seed = run.seed,
            kinds = allowed.toList(),
            writtenSummary = writtenSummary(),
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainMenuScreen(
                    state = state,
                    onPickDiscs = { pickDiscs.launch(arrayOf("*/*")) },
                    onCancelImport = { imports.cancel() },
                    onSeedChange = { seed ->
                        run.seed = seed
                        store.setCurrent(run, activeFile())
                        Log.i(TAG, "menu: new seed $seed")
                        state = state.copy(seed = seed, writeMessage = "", writeFailed = false)
                    },
                    onKindToggle = { index, on ->
                        val kinds = state.kinds.toMutableList()
                        kinds[index] = on
                        run.feature("enemies").pool = maskOf(kinds)
                        store.setCurrent(run, activeFile())
                        Log.i(TAG, "menu: kind $index (${EnemyRandomizer.POOL[index].name}) -> $on, " +
                            "pool=0x${Integer.toHexString(run.feature("enemies").pool)}")
                        state = state.copy(kinds = kinds, writeMessage = "", writeFailed = false)
                    },
                    onWrite = { writeEnemies(state.kinds) },
                    onRestore = { restoreEnemies() },
                    onStart = { startGame() },
                )
            }
        }
    }

    private fun discReady(): Boolean = try {
        DiscInstaller.release(File(imports.root, "disc1")) != null
    } catch (e: Exception) {
        false
    }

    private fun activeFile(): String =
        getSharedPreferences(CoreActivity.GAME_PREFS, MODE_PRIVATE).getString(SeedStore.PREF_ACTIVE, "") ?: ""

    private fun maskOf(kinds: List<Boolean>): Int {
        var mask = 0
        kinds.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
        return mask
    }

    private fun writtenSummary(): String {
        val n = EnemyRandomizer.active(this)
        return if (n == 0) "nothing written yet" else "$n list${if (n == 1) "" else "s"} written"
    }

    /**
     * Writes the enemy lists. The log lines here exist because the last three attempts could not tell
     * "the page never ran the write" from "the write changed nothing": the seed, the mask and the
     * result are all named now.
     */
    private fun writeEnemies(kinds: List<Boolean>) {
        val seed = if (run.seed == 0) RandomizerConfig.freshSeed().also { run.seed = it } else run.seed
        val mask = maskOf(kinds)
        Log.i(TAG, "write: starting seed=$seed pool=0x${Integer.toHexString(mask)} " +
            "kinds=${kinds.count { it }}/${kinds.size}")
        state = state.copy(
            seed = seed,
            writeBusy = true,
            writeDone = 0,
            writeTotal = EnemyRandomizer.STORY_LISTS.size,
            writeStep = "Starting",
            writeMessage = "",
            writeFailed = false,
        )
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    EnemyRandomizer.apply(applicationContext, seed, mask) { done, total, what ->
                        runOnUiThread {
                            state = state.copy(
                                writeDone = done,
                                writeTotal = total,
                                writeStep = if (what.isEmpty()) "Finishing" else "Writing $what",
                            )
                        }
                    }
                }
                Log.i(TAG, "write: done seed=$seed changed=${result.changed} lists=${result.lists} " +
                    result.perList.entries.joinToString { "${it.key}=${it.value}" })
                state = state.copy(
                    writeBusy = false,
                    writeDone = state.writeTotal,
                    writeStep = "",
                    writeMessage = if (result.changed == 0)
                        "No enemy could be changed: every slot is used by a room's script, or every kind is switched off."
                    else "Written: ${result.summary()}. They appear from the next room on.",
                    writeFailed = result.changed == 0,
                    writtenSummary = writtenSummary(),
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "write: failed", e)
                state = state.copy(
                    writeBusy = false,
                    writeStep = "",
                    writeMessage = e.message ?: e.toString(),
                    writeFailed = true,
                )
            }
        }
    }

    private fun restoreEnemies() {
        Log.i(TAG, "restore: removing the written lists")
        state = state.copy(writeBusy = true, writeDone = 0, writeTotal = 0,
            writeStep = "Removing the written lists", writeMessage = "", writeFailed = false)
        CoroutineScope(Dispatchers.Main).launch {
            val removed = withContext(Dispatchers.IO) { EnemyRandomizer.restore(applicationContext) }
            Log.i(TAG, "restore: removed=$removed")
            state = state.copy(
                writeBusy = false,
                writeStep = "",
                writeMessage = "Removed $removed list${if (removed == 1) "" else "s"}: the game reads its own data again.",
                writeFailed = false,
                writtenSummary = writtenSummary(),
            )
        }
    }

    private fun startNextDisc() {
        if (next >= queue.size) return
        val uri = queue[next]
        val disc = if (next < 2) next + 1 else 1
        next++
        state = state.copy(importBusy = true, importDone = 0, importTotal = 0,
            importStatus = "Disc $disc: starting")
        imports.start(uri, disc)
    }

    /** On to the game. The core reads the run from the preferences, which this page has kept current. */
    private fun startGame() {
        Log.i(TAG, "start: seed=${run.seed} written=${EnemyRandomizer.active(this)}")
        val game = Intent(this, CoreActivity::class.java)
        game.putExtra("coreSettings", intent.getStringExtra("coreSettings"))
        startActivity(game)
        finish()
    }

    override fun onDestroy() {
        imports.detach(importListener)
        super.onDestroy()
    }
}
