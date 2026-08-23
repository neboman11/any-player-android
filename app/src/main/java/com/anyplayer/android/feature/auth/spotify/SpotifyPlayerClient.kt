package com.anyplayer.android.feature.auth.spotify

import com.anyplayer.android.core.model.RepeatMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Spotify Connect playback-control calls (play/pause/seek/devices/etc), used by SpotifyConnectBridge. */
@Singleton
class SpotifyPlayerClient @Inject constructor(
    private val spotifyApiExecutor: SpotifyApiExecutor
) {
    fun startPlayback(accessToken: String, trackIds: List<String>, startIndex: Int, deviceId: String? = null): Boolean {
        val uris = spotifyPlaybackUris(trackIds, startIndex)
        if (uris.isEmpty()) return false
        val urisJson = uris.joinToString(prefix = "[", postfix = "]") { uri -> "\"$uri\"" }
        val payload = "{\"uris\":$urisJson}"
        val query = buildMap {
            deviceId?.takeIf { it.isNotBlank() }?.let { put("device_id", it) }
        }
        return spotifyApiExecutor.executePlayerWrite("me/player/play", accessToken, "PUT", payload, query)
    }

    fun play(accessToken: String): Boolean = spotifyApiExecutor.executePlayerWrite("me/player/play", accessToken, "PUT", "{}")

    fun pause(accessToken: String): Boolean = spotifyApiExecutor.executePlayerWrite("me/player/pause", accessToken, "PUT", null)

    fun next(accessToken: String): Boolean = spotifyApiExecutor.executePlayerWrite("me/player/next", accessToken, "POST", null)

    fun seek(accessToken: String, positionMs: Long): Boolean {
        val url = spotifyApiExecutor.buildUrl("me/player/seek", mapOf("position_ms" to positionMs.coerceAtLeast(0).toString()))
        return spotifyApiExecutor.executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setVolume(accessToken: String, volumePercent: Int): Boolean {
        val url = spotifyApiExecutor.buildUrl("me/player/volume", mapOf("volume_percent" to volumePercent.coerceIn(0, 100).toString()))
        return spotifyApiExecutor.executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setShuffle(accessToken: String, enabled: Boolean): Boolean {
        val url = spotifyApiExecutor.buildUrl("me/player/shuffle", mapOf("state" to enabled.toString()))
        return spotifyApiExecutor.executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun setRepeatMode(accessToken: String, repeatMode: RepeatMode): Boolean {
        val spotifyState = when (repeatMode) {
            RepeatMode.OFF -> "off"
            RepeatMode.ONE -> "track"
            RepeatMode.ALL -> "context"
        }
        val url = spotifyApiExecutor.buildUrl("me/player/repeat", mapOf("state" to spotifyState))
        return spotifyApiExecutor.executePlayerWriteAbsolute(url, accessToken, "PUT", null)
    }

    fun getAvailableDeviceId(accessToken: String): String? {
        val devices = getAvailableDevices(accessToken)
        val controllableDevices = devices.filterNot { it.isRestricted }
        return controllableDevices.firstOrNull { it.isActive }?.id ?: controllableDevices.firstOrNull()?.id
    }

    fun getAvailableDevices(accessToken: String): List<SpotifyDevice> {
        val root = spotifyApiExecutor.execute("me/player/devices", accessToken, emptyMap()) ?: return emptyList()
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

    /** Polls the currently active Spotify Connect session. Returns null both on
     *  network/parse failure and on the API's own "nothing playing" 204/empty
     *  response - callers can't distinguish those cases from this alone, but
     *  both mean there's no state to report. */
    fun getPlaybackState(accessToken: String): SpotifyPlaybackState? {
        val root = spotifyApiExecutor.execute("me/player", accessToken, emptyMap()) ?: return null
        val item = root["item"].jsonObject
        return SpotifyPlaybackState(
            isPlaying = root["is_playing"].jsonPrimitiveBooleanOrFalse,
            progressMs = root["progress_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            durationMs = item["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            volumePercent = root["device"].jsonObject["volume_percent"]?.jsonPrimitive?.intOrNull ?: 100,
            shuffleEnabled = root["shuffle_state"].jsonPrimitiveBooleanOrFalse,
            repeatMode = repeatModeFromWireValue(root["repeat_state"].jsonPrimitiveStringOrEmpty),
            currentTrackId = item["id"].jsonPrimitiveStringOrNull
        )
    }

    private fun repeatModeFromWireValue(rawValue: String): RepeatMode = when (rawValue.trim().lowercase()) {
        "track" -> RepeatMode.ONE
        "context" -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

}

internal fun spotifyPlaybackUris(trackIds: List<String>, startIndex: Int): List<String> =
    trackIds
        .drop(startIndex.coerceIn(0, trackIds.size))
        .asSequence()
        .map { it.trim().removePrefix("spotify:track:") }
        .filter(SPOTIFY_TRACK_ID_PATTERN::matches)
        .take(MAX_SPOTIFY_PLAYBACK_URIS)
        .map { "spotify:track:$it" }
        .toList()

private const val MAX_SPOTIFY_PLAYBACK_URIS = 100
private val SPOTIFY_TRACK_ID_PATTERN = Regex("[A-Za-z0-9]{22}")

data class SpotifyPlaybackState(
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long = 0,
    val endOfTrackCount: Long = 0,
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
