package com.eok.mp3recorder.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mediaId: Long,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_items", indices = [Index("playlistId")])
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val mediaId: Long,
    val position: Int,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val trackCount: Int,
)

@Dao
interface MusicDao {

    // ---- 즐겨찾기 ----
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mediaId = :mediaId")
    suspend fun removeFavorite(mediaId: Long)

    // ---- 재생목록 ----
    @Query(
        """SELECT p.id, p.name,
           (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS trackCount
           FROM playlists p ORDER BY p.createdAt ASC"""
    )
    fun playlistsWithCount(): Flow<List<PlaylistWithCount>>

    @Insert
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deletePlaylistItems(playlistId: Long)

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        deletePlaylistItems(id)
        deletePlaylistRow(id)
    }

    // ---- 재생목록 항목 ----
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC")
    fun playlistItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertItemRow(item: PlaylistItemEntity)

    @Transaction
    suspend fun addToPlaylist(playlistId: Long, mediaId: Long) {
        insertItemRow(
            PlaylistItemEntity(
                playlistId = playlistId,
                mediaId = mediaId,
                position = maxPosition(playlistId) + 1,
            )
        )
    }

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun removeItem(itemId: Long)

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    suspend fun updateItemPosition(itemId: Long, position: Int)

    /** 인접 항목과 순서 교환 (위/아래 이동) */
    @Transaction
    suspend fun swapItems(itemId1: Long, pos1: Int, itemId2: Long, pos2: Int) {
        updateItemPosition(itemId1, pos2)
        updateItemPosition(itemId2, pos1)
    }
}

@Database(
    entities = [FavoriteEntity::class, PlaylistEntity::class, PlaylistItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "mp3recorder.db"
                ).build().also { instance = it }
            }
    }
}
