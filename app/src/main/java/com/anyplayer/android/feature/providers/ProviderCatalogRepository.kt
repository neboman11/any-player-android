package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.ProviderSearchResult
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.rust.RustBridge
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
    private val spotifyClient: SpotifyClient,
    private val rustBridge: RustBridge,
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
        val cached = getCachedProviderPlaylists()
        if (cached.isNotEmpty()) {
            return cached
        }

        val remote = getAllProviderPlaylists(offset = offset, limit = limit)
        if (remote.isNotEmpty()) {
            saveProviderPlaylistCache(remote)
        }
        return remote
    }

    suspend fun clearProviderPlaylistCacheData() = withContext(Dispatchers.IO) {
        appCacheEntryDao.delete(PROVIDER_PLAYLIST_CACHE_KEY)
        appCacheEntryDao.deleteByPrefix(PROVIDER_PLAYLIST_TRACK_CACHE_PREFIX)
    }

    suspend fun getAllProviderPlaylists(offset: Int = 0, limit: Int = 100): List<Playlist> = withContext(Dispatchers.IO) {
        // Rust provider bridge is the canonical path for Jellyfin/Plex.
        // If JNI is unavailable, we intentionally degrade to Spotify-only data.
        if (!isRustProviderBridgeEnabled()) {
            return@withContext loadSpotifyPlaylistsOnly(offset, limit)
        }

        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val jellyPlaylists = if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            rustBridge.providerGetPlaylists(
                source = SourceType.JELLYFIN,
                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken),
                offset = offset,
                limit = limit
            ) ?: emptyList()
        } else {
            emptyList()
        }

        val plexPlaylists = if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            rustBridge.providerGetPlaylists(
                source = SourceType.PLEX,
                session = buildPlexSession(plex.serverUrl, plex.token),
                offset = offset,
                limit = limit
            ) ?: emptyList()
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
                limit = Int.MAX_VALUE,
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
        limit: Int = Int.MAX_VALUE,
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
        limit: Int = Int.MAX_VALUE
    ): List<Track> = withContext(Dispatchers.IO) {
        // Rust provider bridge is required for non-Spotify provider tracks.
        // Without JNI we only keep Spotify behavior available.
        if (!isRustProviderBridgeEnabled()) {
            if (sourceType == SourceType.SPOTIFY) {
                return@withContext loadSpotifyPlaylistTracksOnly(
                    playlistId = normalizePlaylistId(sourceType, playlistId),
                    offset = offset,
                    limit = limit
                )
            }
            return@withContext emptyList()
        }

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
                        rustBridge.providerGetPlaylistTracks(
                            source = SourceType.JELLYFIN,
                            session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken),
                            playlistId = resolvedPlaylistId,
                            offset = pageOffset,
                            limit = pageLimit
                        ) ?: emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            SourceType.PLEX -> {
                val plex = secureConnectionStore.read(SourceType.PLEX)
                if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
                    loadAllPages { pageOffset, pageLimit ->
                        rustBridge.providerGetPlaylistTracks(
                            source = SourceType.PLEX,
                            session = buildPlexSession(plex.serverUrl, plex.token),
                            playlistId = resolvedPlaylistId,
                            offset = pageOffset,
                            limit = pageLimit
                        ) ?: emptyList()
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

        // Rust provider bridge is required for Jellyfin/Plex search.
        // Without JNI we intentionally return Spotify-only results.
        if (!isRustProviderBridgeEnabled()) {
            return@withContext loadSpotifySearchOnly(
                source = source,
                normalizedQuery = normalizedQuery,
                offset = offset,
                limit = limit
            )
        }

        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val includeJelly = source == SourceType.ALL || source == SourceType.JELLYFIN
        val includePlex = source == SourceType.ALL || source == SourceType.PLEX
        val includeSpotify = source == SourceType.ALL || source == SourceType.SPOTIFY

        val jellyTracks = if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            rustBridge.providerSearchTracks(
                source = SourceType.JELLYFIN,
                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken),
                query = normalizedQuery,
                offset = offset,
                limit = limit
            ) ?: emptyList()
        } else {
            emptyList()
        }

        val jellyPlaylists = if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            rustBridge.providerSearchPlaylists(
                source = SourceType.JELLYFIN,
                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken),
                query = normalizedQuery,
                offset = offset,
                limit = limit
            ) ?: emptyList()
        } else {
            emptyList()
        }

        val plexTracks = if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            rustBridge.providerSearchTracks(
                source = SourceType.PLEX,
                session = buildPlexSession(plex.serverUrl, plex.token),
                query = normalizedQuery,
                offset = offset,
                limit = limit
            ) ?: emptyList()
        } else {
            emptyList()
        }

        val plexPlaylists = if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
            rustBridge.providerSearchPlaylists(
                source = SourceType.PLEX,
                session = buildPlexSession(plex.serverUrl, plex.token),
                query = normalizedQuery,
                offset = offset,
                limit = limit
            ) ?: emptyList()
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

    private fun buildJellyfinSession(url: String, apiKey: String, userId: String?): Map<String, String> {
        val session = mutableMapOf(
            "url" to url,
            "api_key" to apiKey
        )
        if (!userId.isNullOrBlank()) {
            session["user_id"] = userId
        }
        return session
    }

    private fun buildPlexSession(url: String, token: String): Map<String, String> = mapOf(
        "url" to url,
        "token" to token
    )

    private fun isRustProviderBridgeEnabled(): Boolean = rustBridge.isAvailable()

    private suspend fun loadSpotifyPlaylistsOnly(offset: Int, limit: Int): List<Playlist> {
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
        return if (!spotify?.token.isNullOrBlank()) {
            spotifyClient.getPlaylists(spotify.token, offset = offset, limit = limit.coerceAtMost(50))
        } else {
            emptyList()
        }
    }

    private suspend fun loadSpotifyPlaylistTracksOnly(
        playlistId: String,
        offset: Int,
        limit: Int
    ): List<Track> {
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
        if (spotify?.token.isNullOrBlank()) {
            return emptyList()
        }

        val pageSize = limit.coerceAtLeast(1).coerceAtMost(100)
        val allTracks = mutableListOf<Track>()
        var currentOffset = offset.coerceAtLeast(0)
        while (true) {
            val page = spotifyClient.getPlaylistTracks(
                accessToken = spotify.token,
                playlistId = playlistId,
                offset = currentOffset,
                limit = pageSize
            )
            if (page.isEmpty()) break
            allTracks += page
            if (page.size < pageSize) break
            currentOffset += page.size
        }
        return allTracks
    }

    private suspend fun loadSpotifySearchOnly(
        source: SourceType,
        normalizedQuery: String,
        offset: Int,
        limit: Int
    ): ProviderSearchResult {
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
        val includeSpotify = source == SourceType.ALL || source == SourceType.SPOTIFY
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

        return ProviderSearchResult(
            tracks = spotifyTracks,
            playlists = spotifyPlaylists
        )
    }
}

private const val PROVIDER_PLAYLIST_CACHE_KEY = "provider_playlists"
private const val PROVIDER_PLAYLIST_CACHE_VERSION = 1
private const val PROVIDER_PLAYLIST_TRACK_CACHE_VERSION = 1
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
