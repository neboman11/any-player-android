package com.anyplayer.android.feature.sync

import android.content.Context
import android.util.Log
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SyncPreferences(
    val serverTarget: String = "",
    val authToken: String = "",
    val syncAppState: Boolean = true,
    val syncPlaylists: Boolean = true,
    val syncProviderConfiguration: Boolean = true,
    val syncSettings: Boolean = true
)

@Serializable
data class AppStateSyncPayload(
    val state: String,
    val shuffle: Boolean,
    val repeat_mode: String,
    val volume: Int,
    val position: Long,
    val duration: Long,
    val current_track: Track? = null,
    val queue: List<Track> = emptyList()
)

@Serializable
data class SyncUpdateEvent(
    val event_type: String = "",
    val namespace: String = "",
    val version: Long? = null,
    val source_client_id: String? = null
)

fun playbackStatusToAppStatePayload(status: PlaybackStatus): AppStateSyncPayload {
    val state = when (status.state) {
        PlaybackStateType.PLAYING -> "playing"
        PlaybackStateType.PAUSED -> "paused"
        else -> "stopped"
    }
    val repeatMode = when (status.repeatMode) {
        RepeatMode.ONE -> "one"
        RepeatMode.ALL -> "all"
        RepeatMode.OFF -> "off"
    }

    return AppStateSyncPayload(
        state = state,
        shuffle = status.shuffle,
        repeat_mode = repeatMode,
        volume = status.volume,
        position = status.position,
        duration = status.duration,
        current_track = status.currentTrack,
        queue = status.queue
    )
}

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
    }
}

@Singleton
class SyncSnapshotClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val syncPreferencesStore: SyncPreferencesStore
) {
    fun getClientId(): String = syncPreferencesStore.getOrCreateClientId()

    private fun normalizeToken(raw: String): String {
        val trimmed = raw.trim()
        val bearerRegex = Regex("^Bearer\\s+", RegexOption.IGNORE_CASE)
        return bearerRegex.replace(trimmed, "")
    }

    suspend fun fetchSnapshot(serverTarget: String): JsonObject? = withContext(Dispatchers.IO) {
        val base = serverTarget.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext null
        }

        val request = Request.Builder()
            .url("$base/v1/snapshot")
            .get()
            .apply {
                val token = normalizeToken(syncPreferencesStore.read().authToken)
                if (token.isNotEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        val response = runCatching { okHttpClient.newCall(request).execute() }.getOrNull() ?: return@withContext null
        response.use {
            if (!it.isSuccessful) {
                return@withContext null
            }

            val body = it.body?.string() ?: return@withContext null
            runCatching {
                json.parseToJsonElement(body).jsonObject
            }.getOrNull()
        }
    }

    suspend fun fetchSnapshotSince(serverTarget: String, sinceVersion: Long): JsonObject? = withContext(Dispatchers.IO) {
        val base = serverTarget.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext null
        }

        val request = Request.Builder()
            .url("$base/v1/snapshot?since_version=$sinceVersion")
            .get()
            .apply {
                val token = normalizeToken(syncPreferencesStore.read().authToken)
                if (token.isNotEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        val response = runCatching { okHttpClient.newCall(request).execute() }.getOrNull() ?: return@withContext null
        response.use {
            if (it.code == 304) {
                return@withContext null
            }
            if (!it.isSuccessful) {
                return@withContext null
            }

            val body = it.body?.string() ?: return@withContext null
            runCatching {
                json.parseToJsonElement(body).jsonObject
            }.getOrNull()
        }
    }

    suspend fun pushAppState(serverTarget: String, payload: AppStateSyncPayload): Boolean = withContext(Dispatchers.IO) {
        val base = serverTarget.trim().trimEnd('/')
        if (base.isBlank()) {
            return@withContext false
        }

        val body = JsonObject(
            mapOf(
                "client_id" to JsonPrimitive(syncPreferencesStore.getOrCreateClientId()),
                "data" to json.encodeToJsonElement(AppStateSyncPayload.serializer(), payload)
            )
        )

        val request = Request.Builder()
            .url("$base/v1/state/app-state")
            .apply {
                val token = normalizeToken(syncPreferencesStore.read().authToken)
                if (token.isNotEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = runCatching { okHttpClient.newCall(request).execute() }.getOrNull() ?: return@withContext false
        response.use { it.isSuccessful }
    }

    fun observeStateUpdates(serverTarget: String): Flow<SyncUpdateEvent> = callbackFlow {
        val base = serverTarget.trim().trimEnd('/')
        if (base.isBlank()) {
            close()
            return@callbackFlow
        }

        val wsUrl = when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/v1/ws"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/v1/ws"
            else -> "ws://$base/v1/ws"
        }

        val token = withContext(Dispatchers.IO) {
            normalizeToken(syncPreferencesStore.read().authToken)
        }
        val requestBuilder = Request.Builder().url(wsUrl)
        if (token.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val request = requestBuilder.build()
        var socket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("SyncSnapshotClient", "WebSocket connected to $wsUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching {
                    json.decodeFromString(SyncUpdateEvent.serializer(), text)
                }.getOrNull() ?: return

                trySend(parsed).isSuccess
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }

        socket = okHttpClient.newWebSocket(request, listener)

        awaitClose {
            socket?.close(1000, "closed")
        }
    }

    fun payloadFromPlayback(status: PlaybackStatus): AppStateSyncPayload {
        return playbackStatusToAppStatePayload(status)
    }

    fun parseRemoteAppState(snapshot: JsonObject): JsonElement? = snapshot["app_state"]
}
