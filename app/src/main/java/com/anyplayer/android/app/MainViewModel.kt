package com.anyplayer.android.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.feature.auth.AuthRequest
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playlists.CustomPlaylistEngine
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.search.SearchType
import com.anyplayer.android.feature.startup.StartupResilienceManager
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.ExportOptions
import com.anyplayer.android.feature.state.transfer.ImportOptions
import com.anyplayer.android.feature.state.transfer.ImportSummary
import com.anyplayer.android.feature.state.transfer.MergePolicy
import com.anyplayer.android.feature.state.transfer.StateTransferManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authRepository: ProviderAuthRepository,
    private val playbackQueueManager: PlaybackQueueManager,
    private val stateTransferManager: StateTransferManager,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val customPlaylistEngine: CustomPlaylistEngine,
    private val startupResilienceManager: StartupResilienceManager
) : ViewModel() {
    private val startupStatus = MutableStateFlow("Restoring provider sessions...")
    private val startupInProgress = MutableStateFlow(false)
    private val startupCanRetry = MutableStateFlow(false)
    private val startupCanContinueWithoutProvider = MutableStateFlow(false)
    private val startupWarnings = MutableStateFlow<List<String>>(emptyList())
    private val providerStatuses = MutableStateFlow<List<ProviderConnectionProfile>>(emptyList())
    private val searchResults = MutableStateFlow<List<Track>>(emptyList())
    private val searchPlaylistResults = MutableStateFlow<List<com.anyplayer.android.core.model.Playlist>>(emptyList())
    private val providerPlaylists = MutableStateFlow<List<com.anyplayer.android.core.model.Playlist>>(emptyList())
    private val selectedProviderPlaylist = MutableStateFlow<com.anyplayer.android.core.model.Playlist?>(null)
    private val selectedProviderPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    private val selectedProviderPlaylistLoading = MutableStateFlow(false)
    private val selectedProviderPlaylistError = MutableStateFlow<String?>(null)
    private val providerPlaylistRefreshInProgress = MutableStateFlow(false)
    private val providerPlaylistRefreshStatus = MutableStateFlow<String?>(null)
    private val customPlaylists = MutableStateFlow<List<com.anyplayer.android.core.model.CustomPlaylist>>(emptyList())
    private val activeCustomPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    private val selectedCustomPlaylistId = MutableStateFlow<String?>(null)
    private val stateTransferStatus = MutableStateFlow("State transfer idle")
    private val providerConnectionFeedback = MutableStateFlow<String?>(null)
    private val providerConnectionInProgress = MutableStateFlow(false)
    private val jellyfinUrlInput = MutableStateFlow("")
    private val jellyfinTokenInput = MutableStateFlow("")
    private val plexUrlInput = MutableStateFlow("")
    private val plexTokenInput = MutableStateFlow("")
    private val spotifyTokenInput = MutableStateFlow("")
    private val spotifyAuthLaunchUrl = MutableStateFlow<String?>(null)

    private data class StartupUiState(
        val startupMessage: String,
        val startupInProgress: Boolean,
        val startupCanRetry: Boolean,
        val startupCanContinueWithoutProvider: Boolean,
        val startupWarnings: List<String>
    )

    private data class CatalogUiState(
        val providerStatuses: List<ProviderConnectionProfile>,
        val playbackStatus: com.anyplayer.android.core.model.PlaybackStatus,
        val searchResults: List<Track>,
        val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist>,
        val providerPlaylists: List<com.anyplayer.android.core.model.Playlist>,
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?,
        val providerPlaylistRefreshInProgress: Boolean,
        val providerPlaylistRefreshStatus: String?
    )

    private data class CatalogCoreUiState(
        val providerStatuses: List<ProviderConnectionProfile>,
        val playbackStatus: com.anyplayer.android.core.model.PlaybackStatus,
        val searchResults: List<Track>,
        val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist>,
        val providerPlaylists: List<com.anyplayer.android.core.model.Playlist>
    )

    private data class ProviderPlaylistSummaryUiState(
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?,
        val providerPlaylistRefreshInProgress: Boolean,
        val providerPlaylistRefreshStatus: String?
    )

    private data class ProviderPlaylistSummaryCoreUiState(
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?
    )

    private data class LocalUiState(
        val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist>,
        val activeCustomPlaylistTracks: List<Track>,
        val selectedCustomPlaylistId: String?,
        val stateTransferStatus: String,
        val providerConnectionFeedback: String?,
        val providerConnectionInProgress: Boolean,
        val jellyfinUrlInput: String,
        val jellyfinTokenInput: String,
        val plexUrlInput: String,
        val plexTokenInput: String,
        val spotifyTokenInput: String,
        val spotifyAuthLaunchUrl: String?
    )

    private data class LocalCoreUiState(
        val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist>,
        val activeCustomPlaylistTracks: List<Track>,
        val selectedCustomPlaylistId: String?,
        val stateTransferStatus: String
    )

    private val localCoreState = combine(
        customPlaylists,
        activeCustomPlaylistTracks,
        selectedCustomPlaylistId,
        stateTransferStatus
    ) { localPlaylists, localTracks, selectedLocalId, transferStatus ->
        LocalCoreUiState(
            customPlaylists = localPlaylists,
            activeCustomPlaylistTracks = localTracks,
            selectedCustomPlaylistId = selectedLocalId,
            stateTransferStatus = transferStatus
        )
    }

    private val connectionState = combine(providerConnectionFeedback, providerConnectionInProgress) { feedback, inProgress ->
        feedback to inProgress
    }

    private val providerInputState = combine(jellyfinUrlInput, jellyfinTokenInput, plexUrlInput, plexTokenInput, spotifyTokenInput) { jellyUrl, jellyToken, plexUrl, plexToken, spotifyToken ->
        ProviderSettingsInputs(jellyUrl, jellyToken, plexUrl, plexToken, spotifyToken)
    }

    private val catalogCoreState = combine(
        providerStatuses,
        playbackQueueManager.status,
        searchResults,
        searchPlaylistResults,
        providerPlaylists
    ) { providers, playback, search, searchPlaylists, playlists ->
        CatalogCoreUiState(
            providerStatuses = providers,
            playbackStatus = playback,
            searchResults = search,
            searchPlaylistResults = searchPlaylists,
            providerPlaylists = playlists
        )
    }

    private val providerPlaylistSummaryCoreState = combine(
        selectedProviderPlaylist,
        selectedProviderPlaylistTracks,
        selectedProviderPlaylistLoading,
        selectedProviderPlaylistError
    ) { playlist, tracks, loading, error ->
        ProviderPlaylistSummaryCoreUiState(
            selectedProviderPlaylist = playlist,
            selectedProviderPlaylistTracks = tracks,
            selectedProviderPlaylistLoading = loading,
            selectedProviderPlaylistError = error
        )
    }

    private val providerPlaylistSummaryState = combine(
        providerPlaylistSummaryCoreState,
        providerPlaylistRefreshInProgress,
        providerPlaylistRefreshStatus
    ) { coreState, refreshInProgress, refreshStatus ->
        ProviderPlaylistSummaryUiState(
            selectedProviderPlaylist = coreState.selectedProviderPlaylist,
            selectedProviderPlaylistTracks = coreState.selectedProviderPlaylistTracks,
            selectedProviderPlaylistLoading = coreState.selectedProviderPlaylistLoading,
            selectedProviderPlaylistError = coreState.selectedProviderPlaylistError,
            providerPlaylistRefreshInProgress = refreshInProgress,
            providerPlaylistRefreshStatus = refreshStatus
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            startupStatus,
            startupInProgress,
            startupCanRetry,
            startupCanContinueWithoutProvider,
            startupWarnings
        ) { startup, inProgress, canRetry, canContinue, warnings ->
            StartupUiState(
                startupMessage = startup,
                startupInProgress = inProgress,
                startupCanRetry = canRetry,
                startupCanContinueWithoutProvider = canContinue,
                startupWarnings = warnings
            )
        },
        combine(catalogCoreState, providerPlaylistSummaryState) { catalogBase, summaryState ->
            CatalogUiState(
                providerStatuses = catalogBase.providerStatuses,
                playbackStatus = catalogBase.playbackStatus,
                searchResults = catalogBase.searchResults,
                searchPlaylistResults = catalogBase.searchPlaylistResults,
                providerPlaylists = catalogBase.providerPlaylists,
                selectedProviderPlaylist = summaryState.selectedProviderPlaylist,
                selectedProviderPlaylistTracks = summaryState.selectedProviderPlaylistTracks,
                selectedProviderPlaylistLoading = summaryState.selectedProviderPlaylistLoading,
                selectedProviderPlaylistError = summaryState.selectedProviderPlaylistError,
                providerPlaylistRefreshInProgress = summaryState.providerPlaylistRefreshInProgress,
                providerPlaylistRefreshStatus = summaryState.providerPlaylistRefreshStatus
            )
        },
        combine(localCoreState, connectionState, providerInputState, spotifyAuthLaunchUrl) { localCore, connectionState, providerInputs, spotifyLaunchUrl ->
            LocalUiState(
                customPlaylists = localCore.customPlaylists,
                activeCustomPlaylistTracks = localCore.activeCustomPlaylistTracks,
                selectedCustomPlaylistId = localCore.selectedCustomPlaylistId,
                stateTransferStatus = localCore.stateTransferStatus,
                providerConnectionFeedback = connectionState.first,
                providerConnectionInProgress = connectionState.second,
                jellyfinUrlInput = providerInputs.jellyfinUrl,
                jellyfinTokenInput = providerInputs.jellyfinToken,
                plexUrlInput = providerInputs.plexUrl,
                plexTokenInput = providerInputs.plexToken,
                spotifyTokenInput = providerInputs.spotifyToken,
                spotifyAuthLaunchUrl = spotifyLaunchUrl
            )
        }
    ) { startup, catalog, local ->
        MainUiState(
            startupMessage = startup.startupMessage,
            startupInProgress = startup.startupInProgress,
            startupCanRetry = startup.startupCanRetry,
            startupCanContinueWithoutProvider = startup.startupCanContinueWithoutProvider,
            startupWarnings = startup.startupWarnings,
            providerStatuses = catalog.providerStatuses,
            playbackStatus = catalog.playbackStatus,
            searchResults = catalog.searchResults,
            searchPlaylistResults = catalog.searchPlaylistResults,
            providerPlaylists = catalog.providerPlaylists,
            selectedProviderPlaylist = catalog.selectedProviderPlaylist,
            selectedProviderPlaylistTracks = catalog.selectedProviderPlaylistTracks,
            selectedProviderPlaylistLoading = catalog.selectedProviderPlaylistLoading,
            selectedProviderPlaylistError = catalog.selectedProviderPlaylistError,
            providerPlaylistRefreshInProgress = catalog.providerPlaylistRefreshInProgress,
            providerPlaylistRefreshStatus = catalog.providerPlaylistRefreshStatus,
            customPlaylists = local.customPlaylists,
            activeCustomPlaylistTracks = local.activeCustomPlaylistTracks,
            selectedCustomPlaylistId = local.selectedCustomPlaylistId,
            stateTransferStatus = local.stateTransferStatus,
            providerConnectionFeedback = local.providerConnectionFeedback,
            providerConnectionInProgress = local.providerConnectionInProgress,
            jellyfinUrlInput = local.jellyfinUrlInput,
            jellyfinTokenInput = local.jellyfinTokenInput,
            plexUrlInput = local.plexUrlInput,
            plexTokenInput = local.plexTokenInput,
            spotifyTokenInput = local.spotifyTokenInput,
            spotifyAuthLaunchUrl = local.spotifyAuthLaunchUrl
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    init {
        loadSavedProviderInputs()
        restoreStartup()
        observeCustomPlaylists()
    }

    fun updateJellyfinUrlInput(value: String) {
        jellyfinUrlInput.value = value
    }

    fun updateJellyfinTokenInput(value: String) {
        jellyfinTokenInput.value = value
    }

    fun updatePlexUrlInput(value: String) {
        plexUrlInput.value = value
    }

    fun updatePlexTokenInput(value: String) {
        plexTokenInput.value = value
    }

    fun connectJellyfin(url: String, apiKey: String) {
        viewModelScope.launch {
            val normalizedUrl = url.trim()
            val normalizedApiKey = apiKey.trim()
            if (normalizedUrl.isBlank() || normalizedApiKey.isBlank()) {
                providerConnectionFeedback.value = "Jellyfin URL and API key are required."
                return@launch
            }

            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = "Connecting to Jellyfin..."

            val result = runCatching {
                authRepository.connect(AuthRequest.Jellyfin(serverUrl = normalizedUrl, apiKey = normalizedApiKey))
            }

            result.onFailure {
                providerConnectionFeedback.value = formatProviderFailure("Jellyfin", it)
                providerConnectionInProgress.value = false
            }.onSuccess {
                loadSavedProviderInputs()
                runStartup(continueWithoutProviders = false)
                providerConnectionFeedback.value = if (providerPlaylists.value.isEmpty()) {
                    "Jellyfin connected, but no provider playlists were returned."
                } else {
                    "Jellyfin connected. Loaded ${providerPlaylists.value.size} provider playlist(s)."
                }
                providerConnectionInProgress.value = false
            }
        }
    }

    fun connectPlex(url: String, token: String) {
        viewModelScope.launch {
            val normalizedUrl = url.trim()
            val normalizedToken = token.trim()
            if (normalizedUrl.isBlank() || normalizedToken.isBlank()) {
                providerConnectionFeedback.value = "Plex URL and token are required."
                return@launch
            }

            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = "Connecting to Plex..."

            val result = runCatching {
                authRepository.connect(AuthRequest.Plex(serverUrl = normalizedUrl, token = normalizedToken))
            }

            result.onFailure {
                providerConnectionFeedback.value = formatProviderFailure("Plex", it)
                providerConnectionInProgress.value = false
            }.onSuccess {
                loadSavedProviderInputs()
                runStartup(continueWithoutProviders = false)
                providerConnectionFeedback.value = if (providerPlaylists.value.isEmpty()) {
                    "Plex connected, but no provider playlists were returned."
                } else {
                    "Plex connected. Loaded ${providerPlaylists.value.size} provider playlist(s)."
                }
                providerConnectionInProgress.value = false
            }
        }
    }

    fun beginSpotifyLink() {
        viewModelScope.launch {
            val clientId = SpotifyClientIds.ACTIVE.trim()
            if (clientId.isBlank()) {
                providerConnectionFeedback.value = "Spotify client ID is not configured. Set 'spotifyClientId' in local.properties (or gradle.properties) and register redirect URI anyplayer://spotify-callback in Spotify Developer Dashboard."
                return@launch
            }

            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = "Opening Spotify login..."
            runCatching {
                authRepository.beginSpotifyAuth(
                    clientId = clientId,
                    redirectUri = SPOTIFY_REDIRECT_URI
                )
            }.onFailure {
                providerConnectionFeedback.value = "Spotify connection failed: ${it.message ?: "Unknown error"}"
            }.onSuccess {
                spotifyAuthLaunchUrl.value = it
                providerConnectionFeedback.value = "Continue in browser to link Spotify."
            }
            providerConnectionInProgress.value = false
        }
    }

    fun markSpotifyAuthLaunchHandled() {
        spotifyAuthLaunchUrl.value = null
    }

    fun completeSpotifyLink(redirectUri: String) {
        viewModelScope.launch {
            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = "Finishing Spotify login..."
            runCatching {
                authRepository.completeSpotifyAuth(redirectUri)
            }.onFailure {
                providerConnectionFeedback.value = "Spotify login failed: ${it.message ?: "Unknown error"}"
            }.onSuccess {
                loadSavedProviderInputs()
                runStartup(continueWithoutProviders = false)
                providerConnectionFeedback.value = "Spotify connected successfully."
            }
            providerConnectionInProgress.value = false
        }
    }

    fun disconnect(sourceType: SourceType) {
        viewModelScope.launch {
            authRepository.disconnect(sourceType)
            loadSavedProviderInputs()
            providerConnectionFeedback.value = "Disconnected ${sourceType.name.lowercase()}."
            runStartup(continueWithoutProviders = false)
        }
    }

    fun retryStartup() {
        viewModelScope.launch {
            runStartup(continueWithoutProviders = false)
        }
    }

    fun continueWithoutProviderStartup() {
        viewModelScope.launch {
            runStartup(continueWithoutProviders = true)
        }
    }

    fun togglePlayPause() = playbackQueueManager.togglePlayPause()
    fun next() = playbackQueueManager.next()
    fun previous() = playbackQueueManager.previous()
    fun setShuffle(enabled: Boolean) = playbackQueueManager.setShuffle(enabled)
    fun setRepeatMode(mode: RepeatMode) = playbackQueueManager.setRepeatMode(mode)
    fun seekTo(positionMs: Long) = playbackQueueManager.seekTo(positionMs)
    fun setVolume(volume: Int) = playbackQueueManager.setVolume(volume)
    fun playFromQueue(index: Int) = playbackQueueManager.playFromIndex(index)
    fun playFromSearch(index: Int) {
        val tracks = searchResults.value
        if (tracks.isEmpty()) return
        playbackQueueManager.setQueue(tracks, startIndex = index, autoPlay = true)
    }

    fun search(query: String, sourceType: SourceType, searchType: SearchType) {
        viewModelScope.launch {
            val result = providerCatalogRepository.search(query = query, source = sourceType)
            when (searchType) {
                SearchType.TRACKS -> {
                    searchResults.value = result.tracks
                    searchPlaylistResults.value = emptyList()
                }

                SearchType.PLAYLISTS -> {
                    searchPlaylistResults.value = result.playlists
                    searchResults.value = emptyList()
                }
            }
        }
    }

    fun playPlaylistFromSearch(index: Int) {
        val playlist = searchPlaylistResults.value.getOrNull(index) ?: return
        playPlaylist(playlist.source, playlist.id)
    }

    fun playPlaylist(sourceType: SourceType, playlistId: String) {
        viewModelScope.launch {
            val tracks = providerCatalogRepository.getPlaylistTracksWithCache(sourceType, playlistId)
            playbackQueueManager.setQueue(tracks, startIndex = 0, autoPlay = true)
        }
    }

    fun refreshProviderPlaylistData() {
        viewModelScope.launch {
            providerPlaylistRefreshInProgress.value = true
            providerPlaylistRefreshStatus.value = "Refreshing playlist data..."

            runCatching {
                providerCatalogRepository.refreshAllProviderPlaylistDataWithCache()
            }.onSuccess { refreshedPlaylists ->
                providerPlaylists.value = refreshedPlaylists
                providerPlaylistRefreshStatus.value = "Playlist data refreshed."

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

    fun openProviderPlaylistSummary(playlist: com.anyplayer.android.core.model.Playlist) {
        selectedProviderPlaylist.value = playlist
        selectedProviderPlaylistTracks.value = playlist.tracks.orEmpty()
        selectedProviderPlaylistLoading.value = true
        selectedProviderPlaylistError.value = null

        val isSourceConnected = providerStatuses.value.any { profile ->
            profile.source == playlist.source && profile.connected
        }
        if (!isSourceConnected) {
            selectedProviderPlaylistLoading.value = false
            selectedProviderPlaylistError.value = "${playlist.source.name.lowercase()} is not connected. Reconnect in Settings, then retry."
            return
        }

        viewModelScope.launch {
            val result = runCatching {
                providerCatalogRepository.getPlaylistTracksWithCache(playlist.source, playlist.id)
            }
            val selectedId = selectedProviderPlaylist.value?.id
            if (selectedId != playlist.id) {
                return@launch
            }
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
        selectedProviderPlaylistLoading.value = false
        selectedProviderPlaylistError.value = null
    }

    fun playSelectedProviderPlaylist() {
        val playlist = selectedProviderPlaylist.value ?: return
        val tracks = selectedProviderPlaylistTracks.value
        if (tracks.isNotEmpty()) {
            playbackQueueManager.setQueue(tracks, startIndex = 0, autoPlay = true)
            return
        }
        playPlaylist(playlist.source, playlist.id)
    }

    fun createStandardPlaylist(name: String) {
        viewModelScope.launch {
            customPlaylistEngine.createPlaylist(name, PlaylistType.STANDARD)
        }
    }

    fun createUnionPlaylist(name: String) {
        viewModelScope.launch {
            customPlaylistEngine.createPlaylist(name, PlaylistType.UNION)
        }
    }

    fun deleteCustomPlaylist(playlistId: String) {
        viewModelScope.launch {
            customPlaylistEngine.deletePlaylist(playlistId)
            if (selectedCustomPlaylistId.value == playlistId) {
                selectedCustomPlaylistId.value = null
                activeCustomPlaylistTracks.value = emptyList()
            }
        }
    }

    fun renameCustomPlaylist(playlistId: String, name: String) {
        viewModelScope.launch {
            customPlaylistEngine.renamePlaylist(playlistId, name)
        }
    }

    fun selectCustomPlaylist(playlistId: String) {
        selectedCustomPlaylistId.value = playlistId
        viewModelScope.launch {
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun playCustomPlaylist(playlistId: String) {
        viewModelScope.launch {
            customPlaylistEngine.playPlaylist(playlistId)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
            selectedCustomPlaylistId.value = playlistId
        }
    }

    fun playFromCustomPlaylistTrack(playlistId: String, index: Int) {
        viewModelScope.launch {
            customPlaylistEngine.playFromTrack(playlistId, index)
        }
    }

    fun addSearchTrackToSelectedCustom(trackIndex: Int) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        val track = searchResults.value.getOrNull(trackIndex) ?: return
        viewModelScope.launch {
            customPlaylistEngine.addTrack(playlistId, track)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun removeTrackFromSelectedCustom(playlistTrackIndex: Int) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.removeTrackAt(playlistId, playlistTrackIndex)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun addProviderPlaylistSourceToSelectedUnion(sourcePlaylistId: String, sourceType: SourceType) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.addUnionSource(
                unionPlaylistId = playlistId,
                sourceType = sourceType,
                sourcePlaylistId = sourcePlaylistId
            )
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun reorderSelectedCustomTracks(orderedTrackIds: List<String>) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.reorderTracks(playlistId, orderedTrackIds)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun reorderSelectedUnionSources(orderedSourceIds: List<String>) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            customPlaylistEngine.reorderUnionSources(playlistId, orderedSourceIds)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
        }
    }

    fun materializeSelectedUnion() {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            activeCustomPlaylistTracks.value = customPlaylistEngine.materializeUnionTracks(playlistId)
        }
    }

    private fun observeCustomPlaylists() {
        viewModelScope.launch {
            customPlaylistEngine.observeCustomPlaylists().collect { playlists ->
                customPlaylists.value = playlists.sortedByDescending { it.updatedAt }
            }
        }
    }

    fun exportState(path: String, mode: ExportMode, includePlayback: Boolean, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                val file = stateTransferManager.exportToFile(
                    target = File(path),
                    options = ExportOptions(
                        mode = mode,
                        includePlaybackState = includePlayback,
                        passphrase = passphrase?.takeIf { it.isNotBlank() }
                    ),
                    playbackStatus = uiState.value.playbackStatus
                )
                "Export complete: ${file.absolutePath}"
            }.onSuccess { stateTransferStatus.value = it }
                .onFailure { stateTransferStatus.value = "Export failed: ${it.message}" }
        }
    }

    fun dryRunImport(path: String, policy: MergePolicy, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                stateTransferManager.importFromFile(
                    source = File(path),
                    options = ImportOptions(
                        mergePolicy = policy,
                        passphrase = passphrase?.takeIf { it.isNotBlank() },
                        dryRun = true
                    )
                )
            }.onSuccess { summary ->
                stateTransferStatus.value = formatSummary("Dry run", summary)
            }.onFailure {
                stateTransferStatus.value = "Dry run failed: ${it.message}"
            }
        }
    }

    fun importState(path: String, policy: MergePolicy, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                stateTransferManager.importFromFile(
                    source = File(path),
                    options = ImportOptions(
                        mergePolicy = policy,
                        passphrase = passphrase?.takeIf { it.isNotBlank() },
                        dryRun = false
                    )
                )
            }.onSuccess { summary ->
                stateTransferStatus.value = formatSummary("Import", summary)
            }.onFailure {
                stateTransferStatus.value = "Import failed: ${it.message}"
            }
        }
    }

    private fun formatSummary(prefix: String, summary: ImportSummary): String {
        val warningSummary = if (summary.warnings.isEmpty()) "no warnings" else summary.warnings.joinToString(" | ")
        return "$prefix complete. playlists +${summary.playlistsAdded}/~${summary.playlistsUpdated}, tracks +${summary.tracksAdded}/~${summary.tracksUpdated}, union +${summary.unionLinksAdded}/~${summary.unionLinksUpdated}, connections +${summary.connectionsImported}/skip ${summary.connectionsSkipped}, $warningSummary"
    }

    private fun restoreStartup() {
        viewModelScope.launch {
            runStartup(continueWithoutProviders = false)
        }
    }

    private suspend fun runStartup(continueWithoutProviders: Boolean) {
        startupInProgress.value = true
        startupCanRetry.value = false
        startupCanContinueWithoutProvider.value = false
        startupWarnings.value = emptyList()

        val snapshot = startupResilienceManager.runStartup(
            continueWithoutProviders = continueWithoutProviders,
            onProgress = { message -> startupStatus.value = message }
        )

        providerStatuses.value = snapshot.providerStatuses
        providerPlaylists.value = snapshot.providerPlaylists
        val selectedPlaylistId = selectedProviderPlaylist.value?.id
        if (selectedPlaylistId != null && snapshot.providerPlaylists.none { it.id == selectedPlaylistId }) {
            selectedProviderPlaylist.value = null
            selectedProviderPlaylistTracks.value = emptyList()
            selectedProviderPlaylistLoading.value = false
            selectedProviderPlaylistError.value = null
        }
        startupWarnings.value = snapshot.warnings
        startupCanRetry.value = snapshot.warnings.isNotEmpty()
        startupCanContinueWithoutProvider.value = snapshot.warnings.isNotEmpty() && !continueWithoutProviders
        loadSavedProviderInputsInternal()
        startupInProgress.value = false
    }

    private fun formatProviderFailure(providerName: String, throwable: Throwable): String {
        val message = throwable.message?.trim().orEmpty()
        if (message.isBlank()) {
            return "$providerName connection failed."
        }
        return if (message.startsWith("$providerName connection failed", ignoreCase = true)) {
            message
        } else {
            "$providerName connection failed: $message"
        }
    }

    private fun loadSavedProviderInputs() {
        viewModelScope.launch {
            loadSavedProviderInputsInternal()
        }
    }

    private suspend fun loadSavedProviderInputsInternal() {
        val jelly = authRepository.readStoredConnection(SourceType.JELLYFIN)
        val plex = authRepository.readStoredConnection(SourceType.PLEX)
        val spotify = authRepository.readStoredConnection(SourceType.SPOTIFY)

        jellyfinUrlInput.value = jelly?.serverUrl.orEmpty()
        jellyfinTokenInput.value = jelly?.token.orEmpty()
        plexUrlInput.value = plex?.serverUrl.orEmpty()
        plexTokenInput.value = plex?.token.orEmpty()
        spotifyTokenInput.value = spotify?.token.orEmpty()
    }

    private data class ProviderSettingsInputs(
        val jellyfinUrl: String,
        val jellyfinToken: String,
        val plexUrl: String,
        val plexToken: String,
        val spotifyToken: String
    )
}

