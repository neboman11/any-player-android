package com.anyplayer.android.feature.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import com.anyplayer.android.core.model.SourceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureConnectionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "provider_connections",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(connection: StoredConnection) {
        prefs.edit().putString(connection.source.name, json.encodeToString(connection)).apply()
    }

    fun remove(source: SourceType) {
        prefs.edit().remove(source.name).apply()
    }

    fun read(source: SourceType): StoredConnection? {
        val payload = prefs.getString(source.name, null) ?: return null
        return json.decodeFromString(StoredConnection.serializer(), payload)
    }

    fun readAll(): List<StoredConnection> =
        SourceType.entries
            .filterNot { it == SourceType.ALL || it == SourceType.CUSTOM }
            .mapNotNull { read(it) }
}
