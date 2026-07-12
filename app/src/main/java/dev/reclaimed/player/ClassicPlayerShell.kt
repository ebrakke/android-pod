package dev.reclaimed.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.reclaimed.player.homeassistant.ContinueOnState
import dev.reclaimed.player.homeassistant.SonosPlayer
import dev.reclaimed.player.homeassistant.SonosRemoteSession
import dev.reclaimed.player.jellyfin.AlbumDownloadDetails
import dev.reclaimed.player.jellyfin.AlbumDownloadSummary
import dev.reclaimed.player.jellyfin.JellyfinAlbum
import dev.reclaimed.player.jellyfin.JellyfinArtist
import dev.reclaimed.player.jellyfin.JellyfinConfig
import dev.reclaimed.player.library.LocalAlbum
import dev.reclaimed.player.library.LocalArtist
import kotlinx.coroutines.delay
import kotlin.math.atan2

internal data class CommonShellActions(
    val onOpenLocal: () -> Unit,
    val onOpenSources: () -> Unit,
    val onOpenDownloads: () -> Unit,
    val onOpenJellyfin: () -> Unit,
    val onOpenJellyfinArtist: (JellyfinArtist) -> Unit,
    val onOpenJellyfinAlbum: (JellyfinAlbum) -> Unit,
    val onOpenArtist: (LocalArtist) -> Unit,
    val onOpenAlbum: (LocalAlbum) -> Unit,
    val onOpenNowPlaying: () -> Unit,
    val onOpenQueue: () -> Unit,
)

private data class ClassicQueueActions(
    val label: String,
    val onPlayNext: () -> Unit,
    val onAddToQueue: () -> Unit,
)

private data class ClassicMenuItem(
    val label: String,
    val detail: String = "",
    val onSelect: () -> Unit,
    val onPlay: () -> Unit = onSelect,
    val queueActions: ClassicQueueActions? = null,
)

