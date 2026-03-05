package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.anyplayer.android.core.storage.entity.ProviderPlaylistPreferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderPlaylistPreferenceDao {
    @Query("SELECT * FROM provider_playlist_preferences WHERE source = :source AND playlistId = :playlistId LIMIT 1")
    suspend fun getByKey(source: String, playlistId: String): ProviderPlaylistPreferenceEntity?

    @Query("SELECT * FROM provider_playlist_preferences WHERE source = :source")
    suspend fun getBySource(source: String): List<ProviderPlaylistPreferenceEntity>

    @Query("SELECT * FROM provider_playlist_preferences")
    fun observeAll(): Flow<List<ProviderPlaylistPreferenceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preference: ProviderPlaylistPreferenceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(preferences: List<ProviderPlaylistPreferenceEntity>)

    @Query("DELETE FROM provider_playlist_preferences WHERE source = :source AND playlistId = :playlistId")
    suspend fun deleteByKey(source: String, playlistId: String)

    @Query("DELETE FROM provider_playlist_preferences")
    suspend fun deleteAll()
}
