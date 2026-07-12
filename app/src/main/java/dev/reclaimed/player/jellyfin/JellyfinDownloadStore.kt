package dev.reclaimed.player.jellyfin

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File

enum class TrackDownloadState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

data class AlbumDownloadSummary(
    val downloaded: Int,
    val downloading: Int,
    val failed: Int,
    val total: Int,
) {
    val hasAny: Boolean = downloaded + downloading + failed > 0
    val isComplete: Boolean = total > 0 && downloaded == total
}

data class AlbumDownloadDetails(
    val album: JellyfinAlbum,
    val summary: AlbumDownloadSummary,
    val bytes: Long,
)

class JellyfinDownloadStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    init {
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        preferences.all.values.forEach { serialized ->
            (serialized as? String)?.let { json ->
                runCatching { JSONObject(json).optString("filePath") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.parentFile
                    ?.mkdirs()
            }
        }
    }

    fun enqueueAlbum(config: JellyfinConfig, album: JellyfinAlbum) {
        val client = JellyfinClient(config)
        album.tracks.forEach { track ->
            val existingState = state(track.id)
            if (existingState == TrackDownloadState.DOWNLOADED ||
                existingState == TrackDownloadState.DOWNLOADING
            ) {
                return@forEach
            }

            removeRecord(track.id)
            val destination = destinationFile(album, track)
            destination.parentFile?.mkdirs()
            destination.delete()
            val request = DownloadManager.Request(Uri.parse(client.downloadUrl(track.id)))
                .addRequestHeader(
                    "Authorization",
                    JellyfinClient.authorizationHeader(config.apiKey),
                )
                .setTitle(track.title)
                .setDescription("${album.artist} · ${album.title}")
                .setMimeType("audio/${track.container ?: "mpeg"}")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationUri(Uri.fromFile(destination))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            val downloadId = downloadManager.enqueue(request)
            saveRecord(
                DownloadRecord(
                    trackId = track.id,
                    albumId = album.id,
                    downloadId = downloadId,
                    filePath = destination.absolutePath,
                ),
            )
        }
        enqueueArtwork(config, album)
    }

    fun removeAlbum(album: JellyfinAlbum) {
        album.tracks.forEach { track ->
            record(track.id)?.let { record ->
                downloadManager.remove(record.downloadId)
                File(record.filePath).delete()
            }
            preferences.edit().remove(key(track.id)).apply()
        }
        artworkRecord(album.id)?.let { record ->
            downloadManager.remove(record.downloadId)
            File(record.filePath).delete()
        }
        preferences.edit().remove(artworkKey(album.id)).apply()
    }

    fun localUri(trackId: String): Uri? {
        val record = record(trackId) ?: return null
        val file = File(record.filePath)
        return if (state(record) == TrackDownloadState.DOWNLOADED && file.isFile) {
            Uri.fromFile(file)
        } else {
            null
        }
    }

    fun localArtworkUri(albumId: String): Uri? {
        val record = artworkRecord(albumId) ?: return null
        val file = File(record.filePath)
        return if (state(record) == TrackDownloadState.DOWNLOADED && file.isFile) {
            Uri.fromFile(file)
        } else {
            null
        }
    }

    fun ensureAlbumArtwork(config: JellyfinConfig, albums: List<JellyfinAlbum>) {
        val managedAlbumIds = preferences.all.values.mapNotNull { value ->
            (value as? String)?.let { json ->
                runCatching { JSONObject(json).getString("albumId") }.getOrNull()
            }
        }.toSet()
        albums.filter { it.id in managedAlbumIds }.forEach { album ->
            enqueueArtwork(config, album)
        }
    }

    fun summary(album: JellyfinAlbum): AlbumDownloadSummary {
        val states = album.tracks.map { state(it.id) }
        return AlbumDownloadSummary(
            downloaded = states.count { it == TrackDownloadState.DOWNLOADED },
            downloading = states.count { it == TrackDownloadState.DOWNLOADING },
            failed = states.count { it == TrackDownloadState.FAILED },
            total = states.size,
        )
    }

    fun managedAlbums(catalog: List<JellyfinAlbum>): List<AlbumDownloadDetails> {
        val albumIds = preferences.all.values.mapNotNull { value ->
            (value as? String)?.let { json ->
                runCatching { JSONObject(json).getString("albumId") }.getOrNull()
            }
        }.toSet()
        return catalog
            .filter { it.id in albumIds }
            .map { album ->
                AlbumDownloadDetails(
                    album = album,
                    summary = summary(album),
                    bytes = album.tracks.sumOf { track ->
                        record(track.id)?.filePath?.let(::File)?.takeIf(File::isFile)?.length() ?: 0L
                    } + (artworkRecord(album.id)?.filePath
                        ?.let(::File)
                        ?.takeIf(File::isFile)
                        ?.length() ?: 0L),
                )
            }
            .sortedWith(compareBy({ it.album.artist.lowercase() }, { it.album.title.lowercase() }))
    }

