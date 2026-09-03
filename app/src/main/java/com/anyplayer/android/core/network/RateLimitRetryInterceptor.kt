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

        while (isRetryable(request.method, response.code) && attempt < maxRetries) {
            val waitMs = retryAfterMillis(response) ?: backoffMillis(attempt)
            response.close()
            Thread.sleep(waitMs)
            attempt++
            response = chain.proceed(request)
        }

        return response
    }

    /** 429 means the request was rejected before any server-side effect, so it's safe to
     *  retry regardless of method. A 5xx is ambiguous - for a non-idempotent write (POST/PUT/
     *  PATCH/DELETE, e.g. a Spotify next/seek/setVolume command) the mutation may already have
     *  applied server-side before the error response arrived, so blindly replaying it risks
     *  double-firing (e.g. skipping two tracks instead of one). Only retry those on GET/HEAD. */
    private fun isRetryable(method: String, code: Int): Boolean {
        if (code == 429) return true
        if (code in 500..599) return method == "GET" || method == "HEAD"
        return false
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
