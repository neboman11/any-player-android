package com.anyplayer.android.feature.playback

import android.util.Log
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.core.network.SpotifyPlaybackState
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rust-backed Spotify playback controller.
 *
 * Spotify playback commands are executed through the Rust JNI bridge,
 * which now owns the playback command path for Android.
 */
@Singleton
class SpotifyPlaybackController @Inject constructor(
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyClient: SpotifyClient,
    private val rustBridge: RustBridge
) {
    companion object {
        private const val TAG = "SpotifyPlaybackCtrl"
    }

    /** Human-readable reason for the most recent operation failure. Null when healthy. */
    @Volatile var lastError: String? = null
        private set

    suspend fun startQueue(trackIds: List<String>, startIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) {
                lastError = "Spotify queue is empty"
                return@withContext false
            }
            if (!rustBridge.isAvailable()) {
                lastError = "Rust Spotify playback bridge unavailable"
                return@withContext false
            }

            val accessToken = resolveAccessToken()
            if (accessToken == null) {
                if (lastError == null) {
                    lastError = "Spotify access token unavailable. Reconnect Spotify and retry."
                }
                return@withContext false
            }

            // Preserve existing readiness semantics for profile state, but playback commands
            // now execute through Rust regardless of this boolean result.
            rustBridge.validateAndInitSpotifySession(accessToken, SpotifyClientIds.ACTIVE)

            val normalizedIndex = startIndex.coerceIn(0, trackIds.lastIndex)
            when (rustBridge.spotifyStartQueue(accessToken, trackIds, normalizedIndex)) {
                true -> {
                    lastError = null
                    true
                }
                false -> {
                    lastError = "Rust Spotify playback rejected startQueue"
                    false
                }
                null -> {
                    lastError = "Rust Spotify playback bridge unavailable"
                    false
                }
            }
        }

    suspend fun play(): Boolean = runRustCommand("play") {
        rustBridge.spotifyPlay()
    }

    suspend fun pause(): Boolean = runRustCommand("pause") {
        rustBridge.spotifyPause()
    }

    suspend fun next(): Boolean = runRustCommand("next") {
        rustBridge.spotifyNext()
    }

    suspend fun previous(): Boolean = runRustCommand("previous") {
        rustBridge.spotifyPrevious()
    }

    suspend fun seekTo(positionMs: Long): Boolean = runRustCommand("seek") {
        rustBridge.spotifySeek(positionMs)
    }

    suspend fun setVolume(volume: Int): Boolean = runRustCommand("setVolume") {
        rustBridge.spotifySetVolume(volume)
    }

    suspend fun setShuffle(enabled: Boolean): Boolean = runRustCommand("setShuffle") {
        rustBridge.spotifySetShuffle(enabled)
    }

    suspend fun setRepeatMode(mode: RepeatMode): Boolean = runRustCommand("setRepeatMode") {
        rustBridge.spotifySetRepeatMode(mode)
    }

    suspend fun snapshot(): SpotifyPlaybackState? = withContext(Dispatchers.IO) {
        rustBridge.spotifySnapshot()
    }

    private suspend fun runRustCommand(action: String, block: () -> Boolean?): Boolean =
        withContext(Dispatchers.IO) {
            when (block()) {
                true -> {
                    lastError = null
                    true
                }
                false -> {
                    lastError = "Rust Spotify playback command failed: $action"
                    false
                }
                null -> {
                    lastError = "Rust Spotify playback bridge unavailable"
                    false
                }
            }
        }

    private fun resolveAccessToken(): String? {
        val stored = secureConnectionStore.read(SourceType.SPOTIFY)
            ?: run {
                lastError = "No Spotify account linked. Link Spotify in Settings."
                return null
            }

        val refreshToken = stored.refreshToken?.takeIf { it.isNotBlank() }
        if (refreshToken != null) {
            try {
                val refreshed = spotifyClient.refreshAccessToken(
                    clientId = SpotifyClientIds.ACTIVE,
                    refreshToken = refreshToken
                )
                if (refreshed != null) {
                    val updated = stored.copy(
                        token = refreshed.accessToken,
                        refreshToken = refreshed.refreshToken ?: refreshToken,
                        playbackReady = true
                    )
                    secureConnectionStore.save(updated)
                    return refreshed.accessToken
                }
            } catch (error: Exception) {
                Log.w(TAG, "Spotify token refresh failed; falling back to stored token", error)
            }
        }

        val token = stored.token?.trim().orEmpty()
        return token.takeIf { it.isNotEmpty() }
    }
}
