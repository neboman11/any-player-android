package com.anyplayer.android.dj

import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import com.anyplayer.android.data.settings.SyncSettingsStore

internal fun normalizeBase(raw: String): String = raw.trim().trimEnd('/').let {
    if (it.isBlank() || it.startsWith("https://") || it.startsWith("http://")) it else "https://$it"
}
internal fun normalizeToken(raw: String) = raw.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
internal data class DjAccount(val base: String, val token: String) {
    val stateKey: String get() = "state_" + sha256("$base\u0000$token".toByteArray(Charsets.UTF_8))
    fun request(path: String): Request.Builder = Request.Builder().url("$base/$path").apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }
}
internal suspend fun SyncSettingsStore.djAccount() = awaitLoaded().let { DjAccount(normalizeBase(it.serverTarget), normalizeToken(it.authToken)) }
internal suspend fun Call.awaitDj(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
internal fun describeFailure(code: Int?, error: Throwable?, notConfigured: String): String = when {
    error != null -> "Could not reach the sync server. Check the Sync Server Target and your network connection. (${error.message})"
    code == 401 || code == 403 -> "Sync server rejected the auth token. Check the Sync Auth Token setting."
    code == 404 -> notConfigured
    else -> "Sync server returned an error (HTTP $code)."
}
