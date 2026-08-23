package com.anyplayer.android.core.network

import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.min

/**
 * Retries HTTP 429 and 5xx responses with exponential backoff, honoring the
 * `Retry-After` header Spotify sends when rate limiting. Installed on the
 * shared [okhttp3.OkHttpClient] so every Spotify Web API call gets this
 * behavior without bespoke retry code at each call site.
 */
class RateLimitRetryInterceptor(
    private val maxRetries: Int = 5
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while ((response.code == 429 || response.code in 500..599) && attempt < maxRetries) {
            val waitMs = retryAfterMillis(response) ?: backoffMillis(attempt)
            response.close()
            Thread.sleep(waitMs)
            attempt++
            response = chain.proceed(request)
        }

        return response
    }

    private fun retryAfterMillis(response: Response): Long? {
        val seconds = response.header("Retry-After")?.toLongOrNull() ?: return null
        return seconds.coerceIn(0, 60) * 1000
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = 500L * (1 shl attempt) // 500ms, 1s, 2s, 4s, 8s
        return min(base, 10_000L)
    }
}
