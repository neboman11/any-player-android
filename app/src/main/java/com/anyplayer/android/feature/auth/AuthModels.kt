package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import kotlinx.serialization.Serializable

sealed interface AuthRequest {
    data class Spotify(val accessToken: String, val refreshToken: String? = null, val isPremium: Boolean? = null, val username: String? = null) : AuthRequest
    data class Jellyfin(val serverUrl: String, val apiKey: String, val username: String? = null) : AuthRequest
    data class Plex(val serverUrl: String, val token: String, val username: String? = null) : AuthRequest
}

@Serializable
data class StoredConnection(
    val source: SourceType,
    val serverUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val spotifyPremium: Boolean? = null,
    val playbackReady: Boolean? = null
)

interface ProviderAuthRepository {
    suspend fun connect(request: AuthRequest): ProviderConnectionProfile
    suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String
    suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile
    suspend fun disconnect(sourceType: SourceType)
    suspend fun restoreAll(): List<ProviderConnectionProfile>
    suspend fun status(sourceType: SourceType): ProviderConnectionProfile
    suspend fun readStoredConnection(sourceType: SourceType): StoredConnection?
}

/**
 * Returns true if the given [source] is connected (or does not require a connection check).
 * Sources of [SourceType.CUSTOM] and [SourceType.ALL] are always considered available.
 * A null source is treated as available (no current track).
 */
suspend fun ProviderAuthRepository.isSourceConnected(source: SourceType?): Boolean {
    if (source == null || source == SourceType.CUSTOM || source == SourceType.ALL) return true
    return runCatching { status(source).connected }.getOrDefault(false)
}
