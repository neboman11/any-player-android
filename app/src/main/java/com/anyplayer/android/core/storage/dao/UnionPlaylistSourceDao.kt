package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.anyplayer.android.core.storage.entity.UnionPlaylistSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnionPlaylistSourceDao {
    @Query("SELECT * FROM union_playlist_sources")
    suspend fun getAll(): List<UnionPlaylistSourceEntity>

    @Query("SELECT * FROM union_playlist_sources WHERE unionPlaylistId = :playlistId ORDER BY position ASC")
    fun observeByUnionPlaylist(playlistId: String): Flow<List<UnionPlaylistSourceEntity>>

    @Query("SELECT * FROM union_playlist_sources WHERE unionPlaylistId = :playlistId ORDER BY position ASC")
    suspend fun getByUnionPlaylist(playlistId: String): List<UnionPlaylistSourceEntity>

    @Query("SELECT * FROM union_playlist_sources WHERE unionPlaylistId IN (:playlistIds) ORDER BY position ASC")
    suspend fun getByUnionPlaylistIds(playlistIds: List<String>): List<UnionPlaylistSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sources: List<UnionPlaylistSourceEntity>)

    @Query("DELETE FROM union_playlist_sources WHERE unionPlaylistId = :playlistId")
    suspend fun deleteByUnionPlaylist(playlistId: String)

    @Query("DELETE FROM union_playlist_sources")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceSources(playlistId: String, sources: List<UnionPlaylistSourceEntity>) {
        deleteByUnionPlaylist(playlistId)
        upsert(sources)
    }
}
