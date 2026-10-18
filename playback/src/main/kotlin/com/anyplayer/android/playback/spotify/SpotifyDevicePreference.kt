package com.anyplayer.android.playback.spotify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Connect device the user picked. [name] is kept because web players get a new id per session;
 * the resolver matches by id first, then by name.
 */
data class PreferredSpotifyDevice(val id: String, val name: String)

/** Persisted Spotify playback-device choice; null = automatic. */
interface SpotifyDevicePreferenceStore {
    val preferred: StateFlow<PreferredSpotifyDevice?>
    suspend fun awaitLoaded(): PreferredSpotifyDevice?
    suspend fun set(device: PreferredSpotifyDevice?)
}

/** Plain SharedPreferences `spotify_device` (not secret), read once on IO. */
@Singleton
class SharedPrefsSpotifyDevicePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpotifyDevicePreferenceStore {
    private val _preferred = MutableStateFlow<PreferredSpotifyDevice?>(null)
    override val preferred: StateFlow<PreferredSpotifyDevice?> = _preferred.asStateFlow()
    private val mutex = Mutex()
    private var loaded = false

    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override suspend fun awaitLoaded(): PreferredSpotifyDevice? = mutex.withLock {
        if (!loaded) {
            _preferred.value = withContext(Dispatchers.IO) {
                val id = prefs.getString(KEY_ID, null)
                val name = prefs.getString(KEY_NAME, null)
                if (id.isNullOrBlank() || name == null) null else PreferredSpotifyDevice(id, name)
            }
            loaded = true
        }
        _preferred.value
    }

    override suspend fun set(device: PreferredSpotifyDevice?) {
        awaitLoaded()
        mutex.withLock {
            _preferred.value = device
            withContext(Dispatchers.IO) {
                prefs.edit().apply {
                    if (device == null) remove(KEY_ID).remove(KEY_NAME) else putString(KEY_ID, device.id).putString(KEY_NAME, device.name)
                }.apply()
            }
        }
    }

    private companion object {
        const val FILE = "spotify_device"
        const val KEY_ID = "preferred_id"
        const val KEY_NAME = "preferred_name"
    }
}

/** UI-facing device picker (Settings → Providers → Spotify). */
interface SpotifyDevices {
    val preferred: StateFlow<PreferredSpotifyDevice?>
    /** Controllable Connect devices on the account right now; throws [SpotifyCommandException]. */
    suspend fun available(): List<SpotifyDevice>
    /** Null restores automatic selection. Applies from the next Spotify start. */
    suspend fun setPreferred(device: SpotifyDevice?)
}
