package com.anyplayer.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.AuthRepository
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.catalog.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity-owned redirect stream. Bind the shell implementation in Hilt. */
interface SpotifyRedirectSource { val redirects: SharedFlow<String> }

data class ProviderFields(val url: String = "", val token: String = "", val pageSize: String = "300", val pageSizeSaved: Boolean = false)
data class ProvidersSettingsState(
    val profiles: Map<SourceType, ProviderConnectionProfile> = emptyMap(),
    val jellyfin: ProviderFields = ProviderFields(),
    val plex: ProviderFields = ProviderFields(),
    val busy: Boolean = false,
    val feedback: String = "",
    val authUrl: String? = null,
    /** A Spotify callback is being handled; Settings switches to the Providers tab, where its result shows. */
    val revealSpotifyResult: Boolean = false,
)

@HiltViewModel
class ProvidersSettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val connections: ConnectionStore,
    private val catalog: CatalogRepository,
    redirects: SpotifyRedirectSource,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ProvidersSettingsState())
    val state = mutableState.asStateFlow()
    private val edited = mutableSetOf<SourceType>()
    private val pageSizeWrites = Mutex()
    init {
        viewModelScope.launch { auth.profiles.collect { profiles -> mutableState.update { old ->
            old.copy(profiles = profiles,
                jellyfin = if (SourceType.JELLYFIN in edited) old.jellyfin else old.jellyfin.copy(url = profiles[SourceType.JELLYFIN]?.serverUrl.orEmpty()),
                plex = if (SourceType.PLEX in edited) old.plex else old.plex.copy(url = profiles[SourceType.PLEX]?.serverUrl.orEmpty()))
        } } }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { connections.awaitLoaded() }
            connections.connections.collect { saved ->
                fun fields(source: SourceType, old: ProviderFields): ProviderFields {
                    if (source in edited) return old
                    val connection = saved[source] ?: return ProviderFields()
                    return ProviderFields(connection.serverUrl.orEmpty(), connection.token.orEmpty(), connection.playlistPageSize.toString())
                }
                mutableState.update { it.copy(jellyfin = fields(SourceType.JELLYFIN, it.jellyfin), plex = fields(SourceType.PLEX, it.plex)) }
            }
        }
        viewModelScope.launch { redirects.redirects.collect { completeSpotify(it) } }
    }
    fun edit(source: SourceType, transform: (ProviderFields) -> ProviderFields) {
        edited += source
        mutableState.update { if (source == SourceType.JELLYFIN) it.copy(jellyfin = transform(it.jellyfin)) else it.copy(plex = transform(it.plex)) }
    }
    fun updatePageSize(source: SourceType, text: String) {
        edit(source) { it.copy(pageSize = text, pageSizeSaved = false) }
        val size = text.toIntOrNull() ?: return
        viewModelScope.launch {
            try {
                val saved = pageSizeWrites.withLock {
                    withContext(Dispatchers.IO) { auth.updatePageSize(source, size.coerceIn(1, 1000)) }
                }
                val current = if (source == SourceType.JELLYFIN) state.value.jellyfin else state.value.plex
                if (current.pageSize == text) edit(source) { it.copy(pageSizeSaved = saved) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutableState.update { it.copy(feedback = "Page size save failed: ${e.message ?: "Unknown error"}") }
            }
        }
    }
    fun connect(source: SourceType) {
        val fields = if (source == SourceType.JELLYFIN) state.value.jellyfin else state.value.plex
        val label = if (source == SourceType.JELLYFIN) "Jellyfin" else "Plex"
        action("Connecting to $label...", "$label connection failed") {
            if (source == SourceType.JELLYFIN) auth.connectJellyfin(fields.url, fields.token, fields.pageSize.toIntOrNull()?.coerceIn(1, 1000) ?: 300)
            else auth.connectPlex(fields.url, fields.token, fields.pageSize.toIntOrNull()?.coerceIn(1, 1000) ?: 300)
            val refreshFailure = try {
                catalog.clearCache()
                catalog.refreshPlaylists().firstOrNull { it.source == source }?.message
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e.message ?: "Unknown error"
            }
            if (refreshFailure != null) "$label connected, but playlist refresh failed: $refreshFailure"
            else {
                val count = catalog.playlists.first().count { it.source == source }
                if (count == 0) "$label connected, but no provider playlists were returned."
                else "$label connected. Loaded $count provider playlist(s)."
            }
        }
    }
    fun disconnect(source: SourceType) = action("Disconnecting…", "Disconnect failed") {
        auth.disconnect(source)
        catalog.clearCache()
        catalog.refreshPlaylists()
        "Disconnected ${source.name.lowercase()}."
    }
    fun clearCache() = action("Clearing provider cache…", "Clear provider cache failed") {
        catalog.clearCache(); "Provider cache cleared."
    }
    fun linkSpotify() = action("Opening Spotify login...", "Spotify connection failed") {
        val url = try { auth.beginSpotifyAuth() } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.message?.contains("client ID", ignoreCase = true) == true) throw MissingSpotifyClientId()
            throw e
        }
        mutableState.update { it.copy(authUrl = url) }
        "Continue in browser to link Spotify."
    }
    fun authLaunchHandled() { mutableState.update { it.copy(authUrl = null) } }
    fun authLaunchFailed(message: String) { mutableState.update { it.copy(authUrl = null, feedback = "Spotify connection failed: $message") } }
    fun spotifyResultRevealed() { mutableState.update { it.copy(revealSpotifyResult = false) } }
    private suspend fun completeSpotify(uri: String) {
        // The callback is handled even while a browser-launch action is finishing.
        mutableState.update { it.copy(busy = true, feedback = "Finishing Spotify login...", revealSpotifyResult = true) }
        try {
            withContext(Dispatchers.IO) {
                auth.completeSpotifyAuth(uri)
                catalog.clearCache()
                catalog.refreshPlaylists()
            }
            mutableState.update { it.copy(feedback = "Spotify connected successfully.") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutableState.update { it.copy(feedback = "Spotify login failed: ${e.message ?: "Unknown error"}") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }
    private fun action(start: String, failure: String, block: suspend () -> String) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, feedback = start) }
        viewModelScope.launch {
            try {
                val feedback = withContext(Dispatchers.IO) { block() }
                mutableState.update { it.copy(feedback = feedback) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = e.message?.trim().orEmpty()
                mutableState.update { it.copy(feedback = when {
                    e is MissingSpotifyClientId -> MISSING_CLIENT_ID
                    message.isEmpty() -> "$failure."
                    message.startsWith(failure, ignoreCase = true) -> message
                    else -> "$failure: $message"
                }) }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    private class MissingSpotifyClientId : IllegalStateException()
    companion object {
        const val MISSING_CLIENT_ID = "Spotify client ID is not configured. Set 'spotifyClientId' in local.properties (or gradle.properties) and register redirect URI anyplayer://spotify-callback in Spotify Developer Dashboard."
    }
}

internal fun providerConnectionTooltip(profile: ProviderConnectionProfile): String = if (profile.source == SourceType.SPOTIFY) {
    "Connected • ${when (profile.isPremium) { true -> "Premium"; false -> "Free"; null -> "Tier unknown" }} • ${when (profile.playbackReady) { true -> "Playback ready"; false -> "Playback setup needed"; null -> "Playback status unknown" }}"
} else "Connected" + (profile.serverUrl?.takeIf(String::isNotBlank)?.let { " • $it" } ?: "")
