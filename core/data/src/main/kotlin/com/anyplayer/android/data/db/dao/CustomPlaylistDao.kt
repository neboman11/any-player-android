package com.anyplayer.android.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.anyplayer.android.data.db.entity.CustomPlaylistEntity

@Dao
interface CustomPlaylistDao {
    @Query("SELECT * FROM custom_playlists ORDER BY name COLLATE NOCASE, id")
    suspend fun getAll(): List<CustomPlaylistEntity>
}
