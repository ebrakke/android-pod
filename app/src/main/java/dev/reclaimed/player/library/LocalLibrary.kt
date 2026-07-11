package dev.reclaimed.player.library

import android.content.ContentUris
import android.net.Uri

private const val UNKNOWN_ARTIST = "Unknown Artist"
private const val UNKNOWN_ALBUM = "Unknown Album"

data class LocalArtist(
    val name: String,
    val albums: List<LocalAlbum>,
) {
    val trackCount: Int = albums.sumOf { it.tracks.size }
}

data class LocalAlbum(
    val id: Long,
    val title: String,
    val artist: String,
    val tracks: List<LocalTrack>,
) {
    val artworkUri: Uri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        id,
    )
    val durationMs: Long = tracks.sumOf { it.durationMs }
}

fun List<LocalTrack>.toLocalArtists(): List<LocalArtist> =
    groupBy { it.artist.ifBlank { UNKNOWN_ARTIST } }
        .map { (artistName, artistTracks) ->
            LocalArtist(
                name = artistName,
                albums = artistTracks
                    .groupBy { it.albumId }
                    .map { (albumId, albumTracks) ->
                        LocalAlbum(
                            id = albumId,
                            title = albumTracks.first().album.ifBlank { UNKNOWN_ALBUM },
                            artist = artistName,
                            tracks = albumTracks.sortedWith(
                                compareBy<LocalTrack>(
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
