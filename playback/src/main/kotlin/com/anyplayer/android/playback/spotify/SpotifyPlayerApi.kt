package com.anyplayer.android.playback.spotify

import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.SpotifyTokenProvider
import com.anyplayer.android.data.net.await
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** One `GET me/player` poll. [trackId] is `item.id`; [linkedFromId] is `item.linked_from.id` (track relinking). */
data class SpotifyPlaybackSnapshot(
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    val trackId: String?,
    val linkedFromId: String?,
    val volumePercent: Int,
    val shuffle: Boolean,
    val repeat: RepeatMode,
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean,
    val isRestricted: Boolean,
)

/** A failed Spotify call; [message] is the user-facing legacy string. */
class SpotifyCommandException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Spotify Connect Web API (`https://api.spotify.com/v1/me/player…`). Write calls throw
 * [SpotifyCommandException] with the legacy "Spotify command failed: <action>. <detail>" text or the
 * legacy token messages.
 */
interface SpotifyPlayerApi {
    /** Null on 204/empty/non-2xx ("nothing to report"). Throws on token or transport failure. */
    suspend fun playbackState(): SpotifyPlaybackSnapshot?
    /** Empty on non-2xx. Throws on token or transport failure. */
    suspend fun devices(): List<SpotifyDevice>
    /** `PUT me/player/play?device_id=` with `{"uris":[…],"offset":{"position":n}[,"position_ms":p]}`. */
    suspend fun startPlayback(deviceId: String?, trackIds: List<String>, offset: Int, positionMs: Long)
    /** `PUT me/player/play` with `{}`. */
    suspend fun resume()
    suspend fun pause()
    suspend fun seek(positionMs: Long)
    suspend fun setVolume(percent: Int)
    suspend fun setShuffle(enabled: Boolean)
    suspend fun setRepeat(mode: RepeatMode)
}

internal object SpotifyMessages {
    const val NO_ACCOUNT = "No Spotify account linked. Link Spotify in Settings."
    const val TOKEN_REFRESH_FAILED = "Spotify access token refresh failed. Reconnect Spotify and retry."
    const val NOT_INSTALLED = "Spotify isn't installed. Install it, open it, and try again."
    const val NO_DEVICE =
        "No active Spotify Connect device found. Open Spotify on this phone (or another device on the same account) and try again."

    fun commandFailed(action: String, detail: String? = null): String =
        if (detail.isNullOrBlank()) "Spotify command failed: $action" else "Spotify command failed: $action. $detail".trim()

    fun recoveryExhausted(reason: String): String = "Couldn't get Spotify playback back ($reason). Press play to try again."
}

