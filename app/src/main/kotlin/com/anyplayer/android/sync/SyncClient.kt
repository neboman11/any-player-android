package com.anyplayer.android.sync

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.data.settings.SyncPreferences
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun normalizeSyncBaseUrl(value: String): String {
    val target = value.trim().trimEnd('/')
    if (target.isEmpty()) return ""
    return if (target.startsWith("http://", true) || target.startsWith("https://", true)) target else "https://$target"
}

internal fun normalizeSyncToken(value: String): String =
    value.trim().replaceFirst(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

internal data class SyncUpdate(val namespace: String, val version: Long?, val sourceClientId: String?)
internal sealed interface SocketMessage {
    data object Open : SocketMessage
    data class Update(val event: SyncUpdate) : SocketMessage
}

/** HTTP bodies are consumed on the caller's IO dispatcher; cancellation also cancels the call. */
class SyncClient @Inject constructor(private val http: OkHttpClient) {
    private fun request(preferences: SyncPreferences, path: String): Request.Builder {
        val base = normalizeSyncBaseUrl(preferences.serverTarget)
        require(base.isNotEmpty()) { "Sync server target is not set." }
        return Request.Builder().url("$base$path").apply {
            normalizeSyncToken(preferences.authToken).takeIf(String::isNotEmpty)?.let { header("Authorization", "Bearer $it") }
        }
    }

    internal suspend fun snapshot(preferences: SyncPreferences, since: Long? = null): JsonObject? =
        get(preferences, "/v1/snapshot" + (since?.let { "?since_version=$it" } ?: ""))

    internal suspend fun appState(preferences: SyncPreferences): JsonObject? {
        val response = get(preferences, "/v1/state/app-state") ?: return null
        val data = response["data"] as? JsonObject ?: return null
        return buildJsonObject {
            response["version"]?.let { put("version", it) }
            put("app_state", data)
        }
    }

    private suspend fun get(preferences: SyncPreferences, path: String): JsonObject? = safely {
        http.newCall(request(preferences, path).get().build()).await().use { response ->
            when {
                response.code == 304 -> null
                !response.isSuccessful -> throw IOException("Sync HTTP ${response.code}")
                else -> AppJson.parseToJsonElement(response.body?.string() ?: "") as? JsonObject
                    ?: throw IOException("Invalid sync response")
            }
        }
    }

    internal suspend fun push(preferences: SyncPreferences, namespace: String, clientId: String, data: JsonElement): Boolean = safely {
        val body = buildJsonObject { put("client_id", clientId); put("data", data) }
        val request = request(preferences, "/v1/state/$namespace")
            .put(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).await().use { it.isSuccessful }
    } ?: false

    internal fun updates(preferences: SyncPreferences): Flow<SocketMessage> = callbackFlow {
        val socket = http.newWebSocket(request(preferences, "/v1/ws").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(SocketMessage.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = try { AppJson.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return
                if (json.string("event_type") != "state_updated") return
                val namespace = json.string("namespace") ?: return
                trySend(SocketMessage.Update(SyncUpdate(namespace, json.number("version"), json.string("source_client_id"))))
                    .onFailure { webSocket.cancel(); close(IOException("Sync event buffer overflow")) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        awaitClose { socket.close(1000, "closed"); socket.cancel() }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try { block() } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
