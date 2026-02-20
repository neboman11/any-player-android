package com.anyplayer.android.core.rust

import android.util.Log
import com.anyplayer.android.BuildConfig
import com.anyplayer.android.core.model.RepeatMode
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