    private fun state(trackId: String): TrackDownloadState =
        record(trackId)?.let(::state) ?: TrackDownloadState.NOT_DOWNLOADED

    private fun state(record: DownloadRecord): TrackDownloadState {
        val query = DownloadManager.Query().setFilterById(record.downloadId)
        return downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use TrackDownloadState.FAILED
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> TrackDownloadState.DOWNLOADING
                DownloadManager.STATUS_SUCCESSFUL -> TrackDownloadState.DOWNLOADED
                DownloadManager.STATUS_FAILED -> TrackDownloadState.FAILED
                else -> TrackDownloadState.FAILED
            }
        } ?: TrackDownloadState.FAILED
    }

    private fun destinationFile(album: JellyfinAlbum, track: JellyfinTrack): File {
        val albumDirectory = albumDirectory(album.id)
        val number = track.trackNumber?.toString()?.padStart(2, '0') ?: "00"
        val extension = track.container
            ?.substringBefore(',')
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) }
            ?: "audio"
        val safeTitle = track.title.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(80)
        return albumDirectory.resolve("$number $safeTitle.$extension")
    }

    private fun enqueueArtwork(config: JellyfinConfig, album: JellyfinAlbum) {
        artworkRecord(album.id)?.let { existing ->
            if (state(existing) == TrackDownloadState.DOWNLOADED ||
                state(existing) == TrackDownloadState.DOWNLOADING
            ) {
                return
            }
            downloadManager.remove(existing.downloadId)
            File(existing.filePath).delete()
            preferences.edit().remove(artworkKey(album.id)).apply()
        }

        val destination = albumDirectory(album.id).resolve("cover.jpg")
        destination.parentFile?.mkdirs()
        destination.delete()
        val request = DownloadManager.Request(
            Uri.parse(JellyfinClient(config).imageUrl(album.id, maxWidth = 1200)),
        )
            .addRequestHeader(
                "Authorization",
                JellyfinClient.authorizationHeader(config.apiKey),
            )
            .setTitle("${album.title} artwork")
            .setDescription(album.artist)
            .setMimeType("image/jpeg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val downloadId = downloadManager.enqueue(request)
        preferences.edit().putString(
            artworkKey(album.id),
            JSONObject()
                .put("trackId", "artwork:${album.id}")
                .put("albumId", album.id)
                .put("downloadId", downloadId)
                .put("filePath", destination.absolutePath)
                .toString(),
        ).apply()
    }

    private fun albumDirectory(albumId: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: context.filesDir.resolve("music")
        return root.resolve("jellyfin").resolve(albumId)
    }

    private fun saveRecord(record: DownloadRecord) {
        preferences.edit().putString(
            key(record.trackId),
            JSONObject()
                .put("trackId", record.trackId)
                .put("albumId", record.albumId)
                .put("downloadId", record.downloadId)
                .put("filePath", record.filePath)
                .toString(),
        ).apply()
    }

    private fun record(trackId: String): DownloadRecord? {
        val json = preferences.getString(key(trackId), null) ?: return null
        return runCatching {
            JSONObject(json).run {
                DownloadRecord(
                    trackId = getString("trackId"),
                    albumId = getString("albumId"),
                    downloadId = getLong("downloadId"),
                    filePath = getString("filePath"),
                )
            }
        }.getOrNull()
    }

    private fun artworkRecord(albumId: String): DownloadRecord? {
        val json = preferences.getString(artworkKey(albumId), null) ?: return null
        return parseRecord(json)
    }

    private fun parseRecord(json: String): DownloadRecord? = runCatching {
        JSONObject(json).run {
            DownloadRecord(
                trackId = getString("trackId"),
                albumId = getString("albumId"),
                downloadId = getLong("downloadId"),
                filePath = getString("filePath"),
            )
        }
    }.getOrNull()

    private fun removeRecord(trackId: String) {
        record(trackId)?.let { record ->
            downloadManager.remove(record.downloadId)
            File(record.filePath).delete()
        }
        preferences.edit().remove(key(trackId)).apply()
    }

    private fun key(trackId: String): String = "download_$trackId"

    private fun artworkKey(albumId: String): String = "artwork_$albumId"

    private data class DownloadRecord(
        val trackId: String,
        val albumId: String,
        val downloadId: Long,
        val filePath: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "jellyfin_downloads"
    }
}
