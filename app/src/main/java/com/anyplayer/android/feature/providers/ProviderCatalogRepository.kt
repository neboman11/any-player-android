package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.JellyfinClient
import com.anyplayer.android.core.network.PlexClient
import com.anyplayer.android.core.network.ProviderSearchResult
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.startup.StartupCatalogGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderCatalogRepository @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val jellyfinClient: JellyfinClient,
    private val plexClient: PlexClient,
    private val spotifyClient: SpotifyClient,
    private val appCacheEntryDao: AppCacheEntryDao,
    private val json: Json
) : StartupCatalogGateway {
    override suspend fun getCachedProviderPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val entry = safeGetCacheEntry(PROVIDER_PLAYLIST_CACHE_KEY) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString(ProviderPlaylistCacheEntry.serializer(), entry.valueJson).playlists
        }.getOrElse {
            runCatching { appCacheEntryDao.delete(PROVIDER_PLAYLIST_CACHE_KEY) }
            emptyList()
        }
    }

    override suspend fun getAllProviderPlaylistsWithCache(offset: Int, limit: Int): List<Playlist> {
        val remote = getAllProviderPlaylists(offset = offset, limit = limit)
        if (remote.isNotEmpty()) {
            saveProviderPlaylistCache(remote)
            return remote
        }
        return getCachedProviderPlaylists()
    }

    suspend fun getAllProviderPlaylists(offset: Int = 0, limit: Int = 100): List<Playlist> = withContext(Dispatchers.IO) {
        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val jellyPlaylists = if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            val userId = jelly.refreshToken
            jellyfinClient.getPlaylists(jelly.serverUrl, jelly.token, userId, offset = offset, limit = limit)
        } else {
            emptyList()
        }

        val plexPlaylists = if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            plexClient.getPlaylists(plex.serverUrl, plex.token, offset = offset, limit = limit)
        } else {
            emptyList()
        }

        val spotifyPlaylists = if (!spotify?.token.isNullOrBlank()) {
            spotifyClient.getPlaylists(spotify.token, offset = offset, limit = limit.coerceAtMost(50))
        } else {
            emptyList()
        }

        jellyPlaylists + plexPlaylists + spotifyPlaylists
    }

    private suspend fun saveProviderPlaylistCache(playlists: List<Playlist>) = withContext(Dispatchers.IO) {
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

    suspend fun refreshAllProviderPlaylistDataWithCache(offset: Int = 0, limit: Int = 100): List<Playlist> {
        val remotePlaylists = getAllProviderPlaylists(offset = offset, limit = limit)
        if (remotePlaylists.isEmpty()) {
            return getCachedProviderPlaylists()
        }

        val enrichedPlaylists = remotePlaylists.map { playlist ->
            val tracks = getPlaylistTracksWithCache(
                sourceType = playlist.source,
                playlistId = playlist.id,
                offset = 0,
                limit = 300,
                forceRefresh = true
            )
            playlist.copy(
                trackCount = tracks.takeIf { it.isNotEmpty() }?.size ?: playlist.trackCount,
                tracks = tracks
            )
        }

        saveProviderPlaylistCache(enrichedPlaylists)
        return enrichedPlaylists
    }

    suspend fun getPlaylistTracksWithCache(
        sourceType: SourceType,
        playlistId: String,
        offset: Int = 0,
        limit: Int = 300,
        forceRefresh: Boolean = false
    ): List<Track> {
        val resolvedPlaylistId = normalizePlaylistId(sourceType, playlistId)
        if (!forceRefresh) {
            val cached = getCachedPlaylistTracks(sourceType, resolvedPlaylistId)
            if (cached.isNotEmpty()) {
                return cached
            }
        }

        val remoteTracks = getPlaylistTracks(
            sourceType = sourceType,
            playlistId = resolvedPlaylistId,
            offset = offset,
            limit = limit
        )
        if (remoteTracks.isNotEmpty()) {
            savePlaylistTrackCache(sourceType, resolvedPlaylistId, remoteTracks)
            return remoteTracks
        }

        return getCachedPlaylistTracks(sourceType, resolvedPlaylistId)
    }

    suspend fun getPlaylistTracks(
        sourceType: SourceType,
        playlistId: String,
        offset: Int = 0,
        limit: Int = 300
    ): List<Track> = withContext(Dispatchers.IO) {
        val resolvedPlaylistId = normalizePlaylistId(sourceType, playlistId)
        val pageSize = limit.coerceAtLeast(1)

        suspend fun loadAllPages(
            effectivePageSize: Int = pageSize,
            fetchPage: suspend (offset: Int, limit: Int) -> List<Track>
        ): List<Track> {
            val allTracks = mutableListOf<Track>()
            var currentOffset = offset.coerceAtLeast(0)
            while (true) {
                val page = fetchPage(currentOffset, effectivePageSize)
                if (page.isEmpty()) break
                allTracks += page
                if (page.size < effectivePageSize) break
                currentOffset += page.size
            }
            return allTracks
        }

        when (sourceType) {
            SourceType.JELLYFIN -> {
                val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
                if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
                    loadAllPages { pageOffset, pageLimit ->
                        jellyfinClient.getPlaylistTracks(
                            jelly.serverUrl,
                            jelly.token,
                            resolvedPlaylistId,
                            jelly.refreshToken,
                            offset = pageOffset,
                            limit = pageLimit
                        )
                    }
                } else {
                    emptyList()
                }
            }

            SourceType.PLEX -> {
                val plex = secureConnectionStore.read(SourceType.PLEX)
                if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
                    loadAllPages { pageOffset, pageLimit ->
                        plexClient.getPlaylistTracks(
                            plex.serverUrl,
                            plex.token,
                            resolvedPlaylistId,
                            offset = pageOffset,
                            limit = pageLimit
                        )
                    }
                } else {
                    emptyList()
                }
            }

            SourceType.SPOTIFY -> {
                val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
                if (!spotify?.token.isNullOrBlank()) {
                    val spotifyPageSize = pageSize.coerceAtMost(100)
                    loadAllPages(effectivePageSize = spotifyPageSize) { pageOffset, pageLimit ->
                        spotifyClient.getPlaylistTracks(
                            accessToken = spotify.token,
                            playlistId = resolvedPlaylistId,
                            offset = pageOffset,
                            limit = pageLimit
                        )
                    }
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private suspend fun savePlaylistTrackCache(sourceType: SourceType, playlistId: String, tracks: List<Track>) = withContext(Dispatchers.IO) {
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

    private suspend fun getCachedPlaylistTracks(sourceType: SourceType, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val key = playlistTrackCacheKey(sourceType, playlistId)
        val entry = safeGetCacheEntry(key) ?: return@withContext emptyList()
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

    private fun normalizePlaylistId(sourceType: SourceType, playlistId: String): String {
        if (sourceType != SourceType.SPOTIFY) return playlistId
        val trimmed = playlistId.trim()
        if (trimmed.isBlank()) return trimmed

        val uriPrefix = "spotify:playlist:"
        if (trimmed.startsWith(uriPrefix, ignoreCase = true)) {
            return trimmed.substringAfterLast(':')
        }

        val marker = "/playlist/"
        val markerIndex = trimmed.indexOf(marker)
        if (markerIndex >= 0) {
            return trimmed
                .substring(markerIndex + marker.length)
                .substringBefore('?')
                .substringBefore('/')
        }

        return trimmed
    }

    suspend fun search(
        query: String,
        source: SourceType,
        offset: Int = 0,
        limit: Int = 100
    ): ProviderSearchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return@withContext ProviderSearchResult()
        }

        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val includeJelly = source == SourceType.ALL || source == SourceType.JELLYFIN
        val includePlex = source == SourceType.ALL || source == SourceType.PLEX
        val includeSpotify = source == SourceType.ALL || source == SourceType.SPOTIFY

        val jellyTracks = if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            jellyfinClient.searchTracks(
                jelly.serverUrl,
                jelly.token,
                jelly.refreshToken,
                normalizedQuery,
                offset = offset,
                limit = limit
            )
        } else {
            emptyList()
        }

        val jellyPlaylists = if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            jellyfinClient.searchPlaylists(
                jelly.serverUrl,
                jelly.token,
                jelly.refreshToken,
                normalizedQuery,
                offset = offset,
                limit = limit
            )
        } else {
            emptyList()
        }

        val plexTracks = if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            plexClient.searchTracks(plex.serverUrl, plex.token, normalizedQuery, offset = offset, limit = limit)
        } else {
            emptyList()
        }

        val plexPlaylists = if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            plexClient.searchPlaylists(plex.serverUrl, plex.token, normalizedQuery, offset = offset, limit = limit)
        } else {
            emptyList()
        }

        val spotifyTracks = if (includeSpotify && !spotify?.token.isNullOrBlank()) {
            spotifyClient.searchTracks(spotify.token, normalizedQuery, offset = offset, limit = limit.coerceAtMost(50))
        } else {
            emptyList()
        }

        val spotifyPlaylists = if (includeSpotify && !spotify?.token.isNullOrBlank()) {
            spotifyClient.searchPlaylists(spotify.token, normalizedQuery, offset = offset, limit = limit.coerceAtMost(50))
        } else {
            emptyList()
        }

        ProviderSearchResult(
            tracks = jellyTracks + plexTracks + spotifyTracks,
            playlists = jellyPlaylists + plexPlaylists + spotifyPlaylists
        )
    }
}

private const val PROVIDER_PLAYLIST_CACHE_KEY = "provider_playlists"
private const val PROVIDER_PLAYLIST_CACHE_VERSION = 1
private const val PROVIDER_PLAYLIST_TRACK_CACHE_VERSION = 1
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
