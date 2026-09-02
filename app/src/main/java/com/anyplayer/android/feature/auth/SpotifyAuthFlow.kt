package com.anyplayer.android.feature.auth

import android.net.Uri
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.spotify.SpotifyAuthClient
import com.anyplayer.android.feature.auth.spotify.SpotifyClientIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Owns Spotify's PKCE auth begin/complete flow and access-token refresh. */
@Singleton
class SpotifyAuthFlow @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyAuthSessionStore: SpotifyAuthSessionStore,
    private val spotifyAuthClient: SpotifyAuthClient,
    private val rustBridge: RustBridge
) {
    companion object {
        private const val TAG = "SpotifyAuthFlow"
        private const val SPOTIFY_REFRESH_WINDOW_MILLIS = 5 * 60 * 1000L
    }

    // Guards refreshTokenIfNeeded's read-check-refresh-save critical section: it is
    // reached concurrently by SpotifyConnectBridge's poll loop and
    // SpotifyPlaybackController's command path, and without a shared lock the loser
    // of the race can persist a stale/rotated-out refresh token over the winner's.
    private val refreshMutex = Mutex()

    suspend fun beginAuth(clientId: String, redirectUri: String): String {
        return withContext(Dispatchers.IO) {
            val normalizedClientId = clientId.trim()
            val normalizedRedirect = redirectUri.trim()
            require(normalizedClientId.isNotBlank()) { "Spotify client ID is required" }
            require(normalizedRedirect.isNotBlank()) { "Spotify redirect URI is required" }

            val session = spotifyAuthClient.createPkceSession(
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

    suspend fun completeAuth(redirectUriWithCode: String): StoredConnection {
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

            val tokenResult = spotifyAuthClient.exchangeAuthorizationCode(
                clientId = pending.clientId,
                code = authorizationCode,
                codeVerifier = pending.codeVerifier,
                redirectUri = pending.redirectUri
            ) ?: run {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException("Unable to exchange Spotify authorization code")
            }

            val validation = spotifyAuthClient.validate(tokenResult.accessToken)
            if (validation is ProviderConnectionCheck.Failed) {
                spotifyAuthSessionStore.clearPending()
                throw IllegalStateException(validation.message)
            }
            val validated = validation as ProviderConnectionCheck.Connected
            val tokenExpiresAt = computeTokenExpiresAt(tokenResult.expiresIn)
            // validation is already Connected for this exact token, so it's already
            // playback-ready per resolveSpotifyPlaybackReady's own definition - no need
            // to re-validate the same token again.
            val playbackReady = true

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
            connection
        }
    }

    suspend fun refreshTokenIfNeeded(): String? = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val stored = secureConnectionStore.read(SourceType.SPOTIFY) ?: return@withContext null
            val currentToken = stored.token?.trim().orEmpty()
            if (currentToken.isBlank()) return@withContext null

            val expiresAt = stored.tokenExpiresAt ?: return@withContext currentToken
            val now = System.currentTimeMillis()
            val shouldRefresh = now >= (expiresAt - SPOTIFY_REFRESH_WINDOW_MILLIS)
            if (!shouldRefresh) {
                return@withContext currentToken
            }

            val refreshToken = stored.refreshToken?.trim().orEmpty()
            if (refreshToken.isBlank()) {
                return@withContext if (now < expiresAt) currentToken else null
            }

            val refreshed = runCatching {
                spotifyAuthClient.refreshAccessToken(
                    clientId = SpotifyClientIds.ACTIVE,
                    refreshToken = refreshToken
                )
            }.onFailure { error ->
                CompatLog.e(TAG, "Spotify token refresh network call failed", error)
            }.getOrNull() ?: return@withContext if (now < expiresAt) currentToken else null

            val refreshedToken = refreshed.accessToken.trim()
            if (refreshedToken.isBlank()) return@withContext if (now < expiresAt) currentToken else null
            val refreshedTokenExpiresAt = computeTokenExpiresAt(refreshed.expiresIn)

            val updatedConnection = stored.copy(
                token = refreshedToken,
                refreshToken = refreshed.refreshToken ?: stored.refreshToken,
                tokenExpiresAt = refreshedTokenExpiresAt,
                playbackReady = resolveSpotifyPlaybackReady(refreshedToken)
            )
            runCatching { secureConnectionStore.save(updatedConnection) }.onFailure { error ->
                CompatLog.e(TAG, "Failed to save refreshed Spotify token", error)
            }
            refreshedToken
        }
    }

    /**
     * A token is ready for playback once it validates against Spotify's Web API.
     * Native playback initialization no longer exists (the librespot JNI session was
     * removed) and must not change provider connection status.
     */
    fun resolveSpotifyPlaybackReady(accessToken: String?): Boolean {
        val token = accessToken?.trim().orEmpty()
        if (token.isBlank()) return false
        return spotifyAuthClient.validate(token) is ProviderConnectionCheck.Connected
    }

    fun computeTokenExpiresAt(expiresIn: Int?): Long? {
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
                CompatLog.w(TAG, "Invalid JSON from rust spotifyBeginAuth: $rawResponse")
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
                CompatLog.w(TAG, "Invalid JSON from rust spotifyExchangeCode: $rawResponse")
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
            CompatLog.w(TAG, "Unexpected rust spotifyExchangeCode error: $errorCode $message")
        }
    }
}
