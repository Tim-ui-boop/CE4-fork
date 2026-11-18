package com.ast.re4decomp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ast.re4decomp.ImportManager
import com.ast.re4decomp.Re4Application
import com.ast.re4decomp.disc.DiscInstaller
import java.io.File

/**
 * The disc import's host (Jetpack Compose, 18.11.2026).
 *
 * It wraps the existing [ImportManager] rather than reimplementing an import that took months to get
 * right (RVZ staging, release detection, storage checks). What is new is the screen: one page that
 * says what to pick, a real progress bar from the manager's counters, and a failure named where it
 * happened.
 *
 * The manager takes one disc at a time (start(uri, expectedDisc)); this walks the picked files in
 * order and reports the position, because the picker hands over whatever the player selected.
 */
class ImportActivity : ComponentActivity() {
    private var state by mutableStateOf(ImportUiState())
    private lateinit var imports: ImportManager
    private var queue: List<Uri> = emptyList()
    private var next = 0

    private val listener = ImportManager.Listener {
        // called from the manager's worker thread
        runOnUiThread {
            val nowReady = ready()
            state = state.copy(
                busy = imports.busy,
                done = imports.progressDone,
                total = imports.progressTotal,
                status = imports.status,
                ready = nowReady,
                failed = !imports.busy && !nowReady && imports.status != ImportManager.PROMPT,
            )
            // the disc just finished: on to the next one, until the queue is empty
            if (!imports.busy && next < queue.size) {
                startNext()
            }
        }
    }

    private val pickDiscs = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        queue = uris
        next = 0
        startNext()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imports = (application as Re4Application).imports
        imports.attach(listener)
        val nowReady = ready()
        state = state.copy(
            ready = nowReady,
            status = if (nowReady) "Discs are imported and ready." else ImportManager.PROMPT,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImportScreen(
                        state = state,
                        onPickDiscs = { pickDiscs.launch(arrayOf("*/*")) },
                        onCancel = { imports.cancel() },
                        onReady = { finish() },
                    )
                }
            }
        }
    }

    /** Is disc 1 of a known release in place? */
    private fun ready(): Boolean = try {
        // release() returns the release's name, or null when there is no disc there
        DiscInstaller.release(File(imports.root, "disc1")) != null
    } catch (e: Exception) {
        false
    }

    /**
     * Starts the next picked disc. The manager counts its own discs from 1, so the position in the
     * queue is the expected disc: the player picks them in the order the game asks for them.
     */
    private fun startNext() {
        if (next >= queue.size) return
        val uri = queue[next]
        val disc = if (next < 2) next + 1 else 1
        next++
        state = state.copy(busy = true, failed = false, done = 0, total = 0,
            status = "Disc $disc: starting")
        imports.start(uri, disc)
    }

    override fun onDestroy() {
        imports.detach(listener)
        super.onDestroy()
    }
}
