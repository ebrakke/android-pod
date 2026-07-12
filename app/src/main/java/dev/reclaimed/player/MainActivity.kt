package dev.reclaimed.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.reclaimed.player.jellyfin.JellyfinClient
import dev.reclaimed.player.jellyfin.AlbumDownloadSummary
import dev.reclaimed.player.jellyfin.AlbumDownloadDetails
import dev.reclaimed.player.jellyfin.JellyfinAlbum
import dev.reclaimed.player.jellyfin.JellyfinArtist
import dev.reclaimed.player.jellyfin.JellyfinConfig
import dev.reclaimed.player.jellyfin.JellyfinDownloadStore
import dev.reclaimed.player.jellyfin.JellyfinLibrary
import dev.reclaimed.player.jellyfin.JellyfinMetadataCache
import dev.reclaimed.player.jellyfin.JellyfinMetadataSnapshot
import dev.reclaimed.player.jellyfin.JellyfinSettingsStore
import dev.reclaimed.player.jellyfin.JellyfinSyncScheduler
import dev.reclaimed.player.jellyfin.JellyfinTrack
import dev.reclaimed.player.library.LocalAlbum
import dev.reclaimed.player.library.LocalArtist
import dev.reclaimed.player.library.LocalTrack
import dev.reclaimed.player.library.queryLocalTracks
import dev.reclaimed.player.library.toLocalArtists
import dev.reclaimed.player.playback.PlaybackService
import dev.reclaimed.player.playback.PlaybackItemMetadata
import dev.reclaimed.player.playback.PlaybackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var artists by mutableStateOf<List<LocalArtist>>(emptyList())
    private var libraryState by mutableStateOf<LibraryState>(LibraryState.Loading)
    private var libraryScreen by mutableStateOf<LibraryScreen>(LibraryScreen.Home)
    private var screenBeforeNowPlaying: LibraryScreen = LibraryScreen.Home
    private var screenBeforeJellyfinAlbum: LibraryScreen = LibraryScreen.JellyfinArtists
    private var nowPlaying by mutableStateOf<NowPlaying?>(null)
    private var playbackQueue by mutableStateOf(PlaybackQueue())
    private var interfaceMode by mutableStateOf(InterfaceMode.Classic)
    private var jellyfinConfig by mutableStateOf(JellyfinConfig())
    private var jellyfinLibraries by mutableStateOf<List<JellyfinLibrary>>(emptyList())
    private var jellyfinArtists by mutableStateOf<List<JellyfinArtist>>(emptyList())
    private var jellyfinLibraryState by mutableStateOf<JellyfinLibraryState>(
        JellyfinLibraryState.NotLoaded,
    )
    private var jellyfinConnectionState by mutableStateOf<JellyfinConnectionState>(
        JellyfinConnectionState.Idle,
    )
    private lateinit var jellyfinSettingsStore: JellyfinSettingsStore
    private lateinit var jellyfinMetadataCache: JellyfinMetadataCache
    private lateinit var jellyfinDownloadStore: JellyfinDownloadStore
    private lateinit var interfaceModeStore: InterfaceModeStore
    private var downloadRevision by mutableStateOf(0)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var quickConnectAttempt = 0
    private var jellyfinLoadAttempt = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            controller?.let(::updateNowPlaying)
            progressHandler.postDelayed(this, if (controller?.isPlaying == true) 500L else 1_000L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateNowPlaying(player)
            updatePlaybackQueue(player)
        }
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) loadLibrary() else libraryState = LibraryState.PermissionDenied
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jellyfinSettingsStore = JellyfinSettingsStore(this)
        jellyfinMetadataCache = JellyfinMetadataCache(this)
        jellyfinDownloadStore = JellyfinDownloadStore(this)
        interfaceModeStore = InterfaceModeStore(this)
        interfaceMode = interfaceModeStore.load()
        jellyfinConfig = jellyfinSettingsStore.load()
        enableEdgeToEdge()
        applyInterfaceMode()
        setContent {
            MaterialTheme {
                val openNowPlaying = {
                    if (nowPlaying != null && libraryScreen !is LibraryScreen.NowPlaying) {
                        screenBeforeNowPlaying = libraryScreen
                        libraryScreen = LibraryScreen.NowPlaying
                    }
                }
                val commonShell = CommonShellActions(
                    onOpenLocal = { libraryScreen = LibraryScreen.Artists },
                    onOpenSources = { libraryScreen = LibraryScreen.ManageSources },
                    onOpenDownloads = { libraryScreen = LibraryScreen.Downloads },
                    onOpenJellyfin = {
                        libraryScreen = LibraryScreen.JellyfinArtists
                        if (jellyfinLibraryState is JellyfinLibraryState.NotLoaded) {
                            loadJellyfinLibrary()
                        }
                    },
                    onOpenJellyfinArtist = {
                        libraryScreen = LibraryScreen.JellyfinAlbums(it)
                    },
                    onOpenJellyfinAlbum = {
                        screenBeforeJellyfinAlbum = libraryScreen
                        libraryScreen = LibraryScreen.JellyfinAlbumDetail(it)
                    },
                    onOpenArtist = { libraryScreen = LibraryScreen.Albums(it) },
                    onOpenAlbum = { libraryScreen = LibraryScreen.Album(it) },
                    onOpenNowPlaying = openNowPlaying,
                    onOpenQueue = { libraryScreen = LibraryScreen.Queue },
                )
                val managedDownloads = remember(jellyfinArtists, downloadRevision) {
                    jellyfinDownloadStore.managedAlbums(
                        jellyfinArtists.flatMap { artist ->
                            artist.albums
                        },
                    )
                }
                val jellyfinArtworkUri = { album: JellyfinAlbum -> artworkUri(album) }
                if (interfaceMode == InterfaceMode.Classic) {
                    ClassicPlayerShell(
                        libraryState = libraryState,
                        artists = artists,
                        libraryScreen = libraryScreen,
                        nowPlaying = nowPlaying,
                        playbackQueue = playbackQueue,
                        jellyfinConfig = jellyfinConfig,
                        jellyfinArtists = jellyfinArtists,
                        jellyfinLibraryState = jellyfinLibraryState,
                        downloadRevision = downloadRevision,
                        managedDownloads = managedDownloads,
                        actions = commonShell,
                        onRequestPermission = ::ensureAudioPermission,
                        onBack = ::navigateBack,
                        onPlayAlbum = { album -> play(album, 0) },
                        onPlayTrack = ::play,
                        onPlayJellyfinAlbum = { album -> play(album, 0) },
                        onPlayJellyfinTrack = ::play,
                        onPlayNextLocal = ::playNext,
                        onAddLocalToQueue = ::addToQueue,
                        onPlayNextJellyfin = ::playNext,
                        onAddJellyfinToQueue = ::addToQueue,
                        onPlayQueueItem = ::playQueueItem,
                        onDownloadJellyfinAlbum = ::download,
                        onRemoveJellyfinAlbumDownloads = ::removeDownloads,
                        jellyfinDownloadSummary = jellyfinDownloadStore::summary,
                        onTogglePlayback = ::togglePlayback,
                        onPrevious = ::previousTrack,
                        onNext = ::nextTrack,
                        onAdjustVolume = ::adjustVolume,
                        onSwitchToTouch = { switchInterfaceMode(InterfaceMode.Touch) },
                        onOpenTailscale = ::openTailscale,
                        onOpenWifiSettings = {
                            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        },
                        onOpenBluetoothSettings = {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        },
                        onOpenSystemSettings = {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        },
                    )
                } else PlayerShell(
                    libraryState = libraryState,
                    artists = artists,
                    libraryScreen = libraryScreen,
                    nowPlaying = nowPlaying,
                    playbackQueue = playbackQueue,
                    jellyfinConfig = jellyfinConfig,
                    jellyfinLibraries = jellyfinLibraries,
                    jellyfinConnectionState = jellyfinConnectionState,
                    jellyfinArtists = jellyfinArtists,
                    jellyfinLibraryState = jellyfinLibraryState,
                    downloadRevision = downloadRevision,
                    managedDownloads = managedDownloads,
                    jellyfinArtworkUri = jellyfinArtworkUri,
                    onRequestPermission = ::ensureAudioPermission,
                    onOpenLocal = commonShell.onOpenLocal,
                    onOpenSources = commonShell.onOpenSources,
                    onOpenDownloads = commonShell.onOpenDownloads,
                    onOpenJellyfin = commonShell.onOpenJellyfin,
                    onOpenJellyfinArtist = commonShell.onOpenJellyfinArtist,
                    onOpenJellyfinAlbum = commonShell.onOpenJellyfinAlbum,
                    onOpenArtist = commonShell.onOpenArtist,
                    onOpenAlbum = commonShell.onOpenAlbum,
                    onOpenNowPlaying = commonShell.onOpenNowPlaying,
                    onOpenQueue = commonShell.onOpenQueue,
                    onBack = ::navigateBack,
                    onPlayAlbum = { album -> play(album, 0) },
                    onPlayTrack = ::play,
                    onPlayJellyfinAlbum = { album -> play(album, 0) },
                    onPlayJellyfinTrack = ::play,
                    onPlayQueueItem = ::playQueueItem,
                    onMoveQueueItem = ::moveQueueItem,
                    onRemoveQueueItem = ::removeQueueItem,
                    onClearQueue = ::clearQueue,
                    onToggleShuffle = ::toggleShuffle,
                    onCycleRepeatMode = ::cycleRepeatMode,
                    onDownloadJellyfinAlbum = ::download,
                    onRemoveJellyfinAlbumDownloads = ::removeDownloads,
                    jellyfinDownloadSummary = jellyfinDownloadStore::summary,
                    onRefreshDownloads = { downloadRevision += 1 },
                    onTogglePlayback = ::togglePlayback,
                    onConnectJellyfin = ::connectJellyfin,
                    onStartQuickConnect = ::startQuickConnect,
                    onSelectJellyfinLibrary = ::selectJellyfinLibrary,
                    onOpenTailscale = ::openTailscale,
                    onOpenWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    onOpenBluetoothSettings = {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    },
                    onOpenSystemSettings = { startActivity(Intent(Settings.ACTION_SETTINGS)) },
                    onSwitchToClassic = { switchInterfaceMode(InterfaceMode.Classic) },
                )
            }
        }
        ensureAudioPermission()
        if (jellyfinConfig.libraryId != null) {
            JellyfinSyncScheduler.schedule(this)
            loadJellyfinLibrary()
        }
    }

    @UnstableApi
    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener(
                {
                    runCatching { future.get() }.onSuccess { connectedController ->
                        if (controllerFuture === future) {
                            controller = connectedController.also {
                                it.addListener(playerListener)
                                updateNowPlaying(it)
                                updatePlaybackQueue(it)
                                progressHandler.removeCallbacks(progressUpdater)
                                progressHandler.post(progressUpdater)
                            }
                        } else {
                            MediaController.releaseFuture(future)
                        }
                    }
                },
                ContextCompat.getMainExecutor(this),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::interfaceModeStore.isInitialized) applyInterfaceMode()
        if (::jellyfinDownloadStore.isInitialized) downloadRevision += 1
    }

    override fun onStop() {
        progressHandler.removeCallbacks(progressUpdater)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    private fun navigateBack() {
        libraryScreen = when (val current = libraryScreen) {
            LibraryScreen.Home -> current
            LibraryScreen.NowPlaying -> screenBeforeNowPlaying
            LibraryScreen.Queue -> LibraryScreen.Home
            LibraryScreen.Artists -> LibraryScreen.Home
            LibraryScreen.ManageSources -> LibraryScreen.Home
            LibraryScreen.Downloads -> LibraryScreen.Home
            LibraryScreen.JellyfinArtists -> LibraryScreen.Home
            is LibraryScreen.JellyfinAlbums -> LibraryScreen.JellyfinArtists
            is LibraryScreen.JellyfinAlbumDetail -> {
                screenBeforeJellyfinAlbum
            }
            is LibraryScreen.Albums -> LibraryScreen.Artists
            is LibraryScreen.Album -> {
                val artist = artists.first { it.name == current.album.artist }
                LibraryScreen.Albums(artist)
            }
        }
    }

    private fun connectJellyfin(serverUrl: String, apiKey: String) {
        quickConnectAttempt += 1
        val config = jellyfinSettingsStore.saveConnection(serverUrl, apiKey)
        jellyfinConfig = config
        jellyfinConnectionState = JellyfinConnectionState.Connecting
        Thread {
            val result = runCatching { JellyfinClient(config).getLibraries() }
            runOnUiThread {
                result.onSuccess { libraries ->
                    jellyfinLibraries = libraries.sortedWith(
                        compareBy<JellyfinLibrary>(
                            { it.collectionType != "music" },
                            { it.name.lowercase() },
                        ),
                    )
                    jellyfinConnectionState = JellyfinConnectionState.Connected
                }.onFailure { error ->
                    jellyfinConnectionState = JellyfinConnectionState.Error(
                        error.message ?: "Unable to connect to Jellyfin",
                    )
                }
            }
        }.start()
    }

    private fun startQuickConnect(serverUrl: String) {
        val attempt = ++quickConnectAttempt
        val config = jellyfinSettingsStore.saveServerUrl(serverUrl)
        jellyfinConfig = config
        jellyfinConnectionState = JellyfinConnectionState.StartingQuickConnect

        Thread {
            val result = runCatching {
                val client = JellyfinClient(config)
                val request = client.initiateQuickConnect()
                runOnUiThread {
                    if (attempt == quickConnectAttempt) {
                        jellyfinConnectionState = JellyfinConnectionState.WaitingForApproval(
                            request.code,
                        )
                    }
                }

                var authorized = false
                var pollsRemaining = QUICK_CONNECT_MAX_POLLS
                while (attempt == quickConnectAttempt && pollsRemaining-- > 0 && !authorized) {
                    Thread.sleep(QUICK_CONNECT_POLL_INTERVAL_MS)
                    authorized = client.isQuickConnectAuthorized(request.secret)
                }
                check(authorized) { "Quick Connect expired before it was approved" }

                val authentication = client.authenticateWithQuickConnect(request.secret)
                val authenticatedConfig = jellyfinSettingsStore.saveQuickConnectAuthentication(
                    authentication.accessToken,
                    authentication.userId,
                )
                val libraries = JellyfinClient(authenticatedConfig).getLibraries()
                authenticatedConfig to libraries
            }

            runOnUiThread {
                if (attempt != quickConnectAttempt) return@runOnUiThread
                result.onSuccess { (authenticatedConfig, libraries) ->
                    jellyfinConfig = authenticatedConfig
                    jellyfinLibraries = libraries.sortedWith(
                        compareBy<JellyfinLibrary>(
                            { it.collectionType != "music" },
                            { it.name.lowercase() },
                        ),
                    )
                    jellyfinConnectionState = JellyfinConnectionState.Connected
                }.onFailure { error ->
                    jellyfinConnectionState = JellyfinConnectionState.Error(
                        error.message ?: "Quick Connect failed",
                    )
                }
            }
        }.start()
    }

    private fun selectJellyfinLibrary(library: JellyfinLibrary) {
        jellyfinConfig = jellyfinSettingsStore.selectLibrary(library)
        JellyfinSyncScheduler.schedule(this)
        loadJellyfinLibrary(forceRefresh = true)
    }

    private fun loadJellyfinLibrary(forceRefresh: Boolean = false) {
        val config = jellyfinSettingsStore.load()
        val libraryId = config.libraryId ?: return
        val attempt = ++jellyfinLoadAttempt
        if (jellyfinArtists.isEmpty()) jellyfinLibraryState = JellyfinLibraryState.Loading
        Thread {
            val cached = jellyfinMetadataCache.load(config.serverUrl, libraryId)
            if (cached != null) {
                jellyfinDownloadStore.ensureAlbumArtwork(config, cached.artists.flatMap { it.albums })
                Log.i(JELLYFIN_LOG_TAG, "Loaded ${cached.artists.size} artists from metadata cache")
                runOnUiThread {
                    if (attempt == jellyfinLoadAttempt) {
                        jellyfinArtists = cached.artists
                        jellyfinLibraryState = JellyfinLibraryState.Ready
                    }
                }
            }

            val cacheIsFresh = cached != null &&
                System.currentTimeMillis() - cached.refreshedAtMs < JELLYFIN_CACHE_FRESH_MS
            if (!forceRefresh && cacheIsFresh) return@Thread

            val result = runCatching {
                val artists = JellyfinClient(config).getMusicLibrary(libraryId)
                jellyfinMetadataCache.save(
                    JellyfinMetadataSnapshot(
                        serverUrl = config.serverUrl,
                        libraryId = libraryId,
                        refreshedAtMs = System.currentTimeMillis(),
                        artists = artists,
                    ),
                )
                jellyfinDownloadStore.ensureAlbumArtwork(config, artists.flatMap { it.albums })
                Log.i(JELLYFIN_LOG_TAG, "Refreshed ${artists.size} artists from Jellyfin")
                artists
            }
            runOnUiThread {
                if (attempt != jellyfinLoadAttempt) return@runOnUiThread
                result.onSuccess { artists ->
                    jellyfinArtists = artists
                    jellyfinLibraryState = JellyfinLibraryState.Ready
                }.onFailure { error ->
                    if (cached == null) {
                        jellyfinLibraryState = JellyfinLibraryState.Error(
                            error.message ?: "Unable to load the Jellyfin music library",
                        )
                    }
                }
            }
        }.start()
    }

    private fun openTailscale() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            ?: Intent().setClassName(TAILSCALE_PACKAGE, TAILSCALE_ACTIVITY)
        startActivity(launchIntent)
    }

    private fun ensureAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary()
        } else {
            libraryState = LibraryState.PermissionRequired
            requestAudioPermission.launch(permission)
        }
    }

    private fun loadLibrary() {
        libraryState = LibraryState.Loading
        Thread {
            val result = runCatching { contentResolver.queryLocalTracks().toLocalArtists() }
            runOnUiThread {
                result.onSuccess {
                    artists = it
                    libraryState = if (it.isEmpty()) LibraryState.Empty else LibraryState.Ready
                }.onFailure {
                    libraryState = LibraryState.Error(it.message ?: "Unable to read local music")
                }
            }
        }.start()
    }

    private fun play(album: LocalAlbum, startIndex: Int) {
        controller?.apply {
            setMediaItems(localMediaItems(album), startIndex, 0L)
            prepare()
            play()
        }
    }

    private fun localMediaItems(album: LocalAlbum): List<MediaItem> = album.tracks.map { track ->
        val extras = Bundle().apply {
            putString(PlaybackItemMetadata.SOURCE, PlaybackSource.LOCAL.name)
            putString(PlaybackItemMetadata.SOURCE_ID, track.id.toString())
            putString(PlaybackItemMetadata.ALBUM_ID, track.albumId.toString())
        }
        MediaItem.Builder()
            .setMediaId("local:${track.id}")
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(album.artworkUri)
                    .setTrackNumber(track.trackNumber)
                    .setDurationMs(track.durationMs)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun play(album: JellyfinAlbum, startIndex: Int) {
        controller?.apply {
            setMediaItems(jellyfinMediaItems(album), startIndex, 0L)
            prepare()
            play()
        }
    }
    private fun jellyfinMediaItems(album: JellyfinAlbum): List<MediaItem> {
        val client = JellyfinClient(jellyfinConfig)
        val artworkUri = jellyfinDownloadStore.localArtworkUri(album.id)
            ?: Uri.parse(client.imageUrl(album.id))
        return album.tracks.map { track ->
            val extras = Bundle().apply {
                putString(PlaybackItemMetadata.SOURCE, PlaybackSource.JELLYFIN.name)
                putString(PlaybackItemMetadata.SOURCE_ID, track.id)
                putString(PlaybackItemMetadata.ALBUM_ID, track.albumId)
            }
            MediaItem.Builder()
                .setMediaId("jellyfin:${track.id}")
                .setUri(
                    jellyfinDownloadStore.localUri(track.id)
                        ?: Uri.parse(client.streamUrl(track.id)),
                )
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(artworkUri)
                        .setTrackNumber(track.trackNumber)
                        .setDurationMs(track.durationMs)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }

    private fun playNext(album: LocalAlbum, trackIndex: Int?) {
        insertNext(localMediaItems(album).selectedTrack(trackIndex))
    }

    private fun addToQueue(album: LocalAlbum, trackIndex: Int?) {
        appendToQueue(localMediaItems(album).selectedTrack(trackIndex))
    }

    private fun playNext(album: JellyfinAlbum, trackIndex: Int?) {
        insertNext(jellyfinMediaItems(album).selectedTrack(trackIndex))
    }

    private fun addToQueue(album: JellyfinAlbum, trackIndex: Int?) {
        appendToQueue(jellyfinMediaItems(album).selectedTrack(trackIndex))
    }

    private fun List<MediaItem>.selectedTrack(trackIndex: Int?): List<MediaItem> =
        trackIndex?.let { listOf(get(it)) } ?: this

    private fun insertNext(items: List<MediaItem>) {
        if (items.isEmpty()) return
        controller?.apply {
            if (mediaItemCount == 0) {
                setMediaItems(items)
                prepare()
                play()
            } else {
                addMediaItems((currentMediaItemIndex + 1).coerceAtMost(mediaItemCount), items)
            }
        }
    }

    private fun appendToQueue(items: List<MediaItem>) {
        if (items.isEmpty()) return
        controller?.apply {
            val wasEmpty = mediaItemCount == 0
            addMediaItems(items)
            if (wasEmpty) prepare()
        }
    }

    private fun playQueueItem(index: Int) {
        controller?.apply {
            if (index !in 0 until mediaItemCount) return
            seekToDefaultPosition(index)
            play()
        }
    }

    private fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        controller?.apply {
            if (fromIndex !in 0 until mediaItemCount || toIndex !in 0 until mediaItemCount) return
            moveMediaItem(fromIndex, toIndex)
        }
    }

    private fun removeQueueItem(index: Int) {
        controller?.apply {
            if (index !in 0 until mediaItemCount || index == currentMediaItemIndex) return
            removeMediaItem(index)
        }
    }

    private fun clearQueue() {
        controller?.apply {
            val currentIndex = currentMediaItemIndex
            if (currentIndex !in 0 until mediaItemCount) {
                clearMediaItems()
                return
            }
            if (currentIndex + 1 < mediaItemCount) {
                removeMediaItems(currentIndex + 1, mediaItemCount)
            }
            if (currentIndex > 0) {
                removeMediaItems(0, currentIndex)
            }
        }
    }

    private fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    private fun cycleRepeatMode() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun download(album: JellyfinAlbum) {
        jellyfinDownloadStore.enqueueAlbum(jellyfinConfig, album)
        downloadRevision += 1
    }

    private fun artworkUri(album: JellyfinAlbum): Uri =
        jellyfinDownloadStore.localArtworkUri(album.id)
            ?: Uri.parse(JellyfinClient(jellyfinConfig).imageUrl(album.id))

    private fun removeDownloads(album: JellyfinAlbum) {
        jellyfinDownloadStore.removeAlbum(album)
        downloadRevision += 1
    }

    private fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun previousTrack() {
        controller?.let { player ->
            if (player.currentPosition > 3_000) player.seekTo(0) else player.seekToPreviousMediaItem()
        }
    }

    private fun nextTrack() {
        controller?.seekToNextMediaItem()
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(AudioManager::class.java)
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun switchInterfaceMode(mode: InterfaceMode) {
        interfaceMode = mode
        interfaceModeStore.save(mode)
        applyInterfaceMode()
    }

    private fun applyInterfaceMode() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (interfaceMode == InterfaceMode.Classic) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateNowPlaying(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        nowPlaying = metadata?.title?.toString()?.let { title ->
            NowPlaying(
                title = title,
                artist = metadata.artist?.toString().orEmpty(),
                artworkUri = metadata.artworkUri,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                queueIndex = player.currentMediaItemIndex,
                queueSize = player.mediaItemCount,
            )
        }
    }

    private fun updatePlaybackQueue(player: Player) {
        playbackQueue = PlaybackQueue(
            items = (0 until player.mediaItemCount).map { index ->
                val mediaItem = player.getMediaItemAt(index)
                val metadata = mediaItem.mediaMetadata
                PlaybackQueueItem(
                    mediaId = mediaItem.mediaId,
                    title = metadata.title?.toString().orEmpty(),
                    artist = metadata.artist?.toString().orEmpty(),
                    album = metadata.albumTitle?.toString().orEmpty(),
                )
            },
            currentIndex = player.currentMediaItemIndex,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    private companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
        const val TAILSCALE_ACTIVITY = "com.tailscale.ipn.MainActivity"
        const val QUICK_CONNECT_POLL_INTERVAL_MS = 2_000L
        const val QUICK_CONNECT_MAX_POLLS = 90
        const val JELLYFIN_CACHE_FRESH_MS = 30 * 60 * 1_000L
        const val JELLYFIN_LOG_TAG = "JellyfinLibrary"
    }
}

internal sealed interface LibraryState {
    data object Loading : LibraryState
    data object PermissionRequired : LibraryState
    data object PermissionDenied : LibraryState
    data object Empty : LibraryState
    data object Ready : LibraryState
    data class Error(val message: String) : LibraryState
}

internal sealed interface LibraryScreen {
    data object Home : LibraryScreen
    data object NowPlaying : LibraryScreen
    data object Queue : LibraryScreen
    data object Artists : LibraryScreen
    data object ManageSources : LibraryScreen
    data object Downloads : LibraryScreen
    data object JellyfinArtists : LibraryScreen
    data class JellyfinAlbums(val artist: JellyfinArtist) : LibraryScreen
    data class JellyfinAlbumDetail(val album: JellyfinAlbum) : LibraryScreen
    data class Albums(val artist: LocalArtist) : LibraryScreen
    data class Album(val album: LocalAlbum) : LibraryScreen
}

internal sealed interface JellyfinLibraryState {
    data object NotLoaded : JellyfinLibraryState
    data object Loading : JellyfinLibraryState
    data object Ready : JellyfinLibraryState
    data class Error(val message: String) : JellyfinLibraryState
}

private sealed interface JellyfinConnectionState {
    data object Idle : JellyfinConnectionState
    data object Connecting : JellyfinConnectionState
    data object StartingQuickConnect : JellyfinConnectionState
    data class WaitingForApproval(val code: String) : JellyfinConnectionState
    data object Connected : JellyfinConnectionState
    data class Error(val message: String) : JellyfinConnectionState
}

internal data class NowPlaying(
    val title: String,
    val artist: String,
    val artworkUri: Uri?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val queueIndex: Int,
    val queueSize: Int,
)

internal data class PlaybackQueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
)

internal data class PlaybackQueue(
    val items: List<PlaybackQueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

internal fun PlaybackQueue.positionLabel(): String = if (
    currentIndex in items.indices
) {
    "${currentIndex + 1} of ${items.size}"
} else {
    "${items.size} ${pluralize(items.size, "track")}"
}

internal fun PlaybackQueue.repeatLabel(): String = when (repeatMode) {
    Player.REPEAT_MODE_ONE -> "Repeat One"
    Player.REPEAT_MODE_ALL -> "Repeat All"
    else -> "Repeat Off"
}

@Composable
private fun PlayerShell(
    libraryState: LibraryState,
    artists: List<LocalArtist>,
    libraryScreen: LibraryScreen,
    nowPlaying: NowPlaying?,
    playbackQueue: PlaybackQueue,
    jellyfinConfig: JellyfinConfig,
    jellyfinLibraries: List<JellyfinLibrary>,
    jellyfinConnectionState: JellyfinConnectionState,
    jellyfinArtists: List<JellyfinArtist>,
    jellyfinLibraryState: JellyfinLibraryState,
    downloadRevision: Int,
    managedDownloads: List<AlbumDownloadDetails>,
    jellyfinArtworkUri: (JellyfinAlbum) -> Uri,
    onRequestPermission: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenJellyfin: () -> Unit,
    onOpenJellyfinArtist: (JellyfinArtist) -> Unit,
    onOpenJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onOpenArtist: (LocalArtist) -> Unit,
    onOpenAlbum: (LocalAlbum) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenQueue: () -> Unit,
    onBack: () -> Unit,
    onPlayAlbum: (LocalAlbum) -> Unit,
    onPlayTrack: (LocalAlbum, Int) -> Unit,
    onPlayJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onPlayJellyfinTrack: (JellyfinAlbum, Int) -> Unit,
    onPlayQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onDownloadJellyfinAlbum: (JellyfinAlbum) -> Unit,
    onRemoveJellyfinAlbumDownloads: (JellyfinAlbum) -> Unit,
    jellyfinDownloadSummary: (JellyfinAlbum) -> AlbumDownloadSummary,
    onRefreshDownloads: () -> Unit,
    onTogglePlayback: () -> Unit,
    onConnectJellyfin: (String, String) -> Unit,
    onStartQuickConnect: (String) -> Unit,
    onSelectJellyfinLibrary: (JellyfinLibrary) -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSwitchToClassic: () -> Unit,
) {
    BackHandler(enabled = libraryScreen !is LibraryScreen.Home, onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 40.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSwitchToClassic) { Text("Classic") }
            }
            Box(modifier = Modifier.weight(1f)) {
                when (libraryState) {
                    LibraryState.Loading -> StatusMessage("Scanning this device…")
                    LibraryState.PermissionRequired -> PermissionMessage(onRequestPermission)
                    LibraryState.PermissionDenied -> PermissionMessage(onRequestPermission)
                    LibraryState.Empty -> StatusMessage("No local music found")
                    is LibraryState.Error -> StatusMessage(libraryState.message)
                    LibraryState.Ready -> when (libraryScreen) {
                        LibraryScreen.NowPlaying -> TouchNowPlayingScreen(
                            nowPlaying = nowPlaying,
                            onBack = onBack,
                            onTogglePlayback = onTogglePlayback,
                            onOpenQueue = onOpenQueue,
                        )
                        LibraryScreen.Queue -> QueueScreen(
                            queue = playbackQueue,
                            onBack = onBack,
                            onPlayItem = onPlayQueueItem,
                            onMoveItem = onMoveQueueItem,
                            onRemoveItem = onRemoveQueueItem,
                            onClear = onClearQueue,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeatMode = onCycleRepeatMode,
                        )
                        LibraryScreen.Home -> MusicSourcesScreen(
                            localArtists = artists,
                            jellyfinConfig = jellyfinConfig,
                            jellyfinArtists = jellyfinArtists,
                            jellyfinState = jellyfinLibraryState,
                            managedDownloads = managedDownloads,
                            queue = playbackQueue,
                            onOpenLocal = onOpenLocal,
                            onOpenJellyfin = onOpenJellyfin,
                            onManageSources = onOpenSources,
                            onOpenDownloads = onOpenDownloads,
                            onOpenQueue = onOpenQueue,
                        )
                        LibraryScreen.Downloads -> DownloadsScreen(
                            downloads = managedDownloads,
                            artworkUri = jellyfinArtworkUri,
                            onBack = onBack,
                            onOpenAlbum = onOpenJellyfinAlbum,
                            onPlayAlbum = onPlayJellyfinAlbum,
                            onRemoveAlbum = onRemoveJellyfinAlbumDownloads,
                            onRefreshDownloads = onRefreshDownloads,
                        )
                        LibraryScreen.Artists -> ArtistsScreen(
                            artists = artists,
                            onOpenArtist = onOpenArtist,
                            onBack = onBack,
                        )
                        LibraryScreen.ManageSources -> SourcesScreen(
                            config = jellyfinConfig,
                            libraries = jellyfinLibraries,
                            connectionState = jellyfinConnectionState,
                            onBack = onBack,
                            onConnect = onConnectJellyfin,
                            onStartQuickConnect = onStartQuickConnect,
                            onSelectLibrary = onSelectJellyfinLibrary,
                            onOpenTailscale = onOpenTailscale,
                            onOpenWifiSettings = onOpenWifiSettings,
                            onOpenBluetoothSettings = onOpenBluetoothSettings,
                            onOpenSystemSettings = onOpenSystemSettings,
                        )
                        LibraryScreen.JellyfinArtists -> JellyfinArtistsScreen(
                            artists = jellyfinArtists,
                            state = jellyfinLibraryState,
                            onBack = onBack,
                            onOpenArtist = onOpenJellyfinArtist,
                        )
                        is LibraryScreen.JellyfinAlbums -> JellyfinAlbumsScreen(
                            artist = libraryScreen.artist,
                            artworkUri = jellyfinArtworkUri,
                            onBack = onBack,
                            onOpenAlbum = onOpenJellyfinAlbum,
                        )
                        is LibraryScreen.JellyfinAlbumDetail -> JellyfinAlbumScreen(
                            album = libraryScreen.album,
                            artworkUri = jellyfinArtworkUri,
                            downloadRevision = downloadRevision,
                            downloadSummary = jellyfinDownloadSummary,
                            onBack = onBack,
                            onPlayAlbum = onPlayJellyfinAlbum,
                            onPlayTrack = onPlayJellyfinTrack,
                            onDownloadAlbum = onDownloadJellyfinAlbum,
                            onRemoveDownloads = onRemoveJellyfinAlbumDownloads,
                            onRefreshDownloads = onRefreshDownloads,
                        )
                        is LibraryScreen.Albums -> AlbumsScreen(
                            artist = libraryScreen.artist,
                            onBack = onBack,
                            onOpenAlbum = onOpenAlbum,
                        )
                        is LibraryScreen.Album -> AlbumScreen(
                            album = libraryScreen.album,
                            onBack = onBack,
                            onPlayAlbum = onPlayAlbum,
                            onPlayTrack = onPlayTrack,
                        )
                    }
                }
            }

            nowPlaying?.let {
                NowPlayingBar(it, onTogglePlayback, onOpenNowPlaying)
            }
        }
    }
}

