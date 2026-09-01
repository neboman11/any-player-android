package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState
import com.anyplayer.android.core.rust.RustBridge
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.SecureConnectionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify playback controller backed by the Web API's Connect endpoints (see
 * [SpotifyConnectBridge]), which control whichever Spotify Connect device is
 * currently active on the account rather than decoding audio in-process - Spotify
 * now rejects non-approved client IDs on the private endpoints the old librespot
 * path depended on, a WebView-hosted Web Playback SDK proved unreliable across real
 * OEM Widevine/EME builds, and the App Remote SDK's app-to-app IPC connect() depends
 * on a Background-Activity-Launch exemption that is unreliable on Android 14+.
 * [RustBridge] is kept only for the volume normalization utilities used by
 * non-Spotify sources.
 */
@Singleton
class SpotifyPlaybackController @Inject constructor(
    private val providerAuthRepository: ProviderAuthRepository,
    private val secureConnectionStore: SecureConnectionStore,
    private val rustBridge: RustBridge,
    private val connectBridge: SpotifyConnectBridge
) {
    companion object {
        private const val TAG = "SpotifyPlaybackCtrl"
    }

    private val rustCommandMutex = Mutex()

    /** Human-readable reason for the most recent operation failure. Null when healthy. */
    @Volatile var lastError: String? = null
        private set

    init {
        connectBridge.errorListener = SpotifyConnectBridge.ErrorListener { type, message ->
            lastError = message
            CompatLog.w(TAG, "Spotify Connect error [$type]: $message")
        }
    }

    /** Enables/disables [SpotifyConnectBridge]'s background poll loop's network calls based on
     *  whether Spotify is actually part of the current queue. Called by [PlaybackQueueManager]
     *  whenever the queue's source composition changes. */
    fun setSpotifyPollingActive(active: Boolean) {
        connectBridge.pollingEnabled = active
    }

    /** Starts playback of the track at [startIndex] in [trackIds]. Spotify Connect
     *  plays one URI at a time - Any Player's own queue state machine drives
     *  advancement, the same restart-at-index pattern already used for
     *  user-initiated skip. */
    suspend fun startQueue(trackIds: List<String>, startIndex: Int): Boolean {
        if (trackIds.isEmpty()) {
            lastError = "Spotify queue is empty"
            return false
        }
        return runCommand("startQueue") { connectBridge.playUri(it, trackIds, startIndex) }
    }

    suspend fun play(): Boolean = runCommand("play") { connectBridge.resume(it) }

    suspend fun pause(): Boolean = runCommand("pause") { connectBridge.pause(it) }

    suspend fun next(): Boolean = runCommand("next") { connectBridge.next(it) }

    suspend fun seekTo(positionMs: Long): Boolean =
        runCommand("seek") { connectBridge.seek(it, positionMs) }

    suspend fun setVolume(volume: Int): Boolean {
        val normalizedVolume = normalizeVolumeForSource(volume, SourceType.SPOTIFY)
        return runCommand("setVolume") { connectBridge.setVolume(it, normalizedVolume) }
    }

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
        runCommand("setShuffle") { connectBridge.setShuffle(it, enabled) }

    suspend fun setRepeatMode(mode: RepeatMode): Boolean =
        runCommand("setRepeatMode") { connectBridge.setRepeatMode(it, mode) }

    fun snapshot(): SpotifyPlaybackState? = connectBridge.snapshot()

    private suspend fun runCommand(
        action: String,
        block: suspend (accessToken: String) -> Boolean
    ): Boolean = rustCommandMutex.withLock {
        val accessToken = resolveAccessToken() ?: return@withLock false

        val success = try {
            block(accessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = "Spotify command failed: $action. ${e.message.orEmpty()}".trim()
            CompatLog.w(TAG, "Spotify command '$action' threw", e)
            return@withLock false
        }
        if (!success) {
            val specificError = lastError
            lastError = "Spotify command failed: $action. ${specificError ?: ""}".trim()
            CompatLog.w(TAG, "Spotify command '$action' failed: $lastError")
            return@withLock false
        }

        lastError = null
        true
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
