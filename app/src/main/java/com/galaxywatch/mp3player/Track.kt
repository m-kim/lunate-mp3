package com.galaxywatch.mp3player

import android.media.MediaMetadataRetriever
import java.io.File
import java.text.Normalizer
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
        .filter(SupportedAudio::isSupported)
        .toList(),
    )
        .asSequence()
        .map(::readTrack)
        .toList()

    internal fun readTrack(file: File): Track {
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

internal data class AlbumGroup(
    val title: String,
    val albumArtist: String,
    val tracks: List<Track>,
)

internal data class ArtistGroup(val name: String, val tracks: List<Track>)

internal object ArtistNames {
    fun display(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filterNot { character ->
            when (Character.getType(character)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt(),
                Character.PRIVATE_USE.toInt() -> true
                else -> false
            }
        }
        .trim()
        .replace(Regex("\\s+"), " ")

    fun identity(value: String): String = display(value).lowercase(Locale.ROOT)

    fun canonicalize(tracks: List<Track>, aliases: Map<String, String>): List<Track> {
        val appearances = tracks.flatMap { listOf(it.artist, it.albumArtist) }
            .map(::display)
            .filter(String::isNotBlank)
            .groupBy(::identity)
        val canonicalByIdentity = appearances.mapValues { (identity, values) ->
            aliases[identity]?.let(::display)?.takeIf(String::isNotBlank)
                ?: canonicalDisplay(values)
        }

        fun canonical(value: String): String {
            val cleaned = display(value)
            return aliases[identity(cleaned)]?.let(::display)?.takeIf(String::isNotBlank)
                ?: canonicalByIdentity[identity(cleaned)]
                ?: cleaned
        }

        return tracks.map { track ->
            track.copy(
                artist = canonical(track.artist),
                albumArtist = canonical(track.albumArtist),
            )
        }
    }

    internal fun canonicalDisplay(values: List<String>): String = values
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { unusualCapitalization(it.key) }
                .thenBy { it.key.lowercase(Locale.ROOT) },
        )
        .first()
        .key

    private fun unusualCapitalization(value: String): Int = value
        .drop(1)
        .withIndex()
        .count { (index, character) -> character.isUpperCase() && value[index].isLetter() }
}

internal data class AlbumAliasKey(val albumArtist: String, val album: String)

internal object AlbumNames {
    fun canonicalize(tracks: List<Track>, aliases: Map<AlbumAliasKey, String>): List<Track> {
        val albumsByIdentity = tracks.groupBy { track ->
            AlbumAliasKey(
                ArtistNames.identity(track.albumArtist),
                ArtistNames.identity(track.album),
            )
        }
        val canonicalByIdentity = albumsByIdentity.mapValues { (identity, albumTracks) ->
            aliases[identity]?.let(ArtistNames::display)?.takeIf(String::isNotBlank)
                ?: ArtistNames.canonicalDisplay(albumTracks.map { ArtistNames.display(it.album) })
        }
        return tracks.map { track ->
            val identity = AlbumAliasKey(
                ArtistNames.identity(track.albumArtist),
                ArtistNames.identity(track.album),
            )
            track.copy(album = canonicalByIdentity[identity] ?: ArtistNames.display(track.album))
        }
    }
}

internal fun groupAlbums(tracks: List<Track>): List<AlbumGroup> = tracks
    .groupBy { it.albumArtist to it.album }
    .map { (identity, albumTracks) ->
        AlbumGroup(
            title = identity.second,
            albumArtist = identity.first,
            tracks = sortTracks(albumTracks, SortMode.ALBUM),
        )
    }
    .sortedWith(compareBy({ it.albumArtist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }))

internal fun groupArtists(tracks: List<Track>): List<ArtistGroup> = tracks
    .groupBy { ArtistNames.identity(it.artist) }
    .map { (_, artistTracks) ->
        val artist = ArtistNames.canonicalize(artistTracks, emptyMap()).first().artist
        ArtistGroup(artist, sortTracks(artistTracks, SortMode.ARTIST))
    }
    .sortedBy { it.name.lowercase(Locale.ROOT) }
