package com.galaxywatch.mp3player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ArtistNamesTest {
    @Test
    fun normalizesCaseWhitespaceUnicodeAndInvisibleCharacters() {
        val variants = listOf(
            "Michelle Branch",
            "MICHELLE   BRANCH ",
            "Michelle\u200B Branch",
            "Ｍｉｃｈｅｌｌｅ Branch",
        )

        assertEquals(1, variants.map(ArtistNames::identity).distinct().size)
    }

    @Test
    fun usesMostCommonDisplayAndAppliesExplicitAliases() {
        val tracks = listOf(
            track("Michelle Branch"),
            track("Michelle Branch"),
            track("MICHELLE BRANCH"),
            track("MIchell Branch"),
        )
        val aliases = mapOf(ArtistNames.identity("MIchell Branch") to "Michelle Branch")

        assertEquals(
            listOf("Michelle Branch"),
            ArtistNames.canonicalize(tracks, aliases).map { it.artist }.distinct(),
        )
    }

    @Test
    fun albumAliasesAreScopedToAlbumArtist() {
        val firstArtist = track("Artist One").copy(album = "GREATEST  HITS")
        val secondArtist = track("Artist Two").copy(album = "Greatest Hits")
        val aliases = mapOf(
            AlbumAliasKey(ArtistNames.identity("Artist One"), ArtistNames.identity("GREATEST HITS")) to
                "Greatest Hits: The Collection",
        )

        val albums = AlbumNames.canonicalize(listOf(firstArtist, secondArtist), aliases)

        assertEquals("Greatest Hits: The Collection", albums[0].album)
        assertEquals("Greatest Hits", albums[1].album)
    }

    private fun track(artist: String) = Track(
        file = File("$artist.mp3"),
        title = "Song",
        artist = artist,
        album = "Album",
        albumArtist = artist,
        discNumber = 1,
        trackNumber = 1,
        durationMs = 1L,
        addedAtMs = 1L,
    )
}
