package com.anyplayer.android.data.auth

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.data.net.executeRetrying
import com.anyplayer.android.data.net.HttpFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class SpotifyTokens(val accessToken: String, val refreshToken: String?, val expiresIn: Long?)

@Singleton
class SpotifyAccounts(
    private val http: OkHttpClient,
    private val tokenUrl: String,
    private val profileUrl: String = "https://api.spotify.com/v1/me",
) {
    @Inject constructor(http: OkHttpClient) : this(http, "https://accounts.spotify.com/api/token")

    suspend fun exchange(clientId: String, code: String, verifier: String, redirect: String): SpotifyTokens? =
        token(FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect).build())
    suspend fun refresh(clientId: String, refreshToken: String): SpotifyTokens? =
        token(FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId)
            .add("refresh_token", refreshToken).build())
    /** Refresh readiness check uses the fresh token directly, avoiding a token-provider cycle. */
    suspend fun playbackReady(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        http.executeRetrying(Request.Builder().url(profileUrl).header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken").build()).use {
            if (it.code == 401) return@withContext false
            if (!it.isSuccessful) throw HttpFailure(it.code, "Spotify token validation failed")
            AppJson.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
            true
        }
    }
    private suspend fun token(body: FormBody): SpotifyTokens? = withContext(Dispatchers.IO) {
        http.executeRetrying(Request.Builder().url(tokenUrl.toHttpUrl()).header("Accept", "application/json").post(body).build()).use {
            if (it.code == 401 || it.code == 400) throw HttpFailure(it.code, "Spotify token refresh failed")
            if (!it.isSuccessful) return@withContext null
            val json = AppJson.parseToJsonElement(it.body?.string().orEmpty()).jsonObject
            val access = json["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return@withContext null
            SpotifyTokens(access, json["refresh_token"]?.jsonPrimitive?.contentOrNull, json["expires_in"]?.jsonPrimitive?.longOrNull)
        }
    }
}
