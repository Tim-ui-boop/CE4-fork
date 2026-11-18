package com.ast.re4decomp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ast.re4decomp.EnemyRandomizer

/**
 * The randomizer page (Jetpack Compose, 18.11.2026).
 *
 * Why it replaced the Java Views page: that page grew one row at a time and ended up with switches
 * that said one thing and did another -- the enemy-kind pool was drawn and then ignored by the
 * writer, so Cows kept appearing after they were switched off. Everything here is driven by one
 * state object, so a switch cannot be shown without being read.
 *
 * What it does: the run's seed, the enemy randomizer's kind switches, the write and the restore,
 * each with a progress bar because both read and rewrite eight list files on the shared storage.
 */
@Composable
fun RandomizerScreen(
    state: RandomizerUiState,
    onSeedChange: (Int) -> Unit,
    onKindToggle: (Int, Boolean) -> Unit,
    onWrite: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Randomizer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "One seed defines this run: the same seed and the same switches always give the " +
                "same game. Write the enemies, then play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SeedCard(state = state, onSeedChange = onSeedChange)

        EnemyKindCard(state = state, onKindToggle = onKindToggle)

        WriteCard(state = state, onWrite = onWrite, onRestore = onRestore)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

@Composable
private fun SeedCard(state: RandomizerUiState, onSeedChange: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Seed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (state.seed == 0) "No seed yet" else "Seed ${state.seed}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSeedChange(com.ast.re4decomp.RandomizerConfig.freshSeed()) }) {
                    Text("New seed")
                }
            }
        }
    }
}

@Composable
private fun EnemyKindCard(state: RandomizerUiState, onKindToggle: (Int, Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Which enemies may appear", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "Only these kinds are written into a room. A switch that is off is never written, " +
                    "whatever else is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            EnemyRandomizer.POOL.forEachIndexed { index, kind ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${kind.costKb / 1024} MB in a room",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.kinds.getOrElse(index) { true },
                        onCheckedChange = { onKindToggle(index, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WriteCard(
    state: RandomizerUiState,
    onWrite: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Enemy data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            when {
                state.busy -> {
                    Text(
                        text = state.step.ifEmpty { "Working..." },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // determinate while a list is being written, indeterminate for the restore
                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { state.done.toFloat() / state.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${state.done} of ${state.total} lists",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                state.message.isNotEmpty() -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                else -> Text(
                    text = state.activeSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onWrite, enabled = !state.busy) { Text("Write enemy data") }
                OutlinedButton(onClick = onRestore, enabled = !state.busy) { Text("Restore") }
            }
            Text(
                text = "The game's own data is never changed: the written lists sit beside it and are " +
                    "removed by Restore. Takes effect from the next room.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Everything the page draws, in one place: the seed, the kind switches, and the write's progress.
 * `total == 0` means the current job has no measurable steps (the restore), so the bar is
 * indeterminate.
 */
data class RandomizerUiState(
    val seed: Int = 0,
    val kinds: List<Boolean> = EnemyRandomizer.POOL.map { true },
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val step: String = "",
    val message: String = "",
    val failed: Boolean = false,
    val activeSummary: String = "No written enemy data is in place.",
) {
    /** The switches as the pool bitmask the writer reads: bit i = POOL[i]. */
    fun poolMask(): Int {
        var mask = 0
        kinds.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
        return mask
    }
}
