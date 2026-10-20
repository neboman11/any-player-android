package com.anyplayer.android.sync

import com.anyplayer.android.core.model.AppJson
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.testing.TestServer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse

/**
 * Sync server (legacy wire protocol subset) registered on a shared [TestServer].
 * [online] = false answers every sync call with 503 and drops the realtime socket, like losing the
 * route to a self-hosted server while driving. Other fakes on the same server (audio, providers) keep working.
 */
class FakeSyncServer(val server: TestServer = TestServer()) {
    /** Value for `SyncPreferences.serverTarget`. */
    val target: String get() = server.baseUrl
    @Volatile var online = true
        private set
    @Volatile var appState: JsonObject = JsonObject(emptyMap())
    /** Server copies of the non-app-state namespaces (`settings`, `playlists`, `provider_configuration`), as stored by PUTs. */
    val namespaces = java.util.concurrent.ConcurrentHashMap<String, JsonElement>()
    @Volatile var version = 1L
        private set
    @Volatile private var socket: WebSocket? = null
    private val sockets = LinkedBlockingQueue<WebSocket>()
    /** `data` of every app-state PUT the server accepted. */
    val acceptedPushes = LinkedBlockingQueue<JsonObject>()

    init {
        server.route(pathPrefix = "/v1/") { MockResponse().setResponseCode(if (online) 404 else 503) }
        server.route("GET", "/v1/snapshot") {
            if (!online) return@route unavailable()
            json(buildJsonObject {
                put("version", version); put("app_state", appState)
                namespaces.forEach { (key, value) -> put(key, value) }
            })
        }
        server.route("GET", "/v1/state/app-state") {
            if (!online) unavailable() else json(buildJsonObject { put("version", version); put("data", appState) })
        }
        server.route("PUT", "/v1/state/") { request ->
            if (!online) return@route unavailable()
            val namespace = request.path.removePrefix("/v1/state/")
            val data = AppJson.parseToJsonElement(request.body).jsonObject.getValue("data")
            if (namespace == "app-state") { appState = data.jsonObject; acceptedPushes.put(data.jsonObject) }
            else namespaces[namespace.replace('-', '_')] = data
            version++
            json(JsonObject(emptyMap()))
        }
        server.route("GET", "/v1/ws") {
            if (!online) unavailable() else MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket; sockets.put(webSocket) }
            })
        }
    }

    /** A CUSTOM-source song streamed from this server (no provider connection needed to play it). */
    fun track(n: Int, title: String, durationMs: Long = SONG_MS) = Track("car-$n", title, "Test Artist", durationMs = durationMs,
        source = SourceType.CUSTOM, url = server.audio("/audio/car-$n.wav", durationMs))

    fun goOffline() { online = false; socket?.close(1001, "offline"); socket = null }
    fun goOnline() { online = true }

    /** Waits for the app's realtime socket to (re)connect. */
    fun awaitSocket(timeoutSeconds: Long = 30): WebSocket =
        sockets.poll(timeoutSeconds, TimeUnit.SECONDS) ?: error("Realtime socket never connected")

    /** Another client (e.g. the desktop app) published [namespace] (`app_state`, `playlists`, `settings`, ...). */
    fun publishFromOtherClient(socket: WebSocket, namespace: String = "app_state") {
        version++
        socket.send(buildJsonObject {
            put("event_type", "state_updated"); put("namespace", namespace)
            put("version", version); put("source_client_id", "desktop")
        }.toString())
    }

    /** Another client re-publishes the app state the server already holds. */
    fun republishFromOtherClient(socket: WebSocket) = publishFromOtherClient(socket)

    fun awaitPush(timeoutSeconds: Long = 30, matches: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val push = acceptedPushes.poll(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("No matching app-state push")
            if (matches(push)) return push
        }
    }

    fun shutdown() { socket?.close(1001, "shutdown"); server.close() }

    private fun unavailable() = MockResponse().setResponseCode(503)
    private fun json(body: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    companion object {
        const val SONG_MS = 60_000L

        fun appState(current: Track, queue: List<Track>, state: String) = buildJsonObject {
            put("state", state); put("shuffle", false); put("repeat_mode", "off"); put("volume", 100)
            put("position", 0); put("duration", SONG_MS)
            put("current_track", AppJson.encodeToJsonElement(Track.serializer(), current))
            put("queue", JsonArray(queue.map { AppJson.encodeToJsonElement(Track.serializer(), it) }))
        }

        fun currentId(state: JsonObject) = state["current_track"]?.jsonObject?.get("id")?.jsonPrimitive?.content
    }
}
