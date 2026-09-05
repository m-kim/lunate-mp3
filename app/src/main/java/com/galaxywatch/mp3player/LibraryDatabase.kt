package com.galaxywatch.mp3player

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "tracks",
    indices = [Index(value = ["contentHash"]), Index(value = ["albumArtist", "album"])],
)
internal data class TrackEntity(
    @androidx.room.PrimaryKey val fileName: String,
    val fileSize: Long,
    val lastModified: Long,
    val contentHash: String,
    val duplicateOf: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val addedAtMs: Long,
)

@Dao
internal interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE duplicateOf IS NULL")
    fun visible(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks WHERE duplicateOf IS NULL")
    fun visibleCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    fun deleteAll()
}

@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var instance: LibraryDatabase? = null

        fun get(context: Context): LibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LibraryDatabase::class.java,
                "music-library.db",
            ).build().also { instance = it }
        }
    }
}
