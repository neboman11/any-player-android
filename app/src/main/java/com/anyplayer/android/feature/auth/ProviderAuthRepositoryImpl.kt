package com.anyplayer.android.feature.auth

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.feature.auth.spotify.SpotifyAuthClient
import com.anyplayer.android.feature.auth.spotify.SpotifyClientIds
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.providers.ProviderSessionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderAuthRepositoryImpl @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyAuthClient: SpotifyAuthClient,
    private val rustBridge: RustBridge,
    private val spotifyAuthFlow: SpotifyAuthFlow
) : ProviderAuthRepository {
    companion object {
        private const val TAG = "ProviderAuthRepository"
        private const val RUST_PROVIDER_BRIDGE_UNAVAILABLE =
            "validation unavailable (Rust bridge not loaded)"
    }

    override suspend fun connect(request: AuthRequest): ProviderConnectionProfile {
        return withContext(Dispatchers.IO) {
            val connection = when (request) {
                is AuthRequest.Spotify -> {
                    val token = request.accessToken.trim()
                    require(token.isNotBlank()) { "Spotify access token is required" }
                    val playbackReady = spotifyAuthFlow.resolveSpotifyPlaybackReady(token)
                    when (val check = spotifyAuthClient.validate(token)) {
                        is ProviderConnectionCheck.Connected -> StoredConnection(
                            source = SourceType.SPOTIFY,
                            username = request.username ?: check.username,
                            token = token,
                            refreshToken = request.refreshToken,
                            tokenExpiresAt = spotifyAuthFlow.computeTokenExpiresAt(request.expiresIn),
                            spotifyPremium = request.isPremium ?: check.metadata["isPremium"]?.toBooleanStrictOrNull(),
                            playbackReady = playbackReady
                        ).also { connection ->
                            if (connection.spotifyPremium != true) {
                                throw IllegalStateException("Spotify Premium is required for playback integration.")
                            }
                        }

                        is ProviderConnectionCheck.Failed -> {
                            throw IllegalStateException(check.message)
                        }
                    }
                }

                is AuthRequest.Jellyfin -> {
                    val normalizedServerUrl = normalizeServerUrl(request.serverUrl)
                    val apiKey = request.apiKey.trim()
                    require(apiKey.isNotBlank()) { "Jellyfin API key is required" }
                    val clampedPageSize = request.playlistPageSize.coerceIn(1, 1000)
                    val check = rustBridge.providerValidateConnection(
                        source = SourceType.JELLYFIN,
                        session = buildJellyfinSession(normalizedServerUrl, apiKey, clampedPageSize)
                    ) ?: ProviderConnectionCheck.Failed(
                        buildString {
                            append("Jellyfin $RUST_PROVIDER_BRIDGE_UNAVAILABLE")
                            rustBridge.lastError?.takeIf { it.isNotBlank() }?.let { append(". $it") }
                        }
                    )

                    when (check) {
                        is ProviderConnectionCheck.Connected -> StoredConnection(
                            source = SourceType.JELLYFIN,
                            serverUrl = normalizedServerUrl,
                            username = request.username ?: check.username,
                            token = apiKey,
                            refreshToken = check.metadata["userId"],
                            playbackReady = true,
                            playlistPageSize = clampedPageSize
                        )

                        is ProviderConnectionCheck.Failed -> {
                            throw IllegalStateException(check.message)
                        }
                    }
                }

                is AuthRequest.Plex -> {
                    val normalizedServerUrl = normalizeServerUrl(request.serverUrl)
                    val token = request.token.trim()
                    require(token.isNotBlank()) { "Plex token is required" }
                    val clampedPageSize = request.playlistPageSize.coerceIn(1, 1000)
                    val check = rustBridge.providerValidateConnection(
                        source = SourceType.PLEX,
                        session = buildPlexSession(normalizedServerUrl, token, clampedPageSize)
                    ) ?: ProviderConnectionCheck.Failed(
                        buildString {
                            append("Plex $RUST_PROVIDER_BRIDGE_UNAVAILABLE")
                            rustBridge.lastError?.takeIf { it.isNotBlank() }?.let { append(". $it") }
                        }
                    )

                    when (check) {
                        is ProviderConnectionCheck.Connected -> StoredConnection(
                            source = SourceType.PLEX,
                            serverUrl = normalizedServerUrl,
                            username = request.username ?: check.username,
                            token = token,
                            playbackReady = true,
                            playlistPageSize = clampedPageSize
                        )

                        is ProviderConnectionCheck.Failed -> {
                            throw IllegalStateException(check.message)
                        }
                    }
                }
            }
            secureConnectionStore.save(connection)
            connection.toStatus()
        }
    }

    override suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String =
        spotifyAuthFlow.beginAuth(clientId, redirectUri)

    override suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile =
        spotifyAuthFlow.completeAuth(redirectUriWithCode).toStatus()

    override suspend fun disconnect(sourceType: SourceType) {
        withContext(Dispatchers.IO) {
            secureConnectionStore.remove(sourceType)
        }
    }

    override suspend fun refreshSpotifyTokenIfNeeded(): String? = spotifyAuthFlow.refreshTokenIfNeeded()

    override suspend fun restoreAll(): List<ProviderConnectionProfile> {
        return withContext(Dispatchers.IO) {
            secureConnectionStore.readAll().map { stored -> restoreConnection(stored) }
        }
    }

    private suspend fun restoreConnection(stored: StoredConnection): ProviderConnectionProfile {
        return when (stored.source) {
            SourceType.JELLYFIN -> {
                val normalizedServerUrl = stored.serverUrl?.let(::normalizeServerUrl)
                if (normalizedServerUrl != null && !stored.token.isNullOrBlank()) {
                    val check = rustBridge.providerValidateConnection(
                        source = SourceType.JELLYFIN,
                        session = buildJellyfinSession(normalizedServerUrl, stored.token, stored.playlistPageSize)
                    ) ?: ProviderConnectionCheck.Failed(
                        rustBridge.lastError ?: "Jellyfin $RUST_PROVIDER_BRIDGE_UNAVAILABLE"
                    )

                    when (check) {
                        is ProviderConnectionCheck.Connected -> stored.toStatus()
                        is ProviderConnectionCheck.Failed -> stored.copy(playbackReady = false).toStatus()
                    }
                } else {
                    stored.toStatus()
                }
            }

            SourceType.PLEX -> {
                val normalizedServerUrl = stored.serverUrl?.let(::normalizeServerUrl)
                if (normalizedServerUrl != null && !stored.token.isNullOrBlank()) {
                    val check = rustBridge.providerValidateConnection(
                        source = SourceType.PLEX,
                        session = buildPlexSession(normalizedServerUrl, stored.token, stored.playlistPageSize)
                    ) ?: ProviderConnectionCheck.Failed(
                        rustBridge.lastError ?: "Plex $RUST_PROVIDER_BRIDGE_UNAVAILABLE"
                    )

                    when (check) {
                        is ProviderConnectionCheck.Connected -> stored.toStatus()
                        is ProviderConnectionCheck.Failed -> stored.copy(playbackReady = false).toStatus()
                    }
                } else {
                    stored.toStatus()
                }
            }

            SourceType.SPOTIFY -> {
                if (!stored.token.isNullOrBlank()) {
                    when (val validation = spotifyAuthClient.validate(stored.token)) {
                        is ProviderConnectionCheck.Connected -> stored.copy(
                            username = validation.username ?: stored.username,
                            spotifyPremium = validation.metadata["isPremium"]?.toBooleanStrictOrNull() ?: stored.spotifyPremium,
                            playbackReady = spotifyAuthFlow.resolveSpotifyPlaybackReady(stored.token)
                        ).also { secureConnectionStore.save(it) }.toStatus()

                        is ProviderConnectionCheck.Failed -> {
                            val refreshed = stored.refreshToken?.let {
                                spotifyAuthClient.refreshAccessToken(
                                    clientId = SpotifyClientIds.ACTIVE,
                                    refreshToken = it
                                )
                            }

                            if (refreshed != null) {
                                when (val refreshedValidation = spotifyAuthClient.validate(refreshed.accessToken)) {
                                    is ProviderConnectionCheck.Connected -> {
                                        val refreshedTokenExpiresAt = spotifyAuthFlow.computeTokenExpiresAt(refreshed.expiresIn)
                                        val updated = stored.copy(
                                            username = refreshedValidation.username ?: stored.username,
                                            token = refreshed.accessToken,
                                            refreshToken = refreshed.refreshToken ?: stored.refreshToken,
                                            tokenExpiresAt = refreshedTokenExpiresAt,
                                            spotifyPremium = refreshedValidation.metadata["isPremium"]?.toBooleanStrictOrNull() ?: stored.spotifyPremium,
                                            playbackReady = spotifyAuthFlow.resolveSpotifyPlaybackReady(refreshed.accessToken)
                                        )
                                        secureConnectionStore.save(updated)
                                        updated.toStatus()
                                    }

                                    is ProviderConnectionCheck.Failed -> stored.copy(playbackReady = false).toStatus()
                                }
                            } else {
                                stored.copy(playbackReady = false).toStatus()
                            }
                        }
                    }
                } else {
                    stored.toStatus()
                }
            }

            else -> stored.toStatus()
        }
    }

    override suspend fun status(sourceType: SourceType): ProviderConnectionProfile {
        return withContext(Dispatchers.IO) {
            val connection = secureConnectionStore.read(sourceType)
            connection?.toStatus()?.takeIf { it.connected } ?: ProviderConnectionProfile(
                source = sourceType,
                connected = false,
                hasToken = false
            )
        }
    }

    override suspend fun readStoredConnection(sourceType: SourceType): StoredConnection? {
        return withContext(Dispatchers.IO) {
            secureConnectionStore.read(sourceType)
        }
    }

    override suspend fun updatePlaylistPageSize(sourceType: SourceType, pageSize: Int): Boolean {
        return withContext(Dispatchers.IO) {
            when (sourceType) {
                SourceType.JELLYFIN,
                SourceType.PLEX -> {
                    val current = secureConnectionStore.read(sourceType) ?: return@withContext false
                    val pagedSize = pageSize.coerceIn(1, 1000)
                    val updated = current.copy(playlistPageSize = pagedSize)
                    secureConnectionStore.save(updated)
                    true
                }
                    else -> {
                    CompatLog.d(TAG, "updatePlaylistPageSize not supported for source: $sourceType")
                    false
                }
            }
        }
    }

    private fun StoredConnection.toStatus(): ProviderConnectionProfile {
        val isSpotify = source == SourceType.SPOTIFY
        val spotifyFallback = if (isSpotify && spotifyPremium != true) {
            "Spotify account is not Premium. Provider is ignored."
        } else if (isSpotify && playbackReady == false) {
            "Spotify direct playback may be unavailable; app-control fallback is active."
        } else {
            null
        }
        val spotifyConnected = if (isSpotify) {
            spotifyPremium == true && playbackReady == true && !token.isNullOrBlank()
        } else {
            true
        }
        return ProviderConnectionProfile(
            source = source,
            connected = spotifyConnected,
            serverUrl = serverUrl,
            username = username,
            hasToken = !token.isNullOrBlank(),
            isPremium = spotifyPremium,
            playbackReady = playbackReady,
            lastError = spotifyFallback
        )
    }

    private fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Server URL is required" }
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun buildJellyfinSession(serverUrl: String, apiKey: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> =
        ProviderSessionBuilder.jellyfinSession(url = serverUrl, apiKey = apiKey, pageSize = pageSize)

    private fun buildPlexSession(serverUrl: String, token: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> =
        ProviderSessionBuilder.plexSession(url = serverUrl, token = token, pageSize = pageSize)
}
