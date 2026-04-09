package com.anyplayer.android.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.anyplayer.android.core.storage.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistTrackDao {
    @Query("SELECT * FROM playlist_tracks")
    suspend fun getAll(): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeByPlaylist(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getByPlaylist(playlistId: String): List<PlaylistTrackEntity>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId IN (:playlistIds) ORDER BY position ASC")
    suspend fun getByPlaylistIds(playlistIds: List<String>): List<PlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tracks: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_tracks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM playlist_tracks")
    suspend fun deleteAll()

    @Transaction
    suspend fun replacePlaylistTracks(playlistId: String, tracks: List<PlaylistTrackEntity>) {
        deleteByPlaylist(playlistId)
        upsert(tracks)
    }
}