@Composable
internal fun ClassicPlayerShell(
    interfaceMode: InterfaceMode,
    libraryState: LibraryState,
    artists: List<LocalArtist>,
    libraryScreen: LibraryScreen,
    nowPlaying: NowPlaying?,
    playbackQueue: PlaybackQueue,
    jellyfinConfig: JellyfinConfig,
    jellyfinArtists: List<JellyfinArtist>,
    jellyfinLibraryState: JellyfinLibraryState,
    downloadRevision: Int,
    managedDownloads: List<AlbumDownloadDetails>,
    actions: CommonShellActions,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
    onPlayAlbum: (LocalAlbum) -> Unit,
    onPlayTrack: (LocalAlbum, Int) -> Unit,
    onPlayJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onPlayJellyfinTrack: (JellyfinAlbum, Int) -> Unit,
    onPlayNextLocal: (LocalAlbum, Int?) -> Unit,
    onAddLocalToQueue: (LocalAlbum, Int?) -> Unit,
    onPlayNextJellyfin: (JellyfinAlbum, Int?) -> Unit,
    onAddJellyfinToQueue: (JellyfinAlbum, Int?) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onDownloadJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onRemoveJellyfinAlbumDownloads: (JellyfinAlbum) -> Unit,
    jellyfinDownloadSummary: (JellyfinAlbum) -> AlbumDownloadSummary,
    onTogglePlayback: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAdjustVolume: (Int) -> Float?,
    onSeek: (Long) -> Unit,
    continueOnState: ContinueOnState,
    activeSonosSession: SonosRemoteSession?,
    onOpenContinueOn: () -> Unit,
    onContinueOn: (SonosPlayer) -> Unit,
    onDisconnectSonos: () -> Unit,
    onSwitchToHybrid: () -> Unit,
    onSwitchToClassic: () -> Unit,
    onSwitchToTouch: () -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val isHybrid = interfaceMode == InterfaceMode.Hybrid
    val screenKey = libraryScreen.classicKey()
    var queueActions by remember { mutableStateOf<ClassicQueueActions?>(null) }
    var volumeLevel by remember { mutableStateOf<Float?>(null) }
    var volumeChangeSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(volumeChangeSequence) {
        if (volumeChangeSequence > 0) {
            delay(VOLUME_INDICATOR_DURATION_MS)
            volumeLevel = null
        }
    }
    BackHandler(enabled = libraryScreen !is LibraryScreen.Home || queueActions != null) {
        if (queueActions != null) queueActions = null else onBack()
    }
    LaunchedEffect(screenKey) { queueActions = null }
    val baseMenuItems = when (libraryState) {
        LibraryState.Loading -> listOf(statusItem("Scanning this device…"))
        LibraryState.PermissionRequired,
        LibraryState.PermissionDenied,
        -> listOf(ClassicMenuItem("Allow audio access", onSelect = onRequestPermission))
        LibraryState.Empty -> listOf(statusItem("No local music found"))
        is LibraryState.Error -> listOf(statusItem(libraryState.message))
        LibraryState.Ready -> classicItems(
            screen = libraryScreen,
            nowPlaying = nowPlaying,
            playbackQueue = playbackQueue,
            artists = artists,
            jellyfinConfig = jellyfinConfig,
            jellyfinArtists = jellyfinArtists,
            jellyfinLibraryState = jellyfinLibraryState,
            downloadRevision = downloadRevision,
            managedDownloads = managedDownloads,
            actions = actions,
            onPlayAlbum = onPlayAlbum,
            onPlayTrack = onPlayTrack,
            onPlayJellyfinAlbum = onPlayJellyfinAlbum,
            onPlayJellyfinTrack = onPlayJellyfinTrack,
            onPlayNextLocal = onPlayNextLocal,
            onAddLocalToQueue = onAddLocalToQueue,
            onPlayNextJellyfin = onPlayNextJellyfin,
            onAddJellyfinToQueue = onAddJellyfinToQueue,
            onPlayQueueItem = onPlayQueueItem,
            onDownloadJellyfinAlbum = onDownloadJellyfinAlbum,
            onRemoveJellyfinAlbumDownloads = onRemoveJellyfinAlbumDownloads,
            jellyfinDownloadSummary = jellyfinDownloadSummary,
            continueOnState = continueOnState,
            onContinueOn = onContinueOn,
            onTogglePlayback = onTogglePlayback,
            onAdjustVolume = onAdjustVolume,
            onDisconnectSonos = onDisconnectSonos,
            interfaceMode = interfaceMode,
            onSwitchToHybrid = onSwitchToHybrid,
            onSwitchToClassic = onSwitchToClassic,
            onSwitchToTouch = onSwitchToTouch,
            onOpenTailscale = onOpenTailscale,
            onOpenWifiSettings = onOpenWifiSettings,
            onOpenBluetoothSettings = onOpenBluetoothSettings,
            onOpenSystemSettings = onOpenSystemSettings,
        )
    }
    val menuItems = queueActions?.let { actions ->
        listOf(
            ClassicMenuItem("Play Next", onSelect = {
                actions.onPlayNext()
                queueActions = null
            }),
            ClassicMenuItem("Add to Queue", onSelect = {
                actions.onAddToQueue()
                queueActions = null
            }),
        )
    } ?: baseMenuItems
    val displayKey = queueActions?.let { "$screenKey:queue-actions:${it.label}" } ?: screenKey
    var selectedIndex by remember(displayKey) { mutableIntStateOf(0) }
    LaunchedEffect(menuItems.size) {
        selectedIndex = if (menuItems.isEmpty()) 0 else selectedIndex.coerceIn(menuItems.indices)
    }

    val selectedItem = menuItems.getOrNull(selectedIndex)
    val isNowPlaying = queueActions == null && libraryScreen is LibraryScreen.NowPlaying
    Surface(
        color = if (isHybrid) TouchCanvas else CLASSIC_BODY,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = if (isHybrid) TouchCard else CLASSIC_SCREEN,
                shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                shadowElevation = 5.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.47f),
            ) {
                if (isNowPlaying) {
                    if (isHybrid) {
                        HybridNowPlaying(
                            nowPlaying = nowPlaying,
                            activeSonosSession = activeSonosSession,
                            onSeek = onSeek,
                            onOpenContinueOn = onOpenContinueOn,
                            onDisconnectSonos = onDisconnectSonos,
                        )
                    } else {
                        ClassicNowPlaying(nowPlaying, volumeLevel)
                    }
                } else {
                    val title = queueActions?.label ?: libraryScreen.classicTitle(jellyfinConfig)
                    val onActivate = { index: Int ->
                        selectedIndex = index
                        menuItems[index].onSelect()
                    }
                    if (isHybrid) {
                        HybridMenuDisplay(
                            title = title,
                            items = menuItems.map { HybridMenuRow(it.label, it.detail) },
                            selectedIndex = selectedIndex,
                            nowPlaying = nowPlaying,
                            onOpenNowPlaying = actions.onOpenNowPlaying,
                            onStep = { direction ->
                                if (menuItems.isNotEmpty()) {
                                    selectedIndex = Math.floorMod(
                                        selectedIndex + direction,
                                        menuItems.size,
                                    )
                                }
                            },
                            onActivate = onActivate,
                        )
                    } else {
                        ClassicMenuDisplay(
                            title = title,
                            items = menuItems,
                            selectedIndex = selectedIndex,
                            onActivate = onActivate,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.53f)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                ClassicWheel(
                    onStep = { direction ->
                        if (isNowPlaying) {
                            onAdjustVolume(direction)?.let { adjustedLevel ->
                                volumeLevel = adjustedLevel
                                volumeChangeSequence += 1
                            }
                        } else if (menuItems.isNotEmpty()) {
                            selectedIndex = Math.floorMod(
                                selectedIndex + direction,
                                menuItems.size,
                            )
                        }
                    },
                    onSelect = {
                        if (isNowPlaying) onTogglePlayback() else selectedItem?.onSelect?.invoke()
                    },
                    onSelectLongPress = {
                        if (!isNowPlaying) selectedItem?.queueActions?.let { queueActions = it }
                    },
                    onMenu = {
                        if (queueActions != null) queueActions = null else onBack()
                    },
                    onMenuLongPress = if (isHybrid) onSwitchToTouch else onSwitchToHybrid,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onPlayPause = {
                        if (isNowPlaying) onTogglePlayback() else selectedItem?.onPlay?.invoke()
                    },
                )
            }
        }
    }
}

