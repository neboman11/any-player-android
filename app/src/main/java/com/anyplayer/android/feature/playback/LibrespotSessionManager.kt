package com.anyplayer.android.feature.playback

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.network.SpotifyClient
import com.anyplayer.android.core.network.SpotifyClientIds
import com.anyplayer.android.core.network.SpotifyPlaybackState
import com.anyplayer.android.feature.auth.SecureConnectionStore
import com.anyplayer.android.feature.auth.StoredConnection
import com.google.protobuf.ByteString
import com.spotify.Authentication
import com.spotify.connectstate.Connect
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.gianlu.librespot.audio.MetadataWrapper
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.mercury.MercuryClient
import xyz.gianlu.librespot.metadata.PlayableId
import xyz.gianlu.librespot.player.Player
import xyz.gianlu.librespot.player.PlayerConfiguration
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the librespot-java [Session] and [Player] lifecycle.
 *
 * Authenticates using the stored PKCE access token on first use, then reuses the
 * persisted credential file for subsequent cold starts (no network round-trip).
 *
 * State (isPlaying, position, volume, shuffle, repeat, currentTrackId) is updated
 * in real-time via [Player.EventsListener] and can be read atomically via [snapshot].
 */
@Singleton
class LibrespotSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureConnectionStore: SecureConnectionStore,
    private val spotifyClient: SpotifyClient
) : Player.EventsListener {

    companion object {
        private const val TAG = "LibrespotSessionManager"
    }

    private val credentialsFile = File(context.filesDir, "librespot_credentials.json")

    /**
     * Stable device ID derived from ANDROID_ID so librespot always registers the same
     * Spotify Connect device. A random ID on every cold start causes Mercury 403s because
     * Spotify's backend rejects requests from unrecognised device IDs.
     */
    private val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: "anyplayer_fallback"
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest(("anyplayer:" + androidId).toByteArray())
        sha1.joinToString("") { "%02x".format(it) }
    }

    @Volatile private var session: Session? = null
    @Volatile private var player: Player? = null

    /**
     * Persists which device ID the stored credentials file was created with.
     * If it doesn't match [deviceId] we delete the stale credentials so the next
     * session creation takes the fresh PKCE-token path with the correct device ID.
     */
    private val deviceIdStampFile = File(context.filesDir, "librespot_device_id.txt")

    init {
        // One-time migration: evict credentials saved with a different (random) device ID.
        val stampedId = if (deviceIdStampFile.exists()) deviceIdStampFile.readText().trim() else ""
        if (stampedId != deviceId && credentialsFile.exists()) {
            credentialsFile.delete()
            Log.i(TAG, "Deleted stale credentials (device ID changed from '$stampedId' to '$deviceId')")
        }
        deviceIdStampFile.writeText(deviceId)
    }

    /** The human-readable reason for the last session/player creation failure. Null when healthy. */
    @Volatile var lastError: String? = null
        private set

    // Playback state tracked via EventsListener
    @Volatile private var isPlaying: Boolean = false
    @Volatile private var currentTrackUri: String? = null
    @Volatile private var lastPositionMs: Long = 0L
    @Volatile private var lastSyncTimeMs: Long = 0L   // System.currentTimeMillis() at last resume/seek
    @Volatile private var volumeFloat: Float = 1f
    @Volatile private var shuffleEnabled: Boolean = false
    @Volatile private var repeatMode: RepeatMode = RepeatMode.OFF

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Ensures a [Session] and [Player] are live and ready.
     * Must be called from a background thread.
     *
     * @return `true` if the player is available, `false` on auth / network error.
     */
    fun ensureSessionAndPlayer(): Boolean {
        if (player != null && session != null) return true

        lastError = null
        return try {
            val sessionConf = Session.Configuration.Builder()
                .setStoreCredentials(true)
                .setStoredCredentialsFile(credentialsFile)
                .setCacheEnabled(false)
                .build()

            val builder = Session.Builder(sessionConf)
                .setPreferredLocale("en")
                .setDeviceType(Connect.DeviceType.SMARTPHONE)
                .setDeviceName("AnyPlayer")
                .setDeviceId(deviceId)

            if (credentialsFile.exists()) {
                Log.i(TAG, "Using stored credentials from ${credentialsFile.name}")
                builder.stored(credentialsFile)
            } else {
                val stored = secureConnectionStore.read(SourceType.SPOTIFY)
                if (stored == null) {
                    lastError = "No Spotify account linked. Go to Settings → Link Spotify Account."
                    Log.w(TAG, "No Spotify credentials available for librespot auth")
                    return false
                }
                // Proactively refresh the PKCE token — access tokens expire after 1 hour.
                // refreshTokenIfNeeded() saves the new token back to SecureConnectionStore.
                val token = refreshTokenIfNeeded(stored)
                    ?: stored.token?.takeIf { it.isNotBlank() }
                if (token == null) {
                    lastError = "Spotify access token missing or expired and refresh failed. Re-link the account in Settings."
                    Log.w(TAG, "No valid Spotify token available for librespot auth")
                    return false
                }
                Log.i(TAG, "Authenticating with SPOTIFY_TOKEN")
                builder.credentials(
                    Authentication.LoginCredentials.newBuilder()
                        .setTyp(Authentication.AuthenticationType.AUTHENTICATION_SPOTIFY_TOKEN)
                        .setAuthData(ByteString.copyFromUtf8(token))
                        .build()
                )
            }

            val newSession = builder.create()
            Log.i(TAG, "Session created for: ${newSession.username()}")

            val playerConf = PlayerConfiguration.Builder()
                .setOutput(PlayerConfiguration.AudioOutput.CUSTOM)
                .setOutputClass(LibrespotAndroidSink::class.java.name)
                .setInitialVolume(Player.VOLUME_MAX)
                .build()

            val newPlayer = Player(playerConf, newSession)
            newPlayer.addEventsListener(this)

            session = newSession
            player = newPlayer
            true
        } catch (e: IOException) {
            lastError = "Network error: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Session creation failed (IO)", e)
            false
        } catch (e: GeneralSecurityException) {
            lastError = "Security error: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, "Session creation failed (security)", e)
            false
        } catch (e: Session.SpotifyAuthenticationException) {
            lastError = "Spotify auth rejected (${e.message ?: "bad credentials"}). Stored credentials deleted — retry will use token."
            Log.e(TAG, "Spotify authentication rejected", e)
            // Stored credentials may be stale — delete file and retry next call
            if (credentialsFile.exists()) {
                credentialsFile.delete()
                deviceIdStampFile.delete()
                Log.w(TAG, "Deleted stale credentials file")
            }
            false
        } catch (e: MercuryClient.MercuryException) {
            val msg = e.message ?: e.javaClass.simpleName
            lastError = when {
                msg.contains("403") ->
                    "Mercury 403: Spotify rejected the playback token request. " +
                    "This is usually a token/session mismatch (or missing playback scope). " +
                    "Reconnect Spotify and retry."
                msg.contains("404") ->
                    "Mercury 404: Track or context not found on Spotify's servers."
                msg.contains("401") ->
                    "Mercury 401: Access token rejected. Try re-linking in Settings."
                else ->
                    "Mercury protocol error: $msg"
            }
            Log.e(TAG, "Mercury error during session creation", e)
            false
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            Log.e(TAG, "Unexpected error creating session/player", e)
            false
        }
    }

    /**
     * Refreshes the Spotify PKCE access token using the stored refresh token.
     * Saves the new token to [SecureConnectionStore] and returns it, or null on failure.
     */
    private fun refreshTokenIfNeeded(stored: StoredConnection): String? {
        val refreshToken = stored.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val result = spotifyClient.refreshAccessToken(
                clientId = SpotifyClientIds.ACTIVE,
                refreshToken = refreshToken
            ) ?: return null
            val updated = stored.copy(
                token = result.accessToken,
                refreshToken = result.refreshToken ?: refreshToken
            )
            secureConnectionStore.save(updated)
            Log.i(TAG, "Access token refreshed successfully")
            result.accessToken
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh failed, falling back to stored token", e)
            null
        }
    }

    fun getPlayer(): Player? = player

    /**
     * Returns the current playback snapshot, computing elapsed progress from wall-clock time
     * when playing so callers don't need sub-second polling.
     */
    fun snapshot(): SpotifyPlaybackState? {
        val p = player ?: return null
        val trackId = currentTrackUri?.removePrefix("spotify:track:") ?: return null

        val elapsedMs = if (isPlaying && lastSyncTimeMs > 0L)
            System.currentTimeMillis() - lastSyncTimeMs
        else 0L
        val progressMs = lastPositionMs + elapsedMs
        val volumePercent = (volumeFloat * 100f).toInt().coerceIn(0, 100)

        return SpotifyPlaybackState(
            isPlaying = isPlaying,
            progressMs = progressMs,
            volumePercent = volumePercent,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            currentTrackId = trackId
        )
    }

    /**
     * Shuts down and de-references the current session and player.
     */
    fun closeAll() {
        try { player?.close() } catch (e: Exception) { Log.w(TAG, "Error closing player", e) }
        try { session?.close() } catch (e: Exception) { Log.w(TAG, "Error closing session", e) }
        player = null
        session = null
    }

    // ------------------------------------------------------------------ //
    //  Player.EventsListener implementation                               //
    // ------------------------------------------------------------------ //

    override fun onContextChanged(player: Player, newUri: String) {
        Log.d(TAG, "Context changed: $newUri")
    }

    override fun onTrackChanged(
        player: Player,
        id: PlayableId,
        metadata: MetadataWrapper?,
        userInitiated: Boolean
    ) {
        currentTrackUri = id.toSpotifyUri()
        Log.d(TAG, "Track changed: $currentTrackUri")
    }

    override fun onPlaybackEnded(player: Player) {
        isPlaying = false
        lastPositionMs = 0L
        lastSyncTimeMs = 0L
        Log.d(TAG, "Playback ended")
    }

    override fun onPlaybackPaused(player: Player, trackTime: Long) {
        // Freeze the position at the moment of pause
        lastPositionMs = trackTime
        lastSyncTimeMs = 0L
        isPlaying = false
        Log.d(TAG, "Playback paused at ${trackTime}ms")
    }

    override fun onPlaybackResumed(player: Player, trackTime: Long) {
        lastPositionMs = trackTime
        lastSyncTimeMs = System.currentTimeMillis()
        isPlaying = true
        Log.d(TAG, "Playback resumed at ${trackTime}ms")
    }

    override fun onPlaybackFailed(player: Player, e: Exception) {
        Log.e(TAG, "Playback failed", e)
        isPlaying = false
    }

    override fun onTrackSeeked(player: Player, trackTime: Long) {
        lastPositionMs = trackTime
        lastSyncTimeMs = if (isPlaying) System.currentTimeMillis() else 0L
        Log.d(TAG, "Seeked to ${trackTime}ms")
    }

    override fun onMetadataAvailable(player: Player, metadata: MetadataWrapper) {
        // no-op: metadata not needed by current UI
    }

    override fun onPlaybackHaltStateChanged(player: Player, halted: Boolean, trackTime: Long) {
        if (halted) {
            lastPositionMs = trackTime
            lastSyncTimeMs = 0L
        }
    }

    override fun onInactiveSession(player: Player, timeout: Boolean) {
        Log.w(TAG, "Session inactive (timeout=$timeout)")
    }

    override fun onVolumeChanged(player: Player, volume: Float) {
        volumeFloat = volume
    }

    override fun onPanicState(player: Player) {
        Log.e(TAG, "Player entered panic state")
        isPlaying = false
    }

    override fun onStartedLoading(player: Player) {
        Log.d(TAG, "Started loading track")
    }

    override fun onFinishedLoading(player: Player) {
        Log.d(TAG, "Finished loading track")
    }
}
