package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PROVIDER_PLAYLIST_CACHE_KEY = "provider_playlists"
private const val PROVIDER_PLAYLIST_CACHE_VERSION = 1
private const val PROVIDER_PLAYLIST_TRACK_CACHE_VERSION = 2
private const val PROVIDER_PLAYLIST_TRACK_CACHE_PREFIX = "provider_playlist_tracks_"
private const val MAX_CACHE_JSON_CHARS = 500_000

@Serializable
private data class ProviderPlaylistCacheEntry(
    val updatedAt: String,
    val playlists: List<Playlist>
)

@Serializable
private data class ProviderPlaylistTrackCacheEntry(
    val updatedAt: String,
    val source: SourceType,
    val playlistId: String,
    val tracks: List<Track>
)

/** Reads and writes the on-disk cache of provider playlists and playlist tracks. */
@Singleton
class ProviderPlaylistCache @Inject constructor(
    private val appCacheEntryDao: AppCacheEntryDao,
    private val json: Json
) {
    suspend fun getCachedProviderPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val entry = safeGetCacheEntry(PROVIDER_PLAYLIST_CACHE_KEY) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString(ProviderPlaylistCacheEntry.serializer(), entry.valueJson).playlists
        }.getOrElse {
            runCatching { appCacheEntryDao.delete(PROVIDER_PLAYLIST_CACHE_KEY) }
            emptyList()
        }
    }

    suspend fun clearProviderPlaylistCacheData() = withContext(Dispatchers.IO) {
        appCacheEntryDao.delete(PROVIDER_PLAYLIST_CACHE_KEY)
        appCacheEntryDao.deleteByPrefix(PROVIDER_PLAYLIST_TRACK_CACHE_PREFIX)
    }

    suspend fun saveProviderPlaylistCache(playlists: List<Playlist>) = withContext(Dispatchers.IO) {
        val payload = ProviderPlaylistCacheEntry(
            updatedAt = Instant.now().toString(),
            playlists = playlists
        )
        val valueJson = json.encodeToString(payload)
        if (valueJson.length > MAX_CACHE_JSON_CHARS) {
            runCatching { appCacheEntryDao.delete(PROVIDER_PLAYLIST_CACHE_KEY) }
            return@withContext
        }
        appCacheEntryDao.upsert(
            AppCacheEntryEntity(
                key = PROVIDER_PLAYLIST_CACHE_KEY,
                valueJson = valueJson,
                version = PROVIDER_PLAYLIST_CACHE_VERSION,
                updatedAt = payload.updatedAt
            )
        )
    }

    suspend fun savePlaylistTrackCache(sourceType: SourceType, playlistId: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
        val payload = ProviderPlaylistTrackCacheEntry(
            updatedAt = Instant.now().toString(),
            source = sourceType,
            playlistId = playlistId,
            tracks = tracks
        )
        val valueJson = json.encodeToString(payload)
        if (valueJson.length > MAX_CACHE_JSON_CHARS) {
            runCatching { appCacheEntryDao.delete(playlistTrackCacheKey(sourceType, playlistId)) }
            return@withContext
        }
        appCacheEntryDao.upsert(
            AppCacheEntryEntity(
                key = playlistTrackCacheKey(sourceType, playlistId),
                valueJson = valueJson,
                version = PROVIDER_PLAYLIST_TRACK_CACHE_VERSION,
                updatedAt = payload.updatedAt
            )
        )
    }

    suspend fun getCachedPlaylistTracks(sourceType: SourceType, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val key = playlistTrackCacheKey(sourceType, playlistId)
        val entry = safeGetCacheEntry(key) ?: return@withContext emptyList()
        if (entry.version != PROVIDER_PLAYLIST_TRACK_CACHE_VERSION) {
            // Stale cache format (e.g. pre-dates the multi-artist join fix) — drop it
            // so callers fall through to a live refetch instead of serving old data forever.
            runCatching { appCacheEntryDao.delete(key) }
            return@withContext emptyList()
        }
        runCatching {
            json.decodeFromString<ProviderPlaylistTrackCacheEntry>(entry.valueJson).tracks
        }.getOrElse {
            runCatching { appCacheEntryDao.delete(key) }
            emptyList()
        }
    }

    private suspend fun safeGetCacheEntry(key: String): AppCacheEntryEntity? = withContext(Dispatchers.IO) {
        runCatching {
            appCacheEntryDao.get(key)
        }.getOrElse {
            runCatching { appCacheEntryDao.delete(key) }
            null
        }
    }

    private fun playlistTrackCacheKey(sourceType: SourceType, playlistId: String): String =
        "provider_playlist_tracks_${sourceType.name.lowercase()}_$playlistId"
}
