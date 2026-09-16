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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** 폴더 불러오기로 만든 재생목록이면 그 폴더 경로 — 앱 실행 시 새 파일을 자동 추가 */
    val folderPath: String? = null,
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
    val folderPath: String?,
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
        """SELECT p.id, p.name, p.folderPath,
           (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS trackCount
           FROM playlists p ORDER BY p.createdAt ASC"""
    )
    fun playlistsWithCount(): Flow<List<PlaylistWithCount>>

    /** 폴더와 연결된 재생목록만 (자동 동기화 대상) */
    @Query("SELECT * FROM playlists WHERE folderPath IS NOT NULL")
    suspend fun folderPlaylists(): List<PlaylistEntity>

    @Query("SELECT mediaId FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun itemMediaIds(playlistId: Long): List<Long>

    // ---- 파일 이동(복사 방식)으로 MediaStore ID가 바뀐 경우 참조 갱신 ----
    @Query("UPDATE playlist_items SET mediaId = :newId WHERE mediaId = :oldId")
    suspend fun remapItemMediaId(oldId: Long, newId: Long)

    @Query("UPDATE OR REPLACE favorites SET mediaId = :newId WHERE mediaId = :oldId")
    suspend fun remapFavoriteMediaId(oldId: Long, newId: Long)

    @Transaction
    suspend fun remapMediaId(oldId: Long, newId: Long) {
        remapItemMediaId(oldId, newId)
        remapFavoriteMediaId(oldId, newId)
    }

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
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): MusicDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN folderPath TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "mp3recorder.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
