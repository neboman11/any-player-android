package com.anyplayer.android.app

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.model.normalizePlaylistId
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playlists.DistinctPlaylistUtils
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Owns MainViewModel's provider-playlist list, selection/summary, and playback entry points. */
internal class ProviderPlaylistBrowsingStateHolder(
    private val viewModelScope: CoroutineScope,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val playlistStorageRepository: PlaylistStorageRepository,
    private val playbackQueueManager: PlaybackQueueManager,
    private val currentProviderStatuses: () -> List<ProviderConnectionProfile>
) {
    val providerPlaylists = MutableStateFlow<List<Playlist>>(emptyList())
    val selectedProviderPlaylist = MutableStateFlow<Playlist?>(null)
    val selectedProviderPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    val selectedProviderPlaylistIsDistinct = MutableStateFlow(false)
    val selectedProviderPlaylistLoading = MutableStateFlow(false)
    val selectedProviderPlaylistError = MutableStateFlow<String?>(null)
    val providerPlaylistRefreshInProgress = MutableStateFlow(false)
    val providerPlaylistRefreshStatus = MutableStateFlow<String?>(null)
    private var providerPlaylistDistinctLoadJob: Job? = null

    fun applyStartupSnapshot(playlists: List<Playlist>) {
        providerPlaylists.value = playlists
        val selectedPlaylistId = selectedProviderPlaylist.value?.id
        if (selectedPlaylistId != null && playlists.none { it.id == selectedPlaylistId }) {
            selectedProviderPlaylist.value = null
            selectedProviderPlaylistTracks.value = emptyList()
            selectedProviderPlaylistLoading.value = false
            selectedProviderPlaylistError.value = null
        }
    }

    fun playPlaylist(sourceType: SourceType, playlistId: String) {
        viewModelScope.launch {
            val queue = buildProviderQueueDistinct(sourceType, playlistId)
            playbackQueueManager.setQueue(queue, startIndex = 0, autoPlay = true)
        }
    }

    fun refreshProviderPlaylistData() {
        viewModelScope.launch {
            providerPlaylistRefreshInProgress.value = true
            providerPlaylistRefreshStatus.value = "Starting playlist refresh..."

            runCatching {
                providerCatalogRepository.refreshAllProviderPlaylistDataWithCache(
                    onProgressUpdate = { status ->
                        providerPlaylistRefreshStatus.value = status
                    }
                )
            }.onSuccess { refreshedPlaylists ->
                providerPlaylists.value = refreshedPlaylists
                providerPlaylistRefreshStatus.value = "Playlist data refreshed successfully!"

                val selected = selectedProviderPlaylist.value
                if (selected != null) {
                    val updatedSelection = refreshedPlaylists.firstOrNull {
                        it.id == selected.id && it.source == selected.source
                    }
                    if (updatedSelection != null) {
                        selectedProviderPlaylist.value = updatedSelection
                        selectedProviderPlaylistTracks.value = updatedSelection.tracks.orEmpty()
                        selectedProviderPlaylistError.value = null
                    }
                }
            }.onFailure { throwable ->
                providerPlaylistRefreshStatus.value = throwable.message?.takeIf { it.isNotBlank() }
                    ?: "Playlist data refresh failed."
            }

            providerPlaylistRefreshInProgress.value = false
        }
    }

    fun openProviderPlaylistSummary(playlist: Playlist) {
        selectedProviderPlaylist.value = playlist
        selectedProviderPlaylistIsDistinct.value = false
        selectedProviderPlaylistError.value = null

        providerPlaylistDistinctLoadJob?.cancel()
        providerPlaylistDistinctLoadJob = viewModelScope.launch {
            val persistedDistinct = playlistStorageRepository.getProviderPlaylistIsDistinct(
                playlist.source.name,
                playlist.id
            )
            if (
                selectedProviderPlaylist.value?.id == playlist.id &&
                selectedProviderPlaylist.value?.source == playlist.source
            ) {
                selectedProviderPlaylistIsDistinct.value = persistedDistinct
            }
        }

        val isSourceConnected = currentProviderStatuses().any { profile ->
            profile.source == playlist.source && profile.connected
        }
        if (!isSourceConnected) {
            selectedProviderPlaylistTracks.value = playlist.tracks.orEmpty()
            selectedProviderPlaylistLoading.value = false
            selectedProviderPlaylistError.value = "${playlist.source.name.lowercase()} is not connected. Reconnect in Settings, then retry."
            return
        }

        viewModelScope.launch {
            // Read from the Room DB cache immediately so tracks appear without a loading
            // spinner when the data is already available (e.g. from a prior session or the
            // background prefetch).
            val cached = providerCatalogRepository.getCachedPlaylistTracks(playlist.source, playlist.id)
            if (selectedProviderPlaylist.value?.id != playlist.id) return@launch

            if (cached.isNotEmpty()) {
                selectedProviderPlaylistTracks.value = cached
                selectedProviderPlaylistLoading.value = false
                return@launch
            }

            // No cache yet — show initial tracks from the playlist object (may be empty)
            // and the loading indicator while we fetch from the network.
            selectedProviderPlaylistTracks.value = playlist.tracks.orEmpty()
            selectedProviderPlaylistLoading.value = true

            val result = runCatching {
                providerCatalogRepository.getPlaylistTracksWithCache(playlist.source, playlist.id)
            }
            if (selectedProviderPlaylist.value?.id != playlist.id) return@launch
            result.onSuccess { tracks ->
                selectedProviderPlaylistTracks.value = tracks.ifEmpty { playlist.tracks.orEmpty() }
                if (selectedProviderPlaylistTracks.value.isEmpty() && playlist.trackCount > 0) {
                    selectedProviderPlaylistError.value = "Unable to load tracks for this playlist. Try reconnecting the provider and retry."
                }
            }.onFailure { throwable ->
                selectedProviderPlaylistError.value = throwable.message?.takeIf { it.isNotBlank() }
                    ?: "Unable to load tracks for this playlist."
            }
            selectedProviderPlaylistLoading.value = false
        }
    }

    fun closeProviderPlaylistSummary() {
        selectedProviderPlaylist.value = null
        selectedProviderPlaylistTracks.value = emptyList()
        selectedProviderPlaylistIsDistinct.value = false
        selectedProviderPlaylistLoading.value = false
        selectedProviderPlaylistError.value = null
        providerPlaylistDistinctLoadJob?.cancel()
        providerPlaylistDistinctLoadJob = null
    }

    fun setSelectedProviderPlaylistDistinct(isDistinct: Boolean) {
        val playlist = selectedProviderPlaylist.value ?: return
        // Optimistically update the UI state so the last toggle wins.
        selectedProviderPlaylistIsDistinct.value = isDistinct
        // Cancel any in-flight preference load so it cannot overwrite this choice.
        providerPlaylistDistinctLoadJob?.cancel()
        // Persist the change in the background without reading it back to drive UI state.
        viewModelScope.launch {
            playlistStorageRepository.setProviderPlaylistIsDistinct(
                source = playlist.source.name,
                playlistId = playlist.id,
                isDistinct = isDistinct
            )
        }
    }

    fun playSelectedProviderPlaylist() {
        val playlist = selectedProviderPlaylist.value ?: return
        viewModelScope.launch {
            val cachedTracks = selectedProviderPlaylistTracks.value
            val isDistinct = selectedProviderPlaylistIsDistinct.value
            val queue = buildProviderQueueDistinct(
                sourceType = playlist.source,
                playlistId = playlist.id,
                prefetchedTracks = cachedTracks.takeIf { it.isNotEmpty() },
                distinctOverride = isDistinct
            )
            playbackQueueManager.setQueue(queue, startIndex = 0, autoPlay = true)
        }
    }

    /**
     * Fetches provider playlist tracks and applies the distinct dedup gate if enabled.
     *
     * Shared by [playPlaylist] and [playSelectedProviderPlaylist] so both provider entry
     * points cannot drift in distinct behavior. Pass [prefetchedTracks] to skip a
     * network fetch when the tracks are already available (e.g. from UI cache).
     */
    private suspend fun buildProviderQueueDistinct(
        sourceType: SourceType,
        playlistId: String,
        prefetchedTracks: List<Track>? = null,
        distinctOverride: Boolean? = null
    ): List<Track> {
        val tracks = if (!prefetchedTracks.isNullOrEmpty()) {
            prefetchedTracks
        } else {
            providerCatalogRepository.getPlaylistTracksWithCache(sourceType, playlistId)
        }
        val isDistinct = distinctOverride
            ?: playlistStorageRepository.getProviderPlaylistIsDistinct(sourceType.name, playlistId)
        return if (isDistinct) {
            DistinctPlaylistUtils.deduplicateTracks(tracks).tracks
        } else {
            tracks
        }
    }

    suspend fun ensureProviderPlaylistMetadataForUnionSources(
        unionSources: List<UnionPlaylistSource>
    ) {
        val providerSources = unionSources.filter { it.sourceType != SourceType.CUSTOM && it.sourceType != SourceType.ALL }
        if (providerSources.isEmpty()) return

        val existingPlaylists = providerPlaylists.value
        val unresolvedSources = providerSources.distinctBy { source ->
            source.sourceType.name + ":" + normalizePlaylistId(source.sourceType, source.sourcePlaylistId)
        }.filterNot { source ->
            existingPlaylists.any { playlist -> matchesProviderPlaylistSource(playlist, source) }
        }
        if (unresolvedSources.isEmpty()) return

        val cachedPlaylists = providerCatalogRepository.getCachedProviderPlaylists().orEmpty()
        val cachedMatches = unresolvedSources.mapNotNull { source ->
            cachedPlaylists.firstOrNull { playlist -> matchesProviderPlaylistSource(playlist, source) }
        }
        val stillUnresolvedSources = unresolvedSources.filterNot { source ->
            cachedMatches.any { playlist -> matchesProviderPlaylistSource(playlist, source) }
        }
        val exactMatches = coroutineScope {
            stillUnresolvedSources
                .map { source -> async { providerCatalogRepository.getProviderPlaylist(source.sourceType, source.sourcePlaylistId) } }
                .awaitAll()
                .filterNotNull()
        }
        val loadedPlaylists = cachedMatches + exactMatches

        if (loadedPlaylists.isEmpty()) return

        providerPlaylists.value = mergeProviderPlaylists(existingPlaylists, loadedPlaylists)
    }
}

private fun matchesProviderPlaylistSource(
    playlist: Playlist,
    source: UnionPlaylistSource
): Boolean =
    playlist.source == source.sourceType &&
        normalizePlaylistId(playlist.source, playlist.id) ==
        normalizePlaylistId(source.sourceType, source.sourcePlaylistId)

private fun mergeProviderPlaylists(
    existing: List<Playlist>,
    loaded: List<Playlist>
): List<Playlist> =
    (existing + loaded).distinctBy { playlist ->
        playlist.source.name + ":" + normalizePlaylistId(playlist.source, playlist.id)
    }
