package com.anyplayer.android.core.storage.repository

import com.anyplayer.android.core.model.ColumnPreferences
import com.anyplayer.android.core.model.CustomPlaylist
import com.anyplayer.android.core.model.PlaylistTrack
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.dao.ColumnPreferenceDao
import com.anyplayer.android.core.storage.dao.CustomPlaylistDao
import com.anyplayer.android.core.storage.dao.PlaylistTrackDao
import com.anyplayer.android.core.storage.dao.ProviderPlaylistPreferenceDao
import com.anyplayer.android.core.storage.dao.UnionPlaylistSourceDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import com.anyplayer.android.core.storage.entity.ProviderPlaylistPreferenceEntity
import com.anyplayer.android.core.storage.mapper.toEntity
import com.anyplayer.android.core.storage.mapper.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistStorageRepository @Inject constructor(
    private val customPlaylistDao: CustomPlaylistDao,
    private val playlistTrackDao: PlaylistTrackDao,
    private val unionPlaylistSourceDao: UnionPlaylistSourceDao,
    private val columnPreferenceDao: ColumnPreferenceDao,
    private val providerPlaylistPreferenceDao: ProviderPlaylistPreferenceDao,
    private val appCacheEntryDao: AppCacheEntryDao,
    private val json: Json
) {
    fun observeCustomPlaylists(): Flow<List<CustomPlaylist>> =
        customPlaylistDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrack>> =
        playlistTrackDao.observeByPlaylist(playlistId).map { list -> list.map { it.toModel() } }

    fun observeUnionSources(unionPlaylistId: String): Flow<List<UnionPlaylistSource>> =
        unionPlaylistSourceDao.observeByUnionPlaylist(unionPlaylistId).map { list -> list.map { it.toModel() } }

    fun observeColumnPreferences(): Flow<List<ColumnPreferences>> =
        columnPreferenceDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun upsertCustomPlaylists(playlists: List<CustomPlaylist>) {
        customPlaylistDao.upsert(playlists.map { it.toEntity() })
    }

    suspend fun getCustomPlaylists(): List<CustomPlaylist> =
        customPlaylistDao.getAll().map { it.toModel() }

    suspend fun getCustomPlaylistById(id: String): CustomPlaylist? =
        customPlaylistDao.getById(id)?.toModel()

    suspend fun deleteCustomPlaylistById(id: String) {
        customPlaylistDao.deleteById(id)
    }

    suspend fun replacePlaylistTracks(playlistId: String, tracks: List<PlaylistTrack>) {
        playlistTrackDao.replacePlaylistTracks(playlistId, tracks.map { it.toEntity() })
    }

    suspend fun getPlaylistTracks(playlistId: String): List<PlaylistTrack> =
        playlistTrackDao.getByPlaylist(playlistId).map { it.toModel() }

    suspend fun replaceUnionSources(unionPlaylistId: String, sources: List<UnionPlaylistSource>) {
        unionPlaylistSourceDao.replaceSources(unionPlaylistId, sources.map { it.toEntity() })
    }

    suspend fun getUnionSources(unionPlaylistId: String): List<UnionPlaylistSource> =
        unionPlaylistSourceDao.getByUnionPlaylist(unionPlaylistId).map { it.toModel() }

    suspend fun getCachedUnionPlaylistTracks(unionPlaylistId: String): List<Track>? = withContext(Dispatchers.IO) {
        val key = unionTrackCacheKey(unionPlaylistId)
        val entry = runCatching { appCacheEntryDao.get(key) }
            .getOrElse {
                runCatching { appCacheEntryDao.delete(key) }
                return@withContext null
            }

        if (entry == null) {
            return@withContext null
        }

        runCatching {
            json.decodeFromString<UnionPlaylistTrackCacheEntry>(entry.valueJson).tracks
        }.getOrElse {
            runCatching { appCacheEntryDao.delete(key) }
            null
        }
    }

    suspend fun saveCachedUnionPlaylistTracks(unionPlaylistId: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
        val payload = UnionPlaylistTrackCacheEntry(
            updatedAt = java.time.Instant.now().toString(),
            unionPlaylistId = unionPlaylistId,
            tracks = tracks
        )
        val valueJson = json.encodeToString(payload)
        if (valueJson.length > MAX_CACHE_JSON_CHARS) {
            runCatching { appCacheEntryDao.delete(unionTrackCacheKey(unionPlaylistId)) }
            return@withContext
        }

        appCacheEntryDao.upsert(
            AppCacheEntryEntity(
                key = unionTrackCacheKey(unionPlaylistId),
                valueJson = valueJson,
                version = UNION_PLAYLIST_TRACK_CACHE_VERSION,
                updatedAt = payload.updatedAt
            )
        )
    }

    suspend fun clearCachedUnionPlaylistTracks(unionPlaylistId: String) = withContext(Dispatchers.IO) {
        appCacheEntryDao.delete(unionTrackCacheKey(unionPlaylistId))
    }

    suspend fun upsertColumnPreferences(preferences: List<ColumnPreferences>) {
        columnPreferenceDao.upsert(preferences.map { it.toEntity() })
    }

    // ── Provider playlist preferences ────────────────────────────────────────────────────────────

    /**
     * Returns the isDistinct flag for the given provider playlist, defaulting to false
     * if no preference has been stored yet.
     */
    suspend fun getProviderPlaylistIsDistinct(source: String, playlistId: String): Boolean =
        providerPlaylistPreferenceDao.getByKey(source, playlistId)?.isDistinct ?: false

    /** Persists the isDistinct preference for a provider playlist. */
    suspend fun setProviderPlaylistIsDistinct(source: String, playlistId: String, isDistinct: Boolean) {
        providerPlaylistPreferenceDao.upsert(
            ProviderPlaylistPreferenceEntity(
                source = source,
                playlistId = playlistId,
                isDistinct = isDistinct
            )
        )
    }

    /** Returns all stored provider playlist preferences for a given source. */
    suspend fun getProviderPlaylistPreferences(source: String): List<ProviderPlaylistPreferenceEntity> =
        providerPlaylistPreferenceDao.getBySource(source)

    suspend fun getSnapshot(
        customPlaylists: List<CustomPlaylist>
    ): Triple<List<PlaylistTrack>, List<UnionPlaylistSource>, List<ColumnPreferences>> {
        val playlistIds = customPlaylists.map { it.id }
        val tracks = playlistTrackDao.getByPlaylistIds(playlistIds).map { it.toModel() }
        val unionSources = unionPlaylistSourceDao.getByUnionPlaylistIds(playlistIds).map { it.toModel() }
        val columns = columnPreferenceDao.getAll().map { it.toModel() }
        return Triple(tracks, unionSources, columns)
    }

    private fun unionTrackCacheKey(unionPlaylistId: String): String =
        "union_playlist_tracks_$unionPlaylistId"

    @Serializable
    private data class UnionPlaylistTrackCacheEntry(
        val updatedAt: String,
        val unionPlaylistId: String,
        val tracks: List<Track>
    )

    private companion object {
        const val UNION_PLAYLIST_TRACK_CACHE_VERSION = 1
        const val MAX_CACHE_JSON_CHARS = 500_000
    }
}
