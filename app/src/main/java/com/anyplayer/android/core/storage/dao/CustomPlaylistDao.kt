package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(playlists: List<CustomPlaylistEntity>): List<Long>

    @Update
    suspend fun update(playlists: List<CustomPlaylistEntity>)

    @Transaction
    suspend fun upsert(playlists: List<CustomPlaylistEntity>) {
        if (playlists.isEmpty()) return
        val insertResults = insertIgnore(playlists)
        val toUpdate = playlists.zip(insertResults)
            .filter { (_, result) -> result == -1L }
            .map { (playlist, _) -> playlist }
        if (toUpdate.isNotEmpty()) {
            update(toUpdate)
        }
    }

    @Query("DELETE FROM custom_playlists WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM custom_playlists")
    suspend fun deleteAll()
}
