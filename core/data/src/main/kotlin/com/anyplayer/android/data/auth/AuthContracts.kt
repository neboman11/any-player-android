package com.anyplayer.android.data.auth

import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Legacy `provider_connections` value (key = SourceType.name). Field names/serialization must stay
 * compatible: existing installs are read with this class. [jellyfinUserId] is new (v2); legacy
 * installs never stored a user id, so it is resolved lazily on the first Jellyfin call.
 */
@Serializable
data class StoredConnection(
    val source: SourceType,
    val serverUrl: String? = null,
    val username: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val tokenExpiresAt: Long? = null,
    val spotifyPremium: Boolean? = null,
    val playbackReady: Boolean? = null,
    val playlistPageSize: Int = DEFAULT_PAGE_SIZE,
    val jellyfinUserId: String? = null,
) {
    companion object {
        const val DEFAULT_PAGE_SIZE = 300
    }
}

/** In-memory view of `provider_connections`; single writer (mutex) for read-modify-write. */
interface ConnectionStore {
    val connections: StateFlow<Map<SourceType, StoredConnection>>
    /**
     * False while the backing file is still being opened (or failed to open); [connections] is then
     * an empty placeholder, not "nothing connected". Stores that load synchronously keep the default.
     */
    val isLoaded: Boolean get() = true
    suspend fun awaitLoaded(): Map<SourceType, StoredConnection>
    /** Atomic read-modify-write; returning null removes the entry. */
    suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?)
}

/** Valid (refreshed if needed) Spotify access token, single-flight. */
interface SpotifyTokenProvider {
    /** Null when no Spotify account is linked or refresh failed and the token is expired. */
    suspend fun accessToken(): String?
    /** Forces a refresh after a 401; returns the new token or null. */
    suspend fun refreshAfterUnauthorized(): String?
}

interface AuthRepository {
    /** UI-facing status per provider (SPOTIFY, JELLYFIN, PLEX always present). */
    val profiles: StateFlow<Map<SourceType, ProviderConnectionProfile>>

    suspend fun connectJellyfin(serverUrl: String, apiKey: String, pageSize: Int): ProviderConnectionProfile
    suspend fun connectPlex(serverUrl: String, token: String, pageSize: Int): ProviderConnectionProfile

    /** Returns the browser URL to open (PKCE). Throws with the legacy user-facing messages. */
    suspend fun beginSpotifyAuth(): String
    suspend fun completeSpotifyAuth(redirectUri: String): ProviderConnectionProfile

    suspend fun disconnect(source: SourceType)
    suspend fun updatePageSize(source: SourceType, pageSize: Int): Boolean

    /** Startup restore: no network for Jellyfin/Plex, Spotify refresh only when expiring. */
    suspend fun restore(): List<ProviderConnectionProfile>

    /** Legacy `isSourceConnected`: null/CUSTOM/ALL → true; fails open on errors. */
    fun isSourceConnected(source: SourceType?): Boolean
}
