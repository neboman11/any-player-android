package com.anyplayer.android.feature.auth

import android.net.Uri
import android.util.Log
import com.anyplayer.android.core.model.ProviderConnectionProfile
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.core.rust.RustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderAuthRepositoryImpl @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyAuthSessionStore: SpotifyAuthSessionStore,
    private val spotifyClient: SpotifyClient,
    private val rustBridge: RustBridge
) : ProviderAuthRepository {
    companion object {
        private const val TAG = "ProviderAuthRepository"
        private const val RUST_PROVIDER_BRIDGE_UNAVAILABLE =
            "validation unavailable (Rust bridge not loaded)"
        private const val SPOTIFY_REFRESH_WINDOW_MILLIS = 5 * 60 * 1000L
    }

    override suspend fun connect(request: AuthRequest): ProviderConnectionProfile {
        return withContext(Dispatchers.IO) {
            val connection = when (request) {
                is AuthRequest.Spotify -> {
                    val token = request.accessToken.trim()
                    require(token.isNotBlank()) { "Spotify access token is required" }
                    val playbackReady = resolveSpotifyPlaybackReady(token)
                    when (val check = spotifyClient.validate(token)) {
                        is ProviderConnectionCheck.Connected -> StoredConnection(
                            source = SourceType.SPOTIFY,
                            username = request.username ?: check.username,
                            token = token,
                            refreshToken = request.refreshToken,
                            tokenExpiresAt = computeTokenExpiresAt(request.expiresIn),
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

    override suspend fun beginSpotifyAuth(clientId: String, redirectUri: String): String {
        return withContext(Dispatchers.IO) {
            val normalizedClientId = clientId.trim()
            val normalizedRedirect = redirectUri.trim()
            require(normalizedClientId.isNotBlank()) { "Spotify client ID is required" }
            require(normalizedRedirect.isNotBlank()) { "Spotify redirect URI is required" }

            val session = spotifyClient.createPkceSession(
                clientId = normalizedClientId,
                redirectUri = normalizedRedirect
            )
            spotifyAuthSessionStore.savePending(
                state = session.state,
                codeVerifier = session.codeVerifier,
                clientId = normalizedClientId,
                redirectUri = normalizedRedirect
            )
            buildRustSpotifyAuthUrl(
                clientId = normalizedClientId,
                redirectUri = normalizedRedirect,
                state = session.state,
                fallbackAuthorizationUrl = session.authorizationUrl
            ) ?: session.authorizationUrl
        }
    }

    override suspend fun completeSpotifyAuth(redirectUriWithCode: String): ProviderConnectionProfile {
        return withContext(Dispatchers.IO) {
            val pending = spotifyAuthSessionStore.readPending()
                ?: throw IllegalStateException("No pending Spotify auth session. Start linking again.")

            val callbackUrl = runCatching { Uri.parse(redirectUriWithCode) }.getOrNull()
                ?: throw IllegalStateException("Invalid Spotify callback URI")

            val state = callbackUrl.getQueryParameter("state")
            val code = callbackUrl.getQueryParameter("code")
            val error = callbackUrl.getQueryParameter("error")

            if (!error.isNullOrBlank()) {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException("Spotify authorization failed: $error")
            }

            if (state.isNullOrBlank() || state != pending.state) {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException("Spotify auth state mismatch. Start linking again.")
            }

            if (code.isNullOrBlank()) {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException("Spotify callback did not include an authorization code")
            }
            val authorizationCode = code
            notifyRustSpotifyExchangeBoundary(
                code = authorizationCode,
                verifier = pending.codeVerifier,
                redirectUri = pending.redirectUri
            )

            val tokenResult = spotifyClient.exchangeAuthorizationCode(
                clientId = pending.clientId,
                code = authorizationCode,
                codeVerifier = pending.codeVerifier,
                redirectUri = pending.redirectUri
            ) ?: run {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException("Unable to exchange Spotify authorization code")
            }

            val validation = spotifyClient.validate(tokenResult.accessToken)
            if (validation is ProviderConnectionCheck.Failed) {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException(validation.message)
            }
            val validated = validation as ProviderConnectionCheck.Connected
            val tokenExpiresAt = computeTokenExpiresAt(tokenResult.expiresIn)
            val playbackReady = resolveSpotifyPlaybackReady(
                accessToken = tokenResult.accessToken,
                tokenExpiresAt = tokenExpiresAt
            )

            val connection = StoredConnection(
                source = SourceType.SPOTIFY,
                username = validated.username,
                token = tokenResult.accessToken,
                refreshToken = tokenResult.refreshToken,
                tokenExpiresAt = tokenExpiresAt,
                spotifyPremium = validated.metadata["isPremium"]?.toBooleanStrictOrNull(),
                playbackReady = playbackReady
            )

            secureConnectionStore.save(connection)
            spotifyAuthSessionStore.clearPending()
            connection.toStatus()
        }
    }

    override suspend fun disconnect(sourceType: SourceType) {
        withContext(Dispatchers.IO) {
            secureConnectionStore.remove(sourceType)
        }
    }

    override suspend fun refreshSpotifyTokenIfNeeded(): String? {
        return withContext(Dispatchers.IO) {
            val stored = secureConnectionStore.read(SourceType.SPOTIFY) ?: return@withContext null
            val currentToken = stored.token?.trim().orEmpty()
            if (currentToken.isBlank()) return@withContext null

            val expiresAt = stored.tokenExpiresAt
            val now = System.currentTimeMillis()
            val shouldRefresh = expiresAt != null && now >= (expiresAt - SPOTIFY_REFRESH_WINDOW_MILLIS)
            if (!shouldRefresh) {
                return@withContext currentToken
            }

            val refreshToken = stored.refreshToken?.trim().orEmpty()
            if (refreshToken.isBlank()) {
                return@withContext if (expiresAt != null && now < expiresAt) currentToken else null
            }

            val refreshed = runCatching {
                spotifyClient.refreshAccessToken(
                    clientId = SpotifyClientIds.ACTIVE,
                    refreshToken = refreshToken
                )
            }.onFailure { e ->
                Log.w(TAG, "Spotify token refresh network call failed", e)
            }.getOrNull() ?: return@withContext if (expiresAt != null && now < expiresAt) currentToken else null

            val refreshedToken = refreshed.accessToken.trim()
            if (refreshedToken.isBlank()) return@withContext if (expiresAt != null && now < expiresAt) currentToken else null
            val refreshedTokenExpiresAt = computeTokenExpiresAt(refreshed.expiresIn)

            val updatedConnection = stored.copy(
                token = refreshedToken,
                refreshToken = refreshed.refreshToken ?: stored.refreshToken,
                tokenExpiresAt = refreshedTokenExpiresAt,
                playbackReady = resolveSpotifyPlaybackReady(
                    accessToken = refreshedToken,
                    tokenExpiresAt = refreshedTokenExpiresAt
                )
            )
            runCatching { secureConnectionStore.save(updatedConnection) }.onFailure { e ->
                Log.w(TAG, "Failed to save refreshed Spotify token", e)
            }
            refreshedToken
        }
    }

    override suspend fun restoreAll(): List<ProviderConnectionProfile> {
        return withContext(Dispatchers.IO) {
            secureConnectionStore.readAll().map { stored ->
                when (stored.source) {
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
                            when (val validation = spotifyClient.validate(stored.token)) {
                                is ProviderConnectionCheck.Connected -> stored.copy(
                                    username = validation.username ?: stored.username,
                                    spotifyPremium = validation.metadata["isPremium"]?.toBooleanStrictOrNull() ?: stored.spotifyPremium,
                                    playbackReady = resolveSpotifyPlaybackReady(
                                        accessToken = stored.token,
                                        tokenExpiresAt = stored.tokenExpiresAt
                                    )
                                ).also { secureConnectionStore.save(it) }.toStatus()

                                is ProviderConnectionCheck.Failed -> {
                                    val refreshed = stored.refreshToken?.let {
                                        spotifyClient.refreshAccessToken(
                                            clientId = SpotifyClientIds.ACTIVE,
                                            refreshToken = it
                                        )
                                    }

                                    if (refreshed != null) {
                                        when (val refreshedValidation = spotifyClient.validate(refreshed.accessToken)) {
                                            is ProviderConnectionCheck.Connected -> {
                                                val refreshedTokenExpiresAt = computeTokenExpiresAt(refreshed.expiresIn)
                                                val updated = stored.copy(
                                                    username = refreshedValidation.username ?: stored.username,
                                                    token = refreshed.accessToken,
                                                    refreshToken = refreshed.refreshToken ?: stored.refreshToken,
                                                    tokenExpiresAt = refreshedTokenExpiresAt,
                                                    spotifyPremium = refreshedValidation.metadata["isPremium"]?.toBooleanStrictOrNull() ?: stored.spotifyPremium,
                                                    playbackReady = resolveSpotifyPlaybackReady(
                                                        accessToken = refreshed.accessToken,
                                                        tokenExpiresAt = refreshedTokenExpiresAt
                                                    )
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

    override suspend fun updatePlaylistPageSize(sourceType: SourceType, pageSize: Int) {
        return withContext(Dispatchers.IO) {
            val current = secureConnectionStore.read(sourceType) ?: return@withContext
            val pagedSize = pageSize.coerceIn(1, 1000)
            val updated = current.copy(playlistPageSize = pagedSize)
            secureConnectionStore.save(updated)
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
            "http://$trimmed"
        }
    }

    private fun buildJellyfinSession(serverUrl: String, apiKey: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> = mapOf(
        "url" to serverUrl,
        "api_key" to apiKey,
        "page_size" to pageSize.toString()
    )

    private fun buildPlexSession(serverUrl: String, token: String, pageSize: Int = PROVIDER_DEFAULT_PAGE_SIZE): Map<String, String> = mapOf(
        "url" to serverUrl,
        "token" to token,
        "page_size" to pageSize.toString()
    )

    private fun resolveSpotifyPlaybackReady(accessToken: String?): Boolean {
        return resolveSpotifyPlaybackReady(accessToken = accessToken, tokenExpiresAt = null)
    }

    private fun resolveSpotifyPlaybackReady(accessToken: String?, tokenExpiresAt: Long?): Boolean {
        val token = accessToken?.trim().orEmpty()
        if (token.isBlank()) return false
        return rustBridge.validateAndInitSpotifySession(
            accessToken = token,
            clientId = SpotifyClientIds.ACTIVE,
            tokenExpiresAt = tokenExpiresAt
        ) ?: false
    }

    private fun computeTokenExpiresAt(expiresIn: Int?): Long? {
        val safeExpiresIn = expiresIn?.takeIf { it > 0 } ?: return null
        return System.currentTimeMillis() + (safeExpiresIn * 1000L)
    }

    private fun buildRustSpotifyAuthUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        fallbackAuthorizationUrl: String
    ): String? {
        val parsedFallback = runCatching { Uri.parse(fallbackAuthorizationUrl) }.getOrNull()
        val codeChallenge = parsedFallback?.getQueryParameter("code_challenge")
            ?.takeIf { it.isNotBlank() }
        val scopes = parsedFallback?.getQueryParameter("scope")
            ?.split(' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

        val payload = JSONObject()
            .put("client_id", clientId)
            .put("redirect_uri", redirectUri)
            .put("state", state)
        if (codeChallenge != null) {
            payload.put("code_challenge", codeChallenge)
        }
        if (scopes != null) {
            payload.put("scopes", JSONArray(scopes))
        }

        val rawResponse = rustBridge.spotifyBeginAuth(payload.toString()) ?: return null
        val response = runCatching { JSONObject(rawResponse) }
            .onFailure { error ->
                Log.w(TAG, "Invalid JSON from rust spotifyBeginAuth: $rawResponse", error)
            }
            .getOrNull()
            ?: return null
        if (!response.optBoolean("ok", false)) {
            return null
        }
        return response
            .optJSONObject("data")
            ?.optString("auth_url")
            ?.takeIf { it.isNotBlank() }
    }

    private fun notifyRustSpotifyExchangeBoundary(
        code: String,
        verifier: String,
        redirectUri: String
    ) {
        val rawResponse = rustBridge.spotifyExchangeCode(code, verifier, redirectUri) ?: return
        val response = runCatching { JSONObject(rawResponse) }
            .onFailure { error ->
                Log.w(TAG, "Invalid JSON from rust spotifyExchangeCode: $rawResponse", error)
            }
            .getOrNull()
            ?: return
        if (response.optBoolean("ok", false)) {
            return
        }

        val error = response.optJSONObject("error")
        val errorCode = error?.optString("code").orEmpty()
        if (errorCode.isNotBlank() && errorCode != "platform_auth_required") {
            val message = error?.optString("message").orEmpty()
            Log.w(TAG, "Unexpected rust spotifyExchangeCode error: $errorCode $message")
        }
    }
}
