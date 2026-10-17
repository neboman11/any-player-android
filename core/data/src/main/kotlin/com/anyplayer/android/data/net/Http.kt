package com.anyplayer.android.data.net

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** At most five retries; every rejected response is closed before suspending. */
suspend fun retrying(request: Request, execute: suspend () -> Response): Response {
    var attempt = 0
    while (true) {
        val response = execute()
        val retry = response.code == 429 ||
            (response.code in 500..599 && request.method in setOf("GET", "HEAD"))
        if (!retry || attempt == 5) return response
        val waitMs = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 60)?.times(1000)
            ?: (500L shl attempt).coerceAtMost(10_000)
        response.close()
        delay(waitMs)
        attempt++
    }
}

suspend fun OkHttpClient.executeRetrying(request: Request): Response = retrying(request) { newCall(request).await() }

class HttpFailure(val status: Int, message: String) : IOException(message)

fun normalizeSyncServerBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) trimmed else "https://$trimmed"
}

private val bearerPrefix = Regex("^Bearer\\s+", RegexOption.IGNORE_CASE)
fun normalizeSyncServerAuthToken(value: String): String = value.trim().replace(bearerPrefix, "")
