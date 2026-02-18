package com.anyplayer.android.feature.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpotifyAuthSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "spotify_auth_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePending(state: String, codeVerifier: String, clientId: String, redirectUri: String) {
        prefs.edit()
            .putString("state", state)
            .putString("codeVerifier", codeVerifier)
            .putString("clientId", clientId)
            .putString("redirectUri", redirectUri)
            .apply()
    }

    fun readPending(): PendingSpotifyAuthSession? {
        val state = prefs.getString("state", null) ?: return null
        val verifier = prefs.getString("codeVerifier", null) ?: return null
        val clientId = prefs.getString("clientId", null) ?: return null
        val redirectUri = prefs.getString("redirectUri", null) ?: return null
        return PendingSpotifyAuthSession(
            state = state,
            codeVerifier = verifier,
            clientId = clientId,
            redirectUri = redirectUri
        )
    }

    fun clearPending() {
        prefs.edit()
            .remove("state")
            .remove("codeVerifier")
            .remove("clientId")
            .remove("redirectUri")
            .apply()
    }
}

data class PendingSpotifyAuthSession(
    val state: String,
    val codeVerifier: String,
    val clientId: String,
    val redirectUri: String
)
