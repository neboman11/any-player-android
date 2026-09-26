package com.anyplayer.android.feature.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncPreferences(
    val serverTarget: String = "",
    val authToken: String = "",
    val syncAppState: Boolean = true,
    val syncPlaylists: Boolean = true,
    val syncProviderConfiguration: Boolean = true,
    val syncSettings: Boolean = true
)

@Singleton
class SyncPreferencesStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "sync_preferences",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun read(): SyncPreferences {
        val payload = prefs.getString(SYNC_PREFS_KEY, null) ?: return SyncPreferences()
        return runCatching {
            json.decodeFromString(SyncPreferences.serializer(), payload)
        }.getOrElse {
            SyncPreferences()
        }
    }

    fun save(value: SyncPreferences) {
        prefs.edit().putString(SYNC_PREFS_KEY, json.encodeToString(SyncPreferences.serializer(), value)).apply()
    }

    fun selectedDjVoiceId(): String? = prefs.getString(SELECTED_DJ_VOICE_ID_KEY, null)?.trim()?.takeIf(String::isNotBlank)

    fun setSelectedDjVoiceId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(SELECTED_DJ_VOICE_ID_KEY) else putString(SELECTED_DJ_VOICE_ID_KEY, id)
        }.apply()
    }

    fun djVoiceGain(): Float = prefs.getFloat(DJ_VOICE_GAIN_KEY, DEFAULT_DJ_VOICE_GAIN)

    fun setDjVoiceGain(gain: Float) {
        prefs.edit().putFloat(DJ_VOICE_GAIN_KEY, gain).apply()
    }

    fun getOrCreateClientId(): String {
        val existing = prefs.getString(SYNC_CLIENT_ID_KEY, null)?.trim().orEmpty()
        if (existing.isNotBlank()) {
            return existing
        }

        val next = UUID.randomUUID().toString()
        prefs.edit().putString(SYNC_CLIENT_ID_KEY, next).apply()
        return next
    }

    private companion object {
        const val SYNC_PREFS_KEY = "sync_preferences_json"
        const val SYNC_CLIENT_ID_KEY = "sync_client_id"
        const val SELECTED_DJ_VOICE_ID_KEY = "selected_dj_voice_id"
        const val DJ_VOICE_GAIN_KEY = "dj_voice_gain"
        const val DEFAULT_DJ_VOICE_GAIN = 1.6f
    }
}