private const val SPOTIFY_REDIRECT_URI = "anyplayer://spotify-callback"

data class MainUiState(
    val startupMessage: String = "Starting...",
    val startupInProgress: Boolean = false,
    val startupCanRetry: Boolean = false,
    val startupCanContinueWithoutProvider: Boolean = false,
    val startupWarnings: List<String> = emptyList(),
    val providerStatuses: List<ProviderConnectionProfile> = emptyList(),
    val playbackStatus: com.anyplayer.android.core.model.PlaybackStatus = com.anyplayer.android.core.model.PlaybackStatus(
        state = PlaybackStateType.IDLE,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        volume = 100,
        currentTrack = null,
        position = 0,
        duration = 0,
        queue = emptyList()
    ),
    val searchResults: List<Track> = emptyList(),
    val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist> = emptyList(),
    val providerPlaylists: List<com.anyplayer.android.core.model.Playlist> = emptyList(),
    val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist? = null,
    val selectedProviderPlaylistTracks: List<Track> = emptyList(),
    val selectedProviderPlaylistLoading: Boolean = false,
    val selectedProviderPlaylistError: String? = null,
    val providerPlaylistRefreshInProgress: Boolean = false,
    val providerPlaylistRefreshStatus: String? = null,
    val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist> = emptyList(),
    val activeCustomPlaylistTracks: List<Track> = emptyList(),
    val selectedCustomPlaylistId: String? = null,
    val stateTransferStatus: String = "State transfer idle",
    val providerConnectionFeedback: String? = null,
    val providerConnectionInProgress: Boolean = false,
    val jellyfinUrlInput: String = "",
    val jellyfinTokenInput: String = "",
    val plexUrlInput: String = "",
    val plexTokenInput: String = "",
    val spotifyTokenInput: String = "",
    val spotifyAuthLaunchUrl: String? = null
)
