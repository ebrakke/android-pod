package dev.reclaimed.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.reclaimed.player.homeassistant.SonosRemoteSession
import dev.reclaimed.player.playback.PlaybackSource

internal data class HybridMenuRow(
    val label: String,
    val detail: String,
)

@Composable
internal fun HybridMenuDisplay(
    title: String,
    items: List<HybridMenuRow>,
    selectedIndex: Int,
    nowPlaying: NowPlaying?,
    onOpenNowPlaying: () -> Unit,
    onStep: (Int) -> Unit,
    onActivate: (Int) -> Unit,
) {
    val visibleCount = if (nowPlaying != null) 5 else 6
    val maxStart = (items.size - visibleCount).coerceAtLeast(0)
    val start = (selectedIndex - visibleCount / 2).coerceIn(0, maxStart)
    val end = (start + visibleCount).coerceAtMost(items.size)
    val swipeModifier = Modifier.pointerInput(items.size) {
        val stepDistance = 28.dp.toPx()
        var accumulatedDrag = 0f
        detectVerticalDragGestures(
            onDragStart = { accumulatedDrag = 0f },
            onVerticalDrag = { change, dragAmount ->
                accumulatedDrag += dragAmount
                while (kotlin.math.abs(accumulatedDrag) >= stepDistance) {
                    val direction = if (accumulatedDrag < 0f) 1 else -1
                    onStep(direction)
                    accumulatedDrag += direction * stepDistance
                }
                change.consume()
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TouchBlue)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (items.isEmpty()) "—" else "${selectedIndex + 1}/${items.size}",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (items.isEmpty()) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("Empty") }
        } else {
            for (index in start until end) {
                val item = items[index]
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(if (selected) TouchOrange else Color.Transparent)
                        .clickable { onActivate(index) }
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.label,
                            color = if (selected) Color.White else TouchInk,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.detail.isNotBlank()) {
                            Text(
                                item.detail,
                                color = if (selected) {
                                    Color.White.copy(alpha = 0.82f)
                                } else {
                                    TouchMuted.copy(alpha = 0.72f)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text("›", color = if (selected) Color.White else TouchInk)
                }
            }
        }
        if (nowPlaying != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(TouchInk)
                    .clickable(onClick = onOpenNowPlaying)
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        nowPlaying.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        nowPlaying.artist,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(if (nowPlaying.isPlaying) "Ⅱ" else "▶", color = TouchOrange)
            }
        }
    }
}

@Composable
internal fun HybridNowPlaying(
    nowPlaying: NowPlaying?,
    activeSonosSession: SonosRemoteSession?,
    onSeek: (Long) -> Unit,
    onOpenContinueOn: () -> Unit,
    onDisconnectSonos: () -> Unit,
) {
    if (nowPlaying == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing playing", color = TouchInk)
        }
        return
    }
    var pendingSeekMs by remember(nowPlaying.title, nowPlaying.durationMs) {
        mutableStateOf<Float?>(null)
    }
    val displayedPosition = pendingSeekMs?.toLong() ?: nowPlaying.positionMs
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            nowPlaying.artworkUri?.let { uri ->
                AlbumArtwork(uri, Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.size(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activeSonosSession?.player?.name?.let { "PLAYING ON ${it.uppercase()}" }
                        ?: "NOW PLAYING",
                    color = if (activeSonosSession == null) TouchBlue else TouchOrange,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    nowPlaying.title,
                    color = TouchInk,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nowPlaying.artist,
                    color = TouchMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (nowPlaying.queueSize > 0) {
                    Text(
                        "${nowPlaying.queueIndex + 1} of ${nowPlaying.queueSize}",
                        color = TouchMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Slider(
            value = displayedPosition.toFloat(),
            onValueChange = { pendingSeekMs = it },
            onValueChangeFinished = {
                pendingSeekMs?.let { onSeek(it.toLong()) }
                pendingSeekMs = null
            },
            valueRange = 0f..nowPlaying.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = nowPlaying.durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = TouchOrange,
                activeTrackColor = TouchBlue,
                inactiveTrackColor = TouchBlueSoft,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTrackDuration(displayedPosition), color = TouchMuted)
            Text(
                "−${formatTrackDuration((nowPlaying.durationMs - displayedPosition).coerceAtLeast(0L))}",
                color = TouchMuted,
            )
        }
        if (activeSonosSession != null) {
            TextButton(onClick = onDisconnectSonos) {
                Text("Disconnect ${activeSonosSession.player.name}", color = TouchBlue)
            }
        } else if (nowPlaying.source == PlaybackSource.JELLYFIN) {
            TextButton(onClick = onOpenContinueOn) { Text("Continue on…", color = TouchBlue) }
        }
    }
}
