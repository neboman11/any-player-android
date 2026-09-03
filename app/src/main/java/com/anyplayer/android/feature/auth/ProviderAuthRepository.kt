package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType

interface ProviderAuthRepository {
    suspend fun connect(request: AuthRequest): ProviderConnectionProfile
    suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String
    suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile
    suspend fun refreshSpotifyTokenIfNeeded(): String?
    suspend fun disconnect(sourceType: SourceType)
    suspend fun restoreAll(): List<ProviderConnectionProfile>
    suspend fun status(sourceType: SourceType): ProviderConnectionProfile
    suspend fun readStoredConnection(sourceType: SourceType): StoredConnection?
    suspend fun updatePlaylistPageSize(sourceType: SourceType, pageSize: Int): Boolean
}

/**
 * Returns true if the given [source] is connected (or does not require a connection check).
 * Sources of [SourceType.CUSTOM] and [SourceType.ALL] are always considered available.
 * A null source is treated as available (no current track).
 */
suspend fun ProviderAuthRepository.isSourceConnected(source: SourceType?): Boolean {
    if (source == null || source == SourceType.CUSTOM || source == SourceType.ALL) return true
    return runCatching { status(source).connected }.getOrElse { error ->
        CompatLog.e("ProviderAuth", "Unable to verify provider state for $source; allowing playback", error)
        true
    }
}
