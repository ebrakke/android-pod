package dev.reclaimed.player.playback

import android.content.Context
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

internal enum class PlaybackSource {
    LOCAL,
    JELLYFIN,
}

internal data class PersistedQueueItem(
    val source: PlaybackSource,
    val sourceId: String,
    val albumId: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int?,
    val durationMs: Long,
)

internal data class PersistedPlaybackSession(
    val version: Int = CURRENT_VERSION,
    val queue: List<PersistedQueueItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val shuffleOrder: List<Int>,
    val repeatMode: Int,
    val wasPlaying: Boolean,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

internal class PlaybackSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PersistedPlaybackSession? {
        val serialized = preferences.getString(SESSION_KEY, null) ?: return null
        return runCatching { decode(JSONObject(serialized)) }.getOrNull()
    }

    fun save(session: PersistedPlaybackSession) {
        preferences.edit().putString(SESSION_KEY, encode(session).toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(SESSION_KEY).apply()
    }

    private fun encode(session: PersistedPlaybackSession): JSONObject = JSONObject()
        .put("version", session.version)
        .put(
            "queue",
            JSONArray().apply {
                session.queue.forEach { item ->
                    put(
                        JSONObject()
                            .put("source", item.source.name.lowercase())
                            .put("sourceId", item.sourceId)
                            .put("albumId", item.albumId)
                            .put("title", item.title)
                            .put("artist", item.artist)
                            .put("album", item.album)
                            .put("trackNumber", item.trackNumber ?: JSONObject.NULL)
                            .put("durationMs", item.durationMs),
                    )
                }
            },
        )
        .put("currentIndex", session.currentIndex)
        .put("positionMs", session.positionMs)
        .put("shuffleEnabled", session.shuffleEnabled)
        .put("shuffleOrder", JSONArray(session.shuffleOrder))
        .put("repeatMode", session.repeatMode)
        .put("wasPlaying", session.wasPlaying)

    private fun decode(json: JSONObject): PersistedPlaybackSession? {
        if (json.optInt("version") != PersistedPlaybackSession.CURRENT_VERSION) return null
        val queueJson = json.optJSONArray("queue") ?: return null
        val queue = buildList {
            for (index in 0 until queueJson.length()) {
                val item = queueJson.optJSONObject(index) ?: return null
                val source = runCatching {
                    PlaybackSource.valueOf(item.getString("source").uppercase())
                }.getOrNull() ?: return null
                add(
                    PersistedQueueItem(
                        source = source,
                        sourceId = item.getString("sourceId"),
                        albumId = item.getString("albumId"),
                        title = item.getString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        trackNumber = item.optInt("trackNumber", 0).takeIf { it > 0 },
                        durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
        if (queue.isEmpty()) return null
        val currentIndex = json.optInt("currentIndex").coerceIn(queue.indices)
        val shuffleOrderJson = json.optJSONArray("shuffleOrder")
        val shuffleOrder = buildList {
            if (shuffleOrderJson != null) {
                for (index in 0 until shuffleOrderJson.length()) {
                    add(shuffleOrderJson.optInt(index, -1))
                }
            }
        }.takeIf { it.size == queue.size && it.toSet() == queue.indices.toSet() }
            ?: queue.indices.toList()
        val repeatMode = json.optInt("repeatMode", Player.REPEAT_MODE_OFF)
            .takeIf { it in Player.REPEAT_MODE_OFF..Player.REPEAT_MODE_ALL }
            ?: Player.REPEAT_MODE_OFF
        return PersistedPlaybackSession(
            queue = queue,
            currentIndex = currentIndex,
            positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
            shuffleEnabled = json.optBoolean("shuffleEnabled", false),
            shuffleOrder = shuffleOrder,
            repeatMode = repeatMode,
            wasPlaying = json.optBoolean("wasPlaying", false),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "playback_session"
        const val SESSION_KEY = "session"
    }
}

internal object PlaybackItemMetadata {
    const val SOURCE = "dev.reclaimed.player.source"
    const val SOURCE_ID = "dev.reclaimed.player.source_id"
    const val ALBUM_ID = "dev.reclaimed.player.album_id"
}
