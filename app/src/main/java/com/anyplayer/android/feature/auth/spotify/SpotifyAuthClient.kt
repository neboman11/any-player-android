package com.anyplayer.android.feature.auth.spotify

import com.anyplayer.android.core.di.SpotifyHttpClient
import com.anyplayer.android.core.network.ProviderConnectionCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Handles Spotify's PKCE session creation, authorization-code exchange, token refresh, and token validation. */
@Singleton
class SpotifyAuthClient @Inject constructor(
    @SpotifyHttpClient private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val spotifyApiExecutor: SpotifyApiExecutor
) {
    fun createPkceSession(clientId: String, redirectUri: String): SpotifyPkceSession {
        val state = randomUrlSafeString(24)
        val codeVerifier = randomUrlSafeString(64)
        val codeChallenge = codeChallenge(codeVerifier)
        val scope = "playlist-read-private playlist-read-collaborative user-read-private user-read-email user-library-read user-read-playback-state user-modify-playback-state user-read-currently-playing streaming"

        val url = "https://accounts.spotify.com/authorize".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("response_type", "code")
            ?.addQueryParameter("client_id", clientId)
            ?.addQueryParameter("redirect_uri", redirectUri)
            ?.addQueryParameter("scope", scope)
            ?.addQueryParameter("state", state)
            ?.addQueryParameter("code_challenge_method", "S256")
            ?.addQueryParameter("code_challenge", codeChallenge)
            ?.build()
            ?.toString()
            ?: "https://accounts.spotify.com/authorize?response_type=code&client_id=$clientId&redirect_uri=$redirectUri"

        return SpotifyPkceSession(
            state = state,
            codeVerifier = codeVerifier,
            authorizationUrl = url
        )
    }

    fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): SpotifyTokenExchangeResult? {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val accessToken = parsed["access_token"].jsonPrimitiveStringOrNull ?: return null
            val refreshToken = parsed["refresh_token"].jsonPrimitiveStringOrNull
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.intOrNull
            return SpotifyTokenExchangeResult(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = expiresIn
            )
        }
    }

    fun refreshAccessToken(clientId: String, refreshToken: String): SpotifyTokenExchangeResult? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val raw = response.body?.string().orEmpty()
            val parsed = json.parseToJsonElement(raw) as? JsonObject ?: return null
            val accessToken = parsed["access_token"].jsonPrimitiveStringOrNull ?: return null
            val newRefreshToken = parsed["refresh_token"].jsonPrimitiveStringOrNull
            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.intOrNull
            return SpotifyTokenExchangeResult(
                accessToken = accessToken,
                refreshToken = newRefreshToken,
                expiresIn = expiresIn
            )
        }
    }

    fun validate(accessToken: String): ProviderConnectionCheck {
        val token = accessToken.trim()
        if (token.isBlank()) {
            return ProviderConnectionCheck.Failed("Spotify access token is required")
        }

        val profile = spotifyApiExecutor.execute(
            path = "me",
            token = token,
            query = emptyMap()
        ) ?: return ProviderConnectionCheck.Failed("Spotify token validation failed")

        val username = profile["display_name"].jsonPrimitiveStringOrNull
            ?: profile["id"].jsonPrimitiveStringOrNull
        val isPremium = profile["product"].jsonPrimitiveStringOrNull == "premium"

        return ProviderConnectionCheck.Connected(
            username = username,
            metadata = mapOf("isPremium" to isPremium.toString())
        )
    }

    private fun randomUrlSafeString(length: Int): String {
        val byteArray = ByteArray(length)
        SecureRandom().nextBytes(byteArray)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(byteArray)
            .replace("=", "")
            .take(length.coerceAtLeast(16))
    }

    private fun codeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

data class SpotifyPkceSession(
    val state: String,
    val codeVerifier: String,
    val authorizationUrl: String
)

data class SpotifyTokenExchangeResult(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Int?
)
