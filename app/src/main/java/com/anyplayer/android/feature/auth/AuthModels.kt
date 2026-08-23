package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.model.SourceType
import kotlinx.serialization.Serializable

const val PROVIDER_DEFAULT_PAGE_SIZE = 300

sealed interface AuthRequest {
    data class Spotify(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresIn: Int? = null,
        val isPremium: Boolean? = null,
        val username: String? = null
    ) : AuthRequest
    data class Jellyfin(
        val serverUrl: String,
        val apiKey: String,
        val username: String? = null,
        val playlistPageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE
    ) : AuthRequest
    data class Plex(
        val serverUrl: String,
        val token: String,
        val username: String? = null,
        val playlistPageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE
    ) : AuthRequest
}

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
    val playlistPageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE
)
