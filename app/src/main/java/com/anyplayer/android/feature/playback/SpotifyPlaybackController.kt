package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.core.network.SpotifyPlaybackState
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify playback controller backed by the Rust JNI bridge (librespot + rodio).
 *
 * Audio is decoded and rendered directly in-process by the Rust librespot engine.
 * No Spotify app or Spotify Connect device is required.
 */
@Singleton
class SpotifyPlaybackController @Inject constructor(
    private val providerAuthRepository: ProviderAuthRepository,
    private val secureConnectionStore: SecureConnectionStore,
    private val rustBridge: RustBridge
) {
    companion object {
        private const val TAG = "SpotifyPlaybackCtrl"
    }

    private val rustCommandMutex = Mutex()

    /** Human-readable reason for the most recent operation failure. Null when healthy. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Starts playback of [trackIds] beginning at [startIndex].
     *
     * Initialises the librespot session (if not already active) and hands the queue
     * directly to the Rust engine for gapless, in-process audio output.
     */
    suspend fun startQueue(trackIds: List<String>, startIndex: Int): Boolean =
        rustCommandMutex.withLock {
            withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) {
                lastError = "Spotify queue is empty"
                return@withContext false
            }

            val accessToken = requireReadyAccessToken()
            if (accessToken == null) {
                return@withContext false
            }

            val normalizedIndex = startIndex.coerceIn(0, trackIds.lastIndex)
            val started = rustBridge.spotifyStartQueue(accessToken, trackIds, normalizedIndex)
            if (started != true) {
                lastError = "Spotify failed to start playback. ${rustBridge.lastError ?: ""}"
                return@withContext false
            }

            lastError = null
            true
            }
        }

    suspend fun play(): Boolean = runRustCommand("play") { rustBridge.spotifyPlay() }

    suspend fun pause(): Boolean = runRustCommand("pause") { rustBridge.spotifyPause() }

    suspend fun next(): Boolean = runRustCommand("next") { rustBridge.spotifyNext() }

    suspend fun previous(): Boolean = runRustCommand("previous") { rustBridge.spotifyPrevious() }

    suspend fun seekTo(positionMs: Long): Boolean =
        runRustCommand("seek") { rustBridge.spotifySeek(positionMs) }

    suspend fun setVolume(volume: Int): Boolean =
        runRustCommand("setVolume") { rustBridge.spotifySetVolume(volume) }

    fun normalizeVolumeForSource(volume: Int, source: SourceType): Int {
        val normalized = rustBridge.applyAudioNormalizationVolume(volume, source)
        // If the Rust bridge returns null (e.g. normalization disabled or unavailable),
        // fall back to the original volume clamped to the valid range.
        return (normalized ?: volume).coerceIn(0, 100)
    }

    fun getAudioNormalizationSettings(): AudioNormalizationSettings =
        rustBridge.getAudioNormalizationSettings() ?: AudioNormalizationSettings()

    fun setAudioNormalizationSettings(enabled: Boolean, strictMode: Boolean): Boolean {
        val result = rustBridge.setAudioNormalizationSettings(enabled, strictMode)
        if (result != null) return result
        val errorMessage =
            "Failed to set audio normalization settings. ${rustBridge.lastError ?: ""}".trim()
        CompatLog.e(TAG, errorMessage)
        lastError = errorMessage
        return false
    }

    suspend fun setShuffle(enabled: Boolean): Boolean =
        runRustCommand("setShuffle") { rustBridge.spotifySetShuffle(enabled) }

    suspend fun setRepeatMode(mode: RepeatMode): Boolean =
        runRustCommand("setRepeatMode") { rustBridge.spotifySetRepeatMode(mode) }

    suspend fun snapshot(): SpotifyPlaybackState? = rustCommandMutex.withLock {
        withContext(Dispatchers.IO) {
            rustBridge.spotifySnapshot()
        }
    }

    /**
     * Ensures the librespot session is alive, then executes [block].
     * Re-initialises the session transparently when the token has expired.
     */
    private suspend fun runRustCommand(
        action: String,
        block: () -> Boolean?
    ): Boolean = rustCommandMutex.withLock {
        withContext(Dispatchers.IO) {
            if (requireReadyAccessToken() == null) {
                return@withContext false
            }

            val success = block()
            if (success != true) {
                lastError = "Rust Spotify playback command failed: $action. ${rustBridge.lastError ?: ""}"
                CompatLog.w(TAG, "Rust command '$action' failed: ${rustBridge.lastError}")
                return@withContext false
            }

            lastError = null
            true
        }
    }

    private suspend fun requireReadyAccessToken(): String? {
        val accessToken = resolveAccessToken()
        if (accessToken == null) {
            if (lastError == null) {
                lastError = "Spotify access token unavailable. Reconnect Spotify and retry."
            }
            return null
        }

        val tokenExpiresAt = secureConnectionStore.read(SourceType.SPOTIFY)?.tokenExpiresAt
        val sessionReady = rustBridge.validateAndInitSpotifySession(
            accessToken = accessToken,
            clientId = SpotifyClientIds.ACTIVE,
            tokenExpiresAt = tokenExpiresAt
        )
        if (sessionReady != true) {
            lastError = "Spotify session could not be initialised. ${rustBridge.lastError ?: ""}"
            return null
        }

        return accessToken
    }

    private suspend fun resolveAccessToken(): String? {
        val refreshedOrCurrentToken = providerAuthRepository.refreshSpotifyTokenIfNeeded()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (refreshedOrCurrentToken != null) {
            return refreshedOrCurrentToken
        }

        if (secureConnectionStore.read(SourceType.SPOTIFY) == null) {
            lastError = "No Spotify account linked. Link Spotify in Settings."
            return null
        }

        lastError = "Spotify access token refresh failed. Reconnect Spotify and retry."
        return null
    }
}