@Singleton
class OkHttpSpotifyPlayerApi internal constructor(
    private val client: OkHttpClient,
    private val tokenProvider: SpotifyTokenProvider,
    private val connectionStore: ConnectionStore,
    private val baseUrl: HttpUrl,
) : SpotifyPlayerApi {

    @Inject
    constructor(
        client: OkHttpClient,
        tokenProvider: SpotifyTokenProvider,
        connectionStore: ConnectionStore,
    ) : this(client, tokenProvider, connectionStore, DEFAULT_BASE_URL.toHttpUrl())

    override suspend fun playbackState(): SpotifyPlaybackSnapshot? {
        val root = getJson("me/player", "playbackState") ?: return null
        val item = root["item"] as? JsonObject
        val linkedFrom = item?.get("linked_from") as? JsonObject
        return SpotifyPlaybackSnapshot(
            isPlaying = root["is_playing"].booleanOrFalse(),
            progressMs = root["progress_ms"].longOrNull() ?: 0L,
            durationMs = item?.get("duration_ms").longOrNull() ?: 0L,
            trackId = item?.get("id").stringOrNull(),
            linkedFromId = linkedFrom?.get("id").stringOrNull(),
            volumePercent = (root["device"] as? JsonObject)?.get("volume_percent").intOrNull() ?: 100,
            shuffle = root["shuffle_state"].booleanOrFalse(),
            repeat = when (root["repeat_state"].stringOrNull()?.trim()?.lowercase()) {
                "track" -> RepeatMode.ONE
                "context" -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
    }

    override suspend fun devices(): List<SpotifyDevice> {
        val root = getJson("me/player/devices", "devices") ?: return emptyList()
        val devices = root["devices"] as? JsonArray ?: return emptyList()
        return devices.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj["id"].stringOrNull() ?: return@mapNotNull null
            SpotifyDevice(
                id = id,
                name = obj["name"].stringOrNull().orEmpty(),
                type = obj["type"].stringOrNull().orEmpty(),
                isActive = obj["is_active"].booleanOrFalse(),
                isRestricted = obj["is_restricted"].booleanOrFalse(),
            )
        }
    }

    override suspend fun startPlayback(deviceId: String?, trackIds: List<String>, offset: Int, positionMs: Long) {
        val uris = trackIds.joinToString(prefix = "[", postfix = "]") { "\"spotify:track:$it\"" }
        val position = if (positionMs > 0) ",\"position_ms\":$positionMs" else ""
        val body = "{\"uris\":$uris,\"offset\":{\"position\":$offset}$position}"
        val query = deviceId?.takeIf { it.isNotBlank() }?.let { mapOf("device_id" to it) }.orEmpty()
        write("startQueue", "PUT", "me/player/play", query, body)
    }

    override suspend fun resume() = write("play", "PUT", "me/player/play", emptyMap(), "{}")

    override suspend fun pause() = write("pause", "PUT", "me/player/pause", emptyMap(), null)

    override suspend fun seek(positionMs: Long) =
        write("seek", "PUT", "me/player/seek", mapOf("position_ms" to positionMs.coerceAtLeast(0).toString()), null)

    override suspend fun setVolume(percent: Int) =
        write("setVolume", "PUT", "me/player/volume", mapOf("volume_percent" to percent.coerceIn(0, 100).toString()), null)

    override suspend fun setShuffle(enabled: Boolean) =
        write("setShuffle", "PUT", "me/player/shuffle", mapOf("state" to enabled.toString()), null)

    override suspend fun setRepeat(mode: RepeatMode) {
        val state = when (mode) {
            RepeatMode.OFF -> "off"
            RepeatMode.ONE -> "track"
            RepeatMode.ALL -> "context"
        }
        write("setRepeatMode", "PUT", "me/player/repeat", mapOf("state" to state), null)
    }

    private suspend fun getJson(path: String, action: String): JsonObject? =
        authorized(action) { token ->
            Request.Builder().url(url(path, emptyMap())).headers(token).get().build()
        }.use { response ->
            if (!response.isSuccessful || response.code == 204) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        }

    private suspend fun write(action: String, method: String, path: String, query: Map<String, String>, json: String?) {
        val ok = authorized(action) { token ->
            Request.Builder()
                .url(url(path, query))
                .headers(token)
                .method(method, (json ?: "").toRequestBody(JSON))
                .build()
        }.use { it.isSuccessful }
        if (!ok) throw SpotifyCommandException(SpotifyMessages.commandFailed(action))
    }

    /** Bearer token per call; a 401 forces one refresh and one retry. */
    private suspend fun authorized(action: String, request: (String) -> Request): Response {
        val token = tokenProvider.accessToken()?.trim()?.takeIf { it.isNotEmpty() } ?: throw tokenError()
        val first = execute(action, request(token))
        if (first.code != 401) return first
        first.close()
        val refreshed = tokenProvider.refreshAfterUnauthorized()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SpotifyCommandException(SpotifyMessages.TOKEN_REFRESH_FAILED)
        return execute(action, request(refreshed))
    }

    private suspend fun tokenError(): SpotifyCommandException {
        val linked = connectionStore.awaitLoaded()[SourceType.SPOTIFY] != null
        return SpotifyCommandException(if (linked) SpotifyMessages.TOKEN_REFRESH_FAILED else SpotifyMessages.NO_ACCOUNT)
    }

    private suspend fun execute(action: String, request: Request): Response =
        try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SpotifyCommandException(SpotifyMessages.commandFailed(action, e.message), e)
        }

    private fun url(path: String, query: Map<String, String>): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    private fun Request.Builder.headers(token: String): Request.Builder =
        header("Authorization", "Bearer $token").header("Accept", "application/json")

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.spotify.com/v1/"
        val JSON = "application/json".toMediaType()
    }
}


private fun JsonElement?.primitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonElement?.stringOrNull(): String? = primitive()?.takeIf { it.isString }?.content

private fun JsonElement?.booleanOrFalse(): Boolean = primitive()?.booleanOrNull ?: false

private fun JsonElement?.longOrNull(): Long? = primitive()?.longOrNull

private fun JsonElement?.intOrNull(): Int? = primitive()?.intOrNull
