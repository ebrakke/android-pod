package dev.reclaimed.player.homeassistant

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class SonosRemoteSession(
    val player: SonosPlayer,
    val isPlaying: Boolean,
    val title: String = "",
    val artist: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueIndex: Int,
    val volumeLevel: Float? = null,
    val error: String? = null,
)

class SonosSessionCoordinator(
    private val onSessionChanged: (SonosRemoteSession?) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sonos-session").apply { isDaemon = true }
    }
    @Volatile
    private var active: ActiveSession? = null
    private var polling: ScheduledFuture<*>? = null

    fun activate(
        config: HomeAssistantConfig,
        player: SonosPlayer,
        queueIndex: Int,
        positionMs: Long,
        wasPlaying: Boolean,
    ) {
        val session = SonosRemoteSession(
            player = player,
            isPlaying = wasPlaying,
            positionMs = positionMs,
            queueIndex = queueIndex,
        )
        active = ActiveSession(config, session)
        onSessionChanged(session)
        polling?.cancel(false)
        polling = executor.scheduleWithFixedDelay(::poll, 0L, 1L, TimeUnit.SECONDS)
    }

    fun togglePlayback() = submit { context ->
        val requestedPlaying = !context.session.isPlaying
        context.update(context.session.copy(isPlaying = requestedPlaying, error = null))
        val actual = HomeAssistantClient(context.config).setSonosPlayback(
            context.session.player,
            requestedPlaying,
        )
        context.update(context.session.copy(isPlaying = actual, error = null))
    }

    fun previous() = submit { context ->
        if (context.session.positionMs > 3_000L) {
            HomeAssistantClient(context.config).seekSonos(context.session.player, 0L)
            context.update(context.session.copy(positionMs = 0L, error = null))
        } else {
            HomeAssistantClient(context.config).skipSonos(context.session.player, -1)
            context.update(
                context.session.copy(
                    queueIndex = (context.session.queueIndex - 1).coerceAtLeast(0),
                    positionMs = 0L,
                    error = null,
                ),
            )
        }
    }

    fun next() = submit { context ->
        HomeAssistantClient(context.config).skipSonos(context.session.player, 1)
        context.update(
            context.session.copy(
                queueIndex = context.session.queueIndex + 1,
                positionMs = 0L,
                error = null,
            ),
        )
    }

    fun seekTo(positionMs: Long) = submit { context ->
        HomeAssistantClient(context.config).seekSonos(context.session.player, positionMs)
        context.update(context.session.copy(positionMs = positionMs, error = null))
    }

    fun adjustVolume(direction: Int) = submit { context ->
        HomeAssistantClient(context.config).adjustSonosVolume(context.session.player, direction)
    }

    fun disconnect() = submit { context ->
        val isPlaying = HomeAssistantClient(context.config).setSonosPlayback(
            context.session.player,
            false,
        )
        check(!isPlaying) { "Sonos still reports playback as active" }
        if (active !== context) return@submit
        polling?.cancel(false)
        polling = null
        active = null
        onSessionChanged(null)
    }

    fun updateQueueIndex(queueIndex: Int) {
        executor.execute {
            val context = active ?: return@execute
            context.session = context.session.copy(queueIndex = queueIndex.coerceAtLeast(0))
        }
    }

    private fun poll() {
        val context = active ?: return
        runCatching {
            HomeAssistantClient(context.config, readTimeoutMs = 5_000)
                .getSonosPlaybackState(context.session.player)
        }.onSuccess { state ->
            context.update(
                context.session.copy(
                    isPlaying = state.isPlaying,
                    title = state.title,
                    artist = state.artist,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    volumeLevel = state.volumeLevel,
                    error = null,
                ),
            )
        }.onFailure { context.report(it, "Unable to refresh Sonos playback") }
    }

    private fun submit(action: (ActiveSession) -> Unit) {
        executor.execute {
            val context = active ?: return@execute
            runCatching { action(context) }
                .onFailure { context.report(it, "Unable to control Sonos playback") }
        }
    }

    private inner class ActiveSession(
        val config: HomeAssistantConfig,
        initialSession: SonosRemoteSession,
    ) {
        @Volatile
        var session = initialSession

        fun update(updated: SonosRemoteSession) {
            if (active !== this) return
            session = updated
            onSessionChanged(updated)
        }

        fun report(error: Throwable, fallback: String) {
            update(session.copy(error = error.message ?: fallback))
        }
    }

    override fun close() {
        polling?.cancel(true)
        polling = null
        active = null
        executor.shutdownNow()
        onSessionChanged(null)
    }
}
