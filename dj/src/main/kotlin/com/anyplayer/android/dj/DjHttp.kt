package com.anyplayer.android.dj

import com.anyplayer.android.data.net.normalizeSyncServerAuthToken
import com.anyplayer.android.data.net.normalizeSyncServerBaseUrl
import com.anyplayer.android.data.settings.SyncSettingsStore
import java.security.MessageDigest
import okhttp3.Request

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
internal data class DjAccount(val base: String, val token: String) {
    val stateKey: String get() = "state_" + sha256("$base\u0000$token".toByteArray(Charsets.UTF_8))
    fun request(path: String): Request.Builder = Request.Builder().url("$base/$path").apply {
        if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }
}
internal suspend fun SyncSettingsStore.djAccount() = awaitLoaded().let {
    DjAccount(normalizeSyncServerBaseUrl(it.serverTarget), normalizeSyncServerAuthToken(it.authToken))
}
internal fun describeFailure(code: Int?, error: Throwable?, notConfigured: String): String = when {
    error != null -> "Could not reach the sync server. Check the Sync Server Target and your network connection. (${error.message})"
    code == 401 || code == 403 -> "Sync server rejected the auth token. Check the Sync Auth Token setting."
    code == 404 -> notConfigured
    else -> "Sync server returned an error (HTTP $code)."
}
