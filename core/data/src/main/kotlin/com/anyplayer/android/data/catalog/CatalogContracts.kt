package com.anyplayer.android.data.catalog

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.SearchResult
import com.anyplayer.android.core.model.SearchType
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.flow.Flow

/** Per-provider failure surfaced to the UI without discarding the cache. */
data class ProviderRefreshFailure(val source: SourceType, val message: String)

interface CatalogRepository {
    /** Cached provider playlists (instant on startup), order: Jellyfin, Plex, Spotify. */
    val playlists: Flow<List<Playlist>>

    /** Re-fetches playlist lists of connected providers in parallel; cache kept on failure. */
    suspend fun refreshPlaylists(): List<ProviderRefreshFailure>

    /**
     * Playlist tracks, cache-first. Refreshes when [forceRefresh], when nothing is cached, or
     * (in the background, returning the cache) when the cache is older than 6 h.
     */
    suspend fun tracks(source: SourceType, playlistId: String, forceRefresh: Boolean = false): List<Track>

    /** Cached tracks only (no network); empty when not cached. */
    suspend fun cachedTracks(source: SourceType, playlistId: String): List<Track>

    /** Full refresh of lists and every playlist's tracks; legacy progress strings. */
    suspend fun refreshAll(onProgress: (String) -> Unit): List<ProviderRefreshFailure>

    /**
     * Blank query → empty. Only the requested [type]; [source] ALL → every connected provider.
     * Results from providers that succeed are kept; throws the first failure when every searched provider failed.
     */
    suspend fun search(query: String, source: SourceType, type: SearchType): SearchResult

    suspend fun clearCache()

    /** Provider "distinct playback" preference (legacy provider_playlist_preferences). */
    suspend fun isDistinct(source: SourceType, playlistId: String): Boolean
    suspend fun setDistinct(source: SourceType, playlistId: String, distinct: Boolean)
}
