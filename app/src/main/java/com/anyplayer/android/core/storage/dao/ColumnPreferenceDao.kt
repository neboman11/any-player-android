package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anyplayer.android.core.storage.entity.ColumnPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ColumnPreferenceDao {
    @Query("SELECT * FROM column_preferences ORDER BY position ASC")
    fun observeAll(): Flow<List<ColumnPreferenceEntity>>

    @Query("SELECT * FROM column_preferences")
    suspend fun getAll(): List<ColumnPreferenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preferences: List<ColumnPreferenceEntity>)
}
