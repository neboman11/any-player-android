package com.anyplayer.android.app

import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.DuplicateGroup
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.model.UnionPlaylistSource

internal data class StartupUiState(
    val startupMessage: String,
    val startupInProgress: Boolean,
    val startupCanRetry: Boolean,
    val startupCanContinueWithoutProvider: Boolean,
    val startupWarnings: List<String>
)

internal data class CatalogUiState(
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

internal data class ProviderPlaylistSummaryUiState(
    val selectedProviderPlaylist: com.anyplayer.android.core.model.Playlist?,
    val selectedProviderPlaylistTracks: List<Track>,
    val selectedProviderPlaylistIsDistinct: Boolean,
    val selectedProviderPlaylistLoading: Boolean,
    val selectedProviderPlaylistError: String?,
    val providerPlaylistRefreshInProgress: Boolean,
    val providerPlaylistRefreshStatus: String?
)

internal data class LocalUiState(
    val customPlaylists: List<com.anyplayer.android.core.model.CustomPlaylist>,
    val activeCustomPlaylistTracks: List<Track>,
    val selectedCustomPlaylistId: String?,
    val selectedCustomUnionSources: List<UnionPlaylistSource>,
    val customPlaylistRefreshInProgress: Boolean,
    val customPlaylistRefreshStatus: String?,
    val stateTransferStatus: String,
    val providerConnectionFeedback: String?,
    val providerConnectionInProgress: Boolean,
    val jellyfinUrlInput: String,
    val jellyfinTokenInput: String,
    val jellyfinPlaylistPageSizeInput: String,
    val jellyfinPageSizeSaved: Boolean,
    val plexUrlInput: String,
    val plexTokenInput: String,
    val plexPlaylistPageSizeInput: String,
    val plexPageSizeSaved: Boolean,
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
    val customPlaylistRefreshInProgress: Boolean = false,
    val customPlaylistRefreshStatus: String? = null,
    val stateTransferStatus: String = "State transfer idle",
    val providerConnectionFeedback: String? = null,
    val providerConnectionInProgress: Boolean = false,
    val jellyfinUrlInput: String = "",
    val jellyfinTokenInput: String = "",
    val jellyfinPlaylistPageSizeInput: String = "300",
    val jellyfinPageSizeSaved: Boolean = false,
    val plexUrlInput: String = "",
    val plexTokenInput: String = "",
    val plexPlaylistPageSizeInput: String = "300",
    val plexPageSizeSaved: Boolean = false,
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
