package com.anyplayer.android.data.db.dao

import androidx.room.*
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogCacheDao {
    @Query("SELECT * FROM cached_playlists ORDER BY CASE source WHEN 'JELLYFIN' THEN 0 WHEN 'PLEX' THEN 1 ELSE 2 END, position")
    abstract fun observePlaylists(): Flow<List<CachedPlaylistEntity>>
    @Query("SELECT * FROM cached_playlists ORDER BY CASE source WHEN 'JELLYFIN' THEN 0 WHEN 'PLEX' THEN 1 ELSE 2 END, position")
    abstract suspend fun playlists(): List<CachedPlaylistEntity>
    @Query("SELECT * FROM cached_playlists WHERE source = :source")
    abstract suspend fun playlists(source: SourceType): List<CachedPlaylistEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPlaylists(rows: List<CachedPlaylistEntity>)
    @Query("DELETE FROM cached_playlists WHERE source = :source")
    abstract suspend fun deletePlaylists(source: SourceType)
    @Query("SELECT * FROM cached_tracks WHERE ownerKey = :ownerKey ORDER BY position")
    abstract suspend fun tracks(ownerKey: String): List<CachedTrackEntity>
    @Query("SELECT * FROM cached_track_owners WHERE ownerKey = :ownerKey")
    abstract suspend fun owner(ownerKey: String): CachedTrackOwnerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putTracks(rows: List<CachedTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putOwner(owner: CachedTrackOwnerEntity)
    @Query("DELETE FROM cached_tracks WHERE ownerKey = :ownerKey")
    abstract suspend fun deleteTracks(ownerKey: String)
    @Query("DELETE FROM cached_track_owners WHERE ownerKey = :ownerKey")
    abstract suspend fun deleteOwner(ownerKey: String)
    @Transaction
    open suspend fun replaceTracks(owner: CachedTrackOwnerEntity, tracks: List<CachedTrackEntity>) {
        deleteTracks(owner.ownerKey)
        putTracks(tracks)
        putOwner(owner)
    }
    @Query("UPDATE cached_playlists SET trackCount = :count WHERE source = :source AND id = :id")
    abstract suspend fun updateTrackCount(source: SourceType, id: String, count: Int)

    @Transaction
    open suspend fun replaceProviderTracks(source: SourceType, id: String, owner: CachedTrackOwnerEntity, tracks: List<CachedTrackEntity>) {
        replaceTracks(owner, tracks)
        if (tracks.isNotEmpty()) updateTrackCount(source, id, tracks.size)
    }
    @Query("SELECT ownerKey FROM cached_track_owners WHERE substr(ownerKey, 1, length(:prefix)) = :prefix")
    abstract suspend fun ownerKeys(prefix: String): List<String>
    @Transaction
    open suspend fun replacePlaylists(source: SourceType, rows: List<CachedPlaylistEntity>) {
        val prefix = "p:${source.name}:"
        val retainedKeys = rows.mapTo(HashSet()) { providerOwnerKey(source, it.id) }
        ownerKeys(prefix).filter { it !in retainedKeys }.forEach { key ->
            deleteTracks(key)
            deleteOwner(key)
        }
        deletePlaylists(source)
        putPlaylists(rows)
    }
    @Query("SELECT * FROM provider_playlist_preferences WHERE source = :source AND playlistId = :id")
    abstract suspend fun preference(source: String, id: String): ProviderPlaylistPreferenceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putPreference(preference: ProviderPlaylistPreferenceEntity)
    @Query("SELECT * FROM app_cache_entries WHERE key = 'provider_playlists' OR substr(key, 1, 25) = 'provider_playlist_tracks_' OR substr(key, 1, 22) = 'union_playlist_tracks_'")
    abstract suspend fun legacyEntries(): List<AppCacheEntryEntity>
    @Query("DELETE FROM app_cache_entries WHERE `key` = :key")
    abstract suspend fun deleteLegacy(key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLegacy(row: AppCacheEntryEntity)
    @Transaction
    open suspend fun clearProviders() {
        listOf(SourceType.JELLYFIN, SourceType.PLEX, SourceType.SPOTIFY).forEach { replacePlaylists(it, emptyList()) }
    }
}

fun providerOwnerKey(source: SourceType, id: String): String = "p:${source.name}:$id"
