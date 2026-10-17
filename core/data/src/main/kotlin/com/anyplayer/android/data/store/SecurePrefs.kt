package com.anyplayer.android.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Opens the legacy EncryptedSharedPreferences files (`provider_connections`,
 * `spotify_auth_session`, `sync_preferences`, `playback_state`) lazily on [Dispatchers.IO].
 * Keystore/Tink initialisation and file decryption never run on the caller's thread, and each
 * file is opened once per process. Key scheme matches legacy: shared default-alias MasterKey
 * (AES256_GCM), keys AES256_SIV, values AES256_GCM.
 */
@Singleton
class SecurePrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private var masterKey: MasterKey? = null
    private val opened = HashMap<String, SharedPreferences>()

    suspend fun open(fileName: String): SharedPreferences = withContext(Dispatchers.IO) {
        mutex.withLock {
            opened.getOrPut(fileName) {
                EncryptedSharedPreferences.create(
                    context,
                    fileName,
                    masterKeyLocked(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
        }
    }

    private fun masterKeyLocked(): MasterKey = masterKey ?: MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
        .also { masterKey = it }

    companion object {
        const val PROVIDER_CONNECTIONS = "provider_connections"
        const val SPOTIFY_AUTH_SESSION = "spotify_auth_session"
        const val SYNC_PREFERENCES = "sync_preferences"
        const val PLAYBACK_STATE = "playback_state"
    }
}
