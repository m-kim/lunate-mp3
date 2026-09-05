package com.galaxywatch.mp3player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TrackSortingTest {
    private val first = track("02-second.mp3", "Second", "Artist B", "Album", 2, 20)
    private val second = track("01-first.mp3", "First", "Artist A", "Album", 1, 10)

    @Test
    fun sortsByAlbumTrackNumber() {
        assertEquals(listOf(second, first), sortTracks(listOf(first, second), SortMode.ALBUM))
    }

    @Test
    fun sortsRecentlyAddedNewestFirst() {
        assertEquals(listOf(first, second), sortTracks(listOf(second, first), SortMode.RECENTLY_ADDED))
    }

    @Test
    fun sortsByFileName() {
        assertEquals(listOf(second, first), sortTracks(listOf(first, second), SortMode.FILE_NAME))
    }

    @Test
    fun groupsAlbumsAndOrdersTheirTracks() {
        val albumFirst = first.copy(albumArtist = "Compilation Artist")
        val albumSecond = second.copy(albumArtist = "Compilation Artist")
        val otherAlbum = track("other.mp3", "Other", "Artist A", "Other Album", 1, 30)
            .copy(albumArtist = "Compilation Artist")

        val albums = groupAlbums(listOf(albumFirst, otherAlbum, albumSecond))

        assertEquals(2, albums.size)
        assertEquals("Album", albums[0].title)
        assertEquals(listOf(albumSecond, albumFirst), albums[0].tracks)
        assertEquals("Other Album", albums[1].title)
    }

    private fun track(
        fileName: String,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        addedAtMs: Long,
    ) = Track(
        file = File(fileName),
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        discNumber = 1,
        trackNumber = trackNumber,
        durationMs = 60_000,
        addedAtMs = addedAtMs,
    )
}
