package dev.reclaimed.player.jellyfin

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class JellyfinMetadataSnapshot(
    val serverUrl: String,
    val libraryId: String,
    val refreshedAtMs: Long,
    val artists: List<JellyfinArtist>,
)

class JellyfinMetadataCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, CACHE_FILE_NAME))

    fun load(serverUrl: String, libraryId: String): JellyfinMetadataSnapshot? =
        synchronized(CACHE_LOCK) {
            runCatching {
                val snapshot = runCatching(::readBinary).getOrElse {
                    readLegacyJson().also(::save)
                }
                snapshot.takeIf {
                    it.serverUrl == serverUrl && it.libraryId == libraryId
                }
            }.onFailure { error ->
                Log.w(LOG_TAG, "Unable to read Jellyfin metadata snapshot", error)
            }.getOrNull()
        }

    fun save(snapshot: JellyfinMetadataSnapshot) = synchronized(CACHE_LOCK) {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(
                BufferedOutputStream(GZIPOutputStream(bytes)),
            ).use { output -> output.writeSnapshot(snapshot) }
            bytes.toByteArray()
        }
        val output = file.startWrite()
        try {
            output.write(payload)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun readBinary(): JellyfinMetadataSnapshot = file.openRead().use { input ->
        DataInputStream(BufferedInputStream(GZIPInputStream(input))).use { data ->
            check(data.readInt() == CACHE_MAGIC) { "Not a binary Jellyfin metadata snapshot" }
            check(data.readInt() == CACHE_SCHEMA_VERSION) { "Unsupported cache version" }
            JellyfinMetadataSnapshot(
                serverUrl = data.readUTF(),
                libraryId = data.readUTF(),
                refreshedAtMs = data.readLong(),
                artists = data.readList {
                    JellyfinArtist(
                        id = readUTF(),
                        name = readUTF(),
                        albums = readList {
                            JellyfinAlbum(
                                id = readUTF(),
                                title = readUTF(),
                                artist = readUTF(),
                                tracks = readList {
                                    JellyfinTrack(
                                        id = readUTF(),
                                        title = readUTF(),
                                        artist = readUTF(),
                                        album = readUTF(),
                                        albumId = readUTF(),
                                        durationMs = readLong(),
                                        trackNumber = readNullableInt(),
                                        discNumber = readNullableInt(),
                                        container = readNullableString(),
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun DataOutputStream.writeSnapshot(snapshot: JellyfinMetadataSnapshot) {
        writeInt(CACHE_MAGIC)
        writeInt(CACHE_SCHEMA_VERSION)
        writeUTF(snapshot.serverUrl)
        writeUTF(snapshot.libraryId)
        writeLong(snapshot.refreshedAtMs)
        writeList(snapshot.artists) { artist ->
            writeUTF(artist.id)
            writeUTF(artist.name)
            writeList(artist.albums) { album ->
                writeUTF(album.id)
                writeUTF(album.title)
                writeUTF(album.artist)
                writeList(album.tracks) { track ->
                    writeUTF(track.id)
                    writeUTF(track.title)
                    writeUTF(track.artist)
                    writeUTF(track.album)
                    writeUTF(track.albumId)
                    writeLong(track.durationMs)
                    writeNullableInt(track.trackNumber)
                    writeNullableInt(track.discNumber)
                    writeNullableString(track.container)
                }
            }
        }
    }

    private inline fun <T> DataOutputStream.writeList(items: List<T>, writeItem: (T) -> Unit) {
        writeInt(items.size)
        items.forEach(writeItem)
    }

    private inline fun <T> DataInputStream.readList(readItem: DataInputStream.() -> T): List<T> =
        List(readInt()) { readItem() }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? =
        if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null

    private fun readLegacyJson(): JellyfinMetadataSnapshot = file.openRead().use { input ->
        GZIPInputStream(input).bufferedReader().use { reader ->
            JSONObject(reader.readText()).toLegacySnapshot()
        }
    }

    private fun JSONObject.toLegacySnapshot(): JellyfinMetadataSnapshot = JellyfinMetadataSnapshot(
        serverUrl = getString("serverUrl"),
        libraryId = getString("libraryId"),
        refreshedAtMs = getLong("refreshedAtMs"),
        artists = getJSONArray("artists").mapObjects { artist ->
            JellyfinArtist(
                id = artist.getString("id"),
                name = artist.getString("name"),
                albums = artist.getJSONArray("albums").mapObjects { album ->
                    JellyfinAlbum(
                        id = album.getString("id"),
                        title = album.getString("title"),
                        artist = album.getString("artist"),
                        tracks = album.getJSONArray("tracks").mapObjects { track ->
                            JellyfinTrack(
                                id = track.getString("id"),
                                title = track.getString("title"),
                                artist = track.getString("artist"),
                                album = track.getString("album"),
                                albumId = track.getString("albumId"),
                                durationMs = track.getLong("durationMs"),
                                trackNumber = track.optNullableInt("trackNumber"),
                                discNumber = track.optNullableInt("discNumber"),
                                container = track.optNullableString("container"),
                            )
                        },
                    )
                },
            )
        },
    )

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        List(length()) { index -> transform(getJSONObject(index)) }

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private companion object {
        const val CACHE_FILE_NAME = "jellyfin-metadata.json.gz"
        const val CACHE_MAGIC = 0x52434D50
        const val CACHE_SCHEMA_VERSION = 2
        const val LOG_TAG = "JellyfinMetadataCache"
        val CACHE_LOCK = Any()
    }
}
