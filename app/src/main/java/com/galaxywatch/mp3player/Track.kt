package com.galaxywatch.mp3player

import android.media.MediaMetadataRetriever
import java.io.File
import java.util.Locale

data class Track(
    val file: File,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val addedAtMs: Long,
) {
    val id: String = file.absolutePath
}

enum class SortMode(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    TRACK_NUMBER("Track #"),
    RECENTLY_ADDED("Recent"),
    FILE_NAME("Filename"),
}

object TrackLibrary {
    fun scan(musicDirectory: File): List<Track> = DuplicateFiles.unique(
        musicDirectory
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
        .toList(),
    )
        .asSequence()
        .map(::readTrack)
        .toList()

    private fun readTrack(file: File): Track {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var discNumber = 0
        var trackNumber = 0
        var duration = 0L
        runCatching {
            MediaMetadataRetriever().use { metadata ->
                metadata.setDataSource(file.absolutePath)
                title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                albumArtist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                discNumber = parseTagNumber(
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                )
                trackNumber = parseTagNumber(
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                )
                duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        }

        return Track(
            file = file,
            title = title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist",
            album = album?.takeIf { it.isNotBlank() } ?: "Unknown album",
            albumArtist = albumArtist?.takeIf { it.isNotBlank() }
                ?: artist?.takeIf { it.isNotBlank() }
                ?: "Unknown artist",
            discNumber = discNumber,
            trackNumber = trackNumber,
            durationMs = duration,
            addedAtMs = file.lastModified(),
        )
    }

    private fun parseTagNumber(value: String?): Int = value
        ?.substringBefore('/')
        ?.trim()
        ?.toIntOrNull()
        ?: 0
}

internal fun sortTracks(tracks: List<Track>, mode: SortMode): List<Track> {
    fun String.key() = lowercase(Locale.ROOT)
    fun Int.knownFirst() = if (this > 0) this else Int.MAX_VALUE

    return when (mode) {
        SortMode.TITLE -> tracks.sortedWith(compareBy({ it.title.key() }, { it.artist.key() }))
        SortMode.ARTIST -> tracks.sortedWith(
            compareBy({ it.artist.key() }, { it.album.key() }, { it.discNumber.knownFirst() },
                { it.trackNumber.knownFirst() }, { it.title.key() }),
        )
        SortMode.ALBUM -> tracks.sortedWith(
            compareBy({ it.albumArtist.key() }, { it.album.key() }, { it.discNumber.knownFirst() },
                { it.trackNumber.knownFirst() }, { it.title.key() }),
        )
        SortMode.TRACK_NUMBER -> tracks.sortedWith(
            compareBy({ it.discNumber.knownFirst() }, { it.trackNumber.knownFirst() },
                { it.title.key() }),
        )
        SortMode.RECENTLY_ADDED -> tracks.sortedWith(
            compareByDescending<Track> { it.addedAtMs }.thenBy { it.title.key() },
        )
        SortMode.FILE_NAME -> tracks.sortedBy { it.file.name.key() }
    }
}
