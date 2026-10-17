package com.anyplayer.android.playback.spotify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Launches the Spotify app so it registers itself as a Connect device. */
interface SpotifyAppLauncher {
    /** The app UI is at least STARTED; launching another app from the background is not allowed. */
    fun isAppInForeground(): Boolean

    /** False when Spotify isn't installed (its store listing is opened instead) or the launch failed. */
    fun launchSpotify(): Boolean
}

class AndroidSpotifyAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpotifyAppLauncher {
    override fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun launchSpotify(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
        if (launchIntent == null) {
            openStoreListing()
            return false
        }
        return runCatching { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun openStoreListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(web) }
        }
    }

    private companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}

/**
 * Picks the Connect device to play on: the first non-restricted active device, else the first
 * non-restricted one. With none, launches Spotify (foreground only) and retries 5 × 1.5 s.
 * Single-flight; a resolved id is reused for [CACHE_TTL_MS] so a pre-resolved device makes the
 * next start a single request.
 */
@Singleton
class SpotifyDeviceResolver internal constructor(
    private val api: SpotifyPlayerApi,
    private val launcher: SpotifyAppLauncher,
    private val clock: () -> Long,
) {
    @Inject
    constructor(api: SpotifyPlayerApi, launcher: SpotifyAppLauncher) :
        this(api, launcher, SystemClock::elapsedRealtime)

    private val mutex = Mutex()
    private var cachedId: String? = null
    private var cachedAt = 0L

    /** Returns a device id or throws [SpotifyCommandException] with the legacy message. */
    suspend fun resolve(): String {
        mutex.withLock {
            val cached = cachedId
            if (cached != null && clock() - cachedAt <= CACHE_TTL_MS) return cached
            val id = lookup() ?: launchAndWait()
            cachedId = id
            cachedAt = clock()
            return id
        }
    }

    /** Forgets a cached id, e.g. after a start on it failed. */
    suspend fun invalidate() = mutex.withLock { cachedId = null }

    private suspend fun lookup(): String? {
        val controllable = api.devices().filterNot { it.isRestricted }
        return (controllable.firstOrNull { it.isActive } ?: controllable.firstOrNull())?.id
    }

    private suspend fun launchAndWait(): String {
        if (!launcher.isAppInForeground()) throw SpotifyCommandException(SpotifyMessages.NO_DEVICE)
        if (!launcher.launchSpotify()) throw SpotifyCommandException(SpotifyMessages.NOT_INSTALLED)
        repeat(DEVICE_WAIT_ATTEMPTS) {
            delay(DEVICE_WAIT_INTERVAL_MS)
            lookup()?.let { return it }
        }
        throw SpotifyCommandException(SpotifyMessages.NO_DEVICE)
    }

    internal companion object {
        const val DEVICE_WAIT_ATTEMPTS = 5
        const val DEVICE_WAIT_INTERVAL_MS = 1_500L
        const val CACHE_TTL_MS = 30_000L
    }
}
