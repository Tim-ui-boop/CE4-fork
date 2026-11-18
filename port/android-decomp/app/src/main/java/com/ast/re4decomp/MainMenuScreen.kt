package com.ast.re4decomp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The main menu (Jetpack Compose, 18.11.2026): the one screen a player sees before the game, on a
 * fresh install and on every start after it.
 *
 * The owner's wish, worded after three attempts that each put the settings on their own page:
 * "a main menu that greets user pre starting game, that has the randomizer settings on the page and
 * then the start game button itself". So nothing here navigates to another screen -- the disc import,
 * every randomizer, and Start game all live on this one scrollable page.
 *
 * Sections collapse (the owner's choice: "it's going to be packed with future randomizers"), so the
 * item, briefcase and merchant randomizers can join without growing the page a reader must scroll
 * past to reach Start game.
 */
@Composable
fun MainMenuScreen(
    state: MainMenuUiState,
    onPickDiscs: () -> Unit,
    onCancelImport: () -> Unit,
    onSeedChange: (Int) -> Unit,
    onKindToggle: (Int, Boolean) -> Unit,
    onWrite: () -> Unit,
    onRestore: () -> Unit,
    onStart: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Resident Evil 4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "CubeEvil4",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            // ---- the discs: the import lives here on a fresh install and stays reachable after --------
            Section(
                title = if (state.discReady) "Discs ready" else "Discs",
                subtitle = if (state.discReady) "Your discs are imported." else "Pick the ISO, GCM or RVZ files of the release you own.",
                expanded = state.discsExpanded || !state.discReady,
                onToggle = { state.onDiscsToggle() },
            ) {
                if (!state.discReady || state.importBusy) {
                    Text(
                        state.importStatus.ifEmpty { "No discs imported yet." },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.importBusy) {
                        if (state.importTotal > 0) {
                            LinearProgressIndicator(
                                progress = { state.importDone.toFloat() / state.importTotal.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${state.importDone} of ${state.importTotal}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onPickDiscs, enabled = !state.importBusy) {
                            Text(if (state.discReady) "Import again" else "Select discs")
                        }
                        if (state.importBusy) {
                            OutlinedButton(onClick = onCancelImport) { Text("Cancel") }
                        }
                    }
                } else {
                    Text(
                        "Everything is in place. Start the game whenever you like.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- the enemy randomizer ----------------------------------------------------------------
            Section(
                title = "Enemies",
                subtitle = if (state.seed == 0) "No seed yet" else "Seed ${state.seed} · ${state.writtenSummary}",
                expanded = state.enemiesExpanded,
                onToggle = { state.onEnemiesToggle() },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onSeedChange(RandomizerConfig.freshSeed()) }) { Text("New seed") }
                }

                Text(
                    "Which enemies may appear",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                EnemyRandomizer.POOL.forEachIndexed { index, kind ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(kind.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${kind.costKb / 1024} MB in a room",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.kinds.getOrElse(index) { true },
                            onCheckedChange = { onKindToggle(index, it) },
                            enabled = !state.writeBusy,
                        )
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 6.dp))

                if (state.writeBusy) {
                    Text(
                        state.writeStep.ifEmpty { "Working…" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.writeTotal > 0) {
                        LinearProgressIndicator(
                            progress = { state.writeDone.toFloat() / state.writeTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.writeDone} of ${state.writeTotal} lists",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } else if (state.writeMessage.isNotEmpty()) {
                    Text(
                        state.writeMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.writeFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onWrite, enabled = !state.writeBusy) { Text("Write enemy data") }
                    OutlinedButton(onClick = onRestore, enabled = !state.writeBusy) { Text("Restore") }
                }
                Text(
                    "The game's own data is never changed: the written lists sit beside it and Restore " +
                        "removes them. Takes effect from the next room.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- the future randomizers, so the shape is clear --------------------------------------
            Section(
                title = "Items, briefcase, merchant",
                subtitle = "Coming with the next randomizers.",
                expanded = state.moreExpanded,
                onToggle = { state.onMoreToggle() },
            ) {
                Text(
                    "These will sit here the same way the enemies do: their own section, their own seed, " +
                        "written into the data before the game starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))

            // ---- start ------------------------------------------------------------------------------
            Button(
                onClick = onStart,
                enabled = state.discReady && !state.importBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start game", modifier = Modifier.padding(vertical = 6.dp))
            }
            if (!state.discReady) {
                Text(
                    "Import your discs first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A collapsible section: its header is always visible, its body only when expanded. */
@Composable
private fun Section(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (expanded) "–" else "+",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Everything the menu draws, in one object. The page never reads a setting from anywhere else, which
 * is what went wrong on the old Java page: an enemy-kind switch was drawn and the writer never
 * consulted it, so Cows kept appearing after being switched off.
 */
data class MainMenuUiState(
    // discs
    val discReady: Boolean = false,
    val importBusy: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val importStatus: String = "",
    val discsExpanded: Boolean = false,
    // the run
    val seed: Int = 0,
    val kinds: List<Boolean> = EnemyRandomizer.POOL.map { true },
    val writtenSummary: String = "nothing written yet",
    // the write
    val writeBusy: Boolean = false,
    val writeDone: Int = 0,
    val writeTotal: Int = 0,
    val writeStep: String = "",
    val writeMessage: String = "",
    val writeFailed: Boolean = false,
    // sections
    val enemiesExpanded: Boolean = true,
    val moreExpanded: Boolean = false,
) {
    fun onDiscsToggle() = copy(discsExpanded = !discsExpanded)
    fun onEnemiesToggle() = copy(enemiesExpanded = !enemiesExpanded)
    fun onMoreToggle() = copy(moreExpanded = !moreExpanded)

    /** The switches as the pool bitmask the writer reads: bit i = POOL[i]. */
    fun poolMask(): Int {
        var mask = 0
        kinds.forEachIndexed { i, on -> if (on) mask = mask or (1 shl i) }
        return mask
    }
}
