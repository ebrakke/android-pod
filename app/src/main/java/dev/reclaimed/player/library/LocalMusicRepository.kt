package dev.reclaimed.player.library

import android.content.ContentResolver
import android.content.ContentUris
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore

data class LocalTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int?,
    val uri: Uri,
)

fun ContentResolver.queryLocalTracks(): List<LocalTrack> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.MIME_TYPE,
    )
    val selection = "${MediaStore.Audio.Media.MIME_TYPE} LIKE 'audio/%'"

    return query(collection, projection, selection, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                val duration = cursor.getLong(durationColumn)
                val embedded = if (duration <= 0L) readEmbeddedMetadata(uri) else null
                add(
                    LocalTrack(
                        id = id,
                        title = embedded?.title
                            ?: cursor.getString(titleColumn)
                            ?: "Unknown track",
                        artist = embedded?.artist ?: cursor.getString(artistColumn).orEmpty(),
                        album = embedded?.album ?: cursor.getString(albumColumn).orEmpty(),
                        albumId = cursor.getLong(albumIdColumn),
                        durationMs = embedded?.durationMs ?: duration,
                        trackNumber = embedded?.trackNumber
                            ?: cursor.getInt(trackColumn).normalizeTrackNumber(),
                        uri = uri,
                    ),
                )
            }
        }
    }.orEmpty().sortedWith(
        compareBy<LocalTrack>(
            { it.artist.lowercase() },
            { it.album.lowercase() },
            { it.trackNumber ?: Int.MAX_VALUE },
            { it.title.lowercase() },
        ),
    )
}

private fun Int.normalizeTrackNumber(): Int? = when {
    this <= 0 -> null
    this >= 1000 -> (this % 1000).takeIf { it > 0 }
    else -> this
}

private data class EmbeddedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val trackNumber: Int?,
)

private fun ContentResolver.readEmbeddedMetadata(uri: Uri): EmbeddedMetadata? {
    val retriever = MediaMetadataRetriever()
    return try {
        openFileDescriptor(uri, "r")?.use { descriptor ->
            retriever.setDataSource(descriptor.fileDescriptor)
            EmbeddedMetadata(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull(),
                trackNumber = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.substringBefore('/')
                    ?.toIntOrNull(),
            )
        }
    } catch (_: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}
