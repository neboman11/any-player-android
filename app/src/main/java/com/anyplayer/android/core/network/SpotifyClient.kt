package com.anyplayer.android.core.network

import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
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
            return SpotifyTokenExchangeResult(accessToken = accessToken, refreshToken = refreshToken)
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
            return SpotifyTokenExchangeResult(accessToken = accessToken, refreshToken = newRefreshToken)
        }
    }

    fun validate(accessToken: String): ProviderConnectionCheck {
        val token = accessToken.trim()
        if (token.isBlank()) {
            return ProviderConnectionCheck.Failed("Spotify access token is required")
        }

        val profile = execute(
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

    fun getPlaylists(accessToken: String, offset: Int = 0, limit: Int = 50): List<Playlist> {
        val root = execute(
            path = "me/playlists",
            token = accessToken,
            query = mapOf(
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { playlistItem ->
            val obj = playlistItem.jsonObject
            val id = obj["id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Playlist(
                id = id,
                name = obj["name"].jsonPrimitiveStringOrEmpty,
                owner = obj["owner"].jsonObject["display_name"].jsonPrimitiveStringOrNull ?: "Spotify",
                trackCount = obj["tracks"].jsonObject["total"].jsonPrimitiveIntOrZero,
                source = SourceType.SPOTIFY,
                imageUrl = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url").jsonPrimitiveStringOrNull,
                description = obj["description"].jsonPrimitiveStringOrNull,
                tracks = null
            )
        }
    }

    fun getPlaylistTracks(accessToken: String, playlistId: String, offset: Int = 0, limit: Int = 100): List<Track> {
        val root = execute(
            path = "playlists/$playlistId/tracks",
            token = accessToken,
            query = mapOf(
                "market" to "from_token",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 100).toString()
            )
        ) ?: return emptyList()

        val items = root["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { item ->
            val trackObj = item.jsonObject["track"].jsonObject
            val id = trackObj["id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            val artists = trackObj["artists"] as? JsonArray
            val album = trackObj["album"].jsonObject
            Track(
                id = id,
                title = trackObj["name"].jsonPrimitiveStringOrEmpty,
                artist = artists?.firstOrNull()?.jsonObject?.get("name").jsonPrimitiveStringOrNull ?: "Unknown Artist",
                album = album["name"].jsonPrimitiveStringOrNull,
                durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull,
                source = SourceType.SPOTIFY,
                url = "spotify:track:$id",
                imageUrl = (album["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url").jsonPrimitiveStringOrNull,
                enriched = true
            )
        }
    }

    fun searchTracks(accessToken: String, query: String, offset: Int = 0, limit: Int = 50): List<Track> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val root = execute(
            path = "search",
            token = accessToken,
            query = mapOf(
                "q" to normalizedQuery,
                "type" to "track",
                "market" to "from_token",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["tracks"].jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { item ->
            val trackObj = item.jsonObject
            val id = trackObj["id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            val artists = trackObj["artists"] as? JsonArray
            val album = trackObj["album"].jsonObject
            Track(
                id = id,
                title = trackObj["name"].jsonPrimitiveStringOrEmpty,
                artist = artists?.firstOrNull()?.jsonObject?.get("name").jsonPrimitiveStringOrNull ?: "Unknown Artist",
                album = album["name"].jsonPrimitiveStringOrNull,
                durationMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull,
                source = SourceType.SPOTIFY,
                url = "spotify:track:$id",
                imageUrl = (album["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url").jsonPrimitiveStringOrNull,
                enriched = true
            )
        }
    }

    fun startPlayback(accessToken: String, trackIds: List<String>, startIndex: Int, deviceId: String? = null): Boolean {
        val uris = trackIds.map { "spotify:track:$it" }
        if (uris.isEmpty()) return false
        val urisJson = uris.joinToString(prefix = "[", postfix = "]") { uri -> "\"$uri\"" }
        val payload = "{" +
            "\"uris\":$urisJson," +
            "\"offset\":{\"position\":${startIndex.coerceIn(0, uris.lastIndex)}}" +
            "}"
        val query = buildMap {
            deviceId?.takeIf { it.isNotBlank() }?.let { put("device_id", it) }
        }
        return executePlayerWrite("me/player/play", accessToken, "PUT", payload, query)
    }

    fun play(accessToken: String): Boolean = executePlayerWrite("me/player/play", accessToken, "PUT", "{}")

    fun pause(accessToken: String): Boolean = executePlayerWrite("me/player/pause", accessToken, "PUT", null)

    fun next(accessToken: String): Boolean = executePlayerWrite("me/player/next", accessToken, "POST", null)

    fun previous(accessToken: String): Boolean = executePlayerWrite("me/player/previous", accessToken, "POST", null)

    fun seek(accessToken: String, positionMs: Long): Boolean {
        val url = buildUrl("me/player/seek", mapOf("position_ms" to positionMs.coerceAtLeast(0).toString()))
        return executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setVolume(accessToken: String, volumePercent: Int): Boolean {
        val url = buildUrl("me/player/volume", mapOf("volume_percent" to volumePercent.coerceIn(0, 100).toString()))
        return executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setShuffle(accessToken: String, enabled: Boolean): Boolean {
        val url = buildUrl("me/player/shuffle", mapOf("state" to enabled.toString()))
        return executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setRepeatMode(accessToken: String, repeatMode: RepeatMode): Boolean {
        val spotifyState = when (repeatMode) {
            RepeatMode.OFF -> "off"
            RepeatMode.ONE -> "track"
            RepeatMode.ALL -> "context"
        }
        val url = buildUrl("me/player/repeat", mapOf("state" to spotifyState))
        return executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun getAvailableDeviceId(accessToken: String): String? {
        val devices = getAvailableDevices(accessToken)
        return devices.firstOrNull { it.isActive }?.id ?: devices.firstOrNull()?.id
    }

    fun getAvailableDevices(accessToken: String): List<SpotifyDevice> {
        val root = execute("me/player/devices", accessToken, emptyMap()) ?: return emptyList()
        val devices = root["devices"] as? JsonArray ?: return emptyList()
        return devices.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            SpotifyDevice(
                id = id,
                name = obj["name"].jsonPrimitiveStringOrEmpty,
                type = obj["type"].jsonPrimitiveStringOrEmpty,
                isActive = obj["is_active"].jsonPrimitiveBooleanOrFalse,
                isRestricted = obj["is_restricted"].jsonPrimitiveBooleanOrFalse
            )
        }
    }

    fun transferPlayback(accessToken: String, deviceId: String, play: Boolean = false): Boolean {
        if (deviceId.isBlank()) return false
        val payload = "{" +
            "\"device_ids\":[\"$deviceId\"]," +
            "\"play\":${play}" +
            "}"
        return executePlayerWrite("me/player", accessToken, "PUT", payload)
    }

    fun getPlaybackState(accessToken: String): SpotifyPlaybackState? {
        val root = execute("me/player", accessToken, emptyMap()) ?: return null
        val item = root["item"].jsonObject
        val currentId = item["id"].jsonPrimitiveStringOrNull
        val repeatMode = when (root["repeat_state"].jsonPrimitiveStringOrNull) {
            "track" -> RepeatMode.ONE
            "context" -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        return SpotifyPlaybackState(
            isPlaying = root["is_playing"].jsonPrimitiveBooleanOrFalse,
            progressMs = root["progress_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            volumePercent = root["device"].jsonObject["volume_percent"].jsonPrimitiveIntOrZero,
            shuffleEnabled = root["shuffle_state"].jsonPrimitiveBooleanOrFalse,
            repeatMode = repeatMode,
            currentTrackId = currentId
        )
    }

    fun searchPlaylists(accessToken: String, query: String, offset: Int = 0, limit: Int = 50): List<Playlist> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val root = execute(
            path = "search",
            token = accessToken,
            query = mapOf(
                "q" to normalizedQuery,
                "type" to "playlist",
                "offset" to offset.coerceAtLeast(0).toString(),
                "limit" to limit.coerceIn(1, 50).toString()
            )
        ) ?: return emptyList()

        val items = root["playlists"].jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"].jsonPrimitiveStringOrNull ?: return@mapNotNull null
            Playlist(
                id = id,
                name = obj["name"].jsonPrimitiveStringOrEmpty,
                owner = obj["owner"].jsonObject["display_name"].jsonPrimitiveStringOrNull ?: "Spotify",
                trackCount = obj["tracks"].jsonObject["total"].jsonPrimitiveIntOrZero,
                source = SourceType.SPOTIFY,
                imageUrl = (obj["images"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("url").jsonPrimitiveStringOrNull,
                description = obj["description"].jsonPrimitiveStringOrNull,
                tracks = null
            )
        }
    }

    private fun execute(path: String, token: String, query: Map<String, String>): JsonObject? {
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

    private fun executePlayerWrite(path: String, token: String, method: String, jsonBody: String?, query: Map<String, String> = emptyMap()): Boolean {
        val url = buildUrl(path, query)
        return executePlayerWriteAbsolute(url, token, method, jsonBody)
    }

    private fun executePlayerWriteAbsolute(url: String, token: String, method: String, jsonBody: String?): Boolean {
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

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val base = "https://api.spotify.com/v1/${path.trimStart('/')}".toHttpUrlOrNull()
            ?: return "https://api.spotify.com/v1/${path.trimStart('/')}"
        val builder = base.newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
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
    val refreshToken: String?
)

private val JsonElement?.jsonObject: JsonObject
    get() = this as? JsonObject ?: JsonObject(emptyMap())

private val JsonElement?.jsonPrimitiveStringOrNull: String?
    get() = this?.jsonPrimitive?.contentOrNull

private val JsonElement?.jsonPrimitiveStringOrEmpty: String
    get() = this?.jsonPrimitive?.contentOrNull.orEmpty()

private val JsonElement?.jsonPrimitiveIntOrZero: Int
    get() = this?.jsonPrimitive?.intOrNull ?: 0

private val JsonElement?.jsonPrimitiveBooleanOrFalse: Boolean
    get() = this?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false

data class SpotifyPlaybackState(
    val isPlaying: Boolean,
    val progressMs: Long,
    val endOfTrack: Boolean = false,
    val volumePercent: Int,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val currentTrackId: String?
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean
)
