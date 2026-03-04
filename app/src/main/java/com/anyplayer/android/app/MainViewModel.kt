package com.anyplayer.android.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.DuplicateGroup
import com.anyplayer.android.core.model.PlaylistType
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.core.storage.repository.PlaylistStorageRepository
import com.anyplayer.android.feature.auth.AuthRequest
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.anyplayer.android.feature.playlists.CustomPlaylistEngine
import com.anyplayer.android.feature.playlists.DistinctPlaylistUtils
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import com.anyplayer.android.feature.search.SearchType
import com.anyplayer.android.feature.startup.StartupResilienceManager
import com.anyplayer.android.feature.state.transfer.ExportMode
import com.anyplayer.android.feature.state.transfer.ExportOptions
import com.anyplayer.android.feature.state.transfer.ImportOptions
import com.anyplayer.android.feature.state.transfer.ImportSummary
import com.anyplayer.android.feature.state.transfer.MergePolicy
import com.anyplayer.android.feature.state.transfer.ConfigFileImporter
import com.anyplayer.android.feature.state.transfer.StateTransferManager
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import com.anyplayer.android.feature.sync.SyncSnapshotClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    private val syncJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

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
    private val selectedProviderPlaylistIsDistinct = MutableStateFlow(false)
    private val selectedProviderPlaylistLoading = MutableStateFlow(false)
    private val selectedProviderPlaylistError = MutableStateFlow<String?>(null)
    private val providerPlaylistRefreshInProgress = MutableStateFlow(false)
    private val providerPlaylistRefreshStatus = MutableStateFlow<String?>(null)
    private val customPlaylists = MutableStateFlow<List<com.anyplayer.android.core.model.CustomPlaylist>>(emptyList())
    private val activeCustomPlaylistTracks = MutableStateFlow<List<Track>>(emptyList())
    private val selectedCustomPlaylistId = MutableStateFlow<String?>(null)
    private val selectedCustomUnionSources = MutableStateFlow<List<UnionPlaylistSource>>(emptyList())
    private val stateTransferStatus = MutableStateFlow("State transfer idle")
    private val providerConnectionFeedback = MutableStateFlow<String?>(null)
    private val providerConnectionInProgress = MutableStateFlow(false)
    private val jellyfinUrlInput = MutableStateFlow("")
    private val jellyfinTokenInput = MutableStateFlow("")
    private val plexUrlInput = MutableStateFlow("")
    private val plexTokenInput = MutableStateFlow("")
    private val spotifyTokenInput = MutableStateFlow("")
    private val spotifyAuthLaunchUrl = MutableStateFlow<String?>(null)
    private val syncServerTarget = MutableStateFlow("")
    private val syncAuthToken = MutableStateFlow("")
    private val syncAppStateEnabled = MutableStateFlow(true)
    private val syncPlaylistsEnabled = MutableStateFlow(true)
    private val syncProviderConfigurationEnabled = MutableStateFlow(true)
    private val syncSettingsEnabled = MutableStateFlow(true)
    private val syncStatus = MutableStateFlow("Sync idle")
    private var lastSyncVersion: Long = 0
    private var suppressSyncPushUntilMs: Long = 0
    private var lastPushedSignature: String = ""
    private var lastPushAtMs: Long = 0

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
        val audioNormalizationSettings: AudioNormalizationSettings,
        val searchResults: List<Track>,
        val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist>,
        val providerPlaylists: List<com.anyplayer.android.core.model.Playlist>,
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistIsDistinct: Boolean,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?,
        val providerPlaylistRefreshInProgress: Boolean,
        val providerPlaylistRefreshStatus: String?
    )

    private data class CatalogCoreUiState(
        val providerStatuses: List<ProviderConnectionProfile>,
        val playbackStatus: com.anyplayer.android.core.model.PlaybackStatus,
        val audioNormalizationSettings: AudioNormalizationSettings,
        val searchResults: List<Track>,
        val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist>,
        val providerPlaylists: List<com.anyplayer.android.core.model.Playlist>
    )

    private data class ProviderPlaylistSummaryUiState(
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistIsDistinct: Boolean,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?,
        val providerPlaylistRefreshInProgress: Boolean,
        val providerPlaylistRefreshStatus: String?
    )

    private data class ProviderPlaylistSummaryCoreUiState(
        val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
        val selectedProviderPlaylistTracks: List<Track>,
        val selectedProviderPlaylistIsDistinct: Boolean,
        val selectedProviderPlaylistLoading: Boolean,
        val selectedProviderPlaylistError: String?
    )

    private data class LocalUiState(
        val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist>,
        val activeCustomPlaylistTracks: List<Track>,
        val selectedCustomPlaylistId: String?,
        val selectedCustomUnionSources: List<UnionPlaylistSource>,
        val stateTransferStatus: String,
        val providerConnectionFeedback: String?,
        val providerConnectionInProgress: Boolean,
        val jellyfinUrlInput: String,
        val jellyfinTokenInput: String,
        val plexUrlInput: String,
        val plexTokenInput: String,
        val spotifyTokenInput: String,
        val spotifyAuthLaunchUrl: String?,
        val syncServerTarget: String,
        val syncAuthToken: String,
        val syncAppStateEnabled: Boolean,
        val syncPlaylistsEnabled: Boolean,
        val syncProviderConfigurationEnabled: Boolean,
        val syncSettingsEnabled: Boolean,
        val syncStatus: String
    )

    private data class LocalCoreUiState(
        val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist>,
        val activeCustomPlaylistTracks: List<Track>,
        val selectedCustomPlaylistId: String?,
        val selectedCustomUnionSources: List<UnionPlaylistSource>,
        val stateTransferStatus: String
    )

    private val localCoreState = combine(
        customPlaylists,
        activeCustomPlaylistTracks,
        selectedCustomPlaylistId,
        selectedCustomUnionSources,
        stateTransferStatus
    ) { localPlaylists, localTracks, selectedLocalId, localUnionSources, transferStatus ->
        LocalCoreUiState(
            customPlaylists = localPlaylists,
            activeCustomPlaylistTracks = localTracks,
            selectedCustomPlaylistId = selectedLocalId,
            selectedCustomUnionSources = localUnionSources,
            stateTransferStatus = transferStatus
        )
    }

    private val connectionState = combine(providerConnectionFeedback, providerConnectionInProgress) { feedback, inProgress ->
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
        val plexUrl: String,
        val plexToken: String,
        val spotifyToken: String
    )

    private val syncInputPart = combine(
        syncServerTarget,
        syncAuthToken,
        syncAppStateEnabled,
        syncPlaylistsEnabled,
        syncProviderConfigurationEnabled
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
        syncSettingsEnabled,
        syncStatus
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

    private val providerInputPart = combine(
        jellyfinUrlInput,
        jellyfinTokenInput,
        plexUrlInput,
        plexTokenInput,
        spotifyTokenInput
    ) { jellyUrl, jellyToken, plexUrl, plexToken, spotifyToken ->
        ProviderInputPart(
            jellyUrl = jellyUrl,
            jellyToken = jellyToken,
            plexUrl = plexUrl,
            plexToken = plexToken,
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
            providerPart.plexUrl,
            providerPart.plexToken,
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
        searchResults,
        searchPlaylistResults,
        providerPlaylists
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
        selectedProviderPlaylist,
        selectedProviderPlaylistTracks,
        selectedProviderPlaylistIsDistinct,
        selectedProviderPlaylistLoading,
        selectedProviderPlaylistError
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
        providerPlaylistRefreshInProgress,
        providerPlaylistRefreshStatus
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
        combine(localCoreState, connectionState, providerInputState, spotifyAuthLaunchUrl) { localCore, connectionState, providerInputs, spotifyLaunchUrl ->
            LocalUiState(
                customPlaylists = localCore.customPlaylists,
                activeCustomPlaylistTracks = localCore.activeCustomPlaylistTracks,
                selectedCustomPlaylistId = localCore.selectedCustomPlaylistId,
                selectedCustomUnionSources = localCore.selectedCustomUnionSources,
                stateTransferStatus = localCore.stateTransferStatus,
                providerConnectionFeedback = connectionState.first,
                providerConnectionInProgress = connectionState.second,
                jellyfinUrlInput = providerInputs.jellyfinUrl,
                jellyfinTokenInput = providerInputs.jellyfinToken,
                plexUrlInput = providerInputs.plexUrl,
                plexTokenInput = providerInputs.plexToken,
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
        val providerDuplicateGroups = DistinctPlaylistUtils
            .deduplicateTracks(catalog.selectedProviderPlaylistTracks)
            .duplicateGroups
        val customDuplicateGroups = DistinctPlaylistUtils
            .deduplicateTracks(local.activeCustomPlaylistTracks)
            .duplicateGroups
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
            stateTransferStatus = local.stateTransferStatus,
            providerConnectionFeedback = local.providerConnectionFeedback,
            providerConnectionInProgress = local.providerConnectionInProgress,
            jellyfinUrlInput = local.jellyfinUrlInput,
            jellyfinTokenInput = local.jellyfinTokenInput,
            plexUrlInput = local.plexUrlInput,
            plexTokenInput = local.plexTokenInput,
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
        loadSyncPreferences()
        loadSavedProviderInputs()
        restoreStartup()
        observeCustomPlaylists()
        startRealtimePlaybackSync()
        enforcePlaybackDisabledState()
    }

    fun updateSyncServerTarget(value: String) {
        syncServerTarget.value = value
        persistSyncPreferences()
    }

    fun updateSyncAuthToken(value: String) {
        syncAuthToken.value = value
        persistSyncPreferences()
    }

    fun updateSyncAppStateEnabled(value: Boolean) {
        syncAppStateEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncPlaylistsEnabled(value: Boolean) {
        syncPlaylistsEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncProviderConfigurationEnabled(value: Boolean) {
        syncProviderConfigurationEnabled.value = value
        persistSyncPreferences()
    }

    fun updateSyncSettingsEnabled(value: Boolean) {
        syncSettingsEnabled.value = value
        persistSyncPreferences()
    }

    fun pullSyncState(confirmPlaylistOverwrite: Boolean) {
        viewModelScope.launch {
            val preferences = currentSyncPreferences()
            if (preferences.serverTarget.isBlank()) {
                syncStatus.value = "Sync server target is not set."
                return@launch
            }

            val snapshot = syncSnapshotClient.fetchSnapshot(preferences.serverTarget)
            if (snapshot == null) {
                syncStatus.value = "Sync server unavailable. Continued with local state."
                return@launch
            }

            var applied = 0

            if (preferences.syncSettings) {
                applySettingsDomain(snapshot)
                applied += 1
            }

            if (preferences.syncAppState) {
                applyAppStateDomain(snapshot)
                applied += 1
            }

            if (preferences.syncPlaylists || preferences.syncProviderConfiguration) {
                val imported = applyConfigDomains(
                    snapshot = snapshot,
                    includePlaylists = preferences.syncPlaylists,
                    includeProviderConfiguration = preferences.syncProviderConfiguration,
                    confirmPlaylistOverwrite = confirmPlaylistOverwrite
                )
                if (imported) {
                    applied += 1
                }
            }

            if (applied == 0) {
                syncStatus.value = "Sync completed with no selected domains."
            } else {
                syncStatus.value = "Sync pull complete."
            }

            loadSavedProviderInputsInternal()
            runStartup(continueWithoutProviders = false)
        }
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
                providerCatalogRepository.clearProviderPlaylistCacheData()
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
                providerCatalogRepository.clearProviderPlaylistCacheData()
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
                providerCatalogRepository.clearProviderPlaylistCacheData()
                runStartup(continueWithoutProviders = false)
                providerConnectionFeedback.value = "Spotify connected successfully."
            }
            providerConnectionInProgress.value = false
        }
    }

    fun disconnect(sourceType: SourceType) {
        viewModelScope.launch {
            authRepository.disconnect(sourceType)
            providerCatalogRepository.clearProviderPlaylistCacheData()
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

    fun clearProviderCaches() {
        viewModelScope.launch {
            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = "Clearing provider cache..."

            runCatching {
                providerCatalogRepository.clearProviderPlaylistCacheData()
                runStartup(continueWithoutProviders = false)
            }.onSuccess {
                providerConnectionFeedback.value = "Provider cache cleared. Fresh provider data loaded."
            }.onFailure { throwable ->
                providerConnectionFeedback.value = throwable.message?.takeIf { it.isNotBlank() }
                    ?: "Failed to clear provider cache."
            }

            providerConnectionInProgress.value = false
        }
    }

    fun togglePlayPause() {
        val reason = playbackDisabledReason(
            playbackStatus = playbackQueueManager.status.value,
            profiles = providerStatuses.value
        )

        if (reason != null) {
            providerConnectionFeedback.value = reason
            if (playbackQueueManager.status.value.state == PlaybackStateType.PLAYING) {
                playbackQueueManager.pause()
            }
            return
        }

        playbackQueueManager.togglePlayPause()
    }
    fun next() = playbackQueueManager.next()
    fun previous() = playbackQueueManager.previous()
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
            val queue = buildProviderQueueDistinct(sourceType, playlistId)
            playbackQueueManager.setQueue(queue, startIndex = 0, autoPlay = true)
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
        selectedProviderPlaylistIsDistinct.value = false
        selectedProviderPlaylistLoading.value = true
        selectedProviderPlaylistError.value = null

        viewModelScope.launch {
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
        selectedProviderPlaylistIsDistinct.value = false
        selectedProviderPlaylistLoading.value = false
        selectedProviderPlaylistError.value = null
    }

    fun setSelectedProviderPlaylistDistinct(isDistinct: Boolean) {
        val playlist = selectedProviderPlaylist.value ?: return
        viewModelScope.launch {
            playlistStorageRepository.setProviderPlaylistIsDistinct(
                source = playlist.source.name,
                playlistId = playlist.id,
                isDistinct = isDistinct
            )
            val persistedValue = playlistStorageRepository.getProviderPlaylistIsDistinct(
                source = playlist.source.name,
                playlistId = playlist.id
            )
            if (
                selectedProviderPlaylist.value?.id == playlist.id &&
                selectedProviderPlaylist.value?.source == playlist.source
            ) {
                selectedProviderPlaylistIsDistinct.value = persistedValue
            }
        }
    }

    fun playSelectedProviderPlaylist() {
        val playlist = selectedProviderPlaylist.value ?: return
        viewModelScope.launch {
            val cachedTracks = selectedProviderPlaylistTracks.value
            val rawTracks = if (cachedTracks.isNotEmpty()) {
                cachedTracks
            } else {
                providerCatalogRepository.getPlaylistTracksWithCache(playlist.source, playlist.id)
            }
            val isDistinct = playlistStorageRepository.getProviderPlaylistIsDistinct(
                playlist.source.name, playlist.id
            )
            val queue = if (isDistinct) {
                DistinctPlaylistUtils.deduplicateTracks(rawTracks).tracks
            } else {
                rawTracks
            }
            playbackQueueManager.setQueue(queue, startIndex = 0, autoPlay = true)
        }
    }

    /**
     * Fetches provider playlist tracks and applies the distinct dedup gate if enabled.
     *
     * Shared by [playPlaylist] and [playSelectedProviderPlaylist] so both provider entry
     * points cannot drift in distinct behavior.
     */
    private suspend fun buildProviderQueueDistinct(sourceType: SourceType, playlistId: String): List<Track> {
        val tracks = providerCatalogRepository.getPlaylistTracksWithCache(sourceType, playlistId)
        val isDistinct = playlistStorageRepository.getProviderPlaylistIsDistinct(sourceType.name, playlistId)
        return if (isDistinct) {
            DistinctPlaylistUtils.deduplicateTracks(tracks).tracks
        } else {
            tracks
        }
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
                selectedCustomUnionSources.value = emptyList()
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
            val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId }
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
            selectedCustomUnionSources.value = if (selectedPlaylist?.playlistType == PlaylistType.UNION) {
                customPlaylistEngine.getUnionSources(playlistId)
            } else {
                emptyList()
            }
        }
    }

    fun closeCustomPlaylistDetails() {
        selectedCustomPlaylistId.value = null
        activeCustomPlaylistTracks.value = emptyList()
        selectedCustomUnionSources.value = emptyList()
    }

    fun playCustomPlaylist(playlistId: String) {
        viewModelScope.launch {
            val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId }
            customPlaylistEngine.playPlaylist(playlistId)
            activeCustomPlaylistTracks.value = customPlaylistEngine.getTracksForPlaylist(playlistId)
            selectedCustomPlaylistId.value = playlistId
            selectedCustomUnionSources.value = if (selectedPlaylist?.playlistType == PlaylistType.UNION) {
                customPlaylistEngine.getUnionSources(playlistId)
            } else {
                emptyList()
            }
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

    fun setSelectedCustomPlaylistDistinct(isDistinct: Boolean) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        val selectedPlaylist = customPlaylists.value.firstOrNull { it.id == playlistId } ?: return
        viewModelScope.launch {
            playlistStorageRepository.upsertCustomPlaylists(
                listOf(
                    selectedPlaylist.copy(
                        isDistinct = isDistinct,
                        updatedAt = Instant.now().toString()
                    )
                )
            )

            // Refresh local selected state immediately so UI toggles and playback actions stay in sync.
            playlistStorageRepository.getCustomPlaylistById(playlistId)?.let { refreshedPlaylist ->
                customPlaylists.value = customPlaylists.value.map { playlist ->
                    if (playlist.id == playlistId) refreshedPlaylist else playlist
                }
            }
        }
    }

    fun addProviderPlaylistSourceToSelectedUnion(sourcePlaylistId: String, sourceType: SourceType) {
        val playlistId = selectedCustomPlaylistId.value ?: return
        viewModelScope.launch {
            val normalizedSourcePlaylistId = if (sourceType == SourceType.SPOTIFY) {
                sourcePlaylistId
                    .substringAfter("spotify:playlist:", sourcePlaylistId)
                    .let { value ->
                        value.substringAfter("/playlist/", value)
                            .substringBefore('?')
                            .substringBefore('/')
                    }
            } else {
                sourcePlaylistId
            }
            customPlaylistEngine.addUnionSource(
                unionPlaylistId = playlistId,
                sourceType = sourceType,
                sourcePlaylistId = normalizedSourcePlaylistId
            )
            selectedCustomUnionSources.value = customPlaylistEngine.getUnionSources(playlistId)
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
            selectedCustomUnionSources.value = customPlaylistEngine.getUnionSources(playlistId)
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

    fun exportStateToUri(uri: Uri, mode: ExportMode, includePlayback: Boolean, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?.use { stream ->
                            stateTransferManager.exportToStream(
                                stream = stream,
                                options = ExportOptions(
                                    mode = mode,
                                    includePlaybackState = includePlayback,
                                    passphrase = passphrase
                                ),
                                playbackStatus = uiState.value.playbackStatus
                            )
                        } ?: error("Could not open output stream for export")
                }
                "Export complete"
            }.onSuccess { stateTransferStatus.value = it }
                .onFailure { stateTransferStatus.value = "Export failed: ${it.message}" }
        }
    }

    fun importStateFromUri(uri: Uri, policy: MergePolicy, passphrase: String?, dryRun: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { stream ->
                            stateTransferManager.importFromStream(
                                stream = stream,
                                options = ImportOptions(
                                    mergePolicy = policy,
                                    passphrase = passphrase,
                                    dryRun = dryRun
                                )
                            )
                        } ?: error("Could not open input stream for import")
                }
            }.onSuccess { summary ->
                stateTransferStatus.value = formatSummary(if (dryRun) "Dry run" else "Import", summary)
            }.onFailure {
                stateTransferStatus.value = "${if (dryRun) "Dry run" else "Import"} failed: ${it.message}"
            }
        }
    }

    fun importConfigFromUri(uri: Uri, policy: MergePolicy, dryRun: Boolean) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use { stream ->
                            configFileImporter.importFromStream(
                                stream = stream,
                                mergePolicy = policy,
                                dryRun = dryRun
                            )
                        } ?: error("Could not open input stream for config import")
                }
            }.onSuccess { summary ->
                val prefix = if (dryRun) "Config dry run" else "Config import"
                stateTransferStatus.value = formatSummary(prefix, summary)
                if (!dryRun) loadSavedProviderInputs()
            }.onFailure {
                stateTransferStatus.value = "Config import failed: ${it.message}"
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
            pullSyncState(confirmPlaylistOverwrite = false)
        }
    }

    private fun startRealtimePlaybackSync() {
        viewModelScope.launch {
            combine(syncServerTarget, syncAppStateEnabled) { serverTarget, appStateEnabled ->
                Pair(serverTarget.trim(), appStateEnabled)
            }.collectLatest { (serverTarget, appStateEnabled) ->
                if (!appStateEnabled || serverTarget.isBlank()) {
                    return@collectLatest
                }

                val clientId = syncSnapshotClient.getClientId()

                coroutineScope {
                    val pushJob = launch {
                        playbackQueueManager.status.collect { status ->
                            if (System.currentTimeMillis() < suppressSyncPushUntilMs) {
                                return@collect
                            }

                            val payload = syncSnapshotClient.payloadFromPlayback(status)
                            val positionBucket = payload.position / 5000L
                            val signature = buildString {
                                append(payload.state)
                                append('|')
                                append(payload.current_track?.source?.name ?: "none")
                                append(':')
                                append(payload.current_track?.id ?: "none")
                                append('|')
                                append(payload.shuffle)
                                append('|')
                                append(payload.repeat_mode)
                                append('|')
                                append(payload.volume)
                                append('|')
                                append(positionBucket)
                            }

                            val now = System.currentTimeMillis()
                            if (signature == lastPushedSignature && now - lastPushAtMs < 15_000L) {
                                return@collect
                            }

                            val pushed = runCatching {
                                syncSnapshotClient.pushAppState(serverTarget, payload)
                            }.getOrDefault(false)

                            if (pushed) {
                                lastPushedSignature = signature
                                lastPushAtMs = now
                            }
                        }
                    }

                    val wsJob = launch {
                        while (true) {
                            val result = runCatching {
                                syncSnapshotClient.observeStateUpdates(serverTarget).collect { event ->
                                    if (event.event_type != "state_updated") {
                                        return@collect
                                    }
                                    if (event.namespace != "app_state") {
                                        return@collect
                                    }
                                    if (!event.source_client_id.isNullOrBlank() && event.source_client_id == clientId) {
                                        return@collect
                                    }
                                    if (event.version != null && event.version <= lastSyncVersion) {
                                        return@collect
                                    }

                                    val snapshot = syncSnapshotClient.fetchSnapshotSince(serverTarget, max(0L, lastSyncVersion))
                                        ?: return@collect

                                    suppressSyncPushUntilMs = System.currentTimeMillis() + 3500L
                                    applyAppStateDomain(snapshot)
                                    val nextVersion = snapshot["version"]?.jsonPrimitive?.longOrNull ?: lastSyncVersion
                                    lastSyncVersion = max(lastSyncVersion, nextVersion)
                                }
                            }

                            if (result.isSuccess) {
                                delay(1500L)
                            } else {
                                delay(2500L)
                            }
                        }
                    }
                }
            }
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
                    providerConnectionFeedback.value = reason
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

    private fun loadSyncPreferences() {
        val value = syncPreferencesStore.read()
        syncServerTarget.value = value.serverTarget
        syncAuthToken.value = value.authToken
        syncAppStateEnabled.value = value.syncAppState
        syncPlaylistsEnabled.value = value.syncPlaylists
        syncProviderConfigurationEnabled.value = value.syncProviderConfiguration
        syncSettingsEnabled.value = value.syncSettings
    }

    private fun persistSyncPreferences() {
        syncPreferencesStore.save(currentSyncPreferences())
    }

    private fun currentSyncPreferences(): SyncPreferences = SyncPreferences(
        serverTarget = syncServerTarget.value.trim(),
        authToken = syncAuthToken.value.trim(),
        syncAppState = syncAppStateEnabled.value,
        syncPlaylists = syncPlaylistsEnabled.value,
        syncProviderConfiguration = syncProviderConfigurationEnabled.value,
        syncSettings = syncSettingsEnabled.value
    )

    private suspend fun applyConfigDomains(
        snapshot: JsonObject,
        includePlaylists: Boolean,
        includeProviderConfiguration: Boolean,
        confirmPlaylistOverwrite: Boolean
    ): Boolean {
        val remotePlaylists = (snapshot["playlists"] as? JsonArray)
        val remotePlaylistCount = remotePlaylists?.size ?: 0
        val localPlaylistCount = customPlaylists.value.size

        if (includePlaylists && localPlaylistCount > 0 && !confirmPlaylistOverwrite) {
            syncStatus.value = "Sync cancelled: playlist overwrite requires confirmation."
            return false
        }

        val providerConfiguration = if (includeProviderConfiguration) {
            snapshot["provider_configuration"]
        } else {
            null
        }

        val configPayload = JsonObject(
            mapOf(
                "export_version" to JsonPrimitive(1),
                "provider_configs" to (providerConfiguration ?: JsonObject(emptyMap())),
                "custom_playlists" to if (includePlaylists) {
                    snapshot["playlists"] ?: JsonArray(emptyList())
                } else {
                    JsonArray(emptyList())
                }
            )
        )

        val summary = withContext(Dispatchers.IO) {
            configFileImporter.importFromStream(
                stream = ByteArrayInputStream(configPayload.toString().toByteArray()),
                mergePolicy = if (includePlaylists) MergePolicy.REPLACE_ALL else MergePolicy.MERGE_KEEP_LOCAL,
                dryRun = false
            )
        }

        stateTransferStatus.value = formatSummary("Sync import", summary)
        return includePlaylists || includeProviderConfiguration
    }

    private fun applySettingsDomain(snapshot: JsonObject) {
        val settings = snapshot["settings"]?.asObjectOrNull() ?: return
        val enabled = settings.boolean("audio_normalization_enabled")
            ?: settings.boolean("audioNormalizationEnabled")
        val strictMode = settings.boolean("audio_normalization_strict_mode")
            ?: settings.boolean("audioNormalizationStrictMode")

        if (enabled != null || strictMode != null) {
            playbackQueueManager.setAudioNormalization(
                enabled = enabled ?: uiState.value.audioNormalizationEnabled,
                strictMode = strictMode ?: uiState.value.audioNormalizationStrictMode
            )
        }
    }

    private fun applyAppStateDomain(snapshot: JsonObject) {
        val appState = snapshot["app_state"]?.asObjectOrNull() ?: return
        val localState = playbackQueueManager.status.value

        appState.int("volume")?.let { volume ->
            playbackQueueManager.setVolume(volume)
        }

        appState.boolean("shuffle")?.let { shuffle ->
            playbackQueueManager.setShuffle(shuffle)
        }

        appState.string("repeat_mode")?.let { repeatMode ->
            val mode = when (repeatMode.lowercase()) {
                "one" -> RepeatMode.ONE
                "all" -> RepeatMode.ALL
                else -> RepeatMode.OFF
            }
            playbackQueueManager.setRepeatMode(mode)
        }

        val remoteCurrentTrack = appState.track("current_track")
        val remoteQueue = appState.trackList("queue")
        if (remoteCurrentTrack != null) {
            val sameCurrentTrack = localState.currentTrack?.id == remoteCurrentTrack.id &&
                localState.currentTrack.source == remoteCurrentTrack.source
            if (!sameCurrentTrack) {
                playbackQueueManager.setQueue(listOf(remoteCurrentTrack) + remoteQueue, startIndex = 0, autoPlay = false)
            }
        }

        appState.long("position")?.let { positionMs ->
            if (abs(localState.position - positionMs) > 2500L) {
                playbackQueueManager.seekTo(positionMs)
            }
        }

        appState.string("state")?.lowercase()?.let { state ->
            when (state) {
                "playing" -> playbackQueueManager.play()
                "paused" -> playbackQueueManager.pause()
                "stopped" -> playbackQueueManager.pause()
            }
        }
    }

    private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.doubleOrNull?.toInt()
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private fun JsonObject.track(key: String): Track? {
        val element = this[key] ?: return null
        return runCatching {
            syncJson.decodeFromJsonElement(Track.serializer(), element)
        }.getOrNull()
    }

    private fun JsonObject.trackList(key: String): List<Track> {
        val raw = this[key] as? JsonArray ?: return emptyList()
        return raw.mapNotNull { element ->
            runCatching {
                syncJson.decodeFromJsonElement(Track.serializer(), element)
            }.getOrNull()
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

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
    val playbackDisabledMessage: String? = null,
    val audioNormalizationEnabled: Boolean = false,
    val audioNormalizationStrictMode: Boolean = false,
    val searchResults: List<Track> = emptyList(),
    val searchPlaylistResults: List<com.anyplayer.android.core.model.Playlist> = emptyList(),
    val providerPlaylists: List<com.anyplayer.android.core.model.Playlist> = emptyList(),
    val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist? = null,
    val selectedProviderPlaylistTracks: List<Track> = emptyList(),
    val selectedProviderPlaylistIsDistinct: Boolean = false,
    val selectedProviderPlaylistDuplicateGroups: List<DuplicateGroup> = emptyList(),
    val selectedProviderPlaylistLoading: Boolean = false,
    val selectedProviderPlaylistError: String? = null,
    val providerPlaylistRefreshInProgress: Boolean = false,
    val providerPlaylistRefreshStatus: String? = null,
    val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist> = emptyList(),
    val activeCustomPlaylistTracks: List<Track> = emptyList(),
    val selectedCustomPlaylistId: String? = null,
    val selectedCustomPlaylistIsDistinct: Boolean = false,
    val selectedCustomPlaylistDuplicateGroups: List<DuplicateGroup> = emptyList(),
    val selectedCustomUnionSources: List<UnionPlaylistSource> = emptyList(),
    val stateTransferStatus: String = "State transfer idle",
    val providerConnectionFeedback: String? = null,
    val providerConnectionInProgress: Boolean = false,
    val jellyfinUrlInput: String = "",
    val jellyfinTokenInput: String = "",
    val plexUrlInput: String = "",
    val plexTokenInput: String = "",
    val spotifyTokenInput: String = "",
    val spotifyAuthLaunchUrl: String? = null,
    val syncServerTarget: String = "",
    val syncAuthToken: String = "",
    val syncAppStateEnabled: Boolean = true,
    val syncPlaylistsEnabled: Boolean = true,
    val syncProviderConfigurationEnabled: Boolean = true,
    val syncSettingsEnabled: Boolean = true,
    val syncStatus: String = "Sync idle"
)
