package com.ast.re4decomp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The disc import, shown on a fresh install (Jetpack Compose, 18.11.2026).
 *
 * It stands in front of the same ImportManager the Java screen used, so nothing about the import
 * itself changed -- only that a first-time player now gets one screen that says what to pick, shows
 * a bar while it copies, and names a failure in the same place it starts.
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onPickDiscs: () -> Unit,
    onCancel: () -> Unit,
    onReady: () -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Resident Evil 4", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = "CubeEvil4 plays the game from your own discs. Pick the ISO, GCM or RVZ files of " +
                "the release you own, once.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (state.ready) "Discs ready" else "No discs imported yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(state.status, style = MaterialTheme.typography.bodySmall)

                when {
                    state.busy -> {
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { state.done.toFloat() / state.total.toFloat() },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${state.done} of ${state.total}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    state.failed -> Text(
                        state.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPickDiscs, enabled = !state.busy) {
                        Text(if (state.ready) "Import again" else "Select discs")
                    }
                    if (state.busy) {
                        Button(onClick = onCancel) { Text("Cancel") }
                    }
                }
            }
        }

        if (state.ready) {
            Button(onClick = onReady, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }

        Text(
            text = "About 3 GB of free space per disc. The files stay on this device; nothing is uploaded.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the import screen draws: readiness, progress, and the last word on it. */
data class ImportUiState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val status: String = "",
    val failed: Boolean = false,
)
