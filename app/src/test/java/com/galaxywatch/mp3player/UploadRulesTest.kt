package com.galaxywatch.mp3player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRulesTest {
    @Test
    fun stripsPathTraversal() {
        assertEquals("song.mp3", UploadRules.sanitizeFileName("../../private/song.mp3"))
        assertEquals("song.mp3", UploadRules.sanitizeFileName("C:\\Music\\song.mp3"))
    }

    @Test
    fun replacesUnsafeCharacters() {
        assertEquals("AC_DC _ Live.mp3", UploadRules.sanitizeFileName("AC_DC 🎸 Live.mp3"))
    }

    @Test
    fun limitsFileNameLength() {
        val sanitized = UploadRules.sanitizeFileName("a".repeat(300) + ".mp3")

        assertTrue(sanitized.length <= 180)
        assertTrue(sanitized.endsWith(".mp3"))
    }

    @Test
    fun identifiesSupportedAudioExtensionsWithoutAcceptingExtraSuffixes() {
        assertTrue(SupportedAudio.hasSupportedExtension("Album/Song.MP3"))
        assertTrue(SupportedAudio.hasSupportedExtension("Album/Song.M4A"))
        assertTrue(!SupportedAudio.hasSupportedExtension("Song.mp3.download"))
        assertTrue(!SupportedAudio.hasSupportedExtension("Song.m4p"))
    }

    @Test
    fun storageReserveIsExcludedFromUploadCapacity() {
        assertEquals(800L, StorageStatus.uploadCapacity(1_000L, 200L))
        assertEquals(0L, StorageStatus.uploadCapacity(100L, 200L))
    }
}
