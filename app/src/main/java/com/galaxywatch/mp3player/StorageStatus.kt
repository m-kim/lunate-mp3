package com.galaxywatch.mp3player

import android.os.StatFs
import java.io.File

internal data class StorageStatus(
    val totalBytes: Long,
    val availableBytes: Long,
    val availableForMusicBytes: Long,
    val musicBytes: Long,
    val reserveBytes: Long,
) {
    companion object {
        const val RESERVE_BYTES = 200L * 1024L * 1024L

        fun read(musicDirectory: File): StorageStatus {
            val statistics = StatFs(musicDirectory.absolutePath)
            val available = statistics.availableBytes.coerceAtLeast(0L)
            return StorageStatus(
                totalBytes = statistics.totalBytes.coerceAtLeast(0L),
                availableBytes = available,
                availableForMusicBytes = uploadCapacity(available, RESERVE_BYTES),
                musicBytes = musicDirectory.listFiles().orEmpty()
                    .filter(SupportedAudio::isSupported)
                    .sumOf(File::length),
                reserveBytes = RESERVE_BYTES,
            )
        }

        internal fun uploadCapacity(availableBytes: Long, reserveBytes: Long): Long =
            (availableBytes - reserveBytes).coerceAtLeast(0L)
    }
}
