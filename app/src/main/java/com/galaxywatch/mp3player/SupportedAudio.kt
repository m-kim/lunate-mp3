package com.galaxywatch.mp3player

import java.io.File
import java.util.Locale

internal object SupportedAudio {
    private val extensions = setOf("mp3", "m4a")

    fun hasSupportedExtension(name: String): Boolean {
        val leafName = name.substringAfterLast('/').substringAfterLast('\\')
        return leafName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in extensions
    }

    fun isSupported(file: File): Boolean = file.isFile && hasSupportedExtension(file.name)
}
