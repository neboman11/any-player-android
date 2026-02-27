package com.anyplayer.android.core.rust

import android.util.Log
import com.anyplayer.android.BuildConfig
import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.Playlist
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.core.network.ProviderConnectionCheck
import com.anyplayer.android.core.network.SpotifyPlaybackState
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RustBridge @Inject constructor() {
    companion object {
        private const val TAG = "RustBridge"
    }

    @Volatile
    var lastError: String? = null
        private set

    fun isAvailable(): Boolean = RustBridgeNative.isLoaded

    fun spotifyBeginAuth(configJson: String): String? =
        callRaw("spotifyBeginAuth") { RustBridgeNative.spotifyBeginAuth(configJson) }

    fun spotifyExchangeCode(code: String, verifier: String, redirect: String): String? =
        callRaw("spotifyExchangeCode") { RustBridgeNative.spotifyExchangeCode(code, verifier, redirect) }

    /**
     * Returns:
     * - true/false when Rust bridge handled the request
     * - null when JNI is unavailable or bridge call failed, so callers can use Kotlin fallback
     */
    fun validateAndInitSpotifySession(
        accessToken: String,
        clientId: String = BuildConfig.SPOTIFY_CLIENT_ID
    ): Boolean? {
        val normalizedToken = accessToken.trim()
        if (normalizedToken.isBlank()) return false

        val normalizedClientId = clientId.trim()
        if (normalizedClientId.isBlank()) return null

        val initPayload = JSONObject()
            .put("client_id", normalizedClientId)
            .toString()
        val initResponse = callJson("init") { RustBridgeNative.init(initPayload) } ?: return null
        if (!initResponse.optBoolean("ok", false)) {
            logBridgeError("init", initResponse)
            return null
        }

        val validateResponse = callJson("spotifyValidateToken") {
            RustBridgeNative.spotifyValidateToken(normalizedToken)
        } ?: return null
        if (!validateResponse.optBoolean("ok", false)) {
            logBridgeError("spotifyValidateToken", validateResponse)
            return null
        }
        val valid = validateResponse.optJSONObject("data")?.optBoolean("valid", false) ?: false
        if (!valid) return false

        val initSessionResponse = callJson("spotifyInitSession") {
            RustBridgeNative.spotifyInitSession(normalizedToken)
        } ?: return null
        if (!initSessionResponse.optBoolean("ok", false)) {
            logBridgeError("spotifyInitSession", initSessionResponse)
            return null
        }
        val initReady = initSessionResponse.optJSONObject("data")?.optBoolean("ready", false) ?: false
        if (initReady) return true

        val readyResponse = callJson("spotifySessionReady") {
            RustBridgeNative.spotifySessionReady()
        } ?: return null
        if (!readyResponse.optBoolean("ok", false)) {
            logBridgeError("spotifySessionReady", readyResponse)
            return null
        }
        return readyResponse.optJSONObject("data")?.optBoolean("ready", false) ?: false
    }

    fun spotifyStartQueue(
        accessToken: String,
        trackIds: List<String>,
        startIndex: Int,
        deviceId: String? = null
    ): Boolean? {
        val token = accessToken.trim()
        if (token.isBlank()) return false
        if (trackIds.isEmpty()) return false

        val ids = JSONArray()
        trackIds.forEach { id ->
            val normalized = id.trim()
            if (normalized.isNotEmpty()) {
                ids.put(normalized)
            }
        }
        if (ids.length() == 0) return false

        val payload = JSONObject()
            .put("access_token", token)
            .put("track_ids", ids)
            .put("start_index", startIndex.coerceAtLeast(0))

        val normalizedDeviceId = deviceId?.trim().orEmpty()
        if (normalizedDeviceId.isNotEmpty()) {
            payload.put("device_id", normalizedDeviceId)
        }

        return callBoolean("spotifyStartQueue") {
            RustBridgeNative.spotifyStartQueue(payload.toString())
        }
    }

    fun spotifyPlay(): Boolean? =
        callBoolean("spotifyPlay") { RustBridgeNative.spotifyPlay() }

    fun spotifyPause(): Boolean? =
        callBoolean("spotifyPause") { RustBridgeNative.spotifyPause() }

    fun spotifyNext(): Boolean? =
        callBoolean("spotifyNext") { RustBridgeNative.spotifyNext() }

    fun spotifyPrevious(): Boolean? =
        callBoolean("spotifyPrevious") { RustBridgeNative.spotifyPrevious() }

    fun spotifySeek(positionMs: Long): Boolean? {
        val payload = JSONObject()
            .put("position_ms", positionMs.coerceAtLeast(0L))
            .toString()
        return callBoolean("spotifySeek") { RustBridgeNative.spotifySeek(payload) }
    }

    fun spotifySetVolume(volumePercent: Int): Boolean? {
        val payload = JSONObject()
            .put("volume_percent", volumePercent.coerceIn(0, 100))
            .toString()
        return callBoolean("spotifySetVolume") { RustBridgeNative.spotifySetVolume(payload) }
    }

    fun getAudioNormalizationSettings(): AudioNormalizationSettings? {
        val response = callJson("getAudioNormalizationSettings") {
            RustBridgeNative.getAudioNormalizationSettings()
        } ?: return null
        if (!response.optBoolean("ok", false)) {
            logBridgeError("getAudioNormalizationSettings", response)
            return null
        }

        val data = response.optJSONObject("data") ?: return null
        return AudioNormalizationSettings(
            enabled = data.optBoolean("enabled", false),
            strictMode = data.optBoolean("strict_mode", false)
        )
    }

    fun setAudioNormalizationSettings(enabled: Boolean, strictMode: Boolean): Boolean? {
        val payload = JSONObject()
            .put("enabled", enabled)
            .put("strict_mode", strictMode)
            .toString()
        return callBoolean("setAudioNormalizationSettings") {
            RustBridgeNative.setAudioNormalizationSettings(payload)
        }
    }

    fun applyAudioNormalizationVolume(volumePercent: Int, source: SourceType): Int? {
        val payload = JSONObject()
            .put("volume_percent", volumePercent.coerceIn(0, 100))
            .put("source", source.name.lowercase())
            .toString()

        val response = callJson("applyAudioNormalizationVolume") {
            RustBridgeNative.applyAudioNormalizationVolume(payload)
        } ?: return null

        if (!response.optBoolean("ok", false)) {
            logBridgeError("applyAudioNormalizationVolume", response)
            return null
        }

        return response
            .optJSONObject("data")
            ?.optInt("normalized_volume_percent", volumePercent.coerceIn(0, 100))
            ?.coerceIn(0, 100)
    }

    fun spotifySetShuffle(enabled: Boolean): Boolean? {
        val payload = JSONObject()
            .put("enabled", enabled)
            .toString()
        return callBoolean("spotifySetShuffle") { RustBridgeNative.spotifySetShuffle(payload) }
    }

    fun spotifySetRepeatMode(repeatMode: RepeatMode): Boolean? {
        val payload = JSONObject()
            .put("mode", repeatModeToWireValue(repeatMode))
            .toString()
        return callBoolean("spotifySetRepeatMode") { RustBridgeNative.spotifySetRepeatMode(payload) }
    }

    fun spotifySnapshot(): SpotifyPlaybackState? {
        val response = callJson("spotifySnapshot") { RustBridgeNative.spotifySnapshot() } ?: return null
        if (!response.optBoolean("ok", false)) {
            logBridgeError("spotifySnapshot", response)
            return null
        }

        val data = response.optJSONObject("data") ?: return null
        return SpotifyPlaybackState(
            isPlaying = data.optBoolean("is_playing", false),
            progressMs = data.optLong("progress_ms", 0L).coerceAtLeast(0L),
            endOfTrack = data.optBoolean("end_of_track", false),
            volumePercent = data.optInt("volume_percent", 100).coerceIn(0, 100),
            shuffleEnabled = data.optBoolean("shuffle_enabled", false),
            repeatMode = repeatModeFromWireValue(data.optString("repeat_mode", "off")),
            currentTrackId = data.optString("current_track_id").takeIf { it.isNotBlank() }
        )
    }

    fun providerGetPlaylists(
        source: SourceType,
        session: Map<String, String>,
        offset: Int,
        limit: Int
    ): List<Playlist>? {
        val response = providerCall(
            source = source,
            operation = "get_playlists",
            session = session,
            offset = offset,
            limit = limit
        ) ?: return null

        val data = response.optJSONObject("data") ?: return null
        return parsePlaylists(data.optJSONArray("playlists"), source)
    }

    fun providerValidateConnection(
        source: SourceType,
        session: Map<String, String>
    ): ProviderConnectionCheck? {
        if (!isAvailable()) return null

        val response = providerCall(
            source = source,
            operation = "validate_connection",
            session = session
        ) ?: return ProviderConnectionCheck.Failed(lastError ?: "Provider validation failed")

        val data = response.optJSONObject("data")
            ?: return ProviderConnectionCheck.Failed("Provider validation returned no data")

        val connected = data.optBoolean("connected", false)
        if (!connected) {
            return ProviderConnectionCheck.Failed(
                data.optString("message").ifBlank { "Provider validation failed" }
            )
        }

        val username = data.optString("username").takeIf { it.isNotBlank() }
        val metadata = mutableMapOf<String, String>()
        val metadataObject = data.optJSONObject("metadata")
        if (metadataObject != null) {
            val keys = metadataObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = metadataObject.optString(key)
                if (value.isNotBlank()) {
                    metadata[key] = value
                }
            }
        }

        return ProviderConnectionCheck.Connected(username = username, metadata = metadata)
    }

    fun providerGetPlaylistTracks(
        source: SourceType,
        session: Map<String, String>,
        playlistId: String,
        offset: Int,
        limit: Int
    ): List<Track>? {
        val response = providerCall(
            source = source,
            operation = "get_playlist",
            session = session,
            id = playlistId,
            offset = offset,
            limit = limit
        ) ?: return null

        val playlist = response
            .optJSONObject("data")
            ?.optJSONObject("playlist")
            ?: return null
        return parseTracks(playlist.optJSONArray("tracks"), source)
    }

    fun providerSearchTracks(
        source: SourceType,
        session: Map<String, String>,
        query: String,
        offset: Int,
        limit: Int
    ): List<Track>? {
        val response = providerCall(
            source = source,
            operation = "search_tracks",
            session = session,
            query = query,
            offset = offset,
            limit = limit
        ) ?: return null

        val data = response.optJSONObject("data") ?: return null
        return parseTracks(data.optJSONArray("tracks"), source)
    }

    fun providerSearchPlaylists(
        source: SourceType,
        session: Map<String, String>,
        query: String,
        offset: Int,
        limit: Int
    ): List<Playlist>? {
        val response = providerCall(
            source = source,
            operation = "search_playlists",
            session = session,
            query = query,
            offset = offset,
            limit = limit
        ) ?: return null

        val data = response.optJSONObject("data") ?: return null
        return parsePlaylists(data.optJSONArray("playlists"), source)
    }

    private fun repeatModeToWireValue(repeatMode: RepeatMode): String = when (repeatMode) {
        RepeatMode.OFF -> "off"
        RepeatMode.ONE -> "one"
        RepeatMode.ALL -> "all"
    }

    private fun repeatModeFromWireValue(rawValue: String): RepeatMode = when (rawValue.trim().lowercase()) {
        "one", "track" -> RepeatMode.ONE
        "all", "context" -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    private fun providerCall(
        source: SourceType,
        operation: String,
        session: Map<String, String>,
        id: String? = null,
        query: String? = null,
        offset: Int = 0,
        limit: Int = 100
    ): JSONObject? {
        if (!isAvailable()) return null

        val sourceName = source.name.lowercase()
        if (sourceName != "jellyfin" && sourceName != "plex") {
            throw IllegalArgumentException("Unsupported source for providerCall: $source")
        }

        val sessionJson = JSONObject().apply {
            session.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    put(key, value)
                }
            }
        }

        val payload = JSONObject()
            .put("source", sourceName)
            .put("operation", operation)
            .put("session", sessionJson)
            .put("offset", offset.coerceAtLeast(0))
            .put("limit", limit.coerceAtLeast(1))

        if (!id.isNullOrBlank()) {
            payload.put("id", id.trim())
        }
        if (!query.isNullOrBlank()) {
            payload.put("query", query.trim())
        }

        val response = callJson("providerApiCall") {
            RustBridgeNative.providerApiCall(payload.toString())
        } ?: return null

        if (!response.optBoolean("ok", false)) {
            logBridgeError("providerApiCall", response)
            return null
        }

        return response
    }

    private fun parsePlaylists(array: JSONArray?, fallbackSource: SourceType): List<Playlist> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Playlist(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        owner = item.optString("owner", fallbackSource.name.lowercase()),
                        trackCount = item.optInt("track_count", 0),
                        source = sourceFromWire(item.optString("source")) ?: fallbackSource,
                        imageUrl = item.optString("image_url").takeIf { it.isNotBlank() },
                        tracks = parseTracks(item.optJSONArray("tracks"), fallbackSource).takeIf { it.isNotEmpty() },
                        description = item.optString("description").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun parseTracks(array: JSONArray?, fallbackSource: SourceType): List<Track> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val source = sourceFromWire(item.optString("source")) ?: fallbackSource
                add(
                    Track(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album").takeIf { it.isNotBlank() },
                        durationMs = item.optLong("duration_ms", 0L).takeIf { it > 0L },
                        source = source,
                        url = item.optString("url").takeIf { it.isNotBlank() },
                        imageUrl = item.optString("image_url").takeIf { it.isNotBlank() },
                        bitrateKbps = item.optInt("bitrate_kbps", -1).takeIf { it >= 0 },
                        sampleRateHz = item.optInt("sample_rate_hz", -1).takeIf { it >= 0 },
                        enriched = item.optBoolean("enriched", false)
                    )
                )
            }
        }
    }

    private fun sourceFromWire(raw: String?): SourceType? = when (raw?.trim()?.lowercase()) {
        "spotify" -> SourceType.SPOTIFY
        "jellyfin" -> SourceType.JELLYFIN
        "plex" -> SourceType.PLEX
        "custom" -> SourceType.CUSTOM
        "all" -> SourceType.ALL
        else -> null
    }

    private fun callBoolean(methodName: String, block: () -> String): Boolean? {
        val response = callJson(methodName, block) ?: return null
        if (response.optBoolean("ok", false)) {
            lastError = null
            return true
        }
        logBridgeError(methodName, response)
        return false
    }

    private fun callRaw(methodName: String, block: () -> String): String? {
        if (!RustBridgeNative.isLoaded) return null
        return runCatching(block)
            .onFailure { error ->
                lastError = "JNI call failed for $methodName: ${error.message ?: error::class.java.simpleName}"
                Log.w(TAG, "Rust JNI call failed for $methodName", error)
            }
            .getOrNull()
    }

    private fun callJson(methodName: String, block: () -> String): JSONObject? {
        val raw = callRaw(methodName, block) ?: return null
        return runCatching { JSONObject(raw) }
            .onFailure { error ->
                lastError = "Invalid JSON from Rust bridge method $methodName"
                Log.w(TAG, "Invalid JSON from Rust bridge method $methodName: $raw", error)
            }
            .getOrNull()
    }

    private fun logBridgeError(methodName: String, response: JSONObject) {
        val error = response.optJSONObject("error")
        val code = error?.optString("code").orEmpty()
        val message = error?.optString("message").orEmpty()
        lastError = listOf(code.takeIf { it.isNotBlank() }, message.takeIf { it.isNotBlank() })
            .joinToString(separator = ": ")
            .ifBlank { "Unknown Rust bridge error in $methodName" }
        Log.w(TAG, "Rust bridge method $methodName returned error: $code $message")
    }
}