@Composable
private fun ClassicMenuDisplay(
    title: String,
    items: List<ClassicMenuItem>,
    selectedIndex: Int,
    onActivate: (Int) -> Unit,
) {
    val visibleCount = 6
    val maxStart = (items.size - visibleCount).coerceAtLeast(0)
    val start = (selectedIndex - visibleCount / 2).coerceIn(0, maxStart)
    val end = (start + visibleCount).coerceAtMost(items.size)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CLASSIC_HEADER)
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Empty") }
        } else {
            for (index in start until end) {
                val item = items[index]
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(if (selected) CLASSIC_SELECTION else Color.Transparent)
                        .clickable { onActivate(index) }
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.label,
                            color = if (selected) Color.White else CLASSIC_INK,
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
                                    CLASSIC_INK.copy(alpha = 0.62f)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Text("›", color = if (selected) Color.White else CLASSIC_INK)
                }
            }
        }
    }
}

@Composable
private fun ClassicNowPlaying(nowPlaying: NowPlaying?, volumeLevel: Float?) {
    if (nowPlaying == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing playing")
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        nowPlaying.artworkUri?.let { uri ->
            AlbumArtwork(uri, Modifier.size(132.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            nowPlaying.title,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            nowPlaying.artist,
            color = CLASSIC_INK.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (nowPlaying.queueSize > 0) {
            Text(
                "${nowPlaying.queueIndex + 1} of ${nowPlaying.queueSize}",
                style = MaterialTheme.typography.labelSmall,
                color = CLASSIC_INK.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.height(12.dp))
        if (volumeLevel != null) {
            Text(
                "Volume",
                style = MaterialTheme.typography.labelSmall,
                color = CLASSIC_INK.copy(alpha = 0.65f),
            )
            LinearProgressIndicator(
                progress = { volumeLevel },
                modifier = Modifier.fillMaxWidth(0.76f),
                color = CLASSIC_SELECTION,
                trackColor = CLASSIC_INK.copy(alpha = 0.15f),
            )
        } else {
            LinearProgressIndicator(
                progress = {
                    if (nowPlaying.durationMs > 0L) {
                        (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(0.76f),
                color = CLASSIC_SELECTION,
                trackColor = CLASSIC_INK.copy(alpha = 0.15f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(0.76f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatTrackDuration(nowPlaying.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    formatTrackDuration(nowPlaying.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (nowPlaying.isPlaying) "▶" else "Ⅱ", fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClassicWheel(
    onStep: (Int) -> Unit,
    onSelect: () -> Unit,
    onSelectLongPress: () -> Unit,
    onMenu: () -> Unit,
    onMenuLongPress: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentOnStep by rememberUpdatedState(onStep)
    val menuInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .widthIn(max = 350.dp)
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .background(CLASSIC_WHEEL, CircleShape)
            .border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
            .clip(CircleShape)
            .pointerInput(Unit) {
                var previousAngle = 0f
                var accumulatedAngle = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        previousAngle = offset.angleAround(size)
                        accumulatedAngle = 0f
                    },
                    onDrag = { change, _ ->
                        val currentAngle = change.position.angleAround(size)
                        var delta = currentAngle - previousAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        accumulatedAngle += delta
                        previousAngle = currentAngle
                        while (kotlin.math.abs(accumulatedAngle) >= WHEEL_STEP_DEGREES) {
                            val direction = if (accumulatedAngle > 0) 1 else -1
                            currentOnStep(direction)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            accumulatedAngle -= direction * WHEEL_STEP_DEGREES
                        }
                        change.consume()
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 112.dp, height = 72.dp)
                .combinedClickable(
                    interactionSource = menuInteractionSource,
                    indication = null,
                    onClick = onMenu,
                    onLongClick = onMenuLongPress,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("MENU", color = CLASSIC_CONTROL, fontWeight = FontWeight.Bold)
        }
        WheelButton("◀◀", Alignment.CenterStart, onPrevious)
        WheelButton("▶▶", Alignment.CenterEnd, onNext)
        WheelButton("▶ Ⅱ", Alignment.BottomCenter, onPlayPause)
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .size(112.dp)
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onSelectLongPress,
                ),
            shape = CircleShape,
            color = CLASSIC_BODY,
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("SELECT", color = CLASSIC_CONTROL, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.WheelButton(
    label: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .align(alignment)
            .size(76.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = CLASSIC_CONTROL, fontWeight = FontWeight.Bold)
    }
}

private fun classicItems(
    screen: LibraryScreen,
    nowPlaying: NowPlaying?,
    playbackQueue: PlaybackQueue,
    artists: List<LocalArtist>,
    jellyfinConfig: JellyfinConfig,
    jellyfinArtists: List<JellyfinArtist>,
    jellyfinLibraryState: JellyfinLibraryState,
    @Suppress("UNUSED_PARAMETER") downloadRevision: Int,
    managedDownloads: List<AlbumDownloadDetails>,
    actions: CommonShellActions,
    onPlayAlbum: (LocalAlbum) -> Unit,
    onPlayTrack: (LocalAlbum, Int) -> Unit,
    onPlayJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onPlayJellyfinTrack: (JellyfinAlbum, Int) -> Unit,
    onPlayNextLocal: (LocalAlbum, Int?) -> Unit,
    onAddLocalToQueue: (LocalAlbum, Int?) -> Unit,
    onPlayNextJellyfin: (JellyfinAlbum, Int?) -> Unit,
    onAddJellyfinToQueue: (JellyfinAlbum, Int?) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onDownloadJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onRemoveJellyfinAlbumDownloads: (JellyfinAlbum) -> Unit,
    jellyfinDownloadSummary: (JellyfinAlbum) -> AlbumDownloadSummary,
    continueOnState: ContinueOnState,
    onContinueOn: (SonosPlayer) -> Unit,
    onTogglePlayback: () -> Unit,
    onAdjustVolume: (Int) -> Float?,
    onDisconnectSonos: () -> Unit,
    interfaceMode: InterfaceMode,
    onSwitchToHybrid: () -> Unit,
    onSwitchToClassic: () -> Unit,
    onSwitchToTouch: () -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
): List<ClassicMenuItem> = when (screen) {
    LibraryScreen.Home -> buildList {
        if (nowPlaying != null) {
            add(ClassicMenuItem("Now Playing", nowPlaying.artist, actions.onOpenNowPlaying))
        }
        if (playbackQueue.items.isNotEmpty()) {
            add(ClassicMenuItem("Queue", playbackQueue.positionLabel(), actions.onOpenQueue))
        }
        add(
            ClassicMenuItem(
                "On Device",
                "${artists.size} artists · ${artists.sumOf { it.trackCount }} tracks",
                actions.onOpenLocal,
            ),
        )
        if (jellyfinConfig.libraryId != null) {
            add(
                ClassicMenuItem(
                    jellyfinConfig.libraryName ?: "Jellyfin",
                    "${jellyfinArtists.size} artists · " +
                        "${jellyfinArtists.sumOf { it.trackCount }} tracks",
                    actions.onOpenJellyfin,
                ),
            )
        }
        add(
            ClassicMenuItem(
                "Downloads",
                if (managedDownloads.isEmpty()) {
                    "No music saved offline"
                } else {
                    "${managedDownloads.size} " +
                        "${if (managedDownloads.size == 1) "album" else "albums"} · " +
                        formatBytes(managedDownloads.sumOf { it.bytes })
                },
                actions.onOpenDownloads,
            ),
        )
        add(ClassicMenuItem("Device & Sources", onSelect = actions.onOpenSources))
        if (interfaceMode == InterfaceMode.Hybrid) {
            add(ClassicMenuItem("Open Full Touch", onSelect = onSwitchToTouch))
            add(ClassicMenuItem("Switch to Classic", onSelect = onSwitchToClassic))
        } else {
            add(ClassicMenuItem("Switch to Hybrid", onSelect = onSwitchToHybrid))
        }
    }
    LibraryScreen.NowPlaying -> emptyList()
    LibraryScreen.ContinueOn -> when (continueOnState) {
        ContinueOnState.Idle,
        ContinueOnState.LoadingPlayers,
        -> listOf(statusItem("Finding Sonos speakers…"))
        is ContinueOnState.Error -> listOf(
            ClassicMenuItem(
                continueOnState.message,
                "Open full Touch to edit the connection",
                onSelect = onSwitchToTouch,
            ),
        )
        is ContinueOnState.Transferring -> listOf(
            statusItem("Continuing on ${continueOnState.player.name}…"),
        )
        is ContinueOnState.Ready -> if (continueOnState.players.isEmpty()) {
            listOf(statusItem("No Sonos speakers found"))
        } else {
            continueOnState.players.map { player ->
                ClassicMenuItem(
                    player.name,
                    player.state,
                    onSelect = { onContinueOn(player) },
                )
            }
        }
        is ContinueOnState.Complete -> buildList {
            add(
                ClassicMenuItem(
                    if (continueOnState.isPlaying) "Pause Sonos" else "Play on Sonos",
                    continueOnState.player.name,
                    onSelect = onTogglePlayback,
                ),
            )
            add(ClassicMenuItem("Volume −", onSelect = { onAdjustVolume(-1) }))
            add(ClassicMenuItem("Volume +", onSelect = { onAdjustVolume(1) }))
            add(
                ClassicMenuItem(
                    "Disconnect",
                    continueOnState.player.name,
                    onSelect = onDisconnectSonos,
                ),
            )
            continueOnState.error?.let { add(statusItem(it)) }
        }
    }
    LibraryScreen.Queue -> if (playbackQueue.items.isEmpty()) {
        listOf(statusItem("The queue is empty"))
    } else {
        playbackQueue.items.mapIndexed { index, item ->
            ClassicMenuItem(
                label = if (index == playbackQueue.currentIndex) "▶ ${item.title}" else item.title,
                detail = item.artist,
                onSelect = { onPlayQueueItem(index) },
            )
        }
    }
    LibraryScreen.Downloads -> if (managedDownloads.isEmpty()) {
        listOf(statusItem("No music saved offline"))
    } else {
        managedDownloads.map { download ->
            ClassicMenuItem(
                download.album.title,
                "${download.album.artist} · ${formatBytes(download.bytes)}",
                onSelect = { actions.onOpenJellyfinAlbum(download.album) },
                onPlay = { onPlayJellyfinAlbum(download.album) },
                queueActions = ClassicQueueActions(
                    label = download.album.title,
                    onPlayNext = { onPlayNextJellyfin(download.album, null) },
                    onAddToQueue = { onAddJellyfinToQueue(download.album, null) },
                ),
            )
        }
    }
    LibraryScreen.Artists -> artists.map { artist ->
        ClassicMenuItem(
            artist.name,
            "${artist.albums.size} albums · ${artist.trackCount} tracks",
            onSelect = { actions.onOpenArtist(artist) },
        )
    }
    is LibraryScreen.Albums -> screen.artist.albums.map { album ->
        ClassicMenuItem(
            album.title,
            "${album.tracks.size} tracks",
            onSelect = { actions.onOpenAlbum(album) },
            onPlay = { onPlayAlbum(album) },
            queueActions = ClassicQueueActions(
                label = album.title,
                onPlayNext = { onPlayNextLocal(album, null) },
                onAddToQueue = { onAddLocalToQueue(album, null) },
            ),
        )
    }
    is LibraryScreen.Album -> buildList {
        add(
            ClassicMenuItem(
                "Play Album",
                screen.album.artist,
                onSelect = { onPlayAlbum(screen.album) },
                queueActions = ClassicQueueActions(
                    label = screen.album.title,
                    onPlayNext = { onPlayNextLocal(screen.album, null) },
                    onAddToQueue = { onAddLocalToQueue(screen.album, null) },
                ),
            ),
        )
        screen.album.tracks.forEachIndexed { index, track ->
            add(
                ClassicMenuItem(
                    track.title,
                    track.trackNumber?.let { "Track $it" }.orEmpty(),
                    onSelect = { onPlayTrack(screen.album, index) },
                    queueActions = ClassicQueueActions(
                        label = track.title,
                        onPlayNext = { onPlayNextLocal(screen.album, index) },
                        onAddToQueue = { onAddLocalToQueue(screen.album, index) },
                    ),
                ),
            )
        }
    }
    LibraryScreen.JellyfinArtists -> when (jellyfinLibraryState) {
        JellyfinLibraryState.NotLoaded,
        JellyfinLibraryState.Loading,
        -> listOf(statusItem("Loading Jellyfin music…"))
        is JellyfinLibraryState.Error -> listOf(statusItem(jellyfinLibraryState.message))
        JellyfinLibraryState.Ready -> jellyfinArtists.map { artist ->
            ClassicMenuItem(
                artist.name,
                "${artist.albums.size} albums · ${artist.trackCount} tracks",
                onSelect = { actions.onOpenJellyfinArtist(artist) },
            )
        }
    }
    is LibraryScreen.JellyfinAlbums -> screen.artist.albums.map { album ->
        ClassicMenuItem(
            album.title,
            "${album.tracks.size} tracks",
            onSelect = { actions.onOpenJellyfinAlbum(album) },
            onPlay = { onPlayJellyfinAlbum(album) },
            queueActions = ClassicQueueActions(
                label = album.title,
                onPlayNext = { onPlayNextJellyfin(album, null) },
                onAddToQueue = { onAddJellyfinToQueue(album, null) },
            ),
        )
    }
    is LibraryScreen.JellyfinAlbumDetail -> buildList {
        val summary = jellyfinDownloadSummary(screen.album)
        add(
            ClassicMenuItem(
                if (summary.isComplete) "Play Downloaded Album" else "Play Album",
                screen.album.artist,
                onSelect = { onPlayJellyfinAlbum(screen.album) },
                queueActions = ClassicQueueActions(
                    label = screen.album.title,
                    onPlayNext = { onPlayNextJellyfin(screen.album, null) },
                    onAddToQueue = { onAddJellyfinToQueue(screen.album, null) },
                ),
            ),
        )
        add(
            if (summary.isComplete) {
                ClassicMenuItem(
                    "Remove Download",
                    "${summary.total} tracks offline",
                    onSelect = { onRemoveJellyfinAlbumDownloads(screen.album) },
                )
            } else {
                ClassicMenuItem(
                    if (summary.downloading > 0) "Downloading…" else "Download Album",
                    if (summary.downloading > 0) {
                        "${summary.downloaded}/${summary.total} tracks"
                    } else {
                        "Available offline"
                    },
                    onSelect = { onDownloadJellyfinAlbum(screen.album) },
                )
            },
        )
        screen.album.tracks.forEachIndexed { index, track ->
            add(
                ClassicMenuItem(
                    track.title,
                    track.trackNumber?.let { "Track $it" }.orEmpty(),
                    onSelect = { onPlayJellyfinTrack(screen.album, index) },
                    queueActions = ClassicQueueActions(
                        label = track.title,
                        onPlayNext = { onPlayNextJellyfin(screen.album, index) },
                        onAddToQueue = { onAddJellyfinToQueue(screen.album, index) },
                    ),
                ),
            )
        }
    }
    LibraryScreen.ManageSources -> listOf(
        ClassicMenuItem("Open Touch Settings", onSelect = onSwitchToTouch),
        ClassicMenuItem("Tailscale", onSelect = onOpenTailscale),
        ClassicMenuItem("Wi-Fi", onSelect = onOpenWifiSettings),
        ClassicMenuItem("Bluetooth", onSelect = onOpenBluetoothSettings),
        ClassicMenuItem("All System Settings", onSelect = onOpenSystemSettings),
    )
}

private fun statusItem(message: String) = ClassicMenuItem(message, onSelect = {})

private fun LibraryScreen.classicKey(): String = when (this) {
    LibraryScreen.Home -> "home"
    LibraryScreen.NowPlaying -> "now-playing"
    LibraryScreen.ContinueOn -> "continue-on"
    LibraryScreen.Queue -> "queue"
    LibraryScreen.Artists -> "local-artists"
    LibraryScreen.ManageSources -> "settings"
    LibraryScreen.Downloads -> "downloads"
    LibraryScreen.JellyfinArtists -> "jellyfin-artists"
    is LibraryScreen.JellyfinAlbums -> "jellyfin-artist:${artist.id}"
    is LibraryScreen.JellyfinAlbumDetail -> "jellyfin-album:${album.id}"
    is LibraryScreen.Albums -> "local-artist:${artist.name}"
    is LibraryScreen.Album -> "local-album:${album.id}"
}

private fun LibraryScreen.classicTitle(config: JellyfinConfig): String = when (this) {
    LibraryScreen.Home -> "Reclaimed"
    LibraryScreen.NowPlaying -> "Now Playing"
    LibraryScreen.ContinueOn -> "Continue on…"
    LibraryScreen.Queue -> "Queue"
    LibraryScreen.Artists -> "On Device"
    LibraryScreen.ManageSources -> "Device"
    LibraryScreen.Downloads -> "Downloads"
    LibraryScreen.JellyfinArtists -> config.libraryName ?: "Jellyfin"
    is LibraryScreen.JellyfinAlbums -> artist.name
    is LibraryScreen.JellyfinAlbumDetail -> album.title
    is LibraryScreen.Albums -> artist.name
    is LibraryScreen.Album -> album.title
}

private fun Offset.angleAround(size: IntSize): Float {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    return Math.toDegrees(
        atan2((y - centerY).toDouble(), (x - centerX).toDouble()),
    ).toFloat()
}

private const val WHEEL_STEP_DEGREES = 18f
private const val VOLUME_INDICATOR_DURATION_MS = 1_500L
private val CLASSIC_BODY = Color(0xFFE3DED4)
private val CLASSIC_WHEEL = Color(0xFFF7F4EC)
private val CLASSIC_SCREEN = Color(0xFFFBFAF5)
private val CLASSIC_HEADER = Color(0xFF30343B)
private val CLASSIC_SELECTION = Color(0xFF2878C8)
private val CLASSIC_INK = Color(0xFF202328)
private val CLASSIC_CONTROL = Color(0xFF69717B)
