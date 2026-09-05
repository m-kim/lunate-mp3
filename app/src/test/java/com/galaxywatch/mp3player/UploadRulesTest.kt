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
        assertTrue(UploadRules.sanitizeFileName("a".repeat(300) + ".mp3").length <= 180)
    }
}
