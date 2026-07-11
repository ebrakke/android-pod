package dev.reclaimed.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.reclaimed.player.MainActivity
import dev.reclaimed.player.jellyfin.JellyfinClient
import dev.reclaimed.player.jellyfin.JellyfinSettingsStore

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val jellyfinConfig = JellyfinSettingsStore(this).load()
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
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
