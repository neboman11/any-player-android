package com.anyplayer.android.data.db.dao

import androidx.room.*
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CustomPlaylistDao {
    @Query("SELECT * FROM custom_playlists ORDER BY name COLLATE NOCASE, id")
    abstract suspend fun getAll(): List<CustomPlaylistEntity>
    @Query("SELECT * FROM custom_playlists ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<CustomPlaylistEntity>>
    @Query("SELECT * FROM custom_playlists WHERE id = :id")
    abstract suspend fun get(id: String): CustomPlaylistEntity?
    @Query("SELECT * FROM custom_playlists WHERE id = :id")
    abstract fun observe(id: String): Flow<CustomPlaylistEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(playlist: CustomPlaylistEntity): Long
    @Update
    abstract suspend fun update(playlist: CustomPlaylistEntity)
    @Transaction
    open suspend fun upsert(playlist: CustomPlaylistEntity) {
        if (insert(playlist) == -1L) update(playlist)
    }
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    abstract suspend fun tracks(id: String): List<PlaylistTrackEntity>
    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    abstract fun observeTracks(id: String): Flow<List<PlaylistTrackEntity>>
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position")
    abstract suspend fun allTracks(): List<PlaylistTrackEntity>
    @Query("SELECT * FROM union_playlist_sources WHERE unionPlaylistId = :id ORDER BY position")
    abstract suspend fun sources(id: String): List<UnionPlaylistSourceEntity>
    @Query("SELECT * FROM union_playlist_sources WHERE unionPlaylistId = :id ORDER BY position")
    abstract fun observeSources(id: String): Flow<List<UnionPlaylistSourceEntity>>
    @Query("SELECT * FROM union_playlist_sources ORDER BY unionPlaylistId, position")
    abstract suspend fun allSources(): List<UnionPlaylistSourceEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSources(sources: List<UnionPlaylistSourceEntity>)
    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun clearTracks(id: String)
    @Query("DELETE FROM union_playlist_sources WHERE unionPlaylistId = :id")
    abstract suspend fun clearSources(id: String)
    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun nextTrackPosition(id: String): Int
    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM union_playlist_sources WHERE unionPlaylistId = :id")
    abstract suspend fun nextSourcePosition(id: String): Int
    @Query("UPDATE custom_playlists SET trackCount = (SELECT COUNT(*) FROM playlist_tracks WHERE playlistId = :id), updatedAt = :now WHERE id = :id")
    abstract suspend fun updateTrackCount(id: String, now: String)
    /** Plain (re)materialization: count only. Legacy never moved a union up the list for Play/Open. */
    @Query("UPDATE custom_playlists SET trackCount = :count WHERE id = :id")
    abstract suspend fun materialized(id: String, count: Int)
    @Query("UPDATE custom_playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: String, now: String)
    @Query("UPDATE custom_playlists SET name = CASE WHEN TRIM(:name) = '' THEN name ELSE :name END, updatedAt = :now WHERE id = :id")
    abstract suspend fun renameRow(id: String, name: String, now: String)
    @Query("UPDATE custom_playlists SET isDistinct = :distinct, updatedAt = :now WHERE id = :id")
    abstract suspend fun distinctRow(id: String, distinct: Boolean, now: String)
    @Query("DELETE FROM custom_playlists WHERE id = :id")
    abstract suspend fun deleteRow(id: String)
    @Query("DELETE FROM custom_playlists")
    abstract suspend fun deleteAll()
    @Query("DELETE FROM cached_tracks WHERE ownerKey = :key")
    abstract suspend fun clearCachedTracks(key: String)
    @Query("DELETE FROM cached_track_owners WHERE ownerKey = :key")
    abstract suspend fun clearCachedOwner(key: String)
    @Query("DELETE FROM app_cache_entries WHERE `key` = :key")
    abstract suspend fun clearLegacyCache(key: String)
    @Query("SELECT * FROM column_preferences ORDER BY position")
    abstract suspend fun columns(): List<ColumnPreferenceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putColumns(columns: List<ColumnPreferenceEntity>)

    @Transaction
    open suspend fun rename(id: String, name: String, now: String) { renameRow(id, name, now) }
    @Transaction
    open suspend fun setDistinct(id: String, distinct: Boolean, now: String) { distinctRow(id, distinct, now) }
    @Transaction
    open suspend fun delete(id: String) {
        clearCachedTracks("u:$id")
        clearCachedOwner("u:$id")
        clearLegacyCache("union_playlist_tracks_$id")
        deleteRow(id)
    }
    @Transaction
    open suspend fun append(track: PlaylistTrackEntity, now: String) {
        if (get(track.playlistId)?.playlistType != PlaylistType.STANDARD) return
        insertTracks(listOf(track.copy(position = nextTrackPosition(track.playlistId))))
        updateTrackCount(track.playlistId, now)
    }
    @Transaction
    open suspend fun removeAt(id: String, index: Int, now: String) {
        val rows = tracks(id).toMutableList()
        if (index !in rows.indices) return
        rows.removeAt(index)
        replaceTracks(id, rows)
        updateTrackCount(id, now)
    }
    @Transaction
    open suspend fun reorder(id: String, ids: List<String>, now: String) {
        val rows = tracks(id)
        val byId = rows.associateBy { it.id }
        val requested = ids.distinct().mapNotNull(byId::get)
        val requestedIds = requested.mapTo(HashSet()) { it.id }
        replaceTracks(id, requested + rows.filter { it.id !in requestedIds })
        updateTrackCount(id, now)
    }
    @Transaction
    open suspend fun replaceTracks(id: String, rows: List<PlaylistTrackEntity>) {
        clearTracks(id)
        insertTracks(rows.mapIndexed { position, row -> row.copy(playlistId = id, position = position) })
    }
    @Transaction
    open suspend fun replaceSources(id: String, rows: List<UnionPlaylistSourceEntity>) {
        if (get(id)?.playlistType != PlaylistType.UNION) return
        clearSources(id)
        insertSources(rows.map { it.copy(unionPlaylistId = id) })
        clearCachedTracks("u:$id")
        clearCachedOwner("u:$id")
        clearLegacyCache("union_playlist_tracks_$id")
    }
}
