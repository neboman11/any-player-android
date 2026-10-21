package com.anyplayer.android.testing

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** A request as seen by [TestServer]; [body] is decoded once so handlers and assertions can both read it. */
data class Recorded(val method: String, val path: String, val url: HttpUrl, val headers: Headers, val body: String) {
    fun query(name: String): String? = url.queryParameter(name)
}

/**
 * One loopback HTTP server shared by every fake in a test (sync, DJ, Jellyfin, Plex, Spotify, audio).
 * Fakes register routes by path prefix; the most recently registered matching route wins, so a test can
 * override one endpoint of a fake. Unmatched requests get 404. Every request is recorded.
 *
 * Spotify's hard-coded hosts are redirected here by `TestNetworkModule`: `api.spotify.com/v1/...`
 * arrives as `/spotify-api/v1/...` and `accounts.spotify.com/...` as `/spotify-accounts/...`.
 */
class TestServer : Closeable {
    private class Route(val method: String?, val prefix: String, val handler: (Recorded) -> MockResponse)

    private val server = MockWebServer()
    private val routes = CopyOnWriteArrayList<Route>()
    private val incoming = LinkedBlockingQueue<Recorded>()
    /** Every request received so far, in arrival order. */
    val log = CopyOnWriteArrayList<Recorded>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
                val recorded = Recorded(request.method.orEmpty(), url.encodedPath, url, request.headers, request.body.clone().readUtf8())
                log += recorded
                incoming.put(recorded)
                val route = routes.lastOrNull { (it.method == null || it.method == recorded.method) && recorded.path.startsWith(it.prefix) }
                return route?.handler?.invoke(recorded) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    /** `http://localhost:<port>` without a trailing slash (cleartext to localhost is allowed by the app). */
    val baseUrl: String = server.url("/").toString().trimEnd('/')
    val httpUrl: HttpUrl = server.url("/")

    fun url(path: String): String = baseUrl + (if (path.startsWith("/")) path else "/$path")

    fun route(method: String? = null, pathPrefix: String, handler: (Recorded) -> MockResponse) {
        routes += Route(method, pathPrefix, handler)
    }

    fun json(method: String? = null, pathPrefix: String, status: Int = 200, body: () -> String) =
        route(method, pathPrefix) { MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body()) }

    /** Serves [Audio.silentWav] of [durationMs] at [path] and returns its absolute URL. */
    fun audio(path: String, durationMs: Long = 60_000): String {
        val wav = Audio.silentWav(durationMs)
        route("GET", path) { MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav)) }
        return url(path)
    }

    fun requestsTo(pathPrefix: String, method: String? = null): List<Recorded> =
        log.filter { it.path.startsWith(pathPrefix) && (method == null || it.method == method) }

    /** Waits for the next request (arriving after any already consumed by previous awaits) matching [predicate]. */
    fun awaitRequest(timeoutSeconds: Long = 30, predicate: (Recorded) -> Boolean): Recorded {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val next = incoming.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("No matching request within ${timeoutSeconds}s")
            if (predicate(next)) return next
        }
    }

    override fun close() = server.shutdown()
}

object Audio {
    private const val SAMPLE_RATE = 8_000

    /** 8 kHz mono 16-bit PCM WAV of silence. */
    fun silentWav(durationMs: Long): ByteArray {
        val data = (SAMPLE_RATE * 2L * durationMs / 1000).toInt()
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }
}
