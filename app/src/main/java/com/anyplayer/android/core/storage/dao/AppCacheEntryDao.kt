package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity

@Dao
interface AppCacheEntryDao {
    @Query("SELECT * FROM app_cache_entries WHERE key = :key LIMIT 1")
    suspend fun get(key: String): AppCacheEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AppCacheEntryEntity)

    @Query("DELETE FROM app_cache_entries WHERE key = :key")
    suspend fun delete(key: String)
}
