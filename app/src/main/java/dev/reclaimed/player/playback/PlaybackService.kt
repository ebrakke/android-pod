package dev.reclaimed.player.playback

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.reclaimed.player.MainActivity
import dev.reclaimed.player.jellyfin.JellyfinClient
import dev.reclaimed.player.jellyfin.JellyfinDownloadStore
import dev.reclaimed.player.jellyfin.JellyfinSettingsStore

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var sessionStore: PlaybackSessionStore
    private lateinit var downloadStore: JellyfinDownloadStore
    private lateinit var jellyfinClient: JellyfinClient
    private val checkpointHandler = Handler(Looper.getMainLooper())
    private val checkpoint = object : Runnable {
        override fun run() {
            mediaSession?.player?.let(::persistSession)
            checkpointHandler.postDelayed(this, CHECKPOINT_INTERVAL_MS)
        }
    }
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            persistSession(player)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val jellyfinConfig = JellyfinSettingsStore(this).load()
        sessionStore = PlaybackSessionStore(this)
        downloadStore = JellyfinDownloadStore(this)
        jellyfinClient = JellyfinClient(jellyfinConfig)
        val httpDataSource = DefaultHttpDataSource.Factory().apply {
            if (jellyfinConfig.apiKey.isNotBlank()) {
                setDefaultRequestProperties(
                    mapOf("Authorization" to JellyfinClient.authorizationHeader(jellyfinConfig.apiKey)),
                )
            }
        }
        val dataSource = DefaultDataSource.Factory(this, httpDataSource)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
        restoreSession(player)
        player.addListener(playerListener)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        checkpointHandler.postDelayed(checkpoint, CHECKPOINT_INTERVAL_MS)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        checkpointHandler.removeCallbacks(checkpoint)
        mediaSession?.run {
            player.removeListener(playerListener)
            persistSession(player)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun restoreSession(player: ExoPlayer) {
        val session = sessionStore.load() ?: return
        val items = runCatching { session.queue.map(::resolveMediaItem) }
            .getOrElse {
                sessionStore.clear()
                return
            }
        player.setMediaItems(items, session.currentIndex, session.positionMs)
        player.setShuffleOrder(DefaultShuffleOrder(session.shuffleOrder.toIntArray(), SHUFFLE_SEED))
        player.shuffleModeEnabled = session.shuffleEnabled
        player.repeatMode = session.repeatMode
        player.playWhenReady = session.wasPlaying
        player.prepare()
    }

    private fun resolveMediaItem(item: PersistedQueueItem): MediaItem {
        val mediaUri = when (item.source) {
            PlaybackSource.LOCAL -> ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                item.sourceId.toLong(),
            )
            PlaybackSource.JELLYFIN -> downloadStore.localUri(item.sourceId)
                ?: Uri.parse(jellyfinClient.streamUrl(item.sourceId))
        }
        val artworkUri = when (item.source) {
            PlaybackSource.LOCAL -> ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                item.albumId.toLong(),
            )
            PlaybackSource.JELLYFIN -> downloadStore.localArtworkUri(item.albumId)
                ?: Uri.parse(jellyfinClient.imageUrl(item.albumId))
        }
        return MediaItem.Builder()
            .setMediaId("${item.source.name.lowercase()}:${item.sourceId}")
            .setUri(mediaUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(artworkUri)
                    .setTrackNumber(item.trackNumber)
                    .setDurationMs(item.durationMs)
                    .setExtras(item.extras())
                    .build(),
            )
            .build()
    }

    private fun persistSession(player: Player) {
        if (player.mediaItemCount == 0) {
            sessionStore.clear()
            return
        }
        val queue = buildList {
            for (index in 0 until player.mediaItemCount) {
                player.getMediaItemAt(index).toPersistedQueueItem()?.let(::add) ?: return
            }
        }
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        sessionStore.save(
            PersistedPlaybackSession(
                queue = queue,
                currentIndex = player.currentMediaItemIndex.coerceIn(queue.indices),
                positionMs = positionMs,
                shuffleEnabled = player.shuffleModeEnabled,
                shuffleOrder = player.shuffleOrder(),
                repeatMode = player.repeatMode,
                wasPlaying = player.playWhenReady,
            ),
        )
    }

    private fun MediaItem.toPersistedQueueItem(): PersistedQueueItem? {
        val extras = mediaMetadata.extras ?: return null
        val source = extras.getString(PlaybackItemMetadata.SOURCE)?.let { serialized ->
            runCatching { PlaybackSource.valueOf(serialized) }.getOrNull()
        } ?: return null
        val sourceId = extras.getString(PlaybackItemMetadata.SOURCE_ID) ?: return null
        val albumId = extras.getString(PlaybackItemMetadata.ALBUM_ID) ?: return null
        return PersistedQueueItem(
            source = source,
            sourceId = sourceId,
            albumId = albumId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString().orEmpty(),
            trackNumber = mediaMetadata.trackNumber,
            durationMs = mediaMetadata.durationMs ?: 0L,
        )
    }

    private fun PersistedQueueItem.extras(): Bundle = Bundle().apply {
        putString(PlaybackItemMetadata.SOURCE, source.name)
        putString(PlaybackItemMetadata.SOURCE_ID, sourceId)
        putString(PlaybackItemMetadata.ALBUM_ID, albumId)
    }

    private fun Player.shuffleOrder(): List<Int> {
        val exoPlayer = this as? ExoPlayer ?: return (0 until mediaItemCount).toList()
        val order = exoPlayer.shuffleOrder
        return buildList {
            var index = order.firstIndex
            while (index != C.INDEX_UNSET && size < mediaItemCount) {
                add(index)
                index = order.getNextIndex(index)
            }
        }.takeIf { it.size == mediaItemCount } ?: (0 until mediaItemCount).toList()
    }

    private companion object {
        const val CHECKPOINT_INTERVAL_MS = 5_000L
        const val SHUFFLE_SEED = 0x5245434cL
    }
}
