package com.galaxywatch.mp3player

import android.content.Context
import java.io.File

internal class LibraryRepository(context: Context) {
    private val database = LibraryDatabase.get(context)
    private val dao = database.trackDao()
    private val musicDirectory = File(context.filesDir, "music").apply { mkdirs() }

    fun cachedTracks(): List<Track> = dao.visible().map(::toTrack)

    fun cachedTrackCount(): Int = dao.visibleCount()

    fun reconcile(): List<Track> = synchronized(indexLock) {
        val cachedByName = dao.all().associateBy { it.fileName }
        val files = musicDirectory.listFiles().orEmpty()
            .filter(SupportedAudio::isSupported)
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name.lowercase() }))

        val refreshed = files.map { file ->
            val cached = cachedByName[file.name]
            if (cached != null && cached.matches(file)) {
                cached
            } else {
                val track = TrackLibrary.readTrack(file)
                track.toEntity(DuplicateFiles.sha256(file).toHex())
            }
        }

        val firstFileByHash = mutableMapOf<String, String>()
        val indexed = refreshed.map { entity ->
            val original = firstFileByHash.putIfAbsent(entity.contentHash, entity.fileName)
            entity.copy(duplicateOf = original)
        }

        database.runInTransaction {
            dao.deleteAll()
            dao.upsertAll(indexed)
        }
        indexed.filter { it.duplicateOf == null }.map(::toTrack)
    }

    fun indexUploaded(file: File, contentHash: ByteArray) {
        synchronized(indexLock) {
            val track = TrackLibrary.readTrack(file)
            val hash = contentHash.toHex()
            val duplicate = dao.all().firstOrNull {
                it.fileName != file.name && it.contentHash == hash
            }
            dao.upsert(track.toEntity(hash, duplicate?.fileName))
        }
    }

    private fun TrackEntity.matches(file: File): Boolean =
        fileSize == file.length() && lastModified == file.lastModified()

    private fun Track.toEntity(hash: String, duplicateOf: String? = null) = TrackEntity(
        fileName = file.name,
        fileSize = file.length(),
        lastModified = file.lastModified(),
        contentHash = hash,
        duplicateOf = duplicateOf,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        discNumber = discNumber,
        trackNumber = trackNumber,
        durationMs = durationMs,
        addedAtMs = addedAtMs,
    )

    private fun toTrack(entity: TrackEntity) = Track(
        file = File(musicDirectory, entity.fileName),
        title = entity.title,
        artist = entity.artist,
        album = entity.album,
        albumArtist = entity.albumArtist,
        discNumber = entity.discNumber,
        trackNumber = entity.trackNumber,
        durationMs = entity.durationMs,
        addedAtMs = entity.addedAtMs,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        val indexLock = Any()
    }
}
