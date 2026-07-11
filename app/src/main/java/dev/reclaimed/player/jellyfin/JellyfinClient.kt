package dev.reclaimed.player.jellyfin

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class JellyfinLibrary(
    val id: String,
    val name: String,
    val collectionType: String?,
)

data class QuickConnectRequest(
    val secret: String,
    val code: String,
)

data class QuickConnectAuthentication(
    val accessToken: String,
    val userId: String?,
)

data class JellyfinArtist(
    val id: String,
    val name: String,
    val albums: List<JellyfinAlbum>,
) {
    val trackCount: Int = albums.sumOf { it.tracks.size }
}

data class JellyfinAlbum(
    val id: String,
    val title: String,
    val artist: String,
    val tracks: List<JellyfinTrack>,
) {
    val durationMs: Long = tracks.sumOf { it.durationMs }
}

data class JellyfinTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val container: String?,
)

class JellyfinClient(private val config: JellyfinConfig) {
    fun getLibraries(): List<JellyfinLibrary> {
        require(config.isConfigured) { "Jellyfin server URL and access token are required" }
        val response = requestJson("GET", "/Library/MediaFolders")
        val items = when (response) {
            is JSONArray -> response
            is JSONObject -> response.optJSONArray("Items") ?: JSONArray()
            else -> JSONArray()
        }
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("Id")
                val name = item.optString("Name")
                if (id.isNotBlank() && name.isNotBlank()) {
                    add(
                        JellyfinLibrary(
                            id = id,
                            name = name,
                            collectionType = item.optString("CollectionType").ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    fun getMusicLibrary(libraryId: String): List<JellyfinArtist> {
        require(config.isConfigured) { "Jellyfin server URL and access token are required" }
        val userQuery = config.userId?.let { "&UserId=${encode(it)}" }.orEmpty()
        val path = "/Items?ParentId=${encode(libraryId)}" +
            "&Recursive=true&IncludeItemTypes=Audio" +
            "&Fields=AlbumId,Artists,AlbumArtists" +
            "&SortBy=AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName" +
            "&SortOrder=Ascending&EnableImages=true$userQuery"
        val response = requestJson("GET", path) as? JSONObject
            ?: throw IOException("Jellyfin returned an invalid music library response")
        val items = response.optJSONArray("Items") ?: JSONArray()
        val tracks = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("Id")
                val albumId = item.optString("AlbumId")
                if (id.isBlank() || albumId.isBlank()) continue
                val artist = item.optString("AlbumArtist").ifBlank {
                    item.optJSONArray("AlbumArtists")
                        ?.optJSONObject(0)
                        ?.optString("Name")
                        .orEmpty()
                }.ifBlank {
                    item.optJSONArray("Artists")?.optString(0).orEmpty()
                }.ifBlank { "Unknown Artist" }
                add(
                    JellyfinTrack(
                        id = id,
                        title = item.optString("Name").ifBlank { "Unknown Track" },
                        artist = artist,
                        album = item.optString("Album").ifBlank { "Unknown Album" },
                        albumId = albumId,
                        durationMs = item.optLong("RunTimeTicks", 0L) / 10_000L,
                        trackNumber = item.optInt("IndexNumber", 0).takeIf { it > 0 },
                        discNumber = item.optInt("ParentIndexNumber", 0).takeIf { it > 0 },
                        container = item.optString("Container").ifBlank { null },
                    ),
                )
            }
        }
        return tracks
            .groupBy { it.artist }
            .map { (artistName, artistTracks) ->
                JellyfinArtist(
                    id = artistName.lowercase(),
                    name = artistName,
                    albums = artistTracks
                        .groupBy { it.albumId }
                        .map { (albumId, albumTracks) ->
                            JellyfinAlbum(
                                id = albumId,
                                title = albumTracks.first().album,
                                artist = artistName,
                                tracks = albumTracks.sortedWith(
                                    compareBy<JellyfinTrack>(
                                        { it.discNumber ?: 1 },
                                        { it.trackNumber ?: Int.MAX_VALUE },
                                        { it.title.lowercase() },
                                    ),
                                ),
                            )
                        }
                        .sortedBy { it.title.lowercase() },
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun streamUrl(trackId: String): String =
        "${config.serverUrl}/Audio/${encode(trackId)}/stream?static=true"

    fun imageUrl(itemId: String, maxWidth: Int = 800): String =
        "${config.serverUrl}/Items/${encode(itemId)}/Images/Primary?maxWidth=$maxWidth&quality=90"

    fun downloadUrl(trackId: String): String =
        "${config.serverUrl}/Items/${encode(trackId)}/Download"

    fun initiateQuickConnect(): QuickConnectRequest {
        require(config.serverUrl.isNotBlank()) { "Jellyfin server URL is required" }
        val response = requestJson("POST", "/QuickConnect/Initiate", includeToken = false)
            as? JSONObject
            ?: throw IOException("Jellyfin returned an invalid Quick Connect response")
        return QuickConnectRequest(
            secret = response.getString("Secret"),
            code = response.getString("Code"),
        )
    }

    fun isQuickConnectAuthorized(secret: String): Boolean {
        val encodedSecret = URLEncoder.encode(secret, Charsets.UTF_8.name())
        val response = requestJson(
            "GET",
            "/QuickConnect/Connect?secret=$encodedSecret",
            includeToken = false,
        ) as? JSONObject ?: return false
        return response.optBoolean("Authenticated", false)
    }

    fun authenticateWithQuickConnect(secret: String): QuickConnectAuthentication {
        val response = requestJson(
            method = "POST",
            path = "/Users/AuthenticateWithQuickConnect",
            body = JSONObject().put("Secret", secret).toString(),
            includeToken = false,
        ) as? JSONObject ?: throw IOException("Jellyfin returned an invalid authentication response")
        val accessToken = response.optString("AccessToken")
        if (accessToken.isBlank()) throw IOException("Jellyfin did not return an access token")
        return QuickConnectAuthentication(
            accessToken = accessToken,
            userId = response.optJSONObject("User")?.optString("Id")?.ifBlank { null },
        )
    }

    private fun requestJson(
        method: String,
        path: String,
        body: String? = null,
        includeToken: Boolean = true,
    ): Any {
        val connection = URL("${config.serverUrl}$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Authorization",
                authorizationHeader(config.apiKey.takeIf { includeToken }),
            )
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IOException("Jellyfin returned HTTP $status${message?.let { ": $it" }.orEmpty()}")
            }
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            JSONTokener(responseBody).nextValue()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun authorizationHeader(accessToken: String?): String =
            "MediaBrowser Client=\"Reclaimed Player\", " +
                "Device=\"Android\", DeviceId=\"reclaimed-player-pixel-6\", " +
                "Version=\"0.1.0\"" +
                accessToken?.let { ", Token=\"$it\"" }.orEmpty()

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
