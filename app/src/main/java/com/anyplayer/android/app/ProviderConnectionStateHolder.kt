package com.anyplayer.android.app

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.auth.AuthRequest
import com.anyplayer.android.feature.auth.PROVIDER_DEFAULT_PAGE_SIZE
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.spotify.SpotifyClientIds
import com.anyplayer.android.feature.providers.ProviderCatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ProviderConnectionStateHolder"

/**
 * Owns MainViewModel's provider-connection inputs (Jellyfin/Plex/Spotify) and the
 * connect/disconnect flows. [onProviderChanged] lets MainViewModel re-run its own
 * startup restore after a successful connect/disconnect, since that logic is shared
 * with app-launch startup and isn't part of this holder's scope.
 */
internal class ProviderConnectionStateHolder(
    private val viewModelScope: CoroutineScope,
    private val authRepository: ProviderAuthRepository,
    private val providerCatalogRepository: ProviderCatalogRepository,
    private val currentProviderPlaylistCount: () -> Int,
    private val onProviderChanged: suspend () -> Unit
) {
    val providerConnectionFeedback = MutableStateFlow<String?>(null)
    val providerConnectionInProgress = MutableStateFlow(false)
    val jellyfinUrlInput = MutableStateFlow("")
    val jellyfinTokenInput = MutableStateFlow("")
    val jellyfinPlaylistPageSizeInput = MutableStateFlow("300")
    val jellyfinPageSizeSaved = MutableStateFlow(false)
    private var jellyfinPageSizeSaveJob: Job? = null
    val plexUrlInput = MutableStateFlow("")
    val plexTokenInput = MutableStateFlow("")
    val plexPlaylistPageSizeInput = MutableStateFlow("300")
    val plexPageSizeSaved = MutableStateFlow(false)
    private var plexPageSizeSaveJob: Job? = null
    val spotifyTokenInput = MutableStateFlow("")
    val spotifyAuthLaunchUrl = MutableStateFlow<String?>(null)

    fun loadSavedProviderInputs() {
        viewModelScope.launch {
            refreshSavedProviderInputs()
        }
    }

    suspend fun refreshSavedProviderInputs() {
        val jelly = authRepository.readStoredConnection(SourceType.JELLYFIN)
        val plex = authRepository.readStoredConnection(SourceType.PLEX)
        val spotify = authRepository.readStoredConnection(SourceType.SPOTIFY)

        jellyfinUrlInput.value = jelly?.serverUrl.orEmpty()
        jellyfinTokenInput.value = jelly?.token.orEmpty()
        jellyfinPlaylistPageSizeInput.value = jelly?.playlistPageSize?.toString() ?: "300"
        plexUrlInput.value = plex?.serverUrl.orEmpty()
        plexTokenInput.value = plex?.token.orEmpty()
        plexPlaylistPageSizeInput.value = plex?.playlistPageSize?.toString() ?: "300"
        spotifyTokenInput.value = spotify?.token.orEmpty()
    }

    fun updateJellyfinUrlInput(value: String) {
        jellyfinUrlInput.value = value
    }

    fun updateJellyfinTokenInput(value: String) {
        jellyfinTokenInput.value = value
    }

    fun updateJellyfinPlaylistPageSizeInput(value: String) {
        updateProviderPlaylistPageSizeInput(
            value = value,
            inputFlow = jellyfinPlaylistPageSizeInput,
            savedFlow = jellyfinPageSizeSaved,
            sourceType = SourceType.JELLYFIN,
            providerLabel = "Jellyfin",
            getJob = { jellyfinPageSizeSaveJob },
            setJob = { jellyfinPageSizeSaveJob = it }
        )
    }

    fun updatePlexUrlInput(value: String) {
        plexUrlInput.value = value
    }

    fun updatePlexTokenInput(value: String) {
        plexTokenInput.value = value
    }

    fun updatePlexPlaylistPageSizeInput(value: String) {
        updateProviderPlaylistPageSizeInput(
            value = value,
            inputFlow = plexPlaylistPageSizeInput,
            savedFlow = plexPageSizeSaved,
            sourceType = SourceType.PLEX,
            providerLabel = "Plex",
            getJob = { plexPageSizeSaveJob },
            setJob = { plexPageSizeSaveJob = it }
        )
    }

    private fun updateProviderPlaylistPageSizeInput(
        value: String,
        inputFlow: MutableStateFlow<String>,
        savedFlow: MutableStateFlow<Boolean>,
        sourceType: SourceType,
        providerLabel: String,
        getJob: () -> Job?,
        setJob: (Job?) -> Unit
    ) {
        // Filter out newlines and non-digit characters
        val filtered = value.replace("\n", "").replace("\r", "").filter { it.isDigit() }
        inputFlow.value = filtered

        val pageSize = filtered.toIntOrNull()
        if (pageSize == null || pageSize !in 1..1000) {
            getJob()?.cancel()
            savedFlow.value = false
            return
        }

        getJob()?.cancel()
        setJob(
            viewModelScope.launch {
                try {
                    delay(500)
                    val saved = authRepository.updatePlaylistPageSize(sourceType, pageSize)
                    if (saved) {
                        savedFlow.value = true
                        delay(1500)
                        savedFlow.value = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CompatLog.e(TAG, "Failed to save $providerLabel page size", e)
                }
            }
        )
    }

    fun connectJellyfin(url: String, apiKey: String) {
        val normalizedUrl = url.trim()
        val normalizedApiKey = apiKey.trim()
        connectProvider(
            providerLabel = "Jellyfin",
            isValid = normalizedUrl.isNotBlank() && normalizedApiKey.isNotBlank(),
            validationErrorMessage = "Jellyfin URL and API key are required.",
            buildRequest = {
                val pageSize = (jellyfinPlaylistPageSizeInput.value.toIntOrNull() ?: PROVIDER_DEFAULT_PAGE_SIZE).coerceIn(1, 1000)
                AuthRequest.Jellyfin(
                    serverUrl = normalizedUrl,
                    apiKey = normalizedApiKey,
                    playlistPageSize = pageSize
                )
            }
        )
    }

    fun connectPlex(url: String, token: String) {
        val normalizedUrl = url.trim()
        val normalizedToken = token.trim()
        connectProvider(
            providerLabel = "Plex",
            isValid = normalizedUrl.isNotBlank() && normalizedToken.isNotBlank(),
            validationErrorMessage = "Plex URL and token are required.",
            buildRequest = {
                val pageSize = (plexPlaylistPageSizeInput.value.toIntOrNull() ?: PROVIDER_DEFAULT_PAGE_SIZE).coerceIn(1, 1000)
                AuthRequest.Plex(
                    serverUrl = normalizedUrl,
                    token = normalizedToken,
                    playlistPageSize = pageSize
                )
            }
        )
    }

    private fun connectProvider(
        providerLabel: String,
        isValid: Boolean,
        validationErrorMessage: String,
        buildRequest: () -> AuthRequest
    ) {
        if (!isValid) {
            providerConnectionFeedback.value = validationErrorMessage
            return
        }
        runProviderAction(
            startingFeedback = "Connecting to $providerLabel...",
            action = { authRepository.connect(buildRequest()) },
            onFailureFeedback = { formatProviderFailure(providerLabel, it) },
            onSuccess = {
                applyProviderChangeSideEffects()
                providerConnectionFeedback.value = if (currentProviderPlaylistCount() == 0) {
                    "$providerLabel connected, but no provider playlists were returned."
                } else {
                    "$providerLabel connected. Loaded ${currentProviderPlaylistCount()} provider playlist(s)."
                }
            }
        )
    }

    fun beginSpotifyLink() {
        val clientId = SpotifyClientIds.ACTIVE.trim()
        if (clientId.isBlank()) {
            providerConnectionFeedback.value = "Spotify client ID is not configured. Set 'spotifyClientId' in local.properties (or gradle.properties) and register redirect URI anyplayer://spotify-callback in Spotify Developer Dashboard."
            return
        }
        runProviderAction(
            startingFeedback = "Opening Spotify login...",
            action = { authRepository.beginSpotifyAuth(clientId = clientId, redirectUri = SpotifyClientIds.REDIRECT_URI) },
            onFailureFeedback = { "Spotify connection failed: ${it.message ?: "Unknown error"}" },
            onSuccess = { url ->
                spotifyAuthLaunchUrl.value = url
                providerConnectionFeedback.value = "Continue in browser to link Spotify."
            }
        )
    }

    fun markSpotifyAuthLaunchHandled() {
        spotifyAuthLaunchUrl.value = null
    }

    fun completeSpotifyLink(redirectUri: String) {
        runProviderAction(
            startingFeedback = "Finishing Spotify login...",
            action = { authRepository.completeSpotifyAuth(redirectUri) },
            onFailureFeedback = { "Spotify login failed: ${it.message ?: "Unknown error"}" },
            onSuccess = {
                applyProviderChangeSideEffects()
                providerConnectionFeedback.value = "Spotify connected successfully."
            }
        )
    }

    // Shared in-progress/feedback/runCatching scaffold for connect and Spotify link flows,
    // so a bugfix to the bookkeeping (e.g. when providerConnectionInProgress resets) only
    // needs to be made once instead of drifting across near-identical copies.
    private fun <T> runProviderAction(
        startingFeedback: String,
        action: suspend () -> T,
        onFailureFeedback: (Throwable) -> String,
        onSuccess: suspend (T) -> Unit
    ) {
        viewModelScope.launch {
            providerConnectionInProgress.value = true
            providerConnectionFeedback.value = startingFeedback

            runCatching { action() }
                .onFailure {
                    providerConnectionFeedback.value = onFailureFeedback(it)
                }
                .onSuccess {
                    onSuccess(it)
                }

            providerConnectionInProgress.value = false
        }
    }

    fun disconnect(sourceType: SourceType) {
        viewModelScope.launch {
            authRepository.disconnect(sourceType)
            applyProviderChangeSideEffects()
            providerConnectionFeedback.value = "Disconnected ${sourceType.name.lowercase()}."
        }
    }

    private suspend fun applyProviderChangeSideEffects() {
        refreshSavedProviderInputs()
        providerCatalogRepository.clearProviderPlaylistCacheData()
        onProviderChanged()
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
}
