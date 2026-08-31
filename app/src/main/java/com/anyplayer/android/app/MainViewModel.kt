package com.anyplayer.android.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playlists.CustomPlaylistEngine
import com.anyplayer.android.feature.playlists.DistinctPlaylistUtils
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.search.SearchType
import com.anyplayer.android.feature.startup.StartupResilienceManager
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.MergePolicy
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.state.transfer.StateTransferManager
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import com.anyplayer.android.feature.sync.SyncSnapshotClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authRepository: ProviderAuthRepository,
    private val playbackQueueManager: PlaybackQueueManager,
    private val stateTransferManager: StateTransferManager,
    private val configFileImporter: ConfigFileImporter,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val playlistStorageRepository: PlaylistStorageRepository,
    private val customPlaylistEngine: CustomPlaylistEngine,
    private val startupResilienceManager: StartupResilienceManager,
    private val syncPreferencesStore: SyncPreferencesStore,
    private val syncSnapshotClient: SyncSnapshotClient
) : ViewModel() {
    private var lastAutoPausedTrackKey: String? = null

    private val startupStatus = MutableStateFlow("Restoring provider sessions...")
    private val startupInProgress = MutableStateFlow(false)
    private val startupCanRetry = MutableStateFlow(false)
    private val startupCanContinueWithoutProvider = MutableStateFlow(false)
    private val startupWarnings = MutableStateFlow<List<String>>(emptyList())
    private val providerStatuses = MutableStateFlow<List<ProviderConnectionProfile>>(emptyList())
    private var trackPrefetchJob: Job? = null

    private val providerPlaylistBrowsingStateHolder = ProviderPlaylistBrowsingStateHolder(
        viewModelScope = viewModelScope,
        providerCatalogRepository = providerCatalogRepository,
        playlistStorageRepository = playlistStorageRepository,
        playbackQueueManager = playbackQueueManager,
        currentProviderStatuses = { providerStatuses.value }
    )

    private val searchStateHolder = SearchStateHolder(
        viewModelScope = viewModelScope,
        providerCatalogRepository = providerCatalogRepository,
        playbackQueueManager = playbackQueueManager,
        playPlaylist = providerPlaylistBrowsingStateHolder::playPlaylist
    )

    private val customPlaylistStateHolder = CustomPlaylistStateHolder(
        viewModelScope = viewModelScope,
        customPlaylistEngine = customPlaylistEngine,
        playlistStorageRepository = playlistStorageRepository,
        searchResults = searchStateHolder.searchResults,
        ensureProviderPlaylistMetadataForUnionSources =
            providerPlaylistBrowsingStateHolder::ensureProviderPlaylistMetadataForUnionSources
    )

    private val providerConnectionStateHolder = ProviderConnectionStateHolder(
        viewModelScope = viewModelScope,
        authRepository = authRepository,
        providerCatalogRepository = providerCatalogRepository,
        currentProviderPlaylistCount = { providerPlaylistBrowsingStateHolder.providerPlaylists.value.size },
        onProviderChanged = { runStartup(continueWithoutProviders = false) }
    )

    private val stateTransferStateHolder = StateTransferStateHolder(
        viewModelScope = viewModelScope,
        context = context,
        stateTransferManager = stateTransferManager,
        configFileImporter = configFileImporter,
        currentPlaybackStatus = { uiState.value.playbackStatus }
    )

    private val syncStateHolder = SyncStateHolder(
        viewModelScope = viewModelScope,
        syncPreferencesStore = syncPreferencesStore,
        syncSnapshotClient = syncSnapshotClient,
        playbackQueueManager = playbackQueueManager,
        configFileImporter = configFileImporter,
        customPlaylistCount = {
            customPlaylistStateHolder.awaitCustomPlaylistsLoaded()
            customPlaylistStateHolder.customPlaylists.value.size
        },
        applyImportSummary = stateTransferStateHolder::applyImportSummary,
        onSyncApplied = {
            runStartup(continueWithoutProviders = false)
        }
    )

    private val customPlaylistRefreshState = combine(
        customPlaylistStateHolder.customPlaylistRefreshInProgress,
        customPlaylistStateHolder.customPlaylistRefreshStatus
    ) { inProgress, status ->
        inProgress to status
    }

    private val localBasicState = combine(
        customPlaylistStateHolder.customPlaylists,
        customPlaylistStateHolder.activeCustomPlaylistTracks,
        customPlaylistStateHolder.selectedCustomPlaylistId,
        customPlaylistStateHolder.selectedCustomUnionSources
    ) { playlists, tracks, playlistId, unionSources ->
        LocalBasicStatePart(playlists, tracks, playlistId, unionSources)
    }

    private val localCoreState = combine(
        localBasicState,
        customPlaylistRefreshState,
        stateTransferStateHolder.stateTransferStatus
    ) { basic, refreshState, transferStatus ->
        LocalCoreUiState(
            customPlaylists = basic.playlists,
            activeCustomPlaylistTracks = basic.tracks,
            selectedCustomPlaylistId = basic.playlistId,
            selectedCustomUnionSources = basic.unionSources,
            customPlaylistRefreshInProgress = refreshState.first,
            customPlaylistRefreshStatus = refreshState.second,
            stateTransferStatus = transferStatus
        )
    }

    private val connectionState = combine(
        providerConnectionStateHolder.providerConnectionFeedback,
        providerConnectionStateHolder.providerConnectionInProgress
    ) { feedback, inProgress ->
        feedback to inProgress
    }

    private data class SyncInputPart(
        val serverTarget: String,
        val authToken: String,
        val appStateEnabled: Boolean,
        val playlistsEnabled: Boolean,
        val providerConfigEnabled: Boolean
    )

    private data class ProviderInputPart(
        val jellyUrl: String,
        val jellyToken: String,
        val jellyPageSize: String,
        val plexUrl: String,
        val plexToken: String,
        val plexPageSize: String,
        val spotifyToken: String
    )

    private val syncInputPart = combine(
        syncStateHolder.syncServerTarget,
        syncStateHolder.syncAuthToken,
        syncStateHolder.syncAppStateEnabled,
        syncStateHolder.syncPlaylistsEnabled,
        syncStateHolder.syncProviderConfigurationEnabled
    ) { serverTarget, authToken, appStateEnabled, playlistsEnabled, providerConfigEnabled ->
        SyncInputPart(
            serverTarget = serverTarget,
            authToken = authToken,
            appStateEnabled = appStateEnabled,
            playlistsEnabled = playlistsEnabled,
            providerConfigEnabled = providerConfigEnabled
        )
    }

    private val syncInputState = combine(
        syncInputPart,
        syncStateHolder.syncSettingsEnabled,
        syncStateHolder.syncStatus
    ) { inputPart, settingsEnabled, syncStatusValue ->
        SyncInputs(
            serverTarget = inputPart.serverTarget,
            authToken = inputPart.authToken,
            appStateEnabled = inputPart.appStateEnabled,
            playlistsEnabled = inputPart.playlistsEnabled,
            providerConfigEnabled = inputPart.providerConfigEnabled,
            settingsEnabled = settingsEnabled,
            syncStatusValue = syncStatusValue
        )
    }

    private val jellyfinInputPart = combine(
        providerConnectionStateHolder.jellyfinUrlInput,
        providerConnectionStateHolder.jellyfinTokenInput,
        providerConnectionStateHolder.jellyfinPlaylistPageSizeInput
    ) { url, token, pageSize -> Triple(url, token, pageSize) }

    private val plexInputPart = combine(
        providerConnectionStateHolder.plexUrlInput,
        providerConnectionStateHolder.plexTokenInput,
        providerConnectionStateHolder.plexPlaylistPageSizeInput
    ) { url, token, pageSize -> Triple(url, token, pageSize) }

    private val providerInputPart = combine(
        jellyfinInputPart,
        plexInputPart,
        providerConnectionStateHolder.spotifyTokenInput
    ) { jelly, plex, spotifyToken ->
        ProviderInputPart(
            jellyUrl = jelly.first,
            jellyToken = jelly.second,
            jellyPageSize = jelly.third,
            plexUrl = plex.first,
            plexToken = plex.second,
            plexPageSize = plex.third,
            spotifyToken = spotifyToken
        )
    }

    private val providerInputState = combine(
        providerInputPart,
        syncInputState
    ) { providerPart, syncInputs ->
        ProviderSettingsInputs(
            providerPart.jellyUrl,
            providerPart.jellyToken,
            providerPart.jellyPageSize,
            providerPart.plexUrl,
            providerPart.plexToken,
            providerPart.plexPageSize,
            providerPart.spotifyToken,
            syncInputs.serverTarget,
            syncInputs.authToken,
            syncInputs.appStateEnabled,
            syncInputs.playlistsEnabled,
            syncInputs.providerConfigEnabled,
            syncInputs.settingsEnabled,
            syncInputs.syncStatusValue
        )
    }

    private val catalogPlaybackState = combine(
        providerStatuses,
        playbackQueueManager.status,
        playbackQueueManager.audioNormalizationSettings
    ) { providers, playback, audioNormalization ->
        Triple(providers, playback, audioNormalization)
    }

    private val catalogSearchState = combine(
        searchStateHolder.searchResults,
        searchStateHolder.searchPlaylistResults,
        providerPlaylistBrowsingStateHolder.providerPlaylists
    ) { search, searchPlaylists, playlists ->
        Triple(search, searchPlaylists, playlists)
    }

    private val catalogCoreState = combine(
        catalogPlaybackState,
        catalogSearchState
    ) { playbackState, searchState ->
        CatalogCoreUiState(
            providerStatuses = playbackState.first,
            playbackStatus = playbackState.second,
            audioNormalizationSettings = playbackState.third,
            searchResults = searchState.first,
            searchPlaylistResults = searchState.second,
            providerPlaylists = searchState.third
        )
    }

    private val providerPlaylistSummaryCoreState = combine(
        providerPlaylistBrowsingStateHolder.selectedProviderPlaylist,
        providerPlaylistBrowsingStateHolder.selectedProviderPlaylistTracks,
        providerPlaylistBrowsingStateHolder.selectedProviderPlaylistIsDistinct,
        providerPlaylistBrowsingStateHolder.selectedProviderPlaylistLoading,
        providerPlaylistBrowsingStateHolder.selectedProviderPlaylistError
    ) { playlist, tracks, isDistinct, loading, error ->
        ProviderPlaylistSummaryCoreUiState(
            selectedProviderPlaylist = playlist,
            selectedProviderPlaylistTracks = tracks,
            selectedProviderPlaylistIsDistinct = isDistinct,
            selectedProviderPlaylistLoading = loading,
            selectedProviderPlaylistError = error
        )
    }

    private val providerPlaylistSummaryState = combine(
        providerPlaylistSummaryCoreState,
        providerPlaylistBrowsingStateHolder.providerPlaylistRefreshInProgress,
        providerPlaylistBrowsingStateHolder.providerPlaylistRefreshStatus
    ) { coreState, refreshInProgress, refreshStatus ->
        ProviderPlaylistSummaryUiState(
            selectedProviderPlaylist = coreState.selectedProviderPlaylist,
            selectedProviderPlaylistTracks = coreState.selectedProviderPlaylistTracks,
            selectedProviderPlaylistIsDistinct = coreState.selectedProviderPlaylistIsDistinct,
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
                audioNormalizationSettings = catalogBase.audioNormalizationSettings,
                searchResults = catalogBase.searchResults,
                searchPlaylistResults = catalogBase.searchPlaylistResults,
                providerPlaylists = catalogBase.providerPlaylists,
                selectedProviderPlaylist = summaryState.selectedProviderPlaylist,
                selectedProviderPlaylistTracks = summaryState.selectedProviderPlaylistTracks,
                selectedProviderPlaylistIsDistinct = summaryState.selectedProviderPlaylistIsDistinct,
                selectedProviderPlaylistLoading = summaryState.selectedProviderPlaylistLoading,
                selectedProviderPlaylistError = summaryState.selectedProviderPlaylistError,
                providerPlaylistRefreshInProgress = summaryState.providerPlaylistRefreshInProgress,
                providerPlaylistRefreshStatus = summaryState.providerPlaylistRefreshStatus
            )
        },
        combine(
            combine(localCoreState, connectionState, providerInputState) { localCore, connectionStateVal, providerInputs ->
                Triple(localCore, connectionStateVal, providerInputs)
            },
            providerConnectionStateHolder.spotifyAuthLaunchUrl,
            providerConnectionStateHolder.jellyfinPageSizeSaved,
            providerConnectionStateHolder.plexPageSizeSaved
        ) { localAndConnection, spotifyLaunchUrl, jellyFinSaved, plexSaved ->
            val (localCore, connectionStateVal, providerInputs) = localAndConnection
            
            LocalUiState(
                customPlaylists = localCore.customPlaylists,
                activeCustomPlaylistTracks = localCore.activeCustomPlaylistTracks,
                selectedCustomPlaylistId = localCore.selectedCustomPlaylistId,
                selectedCustomUnionSources = localCore.selectedCustomUnionSources,
                customPlaylistRefreshInProgress = localCore.customPlaylistRefreshInProgress,
                customPlaylistRefreshStatus = localCore.customPlaylistRefreshStatus,
                stateTransferStatus = localCore.stateTransferStatus,
                providerConnectionFeedback = connectionStateVal.first,
                providerConnectionInProgress = connectionStateVal.second,
                jellyfinUrlInput = providerInputs.jellyfinUrl,
                jellyfinTokenInput = providerInputs.jellyfinToken,
                jellyfinPlaylistPageSizeInput = providerInputs.jellyfinPlaylistPageSize,
                jellyfinPageSizeSaved = jellyFinSaved,
                plexUrlInput = providerInputs.plexUrl,
                plexTokenInput = providerInputs.plexToken,
                plexPlaylistPageSizeInput = providerInputs.plexPlaylistPageSize,
                plexPageSizeSaved = plexSaved,
                spotifyTokenInput = providerInputs.spotifyToken,
                spotifyAuthLaunchUrl = spotifyLaunchUrl,
                syncServerTarget = providerInputs.syncServerTarget,
                syncAuthToken = providerInputs.syncAuthToken,
                syncAppStateEnabled = providerInputs.syncAppStateEnabled,
                syncPlaylistsEnabled = providerInputs.syncPlaylistsEnabled,
                syncProviderConfigurationEnabled = providerInputs.syncProviderConfigurationEnabled,
                syncSettingsEnabled = providerInputs.syncSettingsEnabled,
                syncStatus = providerInputs.syncStatus
            )
        }
    ) { startup, catalog, local ->
        val selectedCustomPlaylist = local.customPlaylists.firstOrNull { it.id == local.selectedCustomPlaylistId }
        val providerDuplicateGroups =
            if (catalog.selectedProviderPlaylistIsDistinct) {
                DistinctPlaylistUtils
                    .deduplicateTracks(catalog.selectedProviderPlaylistTracks)
                    .duplicateGroups
            } else {
                emptyList()
            }
        val isCustomDistinct = selectedCustomPlaylist?.isDistinct == true
        val customDuplicateGroups =
            if (isCustomDistinct) {
                DistinctPlaylistUtils
                    .deduplicateTracks(local.activeCustomPlaylistTracks)
                    .duplicateGroups
            } else {
                emptyList()
            }
        val playbackDisabledMessage =
            if (startup.startupInProgress) {
                null
            } else {
                playbackDisabledReason(
                    playbackStatus = catalog.playbackStatus,
                    profiles = catalog.providerStatuses
                )
            }
        MainUiState(
            startupMessage = startup.startupMessage,
            startupInProgress = startup.startupInProgress,
            startupCanRetry = startup.startupCanRetry,
            startupCanContinueWithoutProvider = startup.startupCanContinueWithoutProvider,
            startupWarnings = startup.startupWarnings,
            providerStatuses = catalog.providerStatuses,
            playbackStatus = catalog.playbackStatus,
            playbackDisabledMessage = playbackDisabledMessage,
            audioNormalizationEnabled = catalog.audioNormalizationSettings.enabled,
            audioNormalizationStrictMode = catalog.audioNormalizationSettings.strictMode,
            searchResults = catalog.searchResults,
            searchPlaylistResults = catalog.searchPlaylistResults,
            providerPlaylists = catalog.providerPlaylists,
            selectedProviderPlaylist = catalog.selectedProviderPlaylist,
            selectedProviderPlaylistTracks = catalog.selectedProviderPlaylistTracks,
            selectedProviderPlaylistIsDistinct = catalog.selectedProviderPlaylistIsDistinct,
            selectedProviderPlaylistDuplicateGroups = providerDuplicateGroups,
            selectedProviderPlaylistLoading = catalog.selectedProviderPlaylistLoading,
            selectedProviderPlaylistError = catalog.selectedProviderPlaylistError,
            providerPlaylistRefreshInProgress = catalog.providerPlaylistRefreshInProgress,
            providerPlaylistRefreshStatus = catalog.providerPlaylistRefreshStatus,
            customPlaylists = local.customPlaylists,
            activeCustomPlaylistTracks = local.activeCustomPlaylistTracks,
            selectedCustomPlaylistId = local.selectedCustomPlaylistId,
            selectedCustomPlaylistIsDistinct = selectedCustomPlaylist?.isDistinct ?: false,
            selectedCustomPlaylistDuplicateGroups = customDuplicateGroups,
            selectedCustomUnionSources = local.selectedCustomUnionSources,
            customPlaylistRefreshInProgress = local.customPlaylistRefreshInProgress,
            customPlaylistRefreshStatus = local.customPlaylistRefreshStatus,
            stateTransferStatus = local.stateTransferStatus,
            providerConnectionFeedback = local.providerConnectionFeedback,
            providerConnectionInProgress = local.providerConnectionInProgress,
            jellyfinUrlInput = local.jellyfinUrlInput,
            jellyfinTokenInput = local.jellyfinTokenInput,
            jellyfinPlaylistPageSizeInput = local.jellyfinPlaylistPageSizeInput,
            jellyfinPageSizeSaved = local.jellyfinPageSizeSaved,
            plexUrlInput = local.plexUrlInput,
            plexTokenInput = local.plexTokenInput,
            plexPlaylistPageSizeInput = local.plexPlaylistPageSizeInput,
            plexPageSizeSaved = local.plexPageSizeSaved,
            spotifyTokenInput = local.spotifyTokenInput,
            spotifyAuthLaunchUrl = local.spotifyAuthLaunchUrl,
            syncServerTarget = local.syncServerTarget,
            syncAuthToken = local.syncAuthToken,
            syncAppStateEnabled = local.syncAppStateEnabled,
            syncPlaylistsEnabled = local.syncPlaylistsEnabled,
            syncProviderConfigurationEnabled = local.syncProviderConfigurationEnabled,
            syncSettingsEnabled = local.syncSettingsEnabled,
            syncStatus = local.syncStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    init {
        providerConnectionStateHolder.loadSavedProviderInputs()
        restoreStartup()
        customPlaylistStateHolder.observeCustomPlaylists()
        syncStateHolder.startRealtimePlaybackSync()
        enforcePlaybackDisabledState()
    }

    fun updateSyncServerTarget(value: String) = syncStateHolder.updateSyncServerTarget(value)

    fun updateSyncAuthToken(value: String) = syncStateHolder.updateSyncAuthToken(value)

    fun updateSyncAppStateEnabled(value: Boolean) = syncStateHolder.updateSyncAppStateEnabled(value)

    fun updateSyncPlaylistsEnabled(value: Boolean) = syncStateHolder.updateSyncPlaylistsEnabled(value)

    fun updateSyncProviderConfigurationEnabled(value: Boolean) =
        syncStateHolder.updateSyncProviderConfigurationEnabled(value)

    fun updateSyncSettingsEnabled(value: Boolean) = syncStateHolder.updateSyncSettingsEnabled(value)

    fun pullSyncState(confirmPlaylistOverwrite: Boolean) = syncStateHolder.pullSyncState(confirmPlaylistOverwrite)

    fun updateJellyfinUrlInput(value: String) = providerConnectionStateHolder.updateJellyfinUrlInput(value)

    fun updateJellyfinTokenInput(value: String) = providerConnectionStateHolder.updateJellyfinTokenInput(value)

    fun updateJellyfinPlaylistPageSizeInput(value: String) =
        providerConnectionStateHolder.updateJellyfinPlaylistPageSizeInput(value)

    fun updatePlexUrlInput(value: String) = providerConnectionStateHolder.updatePlexUrlInput(value)

    fun updatePlexTokenInput(value: String) = providerConnectionStateHolder.updatePlexTokenInput(value)

    fun updatePlexPlaylistPageSizeInput(value: String) =
        providerConnectionStateHolder.updatePlexPlaylistPageSizeInput(value)

    fun connectJellyfin(url: String, apiKey: String) = providerConnectionStateHolder.connectJellyfin(url, apiKey)

    fun connectPlex(url: String, token: String) = providerConnectionStateHolder.connectPlex(url, token)

    fun beginSpotifyLink() = providerConnectionStateHolder.beginSpotifyLink()

    fun markSpotifyAuthLaunchHandled() = providerConnectionStateHolder.markSpotifyAuthLaunchHandled()

    fun completeSpotifyLink(redirectUri: String) = providerConnectionStateHolder.completeSpotifyLink(redirectUri)

    fun disconnect(sourceType: SourceType) = providerConnectionStateHolder.disconnect(sourceType)

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

    fun clearProviderCaches() {
        providerConnectionStateHolder.runProviderAction(
            startingFeedback = "Clearing provider cache...",
            action = {
                providerCatalogRepository.clearProviderPlaylistCacheData()
                runStartup(continueWithoutProviders = false)
            },
            onFailureFeedback = { throwable ->
                throwable.message?.takeIf { it.isNotBlank() } ?: "Failed to clear provider cache."
            },
            onSuccess = {
                providerConnectionStateHolder.providerConnectionFeedback.value = "Provider cache cleared. Fresh provider data loaded."
            }
        )
    }

    fun togglePlayPause() {
        val reason = playbackDisabledReason(
            playbackStatus = playbackQueueManager.status.value,
            profiles = providerStatuses.value
        )

        if (reason != null) {
            providerConnectionStateHolder.providerConnectionFeedback.value = reason
            if (playbackQueueManager.status.value.state == PlaybackStateType.PLAYING) {
                playbackQueueManager.pause()
            }
            return
        }

        playbackQueueManager.togglePlayPause()
    }
    fun next() = playbackQueueManager.next()
    fun previous() = playbackQueueManager.previous()
    fun addToQueue(track: Track) = playbackQueueManager.addNextInQueue(track)
    fun setShuffle(enabled: Boolean) = playbackQueueManager.setShuffle(enabled)
    fun setRepeatMode(mode: RepeatMode) = playbackQueueManager.setRepeatMode(mode)
    fun seekTo(positionMs: Long) = playbackQueueManager.seekTo(positionMs)
    fun setVolume(volume: Int) = playbackQueueManager.setVolume(volume)
    fun setAudioNormalizationEnabled(enabled: Boolean) {
        playbackQueueManager.setAudioNormalization(
            enabled = enabled,
            strictMode = uiState.value.audioNormalizationStrictMode
        )
    }

    fun setAudioNormalizationStrictMode(strictMode: Boolean) {
        playbackQueueManager.setAudioNormalization(
            enabled = uiState.value.audioNormalizationEnabled,
            strictMode = strictMode
        )
    }

    fun playFromQueue(index: Int) = playbackQueueManager.playFromIndex(index)
    fun playFromSearch(index: Int) = searchStateHolder.playFromSearch(index)
    fun search(query: String, sourceType: SourceType, searchType: SearchType) = searchStateHolder.search(query, sourceType, searchType)
    fun playPlaylistFromSearch(index: Int) = searchStateHolder.playPlaylistFromSearch(index)

    fun playPlaylist(sourceType: SourceType, playlistId: String) =
        providerPlaylistBrowsingStateHolder.playPlaylist(sourceType, playlistId)

    fun refreshProviderPlaylistData() = providerPlaylistBrowsingStateHolder.refreshProviderPlaylistData()

    fun openProviderPlaylistSummary(playlist: com.anyplayer.android.core.model.Playlist) =
        providerPlaylistBrowsingStateHolder.openProviderPlaylistSummary(playlist)

    fun closeProviderPlaylistSummary() = providerPlaylistBrowsingStateHolder.closeProviderPlaylistSummary()

    fun setSelectedProviderPlaylistDistinct(isDistinct: Boolean) =
        providerPlaylistBrowsingStateHolder.setSelectedProviderPlaylistDistinct(isDistinct)

    fun playSelectedProviderPlaylist() = providerPlaylistBrowsingStateHolder.playSelectedProviderPlaylist()

    fun createStandardPlaylist(name: String) = customPlaylistStateHolder.createStandardPlaylist(name)
    fun createUnionPlaylist(name: String) = customPlaylistStateHolder.createUnionPlaylist(name)
    fun deleteCustomPlaylist(playlistId: String) = customPlaylistStateHolder.deleteCustomPlaylist(playlistId)
    fun renameCustomPlaylist(playlistId: String, name: String) = customPlaylistStateHolder.renameCustomPlaylist(playlistId, name)
    fun selectCustomPlaylist(playlistId: String) = customPlaylistStateHolder.selectCustomPlaylist(playlistId)
    fun closeCustomPlaylistDetails() = customPlaylistStateHolder.closeCustomPlaylistDetails()
    fun playCustomPlaylist(playlistId: String) = customPlaylistStateHolder.playCustomPlaylist(playlistId)
    fun playFromCustomPlaylistTrack(playlistId: String, index: Int) = customPlaylistStateHolder.playFromCustomPlaylistTrack(playlistId, index)
    fun addSearchTrackToSelectedCustom(trackIndex: Int) = customPlaylistStateHolder.addSearchTrackToSelectedCustom(trackIndex)
    fun removeTrackFromSelectedCustom(playlistTrackIndex: Int) = customPlaylistStateHolder.removeTrackFromSelectedCustom(playlistTrackIndex)
    fun setSelectedCustomPlaylistDistinct(isDistinct: Boolean) = customPlaylistStateHolder.setSelectedCustomPlaylistDistinct(isDistinct)
    fun addProviderPlaylistSourceToSelectedUnion(sourcePlaylistId: String, sourceType: SourceType) =
        customPlaylistStateHolder.addProviderPlaylistSourceToSelectedUnion(sourcePlaylistId, sourceType)
    fun reorderSelectedCustomTracks(orderedTrackIds: List<String>) = customPlaylistStateHolder.reorderSelectedCustomTracks(orderedTrackIds)
    fun reorderSelectedUnionSources(orderedSourceIds: List<String>) = customPlaylistStateHolder.reorderSelectedUnionSources(orderedSourceIds)
    fun replaceSelectedUnionSources(sources: List<UnionPlaylistSource>) = customPlaylistStateHolder.replaceSelectedUnionSources(sources)
    fun materializeSelectedUnion() = customPlaylistStateHolder.materializeSelectedUnion()

    fun exportStateToUri(uri: Uri, mode: ExportMode, includePlayback: Boolean, passphrase: String?) =
        stateTransferStateHolder.exportStateToUri(uri, mode, includePlayback, passphrase)

    fun importStateFromUri(uri: Uri, policy: MergePolicy, passphrase: String?, dryRun: Boolean) =
        stateTransferStateHolder.importStateFromUri(uri, policy, passphrase, dryRun)

    fun importConfigFromUri(uri: Uri, policy: MergePolicy, dryRun: Boolean) =
        stateTransferStateHolder.importConfigFromUri(uri, policy, dryRun, onImported = providerConnectionStateHolder::loadSavedProviderInputs)

    private fun restoreStartup() {
        viewModelScope.launch {
            runStartup(continueWithoutProviders = false)
            pullSyncState(confirmPlaylistOverwrite = false)
        }
    }

    private fun enforcePlaybackDisabledState() {
        viewModelScope.launch {
            combine(playbackQueueManager.status, providerStatuses, startupInProgress) { playbackStatus, profiles, inProgress ->
                Triple(playbackStatus, profiles, inProgress)
            }.collectLatest { (playbackStatus, profiles, inProgress) ->
                // Don't enforce during startup: providerStatuses is empty until runStartup
                // completes, so evaluating here would spuriously block persisted-queue tracks.
                if (inProgress) {
                    lastAutoPausedTrackKey = null
                    return@collectLatest
                }

                val reason = playbackDisabledReason(playbackStatus, profiles)
                if (reason == null) {
                    lastAutoPausedTrackKey = null
                    return@collectLatest
                }

                val source = playbackStatus.currentTrack?.source ?: return@collectLatest
                val trackId = playbackStatus.currentTrack?.id ?: "none"
                val key = "${source.name}:$trackId"

                if (playbackStatus.state == PlaybackStateType.PLAYING && lastAutoPausedTrackKey != key) {
                    lastAutoPausedTrackKey = key
                    providerConnectionStateHolder.providerConnectionFeedback.value = reason
                    playbackQueueManager.pause()
                }
            }
        }
    }

    private fun playbackDisabledReason(
        playbackStatus: com.anyplayer.android.core.model.PlaybackStatus,
        profiles: List<ProviderConnectionProfile>
    ): String? {
        val source = playbackStatus.currentTrack?.source ?: return null
        if (source == SourceType.CUSTOM || source == SourceType.ALL) {
            return null
        }

        val connected = profiles.any { profile ->
            profile.source == source && profile.connected
        }

        if (connected) {
            return null
        }

        return "Playback disabled: ${source.name.lowercase()} is not configured/authenticated. Reconnect it in Settings. Next/Previous still works."
    }

    private fun prefetchPlaylistTracksInBackground(playlists: List<com.anyplayer.android.core.model.Playlist>) {
        if (playlists.isEmpty()) return
        trackPrefetchJob?.cancel()
        trackPrefetchJob = viewModelScope.launch {
            val semaphore = Semaphore(3)
            playlists
                .filter { it.source != SourceType.CUSTOM && it.source != SourceType.ALL }
                .forEach { playlist ->
                    launch {
                        semaphore.withPermit {
                            runCatching {
                                providerCatalogRepository.getPlaylistTracksWithCache(playlist.source, playlist.id)
                            }
                        }
                    }
                }
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
        providerPlaylistBrowsingStateHolder.applyStartupSnapshot(snapshot.providerPlaylists)
        startupWarnings.value = snapshot.warnings
        startupCanRetry.value = snapshot.warnings.isNotEmpty()
        startupCanContinueWithoutProvider.value = snapshot.warnings.isNotEmpty() && !continueWithoutProviders
        providerConnectionStateHolder.refreshSavedProviderInputs()
        startupInProgress.value = false
        prefetchPlaylistTracksInBackground(snapshot.providerPlaylists)
    }

    private data class ProviderSettingsInputs(
        val jellyfinUrl: String,
        val jellyfinToken: String,
        val jellyfinPlaylistPageSize: String,
        val plexUrl: String,
        val plexToken: String,
        val plexPlaylistPageSize: String,
        val spotifyToken: String,
        val syncServerTarget: String,
        val syncAuthToken: String,
        val syncAppStateEnabled: Boolean,
        val syncPlaylistsEnabled: Boolean,
        val syncProviderConfigurationEnabled: Boolean,
        val syncSettingsEnabled: Boolean,
        val syncStatus: String
    )

    private data class SyncInputs(
        val serverTarget: String,
        val authToken: String,
        val appStateEnabled: Boolean,
        val playlistsEnabled: Boolean,
        val providerConfigEnabled: Boolean,
        val settingsEnabled: Boolean,
        val syncStatusValue: String
    )
}
