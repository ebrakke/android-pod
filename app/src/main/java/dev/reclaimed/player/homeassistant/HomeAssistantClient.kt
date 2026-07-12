package dev.reclaimed.player.homeassistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class SonosPlayer(
    val entityId: String,
    val name: String,
    val state: String,
)

data class JellyfinHandoff(
    val trackIds: List<String>,
    val currentIndex: Int,
    val positionMs: Long,
    val wasPlaying: Boolean,
)

fun parseHomeAssistantTokenQr(contents: String): String? {
    val payload = contents.trim()
    if (payload.isBlank()) return null
    val jsonToken = Regex(
        """[\"'](?:access_token|token)[\"']\s*:\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE,
    ).find(payload)?.groupValues?.get(1)
    if (!jsonToken.isNullOrBlank()) return jsonToken
    val queryToken = Regex(
        """[?&](?:access_token|token)=([^&#]+)""",
        RegexOption.IGNORE_CASE,
    ).find(payload)?.groupValues?.get(1)?.let {
        runCatching { URLDecoder.decode(it, Charsets.UTF_8.name()) }.getOrNull()
    }
    if (!queryToken.isNullOrBlank()) return queryToken
    if (payload.startsWith("http://", ignoreCase = true) ||
        payload.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    return payload
}

class HomeAssistantClient(private val config: HomeAssistantConfig) {
    fun getSonosPlayers(): List<SonosPlayer> {
        require(config.isConfigured) { "Home Assistant URL and access token are required" }
        val response = request(
            method = "POST",
            path = "/api/services/reclaimed_player/list_players?return_response",
            body = JSONObject(),
        )
        val players = response.optJSONObject("service_response")
            ?.optJSONArray("players")
            ?: JSONArray()
        return buildList {
            for (index in 0 until players.length()) {
                val player = players.optJSONObject(index) ?: continue
                val entityId = player.optString("entity_id")
                if (entityId.isBlank()) continue
                add(
                    SonosPlayer(
                        entityId = entityId,
                        name = player.optString("name").ifBlank { entityId },
                        state = player.optString("state", "unknown"),
                    ),
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun continueOn(player: SonosPlayer, handoff: JellyfinHandoff) {
        require(handoff.trackIds.isNotEmpty()) { "The Jellyfin queue is empty" }
        val response = request(
            method = "POST",
            path = "/api/services/reclaimed_player/continue_on?return_response",
            body = JSONObject()
                .put("entity_id", player.entityId)
                .put("track_ids", JSONArray(handoff.trackIds))
                .put("current_index", handoff.currentIndex)
                .put("position_ms", handoff.positionMs)
                .put("was_playing", handoff.wasPlaying),
        )
        val serviceResponse = response.optJSONObject("service_response")
        if (serviceResponse?.optBoolean("success", true) == false) {
            throw IOException(
                serviceResponse.optString("error").ifBlank {
                    "Home Assistant could not continue playback"
                },
            )
        }
    }

    fun setSonosPlayback(player: SonosPlayer, isPlaying: Boolean): Boolean {
        val response = request(
            method = "POST",
            path = "/api/services/reclaimed_player/control?return_response",
            body = JSONObject()
                .put("entity_id", player.entityId)
                .put("command", if (isPlaying) "play" else "pause"),
        )
        val serviceResponse = response.optJSONObject("service_response")
        if (serviceResponse?.optBoolean("success", true) == false) {
            throw IOException(
                serviceResponse.optString("error").ifBlank {
                    "Home Assistant could not control Sonos playback"
                },
            )
        }
        return serviceResponse?.optBoolean("is_playing", isPlaying) ?: isPlaying
    }

    fun adjustSonosVolume(player: SonosPlayer, direction: Int) {
        val response = request(
            method = "POST",
            path = "/api/services/reclaimed_player/control?return_response",
            body = JSONObject()
                .put("entity_id", player.entityId)
                .put("command", if (direction > 0) "volume_up" else "volume_down"),
        )
        val serviceResponse = response.optJSONObject("service_response")
        if (serviceResponse?.optBoolean("success", true) == false) {
            throw IOException(
                serviceResponse.optString("error").ifBlank {
                    "Home Assistant could not adjust Sonos volume"
                },
            )
        }
    }

    private fun request(method: String, path: String, body: JSONObject): JSONObject {
        val connection = URL("${config.serverUrl}$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseBody).optString("message")
                }.getOrNull().orEmpty()
                throw IOException(
                    message.ifBlank { "Home Assistant returned HTTP $status" },
                )
            }
            if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        } finally {
            connection.disconnect()
        }
    }
}
