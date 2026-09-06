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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(tableName = "artist_aliases")
internal data class ArtistAliasEntity(
    @androidx.room.PrimaryKey val normalizedAlias: String,
    val canonicalName: String,
)

@Entity(
    tableName = "album_aliases",
    primaryKeys = ["normalizedAlbumArtist", "normalizedAlias"],
)
internal data class AlbumAliasEntity(
    val normalizedAlbumArtist: String,
    val normalizedAlias: String,
    val canonicalName: String,
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

    @Query("SELECT * FROM artist_aliases")
    fun artistAliases(): List<ArtistAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertArtistAlias(alias: ArtistAliasEntity)

    @Query("SELECT * FROM album_aliases")
    fun albumAliases(): List<AlbumAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAlbumAlias(alias: AlbumAliasEntity)
}

@Database(
    entities = [TrackEntity::class, ArtistAliasEntity::class, AlbumAliasEntity::class],
    version = 3,
    exportSchema = false,
)
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artist_aliases` (`normalizedAlias` TEXT NOT NULL, `canonicalName` TEXT NOT NULL, PRIMARY KEY(`normalizedAlias`))",
                )
            }
        }


        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album_aliases` (`normalizedAlbumArtist` TEXT NOT NULL, `normalizedAlias` TEXT NOT NULL, `canonicalName` TEXT NOT NULL, PRIMARY KEY(`normalizedAlbumArtist`, `normalizedAlias`))",
                )
            }
        }
    }
}
