package com.anyplayer.android.feature.auth.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Shared low-level HTTP plumbing for the Spotify Web API (`api.spotify.com/v1`) clients. */
@Singleton
class SpotifyApiExecutor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun execute(path: String, token: String, query: Map<String, String>): JsonObject? {
        val url = buildUrl(path, query)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            if (response.code == 204) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return runCatching {
                json.parseToJsonElement(body) as? JsonObject
            }.getOrNull()
        }
    }

    fun executePlayerWrite(path: String, token: String, method: String, jsonBody: String?, query: Map<String, String> = emptyMap()): Boolean {
        val url = buildUrl(path, query)
        return executePlayerWriteAbsolute(url, token, method, jsonBody)
    }

    fun executePlayerWriteAbsolute(url: String, token: String, method: String, jsonBody: String?): Boolean {
        val body = (jsonBody ?: "").toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")

        when (method.uppercase()) {
            "POST" -> requestBuilder.post(body)
            "PUT" -> requestBuilder.put(body)
            else -> requestBuilder.method(method.uppercase(), body)
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            return response.isSuccessful
        }
    }

    fun buildUrl(path: String, query: Map<String, String>): String {
        val base = "https://api.spotify.com/v1/${path.trimStart('/')}".toHttpUrlOrNull()
            ?: return "https://api.spotify.com/v1/${path.trimStart('/')}"
        val builder = base.newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }
}
