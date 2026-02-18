package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.anyplayer.android.core.storage.entity.CustomPlaylistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomPlaylistDao {
    @Query("SELECT * FROM custom_playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CustomPlaylistEntity>>

    @Query("SELECT * FROM custom_playlists")
    suspend fun getAll(): List<CustomPlaylistEntity>

    @Query("SELECT * FROM custom_playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CustomPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlists: List<CustomPlaylistEntity>)

    @Update
    suspend fun update(playlist: CustomPlaylistEntity)

    @Query("DELETE FROM custom_playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM custom_playlists")
    suspend fun deleteAll()
}
