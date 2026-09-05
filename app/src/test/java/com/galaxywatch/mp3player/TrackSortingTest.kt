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
