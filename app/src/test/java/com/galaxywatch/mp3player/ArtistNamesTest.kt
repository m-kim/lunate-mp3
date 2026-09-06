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
