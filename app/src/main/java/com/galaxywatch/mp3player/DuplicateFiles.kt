package com.galaxywatch.mp3player

import java.io.File
import java.security.MessageDigest

internal object DuplicateFiles {
    fun findMatch(directory: File, candidate: File, candidateHash: ByteArray): File? = directory
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter {
            it.isFile &&
                it != candidate &&
                it.extension.equals("mp3", ignoreCase = true) &&
                it.length() == candidate.length()
        }
        .firstOrNull { sha256(it).contentEquals(candidateHash) }

    fun unique(files: List<File>): List<File> {
        val fileCountBySize = files.groupingBy { it.length() }.eachCount()
        val hashesBySize = mutableMapOf<Long, MutableList<ByteArray>>()
        return files
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name.lowercase() }))
            .filter { file ->
                if (fileCountBySize[file.length()] == 1) return@filter true

                val hash = sha256(file)
                val hashes = hashesBySize.getOrPut(file.length()) { mutableListOf() }
                if (hashes.any { it.contentEquals(hash) }) {
                    false
                } else {
                    hashes += hash
                    true
                }
            }
    }

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}
