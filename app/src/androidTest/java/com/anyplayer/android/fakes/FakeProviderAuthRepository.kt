package com.anyplayer.android.fakes

import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.auth.AuthRequest
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.StoredConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [ProviderAuthRepository] double for instrumented tests. Avoids real
 * network/Rust FFI calls to Jellyfin/Plex/Spotify so UI flows can be exercised
 * offline. Installed in place of `AuthModule` via [com.anyplayer.android.di.FakeAuthModule].
 */
@Singleton
class FakeProviderAuthRepository @Inject constructor() : ProviderAuthRepository {
    /** Set by a test to make the next [connect] call fail with this error. */
    var nextConnectError: Throwable? = null

    private val connections = mutableMapOf<SourceType, ProviderConnectionProfile>()
    private val stored = mutableMapOf<SourceType, StoredConnection>()

    fun reset() {
        connections.clear()
        stored.clear()
        nextConnectError = null
    }

    override suspend fun connect(request: AuthRequest): ProviderConnectionProfile {
        nextConnectError?.let {
            nextConnectError = null
            throw it
        }
        val (profile, storedConnection) = when (request) {
            is AuthRequest.Jellyfin -> ProviderConnectionProfile(
                source = SourceType.JELLYFIN,
                connected = true,
                serverUrl = request.serverUrl,
                hasToken = true
            ) to StoredConnection(
                source = SourceType.JELLYFIN,
                serverUrl = request.serverUrl,
                token = TEST_JELLYFIN_TOKEN,
                playlistPageSize = request.playlistPageSize
            )
            is AuthRequest.Plex -> ProviderConnectionProfile(
                source = SourceType.PLEX,
                connected = true,
                serverUrl = request.serverUrl,
                hasToken = true
            ) to StoredConnection(
                source = SourceType.PLEX,
                serverUrl = request.serverUrl,
                token = TEST_PLEX_TOKEN,
                playlistPageSize = request.playlistPageSize
            )
            is AuthRequest.Spotify -> ProviderConnectionProfile(
                source = SourceType.SPOTIFY,
                connected = true,
                hasToken = true,
                isPremium = request.isPremium
            ) to StoredConnection(source = SourceType.SPOTIFY, token = request.accessToken)
        }
        connections[profile.source] = profile
        stored[profile.source] = storedConnection
        return profile
    }

    override suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String =
        "https://fake-spotify-auth.test/authorize?client_id=$clientId&redirect_uri=$redirectUri"

    override suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile {
        val profile = ProviderConnectionProfile(source = SourceType.SPOTIFY, connected = true, hasToken = true)
        connections[SourceType.SPOTIFY] = profile
        stored[SourceType.SPOTIFY] = StoredConnection(source = SourceType.SPOTIFY, token = TEST_SPOTIFY_TOKEN)
        return profile
    }

    override suspend fun refreshSpotifyTokenIfNeeded(): String? = null

    override suspend fun disconnect(sourceType: SourceType) {
        connections.remove(sourceType)
        stored.remove(sourceType)
    }

    override suspend fun restoreAll(): List<ProviderConnectionProfile> = connections.values.toList()

    override suspend fun status(sourceType: SourceType): ProviderConnectionProfile =
        connections[sourceType] ?: ProviderConnectionProfile(source = sourceType, connected = false)

    override suspend fun readStoredConnection(sourceType: SourceType): StoredConnection? = stored[sourceType]

    override suspend fun updatePlaylistPageSize(sourceType: SourceType, pageSize: Int): Boolean {
        val existing = stored[sourceType] ?: return false
        stored[sourceType] = existing.copy(playlistPageSize = pageSize)
        return true
    }

    private companion object {
        const val TEST_JELLYFIN_TOKEN = "test-fixture-jellyfin"
        const val TEST_PLEX_TOKEN = "test-fixture-plex"
        const val TEST_SPOTIFY_TOKEN = "test-fixture-spotify"
    }
}
