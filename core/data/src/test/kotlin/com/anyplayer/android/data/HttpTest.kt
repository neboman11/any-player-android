package com.anyplayer.android.data

import com.anyplayer.android.data.net.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class HttpTest {
    @Test fun normalizesLegacyServerAndTokenInputs() {
        assertEquals("https://example.org", normalizeSyncServerBaseUrl(" example.org/// "))
        assertEquals("http://localhost:8080/base", normalizeSyncServerBaseUrl("http://localhost:8080/base/"))
        assertEquals("", normalizeSyncServerBaseUrl(" "))
        assertEquals("secret", normalizeSyncServerAuthToken(" bEaReR secret "))
        assertEquals("secret", normalizeSyncServerAuthToken("secret"))
        assertEquals("", normalizeSyncServerBaseUrl(" / "))
        assertEquals("token", normalizeSyncServerAuthToken(" bEaReR  token "))
        assertEquals("", normalizeSyncServerAuthToken(" "))
    }
    @Test fun retries429AnyMethodButNotPost500AndClampsRetryAfter() = runTest {
        val request = Request.Builder().url("http://localhost/").post(FormBody.Builder().build()).build()
        var attempts = 0
        val start = testScheduler.currentTime
        val response = retrying(request) {
            attempts++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (attempts == 1) 429 else 500)
                .message("test").header("Retry-After", "100").body("".toResponseBody()).build()
        }
        response.close()
        assertEquals(2, attempts)
        assertEquals(60_000L, testScheduler.currentTime - start)
    }
    @Test fun getRetriesFiveTimesWithExponentialCoroutineDelay() = runTest {
        val request = Request.Builder().url("http://localhost/").build()
        var attempts = 0
        retrying(request) {
            attempts++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("test").body("".toResponseBody()).build()
        }.close()
        assertEquals(6, attempts)
        assertEquals(15_500L, testScheduler.currentTime)
    }
    @Test fun awaitUsesRealAsyncHttpAndPropagatesResponse() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).await().use { assertEquals("ok", it.body!!.string()) }
        }
    }
}
