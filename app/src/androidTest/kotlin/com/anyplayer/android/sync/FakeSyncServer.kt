package com.anyplayer.android.sync

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Sync server (legacy wire protocol subset) plus a host for silent WAV "songs", on one loopback port.
 * [online] = false answers every sync call with 503 and drops the realtime socket, like losing the
 * route to a self-hosted server while driving.
 */
class FakeSyncServer {
    private val server = MockWebServer()
    val target: String
    @Volatile var online = true
        private set
    @Volatile var appState: JsonObject = JsonObject(emptyMap())
    @Volatile private var version = 1L
    @Volatile private var socket: WebSocket? = null
    private val sockets = LinkedBlockingQueue<WebSocket>()
    /** `data` of every app-state PUT the server accepted. */
    val acceptedPushes = LinkedBlockingQueue<JsonObject>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/audio/")) return MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(SILENT_WAV))
                if (!online) return MockResponse().setResponseCode(503)
                return when {
                    path.startsWith("/v1/snapshot") -> json(buildJsonObject { put("version", version); put("app_state", appState) })
                    path == "/v1/state/app-state" && request.method == "GET" -> json(buildJsonObject { put("version", version); put("data", appState) })
                    path == "/v1/state/app-state" && request.method == "PUT" -> {
                        val data = AppJson.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("data").jsonObject
                        appState = data; version++
                        acceptedPushes.put(data)
                        json(JsonObject(emptyMap()))
                    }
                    path.startsWith("/v1/state/") -> json(JsonObject(emptyMap()))
                    path.startsWith("/v1/ws") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; sockets.put(webSocket) }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        target = server.url("/").toString().trimEnd('/')
    }

    fun track(n: Int, title: String) = Track("car-$n", title, "Test Artist", durationMs = SONG_MS, source = SourceType.CUSTOM,
        url = server.url("/audio/car-$n.wav").toString())

    fun goOffline() { online = false; socket?.close(1001, "offline"); socket = null }
    fun goOnline() { online = true }

    /** Waits for the app's realtime socket to (re)connect. */
    fun awaitSocket(timeoutSeconds: Long = 30): WebSocket =
        sockets.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("Realtime socket never connected")

    /** Another client (the desktop app) re-publishes the app state the server already holds. */
    fun republishFromOtherClient(socket: WebSocket) {
        version++
        socket.send(buildJsonObject {
            put("event_type", "state_updated"); put("namespace", "app_state")
            put("version", version); put("source_client_id", "desktop")
        }.toString())
    }

    fun awaitPush(timeoutSeconds: Long = 30, matches: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val push = acceptedPushes.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("No matching app-state push")
            if (matches(push)) return push
        }
    }

    fun shutdown() { socket?.close(1001, "shutdown"); server.shutdown() }

    private fun json(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    companion object {
        const val SONG_MS = 60_000L
        private const val SAMPLE_RATE = 8_000
        /** 60 s of 8 kHz mono 16-bit silence: long enough that no song ends on its own during a test. */
        private val SILENT_WAV: ByteArray = run {
            val data = SAMPLE_RATE * 2 * (SONG_MS / 1000).toInt()
            ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
                putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data)
            }.array()
        }

        fun appState(current: Track, queue: List<Track>, state: String) = buildJsonObject {
            put("state", state); put("shuffle", false); put("repeat_mode", "off"); put("volume", 100)
            put("position", 0); put("duration", SONG_MS)
            put("current_track", AppJson.encodeToJsonElement(Track.serializer(), current))
            put("queue", JsonArray(queue.map { AppJson.encodeToJsonElement(Track.serializer(), it) }))
        }

        fun currentId(state: JsonObject) = state["current_track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
    }
}
