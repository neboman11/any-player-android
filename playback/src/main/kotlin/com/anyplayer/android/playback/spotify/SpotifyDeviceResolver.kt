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

    /** Whether the Spotify app is installed on this phone. */
    fun isSpotifyInstalled(): Boolean

    /** False when the launch failed. */
    fun launchSpotify(): Boolean

    /** Opens Spotify's store listing (used only when no Connect device exists at all). */
    fun openSpotifyStoreListing()

    /** This phone's name, which the Spotify app uses as its Connect device name; null if unknown. */
    fun localDeviceName(): String?
}

class AndroidSpotifyAppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpotifyAppLauncher {
    override fun isAppInForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun isSpotifyInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null

    override fun launchSpotify(): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) ?: return false
        return runCatching { context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    override fun localDeviceName(): String? =
        android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }

    override fun openSpotifyStoreListing() {
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
 * Picks the Connect device to play on:
 * 1. the user's preferred device ([SpotifyDevicePreferenceStore]) when it is online (id, else name);
 * 2. the active device;
 * 3. this phone (device name, else any smartphone);
 * 4. while the app is in the foreground, launch Spotify on this phone and wait 5 × 1.5 s for it —
 *    before settling for another device, so an idle web player is not picked over the phone;
 * 5. any other controllable device.
 * Single-flight; a resolved id is reused for [CACHE_TTL_MS] so a pre-resolved device makes the
 * next start a single request.
 */
@Singleton
class SpotifyDeviceResolver internal constructor(
    private val api: SpotifyPlayerApi,
    private val launcher: SpotifyAppLauncher,
    private val preference: SpotifyDevicePreferenceStore,
    private val clock: () -> Long,
) : SpotifyDevices {
    @Inject
    constructor(api: SpotifyPlayerApi, launcher: SpotifyAppLauncher, preference: SpotifyDevicePreferenceStore) :
        this(api, launcher, preference, SystemClock::elapsedRealtime)

    private val mutex = Mutex()
    private var cachedId: String? = null
    private var cachedAt = 0L

    override val preferred get() = preference.preferred

    override suspend fun available(): List<SpotifyDevice> = api.devices().filterNot { it.isRestricted }

    override suspend fun setPreferred(device: SpotifyDevice?) {
        preference.set(device?.let { PreferredSpotifyDevice(it.id, it.name) })
        invalidate()
    }

    /** Returns a device id or throws [SpotifyCommandException] with the legacy message. */
    suspend fun resolve(): String {
        mutex.withLock {
            val cached = cachedId
            if (cached != null && clock() - cachedAt <= CACHE_TTL_MS) return cached
            val id = pick(available(), preference.awaitLoaded()) ?: launchAndWait()
            cachedId = id
            cachedAt = clock()
            return id
        }
    }

    /** Forgets a cached id, e.g. after a start on it failed. */
    suspend fun invalidate() = mutex.withLock { cachedId = null }

    /** Steps 1–3 and, when launching is impossible, 5; null means "launch Spotify on this phone". */
    private fun pick(devices: List<SpotifyDevice>, preferred: PreferredSpotifyDevice?): String? {
        preferred?.let { want ->
            (devices.firstOrNull { it.id == want.id } ?: devices.firstOrNull { it.name == want.name })?.let { return it.id }
        }
        devices.firstOrNull { it.isActive }?.let { return it.id }
        thisPhone(devices)?.let { return it.id }
        val canLaunch = launcher.isAppInForeground() && launcher.isSpotifyInstalled()
        return if (canLaunch) null else devices.firstOrNull()?.id
    }

    private fun thisPhone(devices: List<SpotifyDevice>): SpotifyDevice? {
        val name = launcher.localDeviceName()
        return devices.firstOrNull { name != null && it.name.equals(name, ignoreCase = true) }
            ?: devices.firstOrNull { it.type.equals(SMARTPHONE, ignoreCase = true) }
    }

    /** No device was picked: either launch Spotify here (installed, foreground) or explain why not. */
    private suspend fun launchAndWait(): String {
        if (!launcher.isAppInForeground()) throw SpotifyCommandException(SpotifyMessages.NO_DEVICE)
        if (!launcher.isSpotifyInstalled()) {
            launcher.openSpotifyStoreListing()
            throw SpotifyCommandException(SpotifyMessages.NOT_INSTALLED)
        }
        val launched = launcher.launchSpotify()
        repeat(if (launched) DEVICE_WAIT_ATTEMPTS else 0) {
            delay(DEVICE_WAIT_INTERVAL_MS)
            val devices = available()
            (thisPhone(devices) ?: devices.firstOrNull { it.isActive })?.let { return it.id }
        }
        // This phone never showed up: settle for any other device rather than failing.
        available().let { devices -> (devices.firstOrNull { it.isActive } ?: devices.firstOrNull())?.let { return it.id } }
        throw SpotifyCommandException(SpotifyMessages.NO_DEVICE)
    }

    internal companion object {
        const val DEVICE_WAIT_ATTEMPTS = 5
        const val DEVICE_WAIT_INTERVAL_MS = 1_500L
        const val CACHE_TTL_MS = 30_000L
        const val SMARTPHONE = "Smartphone"
    }
}
