package com.anyplayer.android.data.auth

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.providers.ProviderRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val remoteSources = listOf(SourceType.SPOTIFY, SourceType.JELLYFIN, SourceType.PLEX)
internal fun StoredConnection?.profile(source: SourceType): ProviderConnectionProfile {
    if (this == null) return ProviderConnectionProfile(source, false)
    val hasToken = !token.isNullOrBlank()
    return ProviderConnectionProfile(source, if (source == SourceType.SPOTIFY) spotifyPremium == true && playbackReady == true && hasToken else true,
        serverUrl, username, hasToken, spotifyPremium, playbackReady,
        if (source != SourceType.SPOTIFY) null else if (spotifyPremium != true) "Spotify account is not Premium. Provider is ignored."
        else if (playbackReady == false) "Spotify direct playback may be unavailable; app-control fallback is active." else null)
}

@Singleton
class DefaultAuthRepository @Inject constructor(
    private val store: ConnectionStore,
    private val registry: ProviderRegistry,
    private val flow: SpotifyAuthFlow,
    private val tokens: SpotifyTokenProvider,
) : AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val profiles = store.connections.map { connections -> remoteSources.associateWith { connections[it].profile(it) } }
        .stateIn(scope, SharingStarted.Eagerly, remoteSources.associateWith { null.profile(it) })
    private suspend fun connect(source: SourceType, url: String, token: String, pageSize: Int): ProviderConnectionProfile {
        require(url.trim().trimEnd('/').isNotBlank()) { "Server URL is required" }
        require(token.trim().isNotBlank()) { if (source == SourceType.JELLYFIN) "Jellyfin API key is required" else "Plex token is required" }
        val trimmedUrl = url.trim().trimEnd('/')
        val normalizedUrl = if (trimmedUrl.startsWith("http://", true) || trimmedUrl.startsWith("https://", true))
            trimmedUrl else "https://$trimmedUrl"
        val connection = StoredConnection(source, serverUrl = normalizedUrl, token = token.trim(),
            playbackReady = true, playlistPageSize = pageSize.coerceIn(1, 1000))
        val result = checkNotNull(registry[source]).validate(connection)
        val saved = connection.copy(username = result.username, jellyfinUserId = result.jellyfinUserId)
        store.update(source) { saved }
        return saved.profile(source)
    }
    override suspend fun connectJellyfin(serverUrl: String, apiKey: String, pageSize: Int) = connect(SourceType.JELLYFIN, serverUrl, apiKey, pageSize)
    override suspend fun connectPlex(serverUrl: String, token: String, pageSize: Int) = connect(SourceType.PLEX, serverUrl, token, pageSize)
    override suspend fun beginSpotifyAuth(): String = flow.beginAuth()
    override suspend fun completeSpotifyAuth(redirectUri: String): ProviderConnectionProfile = flow.completeAuth(redirectUri).profile(SourceType.SPOTIFY)
    override suspend fun disconnect(source: SourceType) { store.update(source) { null } }
    override suspend fun updatePageSize(source: SourceType, pageSize: Int): Boolean {
        if (source != SourceType.JELLYFIN && source != SourceType.PLEX) return false
        var updated = false
        store.update(source) { current -> current?.copy(playlistPageSize = pageSize.coerceIn(1, 1000))?.also { updated = true } }
        return updated
    }
    override suspend fun restore(): List<ProviderConnectionProfile> {
        store.awaitLoaded()
        tokens.accessToken()
        val connections = store.awaitLoaded()
        return remoteSources.filter { connections.containsKey(it) }.map { connections[it].profile(it) }
    }
    // Fail open like legacy: before the store has loaded, "no entry" means "unknown", not "disconnected".
    // Reads the store directly so the answer never lags behind [profiles]' collector.
    override fun isSourceConnected(source: SourceType?): Boolean = try {
        source == null || source == SourceType.CUSTOM || source == SourceType.ALL || !store.isLoaded ||
            store.connections.value[source].profile(source).connected
    } catch (_: Exception) { true }
}
