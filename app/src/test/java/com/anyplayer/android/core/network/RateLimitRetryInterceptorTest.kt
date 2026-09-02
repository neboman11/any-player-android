package com.anyplayer.android.core.network

import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimitRetryInterceptorTest {

    @Test
    fun retries429UntilSuccess() {
        val callCount = AtomicInteger(0)
        val client = clientReturning(RateLimitRetryInterceptor(maxRetries = 5)) {
            if (callCount.getAndIncrement() == 0) canned(429, retryAfterSeconds = 0) else canned(200)
        }

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(2, callCount.get())
    }

    @Test
    fun stopsAfterMaxRetries() {
        val callCount = AtomicInteger(0)
        val client = clientReturning(RateLimitRetryInterceptor(maxRetries = 2)) {
            callCount.incrementAndGet()
            canned(429, retryAfterSeconds = 0)
        }

        val response = client.newCall(request()).execute()

        assertEquals(429, response.code)
        assertEquals(3, callCount.get()) // initial attempt + 2 retries
    }

    @Test
    fun retries5xxOnGet() {
        val callCount = AtomicInteger(0)
        val client = clientReturning(RateLimitRetryInterceptor(maxRetries = 5)) {
            if (callCount.getAndIncrement() == 0) canned(503, retryAfterSeconds = 0) else canned(200)
        }

        val response = client.newCall(request(method = "GET")).execute()

        assertEquals(200, response.code)
        assertEquals(2, callCount.get())
    }

    @Test
    fun doesNotRetry5xxOnNonIdempotentWrite() {
        val callCount = AtomicInteger(0)
        val client = clientReturning(RateLimitRetryInterceptor(maxRetries = 5)) {
            callCount.incrementAndGet()
            canned(500)
        }

        val response = client.newCall(request(method = "POST")).execute()

        assertEquals(500, response.code)
        assertEquals(1, callCount.get())
    }

    @Test
    fun doesNotRetrySuccessfulResponse() {
        val callCount = AtomicInteger(0)
        val client = clientReturning(RateLimitRetryInterceptor(maxRetries = 5)) {
            callCount.incrementAndGet()
            canned(200)
        }

        val response = client.newCall(request()).execute()

        assertEquals(200, response.code)
        assertEquals(1, callCount.get())
    }

    private fun request(method: String = "GET"): Request {
        val builder = Request.Builder().url("https://example.com/test")
        return if (method == "GET") {
            builder.get().build()
        } else {
            builder.method(method, "".toRequestBody(null)).build()
        }
    }

    private fun canned(code: Int, retryAfterSeconds: Long? = null): Response.Builder {
        val builder = Response.Builder()
            .code(code)
            .message("test")
            .protocol(Protocol.HTTP_1_1)
            .body("".toResponseBody(null))
        return if (retryAfterSeconds != null) builder.header("Retry-After", retryAfterSeconds.toString()) else builder
    }

    private fun clientReturning(
        interceptor: RateLimitRetryInterceptor,
        respond: () -> Response.Builder
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain -> respond().request(chain.request()).build() }
            .build()
    }
}
