package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.ProviderSearchResult
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.core.storage.dao.AppCacheEntryDao
import com.anyplayer.android.core.storage.entity.AppCacheEntryEntity
import com.anyplayer.android.feature.auth.PROVIDER_DEFAULT_PAGE_SIZE
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.startup.StartupCatalogGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProviderCatalogRepository"

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

        if (!rustBridge.isAvailable()) {
            return@withContext loadSpotifyPlaylistsOnly(offset, limit)
        }

        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        coroutineScope {
            val jellyDeferred = async {
                if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylists(
                        source = SourceType.JELLYFIN,
                        session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, jelly.playlistPageSize),
                        offset = offset,
                        limit = limit
                    ) ?: emptyList()
                } else {
                    emptyList()
                }
            }

            val plexDeferred = async {
                if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylists(
                        source = SourceType.PLEX,
                        session = buildPlexSession(plex.serverUrl, plex.token, plex.playlistPageSize),
                        offset = offset,
                        limit = limit
                    ) ?: emptyList()
                } else {
                    emptyList()
                }
            }

            val spotifyDeferred = async {
                if (!spotify?.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylists(
                        source = SourceType.SPOTIFY,
                        session = buildSpotifySession(spotify.token, spotify.refreshToken, limit.coerceAtMost(50)),
                        offset = offset,
                        limit = limit
                    ) ?: emptyList()
                } else {
                    emptyList()
                }
            }

            jellyDeferred.await() + plexDeferred.await() + spotifyDeferred.await()
        }
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

    suspend fun refreshAllProviderPlaylistDataWithCache(
        offset: Int = 0,
        limit: Int = 100,
        onProgressUpdate: (String) -> Unit = {}
    ): List<Playlist> {
        onProgressUpdate("Fetching playlists from providers...")
        val remotePlaylists = getAllProviderPlaylists(offset = offset, limit = limit)
        if (remotePlaylists.isEmpty()) {
            onProgressUpdate("No playlists found, loading from cache...")
            return getCachedProviderPlaylists()
        }

        val totalPlaylists = remotePlaylists.size
        val semaphore = Semaphore(4)
        val progressMutex = Mutex()
        var completedCount = 0
        val enrichedPlaylists = coroutineScope {
            remotePlaylists.map { playlist ->
                async {
                    semaphore.withPermit {
                        progressMutex.withLock {
                            completedCount++
                            onProgressUpdate("Fetching tracks for \"${playlist.name}\" ($completedCount/$totalPlaylists) from ${playlist.source.name}...")
                        }
                        val tracks = getPlaylistTracksWithCache(
                            sourceType = playlist.source,
                            playlistId = playlist.id,
                            offset = 0,
                            pageSize = limit,
                            maxTracks = null,
                            forceRefresh = true
                        )
                        playlist.copy(
                            trackCount = tracks.takeIf { it.isNotEmpty() }?.size ?: playlist.trackCount,
                            tracks = tracks
                        )
                    }
                }
            }.map { it.await() }
        }

        onProgressUpdate("Saving playlist data...")
        saveProviderPlaylistCache(enrichedPlaylists)
        onProgressUpdate("Playlist refresh complete!")
        return enrichedPlaylists
    }

    suspend fun getPlaylistTracksWithCache(
        sourceType: SourceType,
        playlistId: String,
        offset: Int = 0,
        pageSize: Int? = null,
        maxTracks: Int? = null,
        forceRefresh: Boolean = false
    ): List<Track> {
        val resolvedPlaylistId = normalizePlaylistId(sourceType, playlistId)
        if (!forceRefresh) {
            val cached = getCachedPlaylistTracks(sourceType, resolvedPlaylistId)
            if (cached.isNotEmpty()) {
                return if (maxTracks != null) cached.take(maxTracks) else cached
            }
        }

        val remoteTracks = getPlaylistTracks(
            sourceType = sourceType,
            playlistId = resolvedPlaylistId,
            offset = offset,
            pageSize = pageSize,
            maxTracks = maxTracks
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
        pageSize: Int? = null,
        maxTracks: Int? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        // All provider track fetching uses the Rust provider bridge.
        // Spotify no longer has a Kotlin fallback and is fetched via the Rust bridge.
        val resolvedPlaylistId = normalizePlaylistId(sourceType, playlistId)
        
        // Get the configured page size for this provider, or use default.
        // Read the stored connection once and reuse it for credentials below.
        val storedConnection = secureConnectionStore.read(sourceType)
        val configuredPageSize = storedConnection?.playlistPageSize ?: PROVIDER_DEFAULT_PAGE_SIZE
        
        val effectivePageSize = (pageSize ?: configuredPageSize).coerceAtLeast(1)

        suspend fun loadAllPages(
            pageLimit: Int = effectivePageSize,
            fetchPage: suspend (offset: Int, limit: Int) -> List<Track>
        ): List<Track> {
            val allTracks = mutableListOf<Track>()
            var currentOffset = offset.coerceAtLeast(0)
            while (true) {
                val page = fetchPage(currentOffset, pageLimit)
                if (page.isEmpty()) break
                allTracks += page
                if (maxTracks != null && allTracks.size >= maxTracks) break
                if (page.size < pageLimit) break
                currentOffset += page.size
            }
            return if (maxTracks != null && allTracks.size > maxTracks) {
                allTracks.take(maxTracks)
            } else {
                allTracks
            }
        }

        when (sourceType) {
            SourceType.JELLYFIN -> {
                val jelly = storedConnection
                if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
                    try {
                        loadAllPages(pageLimit = effectivePageSize) { pageOffset, pageLimit ->
                            val requestedPageSize = (pageOffset + pageLimit)
                            rustBridge.providerGetPlaylistTracks(
                                source = SourceType.JELLYFIN,
                                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, requestedPageSize),
                                playlistId = resolvedPlaylistId,
                                offset = pageOffset,
                                limit = pageLimit
                            ) ?: emptyList()
                        }
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to fetch Jellyfin playlist tracks", e)
                    }
                } else {
                    emptyList()
                }
            }

            SourceType.PLEX -> {
                val plex = storedConnection
                if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
                    try {
                        loadAllPages(pageLimit = effectivePageSize) { pageOffset, pageLimit ->
                            val requestedPageSize = (pageOffset + pageLimit)
                            rustBridge.providerGetPlaylistTracks(
                                source = SourceType.PLEX,
                                session = buildPlexSession(plex.serverUrl, plex.token, requestedPageSize),
                                playlistId = resolvedPlaylistId,
                                offset = pageOffset,
                                limit = pageLimit
                            ) ?: emptyList()
                        }
                        } catch (e: Exception) {
                            CompatLog.e(TAG, "Failed to fetch Plex playlist tracks: ${e.message}", e)
                            throw IllegalStateException("Failed to fetch Plex playlist tracks", e)
                        }
                } else {
                    emptyList()
                }
            }

            SourceType.SPOTIFY -> {
                val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
                if (!spotify?.token.isNullOrBlank()) {
                    try {
                        // If the Rust provider bridge is available, prefer it (handled earlier by caller).
                        // However tests and some environments may not have Rust available; in that case
                        // fall back to the Kotlin SpotifyClient and paginate using the 'total' field so
                        // mapNotNull filtering doesn't prematurely stop pagination.
                        if (rustBridge.isAvailable()) {
                            val spotifyPageSize = effectivePageSize.coerceAtMost(100)
                            loadAllPages(pageLimit = spotifyPageSize) { pageOffset, pageLimit ->
                                val requestedPageSize = (pageOffset + pageLimit)
                                rustBridge.providerGetPlaylistTracks(
                                    source = SourceType.SPOTIFY,
                                    session = buildSpotifySession(spotify.token, spotify.refreshToken, requestedPageSize),
                                    playlistId = resolvedPlaylistId,
                                    offset = pageOffset,
                                    limit = pageLimit
                                ) ?: emptyList()
                            }
                        } else {
                            // Kotlin fallback using SpotifyClient.getPlaylistTracksPage
                            val pageSize = effectivePageSize.coerceAtMost(100)
                            val allTracks = mutableListOf<Track>()
                            var currentOffset = offset.coerceAtLeast(0)
                            var total = Int.MAX_VALUE
                            while (true) {
                                val page = spotifyClient.getPlaylistTracksPage(
                                    accessToken = spotify.token,
                                    playlistId = resolvedPlaylistId,
                                    offset = currentOffset,
                                    limit = pageSize
                                )
                                if (page.tracks.isEmpty()) break
                                allTracks += page.tracks
                                total = page.total.coerceAtLeast(allTracks.size)
                                if (maxTracks != null && allTracks.size >= maxTracks) break
                                if (allTracks.size >= total) break
                                currentOffset += page.tracks.size
                            }
                            if (maxTracks != null && allTracks.size > maxTracks) allTracks.take(maxTracks) else allTracks
                        }
                        } catch (e: Exception) {
                            CompatLog.e(TAG, "Failed to fetch Spotify playlist tracks: ${e.message}", e)
                            throw IllegalStateException("Failed to fetch Spotify playlist tracks", e)
                        }
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    suspend fun getProviderPlaylist(sourceType: SourceType, playlistId: String): Playlist? = withContext(Dispatchers.IO) {
        val resolvedPlaylistId = normalizePlaylistId(sourceType, playlistId)
        val cachedPlaylist = getCachedProviderPlaylists().firstOrNull { playlist ->
            playlist.source == sourceType && normalizePlaylistId(playlist.source, playlist.id) == resolvedPlaylistId
        }
        if (cachedPlaylist != null) {
            return@withContext cachedPlaylist
        }

        when (sourceType) {
            SourceType.JELLYFIN -> {
                val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
                if (jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylist(
                        source = SourceType.JELLYFIN,
                        session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, jelly.playlistPageSize),
                        playlistId = resolvedPlaylistId
                    )
                } else {
                    null
                }
            }

            SourceType.PLEX -> {
                val plex = secureConnectionStore.read(SourceType.PLEX)
                if (plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylist(
                        source = SourceType.PLEX,
                        session = buildPlexSession(plex.serverUrl, plex.token, plex.playlistPageSize),
                        playlistId = resolvedPlaylistId
                    )
                } else {
                    null
                }
            }

            SourceType.SPOTIFY -> {
                val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
                if (!spotify?.token.isNullOrBlank()) {
                    rustBridge.providerGetPlaylist(
                        source = SourceType.SPOTIFY,
                        session = buildSpotifySession(spotify.token, spotify.refreshToken, spotify.playlistPageSize),
                        playlistId = resolvedPlaylistId
                    )
                } else {
                    null
                }
            }

            SourceType.CUSTOM, SourceType.ALL -> null
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

        // All provider search uses the Rust provider bridge; Spotify search
        // goes through the Rust bridge as well (no Kotlin fallback).
        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val includeJelly = source == SourceType.ALL || source == SourceType.JELLYFIN
        val includePlex = source == SourceType.ALL || source == SourceType.PLEX
        val includeSpotify = source == SourceType.ALL || source == SourceType.SPOTIFY

        val jellyTracks = if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
            rustBridge.providerSearchTracks(
                source = SourceType.JELLYFIN,
                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, jelly.playlistPageSize),
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
                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, jelly.playlistPageSize),
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
                session = buildPlexSession(plex.serverUrl, plex.token, plex.playlistPageSize),
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
                session = buildPlexSession(plex.serverUrl, plex.token, plex.playlistPageSize),
                query = normalizedQuery,
                offset = offset,
                limit = limit
            ) ?: emptyList()
        } else {
            emptyList()
        }

        val spotifyTracks = if (includeSpotify && !spotify?.token.isNullOrBlank()) {
            if (rustBridge.isAvailable()) {
                rustBridge.providerSearchTracks(
                    source = SourceType.SPOTIFY,
                    session = buildSpotifySession(spotify.token, spotify.refreshToken, limit),
                    query = normalizedQuery,
                    offset = offset,
                    limit = limit
                ) ?: emptyList()
                } else {
                // Kotlin fallback
                spotifyClient.searchTracks(spotify.token, normalizedQuery, offset, limit.coerceAtMost(50))
            }
        } else {
            emptyList()
        }

        val spotifyPlaylists = if (includeSpotify && !spotify?.token.isNullOrBlank()) {
            if (rustBridge.isAvailable()) {
                rustBridge.providerSearchPlaylists(
                    source = SourceType.SPOTIFY,
                    session = buildSpotifySession(spotify.token, spotify.refreshToken, limit),
                    query = normalizedQuery,
                    offset = offset,
                    limit = limit
                ) ?: emptyList()
            } else {
                // Kotlin fallback
                spotifyClient.searchPlaylists(spotify.token, normalizedQuery, offset, limit.coerceAtMost(50))
            }
        } else {
            emptyList()
        }

        ProviderSearchResult(
            tracks = jellyTracks + plexTracks + spotifyTracks,
            playlists = jellyPlaylists + plexPlaylists + spotifyPlaylists
        )
    }

    private fun buildJellyfinSession(url: String, apiKey: String, userId: String?, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> {
        val session = mutableMapOf(
            "url" to url,
            "api_key" to apiKey,
            "page_size" to pageSize.toString()
        )
        if (!userId.isNullOrBlank()) {
            session["user_id"] = userId
        }
        return session
    }

    private fun buildPlexSession(url: String, token: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> {
        return mapOf(
            "url" to url,
            "token" to token,
            "page_size" to pageSize.toString()
        )
    }

    private fun buildSpotifySession(accessToken: String, refreshToken: String?, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> {
        val session = mutableMapOf(
            "access_token" to accessToken,
            "page_size" to pageSize.toString()
        )
        if (!refreshToken.isNullOrBlank()) {
            session["refresh_token"] = refreshToken
        }
        return session
    }
    private suspend fun loadSpotifyPlaylistsOnly(offset: Int = 0, limit: Int = 100): List<Playlist> = withContext(Dispatchers.IO) {
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)
        if (spotify?.token.isNullOrBlank()) return@withContext emptyList()
        try {
            // Spotify API limits playlists fetch to max 50 per request
            val requestLimit = limit.coerceIn(1, 50)
            spotifyClient.getPlaylists(spotify.token, offset.coerceAtLeast(0), requestLimit)
            } catch (e: Exception) {
                CompatLog.e(TAG, "Failed to fetch Spotify playlists via Kotlin client: ${e.message}", e)
                emptyList()
            }
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
