package com.anyplayer.android.feature.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.feature.auth.spotify.SpotifyPlayerClient
import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState
import com.anyplayer.android.feature.auth.spotify.spotifyPlaybackUris
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives Spotify playback via the Web API's Connect endpoints (`/v1/me/player`, etc.)
 * rather than an in-process decoder or IPC-bound SDK - it just tells whichever
 * Spotify Connect device is active on the account what to do, the same way the
 * official Spotify app's own "Devices" picker would. Replaces the App Remote SDK
 * bridge, whose AIDL/IPC connect() depends on an Android Background-Activity-Launch
 * exemption for Spotify's consent screen that is unreliable on Android 14+.
 */
@Singleton
class SpotifyConnectBridge @Inject constructor(
    private val spotifyPlayerClient: SpotifyPlayerClient,
    private val providerAuthRepository: ProviderAuthRepository,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SpotifyConnectBridge"
        private const val POLL_INTERVAL_MS = 2_000L

        // Wider than the App Remote bridge's 500ms window (which reacted to a
        // pushed event) - a poll only samples every POLL_INTERVAL_MS, so a natural
        // track end could land anywhere within that window before we observe it.
        private const val END_OF_TRACK_POSITION_TOLERANCE_MS = 2_000L
        private const val MANUAL_PAUSE_GRACE_MS = 5_000L

        private const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"

        // Give the Spotify app a moment to register itself as a Connect device
        // after being auto-launched, before giving up on the current command.
        private const val DEVICE_WAIT_ATTEMPTS = 5
        private const val DEVICE_WAIT_INTERVAL_MS = 1_500L
    }

    fun interface ErrorListener {
        fun onError(type: String, message: String)
    }

    var errorListener: ErrorListener? = null

    /** Whether Spotify is the currently active/mixed-in playback source. Set by
     *  [com.anyplayer.android.feature.playback.PlaybackQueueManager] whenever the queue's
     *  source composition changes. Defaults to true so playback isn't missed before the
     *  first queue is loaded. When false, the poll loop skips the network call - a user who
     *  only ever plays Jellyfin/Plex/local tracks shouldn't pay for a Spotify Web API call
     *  every [POLL_INTERVAL_MS] for the life of the media service. */
    @Volatile var pollingEnabled: Boolean = true

    private val playbackStateCache = SpotifyPlaybackStateCache(END_OF_TRACK_POSITION_TOLERANCE_MS)
    private var bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    /** Must be called from the hosting Service's onCreate. Starts the background
     *  poll loop; idempotent. */
    fun attach() {
        if (pollJob?.isActive == true) return
        bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollJob = bridgeScope.launch {
            while (true) {
                if (pollingEnabled) {
                    pollOnce()
                } else {
                    playbackStateCache.clear()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Must be called from the hosting Service's onDestroy. */
    fun release() {
        bridgeScope.cancel()
        pollJob = null
        playbackStateCache.clear()
    }

    private suspend fun pollOnce() {
        val token = runCatching { providerAuthRepository.refreshSpotifyTokenIfNeeded() }.getOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            playbackStateCache.update(polled = null, nowMs = SystemClock.elapsedRealtime())
            return
        }

        val polled = runCatching {
            spotifyPlayerClient.getPlaybackState(token)
        }.onFailure { error ->
            CompatLog.w(TAG, "Spotify Connect poll failed: ${error.message}")
        }.getOrNull()

        playbackStateCache.update(polled, SystemClock.elapsedRealtime())
    }

    /** Returns the last-polled playback state, with position extrapolated forward
     *  from the last poll while playing (polls land every [POLL_INTERVAL_MS], not
     *  continuously). */
    fun snapshot(): SpotifyPlaybackState? {
        return playbackStateCache.snapshot(SystemClock.elapsedRealtime())
    }

    suspend fun playUri(accessToken: String, trackIds: List<String>, startIndex: Int): Boolean {
        if (spotifyPlaybackUris(trackIds).isEmpty()) return false
        val deviceId = resolveDeviceId(accessToken) ?: return false
        return withContext(Dispatchers.IO) {
            spotifyPlayerClient.startPlayback(accessToken, trackIds, startIndex, deviceId)
        }
    }

    suspend fun resume(accessToken: String): Boolean = withContext(Dispatchers.IO) { spotifyPlayerClient.play(accessToken) }

    suspend fun pause(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        playbackStateCache.markManualPause(SystemClock.elapsedRealtime(), MANUAL_PAUSE_GRACE_MS)
        runCatching { spotifyPlayerClient.pause(accessToken) }
            .onSuccess { paused ->
                if (paused) {
                    playbackStateCache.extendManualPauseAfterSuccessfulCommand(
                        SystemClock.elapsedRealtime(),
                        MANUAL_PAUSE_GRACE_MS
                    )
                } else {
                    playbackStateCache.clearManualPause()
                }
            }
            .onFailure { playbackStateCache.clearManualPause() }
            .getOrThrow()
    }

    suspend fun next(accessToken: String): Boolean = withContext(Dispatchers.IO) { spotifyPlayerClient.next(accessToken) }

    suspend fun seek(accessToken: String, positionMs: Long): Boolean =
        withContext(Dispatchers.IO) { spotifyPlayerClient.seek(accessToken, positionMs) }

    suspend fun setVolume(accessToken: String, volumePercent: Int): Boolean =
        withContext(Dispatchers.IO) { spotifyPlayerClient.setVolume(accessToken, volumePercent) }

    suspend fun setShuffle(accessToken: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) { spotifyPlayerClient.setShuffle(accessToken, enabled) }

    suspend fun setRepeatMode(accessToken: String, mode: RepeatMode): Boolean =
        withContext(Dispatchers.IO) { spotifyPlayerClient.setRepeatMode(accessToken, mode) }

    private suspend fun resolveDeviceId(accessToken: String): String? {
        val deviceId = withContext(Dispatchers.IO) { spotifyPlayerClient.getAvailableDeviceId(accessToken) }
        if (deviceId != null) return deviceId

        if (!launchSpotifyApp()) {
            errorListener?.onError(
                "no_device",
                "Spotify isn't installed. Install it, open it, and try again."
            )
            return null
        }

        repeat(DEVICE_WAIT_ATTEMPTS) {
            delay(DEVICE_WAIT_INTERVAL_MS)
            val retriedDeviceId = withContext(Dispatchers.IO) { spotifyPlayerClient.getAvailableDeviceId(accessToken) }
            if (retriedDeviceId != null) return retriedDeviceId
        }

        errorListener?.onError(
            "no_device",
            "No active Spotify Connect device found. Open Spotify on this phone (or another device on the same account) and try again."
        )
        return null
    }

    /** Launches the Spotify app so it registers itself as a Connect device.
     *  Returns false when Spotify isn't installed (and opens its Play Store
     *  listing instead) so the caller can surface a distinct error. */
    private fun launchSpotifyApp(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE_NAME)
        if (launchIntent == null) {
            CompatLog.w(TAG, "Spotify app not installed; opening its store listing")
            openSpotifyStoreListing()
            return false
        }

        return runCatching {
            context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CompatLog.i(TAG, "Auto-launched Spotify app to register a Connect device")
        }.onFailure { error ->
            CompatLog.w(TAG, "Failed to auto-launch Spotify app: ${error.message}")
        }.isSuccess
    }

    private fun openSpotifyStoreListing() {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE_NAME")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(marketIntent) }
            .onFailure {
                runCatching { context.startActivity(webIntent) }
                    .onFailure { error -> CompatLog.w(TAG, "Failed to open Spotify store listing: ${error.message}") }
            }
    }
}

/** A track has naturally ended when playback was running and has since stopped at
 *  either the start or the end of the track, within [toleranceMs] - accounting for
 *  the poll cadence, since a natural stop isn't guaranteed to be observed exactly
 *  at position 0 or [SpotifyPlaybackState.durationMs]. */
internal fun mergeEndOfTrackCount(
    previous: SpotifyPlaybackState?,
    polled: SpotifyPlaybackState,
    toleranceMs: Long,
    manualPauseExpected: Boolean = false
): SpotifyPlaybackState {
    val wasPlaying = previous?.isPlaying == true
    val finishedNaturally = !manualPauseExpected && wasPlaying && !polled.isPlaying &&
        (polled.progressMs <= toleranceMs ||
            (polled.durationMs > 0 && polled.progressMs >= polled.durationMs - toleranceMs))
    return polled.copy(
        endOfTrackCount = (previous?.endOfTrackCount ?: 0) + if (finishedNaturally) 1 else 0
    )
}

internal fun extrapolatePosition(cached: SpotifyPlaybackState, elapsedSincePollMs: Long): SpotifyPlaybackState {
    val extrapolated = cached.progressMs + elapsedSincePollMs
    val clamped = if (cached.durationMs > 0) extrapolated.coerceIn(0, cached.durationMs) else extrapolated.coerceAtLeast(0)
    return cached.copy(progressMs = clamped)
}
