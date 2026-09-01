package com.anyplayer.android.feature.providers

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.normalizePlaylistId
import com.anyplayer.android.core.network.ProviderSearchResult
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.PROVIDER_DEFAULT_PAGE_SIZE
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.spotify.SpotifyCatalogClient
import com.anyplayer.android.feature.startup.StartupCatalogGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TAG = "ProviderCatalogRepository"

@Singleton
class ProviderCatalogRepository @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyCatalogClient: SpotifyCatalogClient,
    private val rustBridge: RustBridge,
    private val playlistCache: ProviderPlaylistCache
) : StartupCatalogGateway {
    override suspend fun getCachedProviderPlaylists(): List<Playlist> =
        playlistCache.getCachedProviderPlaylists()

    override suspend fun getAllProviderPlaylistsWithCache(offset: Int, limit: Int): List<Playlist> {
        val cached = getCachedProviderPlaylists()
        if (cached.isNotEmpty()) {
            return cached
        }

        val remote = getAllProviderPlaylists(offset = offset, limit = limit)
        if (remote.isNotEmpty()) {
            playlistCache.saveProviderPlaylistCache(remote)
        }
        return remote
    }

    suspend fun clearProviderPlaylistCacheData() = playlistCache.clearProviderPlaylistCacheData()

    suspend fun getCachedPlaylistTracks(sourceType: SourceType, playlistId: String): List<Track> =
        playlistCache.getCachedPlaylistTracks(sourceType, playlistId)

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
        playlistCache.saveProviderPlaylistCache(enrichedPlaylists)
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
            val cached = playlistCache.getCachedPlaylistTracks(sourceType, resolvedPlaylistId)
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
            playlistCache.savePlaylistTrackCache(sourceType, resolvedPlaylistId, remoteTracks)
            return remoteTracks
        }

        return playlistCache.getCachedPlaylistTracks(sourceType, resolvedPlaylistId)
    }

    suspend fun getPlaylistTracks(
        sourceType: SourceType,
        playlistId: String,
        offset: Int = 0,
        pageSize: Int? = null,
        maxTracks: Int? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        // Provider track fetching prefers the Rust provider bridge.
        // Spotify falls back to SpotifyCatalogClient pagination when the Rust bridge is unavailable.
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
                            rustBridge.providerGetPlaylistTracks(
                                source = SourceType.JELLYFIN,
                                session = buildJellyfinSession(jelly.serverUrl, jelly.token, jelly.refreshToken, pageLimit),
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
                            rustBridge.providerGetPlaylistTracks(
                                source = SourceType.PLEX,
                                session = buildPlexSession(plex.serverUrl, plex.token, pageLimit),
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
                        // fall back to the Kotlin SpotifyCatalogClient and paginate using the 'total' field so
                        // mapNotNull filtering doesn't prematurely stop pagination.
                        if (rustBridge.isAvailable()) {
                            val spotifyPageSize = effectivePageSize.coerceAtMost(100)
                            loadAllPages(pageLimit = spotifyPageSize) { pageOffset, pageLimit ->
                                rustBridge.providerGetPlaylistTracks(
                                    source = SourceType.SPOTIFY,
                                    session = buildSpotifySession(spotify.token, spotify.refreshToken, pageLimit),
                                    playlistId = resolvedPlaylistId,
                                    offset = pageOffset,
                                    limit = pageLimit
                                ) ?: emptyList()
                            }
                        } else {
                            // Kotlin fallback using SpotifyCatalogClient.getPlaylistTracksPage
                            val pageSize = effectivePageSize.coerceAtMost(100)
                            val allTracks = mutableListOf<Track>()
                            var currentOffset = offset.coerceAtLeast(0)
                            var total = Int.MAX_VALUE
                            while (true) {
                                val page = spotifyCatalogClient.getPlaylistTracksPage(
                                    accessToken = spotify.token,
                                    playlistId = resolvedPlaylistId,
                                    offset = currentOffset,
                                    limit = pageSize
                                )
                                allTracks += page.tracks
                                total = page.total.coerceAtLeast(0)
                                if (maxTracks != null && allTracks.size >= maxTracks) break
                                val nextOffset = currentOffset + pageSize
                                if (nextOffset >= total) break
                                currentOffset = nextOffset
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

        // Provider search prefers the Rust provider bridge where available.
        // Spotify search falls back to the Kotlin spotifyClient when Rust is unavailable.
        val jelly = secureConnectionStore.read(SourceType.JELLYFIN)
        val plex = secureConnectionStore.read(SourceType.PLEX)
        val spotify = secureConnectionStore.read(SourceType.SPOTIFY)

        val includeJelly = source == SourceType.ALL || source == SourceType.JELLYFIN
        val includePlex = source == SourceType.ALL || source == SourceType.PLEX
        val includeSpotify = source == SourceType.ALL || source == SourceType.SPOTIFY

        // Fanned out with async/coroutineScope (same pattern as getAllProviderPlaylists())
        // rather than awaited one at a time - each is a separate network round-trip to a
        // different backend, so sequential awaiting paid up to 6x the latency for no reason.
        coroutineScope {
            val jellyTracksDeferred = async {
                if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
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
            }

            val jellyPlaylistsDeferred = async {
                if (includeJelly && jelly?.serverUrl != null && !jelly.token.isNullOrBlank()) {
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
            }

            val plexTracksDeferred = async {
                if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
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
            }

            val plexPlaylistsDeferred = async {
                if (includePlex && plex?.serverUrl != null && !plex.token.isNullOrBlank()) {
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
            }

            val spotifyTracksDeferred = async {
                if (includeSpotify && !spotify?.token.isNullOrBlank()) {
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
                        spotifyCatalogClient.searchTracks(spotify.token, normalizedQuery, offset, limit.coerceAtMost(50))
                    }
                } else {
                    emptyList()
                }
            }

            val spotifyPlaylistsDeferred = async {
                if (includeSpotify && !spotify?.token.isNullOrBlank()) {
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
                        spotifyCatalogClient.searchPlaylists(spotify.token, normalizedQuery, offset, limit.coerceAtMost(50))
                    }
                } else {
                    emptyList()
                }
            }

            ProviderSearchResult(
                tracks = jellyTracksDeferred.await() + plexTracksDeferred.await() + spotifyTracksDeferred.await(),
                playlists = jellyPlaylistsDeferred.await() + plexPlaylistsDeferred.await() + spotifyPlaylistsDeferred.await()
            )
        }
    }

    private fun buildJellyfinSession(url: String, apiKey: String, userId: String?, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> =
        ProviderSessionBuilder.jellyfinSession(url, apiKey, userId, pageSize)

    private fun buildPlexSession(url: String, token: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> =
        ProviderSessionBuilder.plexSession(url, token, pageSize)

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
            spotifyCatalogClient.getPlaylists(spotify.token, offset.coerceAtLeast(0), requestLimit)
        } catch (e: Exception) {
            CompatLog.e(TAG, "Failed to fetch Spotify playlists via Kotlin client: ${e.message}", e)
            emptyList()
        }
    }
}
