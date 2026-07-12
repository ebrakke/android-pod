package dev.reclaimed.player.homeassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.reclaimed.player.BackHeader

sealed interface ContinueOnState {
    data object Idle : ContinueOnState
    data object LoadingPlayers : ContinueOnState
    data class Ready(val players: List<SonosPlayer>) : ContinueOnState
    data class Transferring(val player: SonosPlayer) : ContinueOnState
    data class Complete(
        val player: SonosPlayer,
        val isPlaying: Boolean,
        val isChanging: Boolean = false,
        val error: String? = null,
    ) : ContinueOnState
    data class Error(val message: String) : ContinueOnState
}

@Composable
fun ContinueOnScreen(
    state: ContinueOnState,
    onContinueOn: (SonosPlayer) -> Unit,
    onTogglePlayback: () -> Unit,
    onAdjustVolume: (Int) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BackHeader("Continue on…", "Now Playing", onBack)
            Text(
                "Move the active Jellyfin queue and position to a Sonos speaker.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (state) {
            ContinueOnState.Idle,
            ContinueOnState.LoadingPlayers,
            -> item { Text("Finding Sonos speakers…") }
            is ContinueOnState.Error -> item {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
            is ContinueOnState.Transferring -> item {
                Text("Continuing on ${state.player.name}…")
            }
            is ContinueOnState.Complete -> item {
                Text("Playing on ${state.player.name}")
                Text(
                    "Playback on this device is paused. Controls now target Sonos.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onTogglePlayback,
                    enabled = !state.isChanging,
                ) {
                    Text(
                        if (state.isChanging) {
                            "Updating…"
                        } else if (state.isPlaying) {
                            "Pause Sonos"
                        } else {
                            "Play on Sonos"
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { onAdjustVolume(-1) }) { Text("Volume −") }
                    Button(onClick = { onAdjustVolume(1) }) { Text("Volume +") }
                }
                state.error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.error)
                }
            }
            is ContinueOnState.Ready -> if (state.players.isEmpty()) {
                item { Text("No Sonos players were found in Home Assistant.") }
            } else {
                items(state.players, key = { it.entityId }) { player ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onContinueOn(player) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(player.name, style = MaterialTheme.typography.titleLarge)
                            Text(player.state, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
