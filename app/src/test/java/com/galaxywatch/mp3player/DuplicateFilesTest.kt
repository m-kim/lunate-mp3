package com.galaxywatch.mp3player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateFilesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun findsIdenticalContentUnderDifferentNames() {
        val directory = temporaryFolder.newFolder("music")
        val original = directory.resolve("Song.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val upload = directory.resolve(".Song (2).mp3.uploading").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(
            original.absolutePath,
            DuplicateFiles.findMatch(directory, upload, DuplicateFiles.sha256(upload))?.absolutePath,
        )
    }

    @Test
    fun acceptsSameSizedFilesWithDifferentContent() {
        val directory = temporaryFolder.newFolder("music")
        directory.resolve("First.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val upload = directory.resolve(".Second.mp3.uploading").apply {
            writeBytes(byteArrayOf(3, 2, 1))
        }

        assertNull(DuplicateFiles.findMatch(directory, upload, DuplicateFiles.sha256(upload)))
    }

    @Test
    fun collapsesExistingExactDuplicatesWithoutDeletingThem() {
        val first = temporaryFolder.newFile("First.mp3").apply { writeText("same") }
        val second = temporaryFolder.newFile("Second.mp3").apply { writeText("same") }
        val different = temporaryFolder.newFile("Different.mp3").apply { writeText("else") }

        assertEquals(2, DuplicateFiles.unique(listOf(first, second, different)).size)
        assertEquals(true, first.exists())
        assertEquals(true, second.exists())
    }
}