@Composable
private fun MusicSourcesScreen(
    localArtists: List<LocalArtist>,
    jellyfinConfig: JellyfinConfig,
    jellyfinArtists: List<JellyfinArtist>,
    jellyfinState: JellyfinLibraryState,
    managedDownloads: List<AlbumDownloadDetails>,
    queue: PlaybackQueue,
    onOpenLocal: () -> Unit,
    onOpenJellyfin: () -> Unit,
    onManageSources: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "Music Sources",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                "Choose where you want to listen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            SourceCard(
                title = "On Device",
                subtitle = "${localArtists.size} artists · " +
                    "${localArtists.sumOf { it.trackCount }} tracks",
                onClick = onOpenLocal,
            )
        }
        if (jellyfinConfig.libraryId != null) {
            item {
                val subtitle = when (jellyfinState) {
                    JellyfinLibraryState.NotLoaded,
                    JellyfinLibraryState.Loading,
                    -> "Loading ${jellyfinConfig.libraryName ?: "library"}…"
                    JellyfinLibraryState.Ready -> "${jellyfinArtists.size} artists · " +
                        "${jellyfinArtists.sumOf { it.trackCount }} tracks"
                    is JellyfinLibraryState.Error -> "Unavailable · tap to inspect"
                }
                SourceCard(
                    title = "Jellyfin · ${jellyfinConfig.libraryName ?: "Music"}",
                    subtitle = subtitle,
                    onClick = onOpenJellyfin,
                )
            }
        }
        item {
            val downloadedTracks = managedDownloads.sumOf { it.summary.downloaded }
            val downloadedBytes = managedDownloads.sumOf { it.bytes }
            SourceCard(
                title = "Downloads",
                subtitle = if (managedDownloads.isEmpty()) {
                    "No Jellyfin music saved offline"
                } else {
                    "${managedDownloads.size} ${pluralize(managedDownloads.size, "album")} · " +
                        "$downloadedTracks tracks · ${formatBytes(downloadedBytes)}"
                },
                onClick = onOpenDownloads,
            )
        }
        if (queue.items.isNotEmpty()) {
            item {
                SourceCard(
                    title = "Queue",
                    subtitle = queue.positionLabel(),
                    onClick = onOpenQueue,
                )
            }
        }
        item {
            TextButton(onClick = onManageSources) {
                Text("Manage sources and device connections")
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ArtistsScreen(
    artists: List<LocalArtist>,
    onOpenArtist: (LocalArtist) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader("On Device", "Music Sources", onBack)
            Text(
                "${artists.size} artists on this device",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(artists, key = { it.name }) { artist ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(artist) }
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                Text(artist.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${artist.albums.size} ${pluralize(artist.albums.size, "album")} · " +
                        "${artist.trackCount} ${pluralize(artist.trackCount, "track")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun JellyfinArtistsScreen(
    artists: List<JellyfinArtist>,
    state: JellyfinLibraryState,
    onBack: () -> Unit,
    onOpenArtist: (JellyfinArtist) -> Unit,
) {
    when (state) {
        JellyfinLibraryState.NotLoaded,
        JellyfinLibraryState.Loading,
        -> StatusMessage("Loading Jellyfin music…")
        is JellyfinLibraryState.Error -> Column {
            BackHeader("Jellyfin", "Music Sources", onBack)
            StatusMessage(state.message)
        }
        JellyfinLibraryState.Ready -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                BackHeader("Jellyfin Artists", "Music Sources", onBack)
                Text(
                    "${artists.size} artists from the selected library",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(artists, key = { it.id }) { artist ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenArtist(artist) }
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                ) {
                    Text(artist.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${artist.albums.size} ${pluralize(artist.albums.size, "album")} · " +
                            "${artist.trackCount} ${pluralize(artist.trackCount, "track")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun JellyfinAlbumsScreen(
    artist: JellyfinArtist,
    artworkUri: (JellyfinAlbum) -> Uri,
    onBack: () -> Unit,
    onOpenAlbum: (JellyfinAlbum) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { BackHeader(artist.name, "Jellyfin Artists", onBack) }
        items(artist.albums, key = { it.id }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAlbum(album) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(artworkUri(album), Modifier.size(96.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(album.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${album.tracks.size} ${pluralize(album.tracks.size, "track")} · " +
                            formatAlbumDuration(album.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun JellyfinAlbumScreen(
    album: JellyfinAlbum,
    artworkUri: (JellyfinAlbum) -> Uri,
    downloadRevision: Int,
    downloadSummary: (JellyfinAlbum) -> AlbumDownloadSummary,
    onBack: () -> Unit,
    onPlayAlbum: (JellyfinAlbum) -> Unit,
    onPlayTrack: (JellyfinAlbum, Int) -> Unit,
    onDownloadAlbum: (JellyfinAlbum) -> Unit,
    onRemoveDownloads: (JellyfinAlbum) -> Unit,
    onRefreshDownloads: () -> Unit,
) {
    val summary = remember(downloadRevision, album.id) { downloadSummary(album) }
    LaunchedEffect(album.id, summary.downloading > 0) {
        while (summary.downloading > 0) {
            delay(1_000)
            onRefreshDownloads()
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader(album.title, album.artist, onBack)
            AlbumArtwork(
                artworkUri(album),
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .aspectRatio(1f),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(album.title, style = MaterialTheme.typography.headlineMedium)
                Text(album.artist, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onPlayAlbum(album) }) {
                    Text(if (summary.isComplete) "Play downloaded" else "Play album")
                }
                Spacer(Modifier.height(8.dp))
                when {
                    summary.downloading > 0 -> Text(
                        "Downloading ${summary.downloaded}/${summary.total} tracks…",
                    )
                    summary.isComplete -> Text("Available offline")
                    else -> Button(onClick = { onDownloadAlbum(album) }) {
                        Text(if (summary.failed > 0) "Retry download" else "Download album")
                    }
                }
                if (summary.hasAny) {
                    TextButton(onClick = { onRemoveDownloads(album) }) {
                        Text("Remove downloads")
                    }
                }
            }
        }
        items(album.tracks.size, key = { album.tracks[it].id }) { index ->
            val track = album.tracks[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTrack(album, index) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = track.trackNumber?.toString().orEmpty(),
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(formatTrackDuration(track.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun DownloadsScreen(
    downloads: List<AlbumDownloadDetails>,
    artworkUri: (JellyfinAlbum) -> Uri,
    onBack: () -> Unit,
    onOpenAlbum: (JellyfinAlbum) -> Unit,
    onPlayAlbum: (JellyfinAlbum) -> Unit,
    onRemoveAlbum: (JellyfinAlbum) -> Unit,
    onRefreshDownloads: () -> Unit,
) {
    val activeDownloads = downloads.sumOf { it.summary.downloading }
    LaunchedEffect(activeDownloads) {
        while (activeDownloads > 0) {
            delay(1_000)
            onRefreshDownloads()
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader("Downloads", "Music Sources", onBack)
            val totalTracks = downloads.sumOf { it.summary.downloaded }
            val totalBytes = downloads.sumOf { it.bytes }
            Text(
                if (downloads.isEmpty()) {
                    "No Jellyfin music is saved offline."
                } else {
                    "${downloads.size} ${pluralize(downloads.size, "album")} · " +
                        "$totalTracks tracks · ${formatBytes(totalBytes)}"
                },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(downloads, key = { it.album.id }) { download ->
            val album = download.album
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAlbum(album) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(artworkUri(album), Modifier.size(86.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        album.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(album.artist, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            download.summary.downloading > 0 ->
                                "Downloading ${download.summary.downloaded}/${download.summary.total}"
                            download.summary.failed > 0 ->
                                "${download.summary.failed} failed · ${formatBytes(download.bytes)}"
                            else -> "Available offline · ${formatBytes(download.bytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onPlayAlbum(album) }) { Text("Play") }
                        TextButton(onClick = { onRemoveAlbum(album) }) { Text("Remove") }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SourcesScreen(
    config: JellyfinConfig,
    libraries: List<JellyfinLibrary>,
    connectionState: JellyfinConnectionState,
    onBack: () -> Unit,
    onConnect: (String, String) -> Unit,
    onStartQuickConnect: (String) -> Unit,
    onSelectLibrary: (JellyfinLibrary) -> Unit,
    onOpenTailscale: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var apiKey by remember(config.serverUrl) { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            TextButton(onClick = onBack) {
                Text("‹ Music Sources")
            }
            Text("Manage Sources", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Connect directly to the Music library on your Jellyfin server.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Text("Device", style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onOpenTailscale) { Text("Tailscale") }
                TextButton(onClick = onOpenWifiSettings) { Text("Wi‑Fi") }
                TextButton(onClick = onOpenBluetoothSettings) { Text("Bluetooth") }
            }
            TextButton(onClick = onOpenSystemSettings) { Text("All system settings") }
            HorizontalDivider()
        }
        item {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("http://jellyfin:8096") },
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = { onStartQuickConnect(serverUrl) },
                enabled = serverUrl.isNotBlank() && !connectionState.isBusy(),
            ) {
                Text("Use Quick Connect")
            }
        }
        item {
            when (connectionState) {
                JellyfinConnectionState.StartingQuickConnect -> Text(
                    "Requesting a Quick Connect code…",
                )
                is JellyfinConnectionState.WaitingForApproval -> Column {
                    Text("Approve this code in Jellyfin:")
                    Text(
                        connectionState.code,
                        style = MaterialTheme.typography.displayMedium,
                    )
                    Text("Jellyfin Web → Settings → Quick Connect")
                }
                else -> Unit
            }
        }
        item {
            HorizontalDivider()
            Text("Advanced", style = MaterialTheme.typography.titleMedium)
            Text(
                "Use a server API key instead of user-scoped Quick Connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = {
                    Text(if (config.apiKey.isBlank()) "Required" else "Saved securely")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = { onConnect(serverUrl, apiKey) },
                enabled = serverUrl.isNotBlank() &&
                    (apiKey.isNotBlank() || config.apiKey.isNotBlank()) &&
                    !connectionState.isBusy(),
            ) {
                Text(
                    if (connectionState is JellyfinConnectionState.Connecting) {
                        "Connecting…"
                    } else {
                        "Connect and find libraries"
                    },
                )
            }
        }
        item {
            when (connectionState) {
                JellyfinConnectionState.Idle -> config.libraryName?.let {
                    Text("Selected library: $it")
                }
                JellyfinConnectionState.Connecting -> Text("Contacting Jellyfin…")
                JellyfinConnectionState.StartingQuickConnect -> Unit
                is JellyfinConnectionState.WaitingForApproval -> Unit
                JellyfinConnectionState.Connected -> Text("Choose a media library")
                is JellyfinConnectionState.Error -> Text(
                    connectionState.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(libraries, key = { it.id }) { library ->
            val selected = library.id == config.libraryId
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectLibrary(library) },
                shape = RoundedCornerShape(12.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(library.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            library.collectionType,
                            if (selected) "Selected" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun JellyfinConnectionState.isBusy(): Boolean =
    this is JellyfinConnectionState.Connecting ||
        this is JellyfinConnectionState.StartingQuickConnect ||
        this is JellyfinConnectionState.WaitingForApproval

@Composable
private fun AlbumsScreen(
    artist: LocalArtist,
    onBack: () -> Unit,
    onOpenAlbum: (LocalAlbum) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader(artist.name, "On Device", onBack)
        }
        items(artist.albums, key = { it.id }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAlbum(album) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumArtwork(album.artworkUri, Modifier.size(96.dp))
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(album.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${album.tracks.size} ${pluralize(album.tracks.size, "track")} · " +
                            formatAlbumDuration(album.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun AlbumScreen(
    album: LocalAlbum,
    onBack: () -> Unit,
    onPlayAlbum: (LocalAlbum) -> Unit,
    onPlayTrack: (LocalAlbum, Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader(album.title, album.artist, onBack)
            AlbumArtwork(
                album.artworkUri,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp)
                    .aspectRatio(1f),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(album.title, style = MaterialTheme.typography.headlineMedium)
                Text(album.artist, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onPlayAlbum(album) }) {
                    Text("Play album")
                }
            }
        }
        items(album.tracks.size, key = { album.tracks[it].id }) { index ->
            val track = album.tracks[index]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTrack(album, index) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = track.trackNumber?.toString().orEmpty(),
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = track.title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(formatTrackDuration(track.durationMs), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun LibraryTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BackHeader(title: String, parent: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("‹ $parent")
        }
        Text(
            title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
internal fun AlbumArtwork(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (uri.scheme == "http" || uri.scheme == "https") {
                    val config = JellyfinSettingsStore(context).load()
                    val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 15_000
                        connection.setRequestProperty(
                            "Authorization",
                            JellyfinClient.authorizationHeader(config.apiKey),
                        )
                        connection.inputStream.use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    } finally {
                        connection.disconnect()
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = "Album artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("♪", style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun NowPlayingBar(
    nowPlaying: NowPlaying,
    onTogglePlayback: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    Surface(tonalElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            nowPlaying.artworkUri?.let {
                AlbumArtwork(it, Modifier.size(54.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(nowPlaying.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    nowPlaying.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onTogglePlayback) {
                Text(if (nowPlaying.isPlaying) "Pause" else "Play")
            }
        }
    }
}

@Composable
private fun QueueScreen(
    queue: PlaybackQueue,
    onBack: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClear: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            BackHeader("Queue", "Music Sources", onBack)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleShuffle) {
                    Text(if (queue.shuffleEnabled) "Shuffle On" else "Shuffle Off")
                }
                TextButton(onClick = onCycleRepeatMode) { Text(queue.repeatLabel()) }
                TextButton(onClick = onClear, enabled = queue.items.size > 1) {
                    Text("Clear Others")
                }
            }
            Text(
                queue.positionLabel(),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (queue.items.isEmpty()) {
            item {
                Text(
                    "The queue is empty",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            itemsIndexed(
                items = queue.items,
                key = { index, item -> "$index:${item.mediaId}" },
            ) { index, item ->
                val isCurrent = index == queue.currentIndex
                Surface(
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayItem(index) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            if (isCurrent) "▶ ${item.title}" else item.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(item.artist, item.album)
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onMoveItem(index, index - 1) },
                                enabled = index > 0,
                            ) { Text("Up") }
                            TextButton(
                                onClick = { onMoveItem(index, index + 1) },
                                enabled = index < queue.items.lastIndex,
                            ) { Text("Down") }
                            TextButton(
                                onClick = { onRemoveItem(index) },
                                enabled = !isCurrent,
                            ) { Text("Remove") }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TouchNowPlayingScreen(
    nowPlaying: NowPlaying?,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BackHeader("Now Playing", "Music Sources", onBack)
        Spacer(Modifier.height(20.dp))
        nowPlaying?.artworkUri?.let { artwork ->
            AlbumArtwork(
                artwork,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .aspectRatio(1f),
            )
            Spacer(Modifier.height(24.dp))
        }
        Text(nowPlaying?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineMedium)
        Text(nowPlaying?.artist.orEmpty(), style = MaterialTheme.typography.titleMedium)
        nowPlaying?.takeIf { it.queueSize > 0 }?.let {
            Text(
                "${it.queueIndex + 1} of ${it.queueSize}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (nowPlaying != null) {
            LinearProgressIndicator(
                progress = {
                    if (nowPlaying.durationMs > 0L) {
                        (nowPlaying.positionMs.toFloat() / nowPlaying.durationMs)
                            .coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTrackDuration(nowPlaying.positionMs))
                Text(formatTrackDuration(nowPlaying.durationMs))
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onTogglePlayback) {
                Text(if (nowPlaying.isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = onOpenQueue) { Text("View Queue") }
        }
    }
}

@Composable
private fun StatusMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PermissionMessage(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Allow access to audio stored on this device")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Allow audio access")
        }
    }
}

private fun pluralize(count: Int, singular: String): String =
    if (count == 1) singular else "${singular}s"

internal fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatAlbumDuration(durationMs: Long): String = "${durationMs / 60_000} min"

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
