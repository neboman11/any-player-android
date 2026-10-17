package com.anyplayer.android.sync

import com.anyplayer.android.core.model.*
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.transfer.MergePolicy
import com.anyplayer.android.playback.ChangeOrigin
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSyncEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    @Before fun before() { server = MockWebServer(); server.start(); http = OkHttpClient() }
    @After fun after() { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); server.shutdown(); http.dispatcher.executorService.shutdown() }

    private fun preferences() = SyncPreferences(serverTarget = server.url("/").toString(), authToken = " Bearer secret ", syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false)
    private inner class Fixture(scope: TestScope, preferences: SyncPreferences = preferences(), online: Boolean = false) {
        val engine = FakeEngine()
        val settings = FakeSyncSettings(preferences)
        val playerSettings = FakePlayerSettings()
        val playlists = FakePlaylists()
        val config = FakeConfig()
        val network = MutableStateFlow(online)
        val sync = DefaultSyncEngine(engine, settings, playerSettings, playlists, config, SyncClient(http),
            object : SyncNetwork { override val available = network }, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler),
            { scope.testScheduler.currentTime }, { it })
        suspend fun start() {
            server.enqueue(MockResponse().setBody("{\"version\":1,\"app_state\":{}}"))
            sync.start()
            sync.state.first { it.status == "Sync pull complete." }
        }
    }
    private suspend fun request() = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected sync request") }
    private fun json(text: String) = AppJson.parseToJsonElement(text).jsonObject
    private fun appState(position: Long = 3_000, queue: List<Track> = listOf(song)): JsonObject = buildJsonObject {
        put("state", "playing"); put("volume", 50); put("shuffle", true); put("repeat_mode", "all"); put("position", position)
        put("current_track", AppJson.encodeToJsonElement(Track.serializer(), song))
        put("queue", JsonArray(queue.map { AppJson.encodeToJsonElement(Track.serializer(), it) }))
    }

    @Test fun `initial empty queue never pushes but later clearing does`() = runTest {
        val f = Fixture(this); f.start(); request(); runCurrent()
        f.engine.pause(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setBody("{}"))
        f.engine.setQueue(listOf(song)); runCurrent()
        val push = request()
        assertEquals("/v1/state/app-state", push.path)
        assertEquals("Bearer secret", push.getHeader("Authorization"))
        assertEquals("this-client", json(push.body.readUtf8()).string("client_id"))
        advanceTimeBy(2_001); runCurrent()
        server.enqueue(MockResponse().setBody("{}"))
        f.engine.setQueue(emptyList()); runCurrent()
        assertFalse(json(request().body.readUtf8())["data"]!!.jsonObject.containsKey("queue"))
    }

    @Test fun `SYNC and RESTORE changes never push even after echo window`() = runTest {
        val f = Fixture(this); f.engine.setQueue(listOf(song), origin = ChangeOrigin.RESTORE)
        f.start(); request(); runCurrent()
        f.engine.seekTo(8_000, ChangeOrigin.SYNC); f.engine.pause(ChangeOrigin.RESTORE)
        runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertEquals(1, server.requestCount)
    }

    @Test fun `remote apply suppresses user echo for 3500 milliseconds`() = runTest {
        val f = Fixture(this); f.start(); request(); runCurrent()
        f.sync.applyAppState(appState())
        f.engine.setVolume(55); runCurrent(); advanceTimeBy(3_499); runCurrent()
        assertEquals(1, server.requestCount)
        advanceTimeBy(1); runCurrent()
        server.enqueue(MockResponse().setBody("{}"))
        f.engine.pause(); runCurrent()
        assertEquals("/v1/state/app-state", request().path)
    }

    @Test fun `apply preserves legacy command order and uses SYNC origin`() = runTest {
        val f = Fixture(this)
        f.sync.applyAppState(appState())
        assertEquals(listOf("volume:50", "shuffle:true", "repeat:ALL", "queue:false", "seek:3000", "play"), f.engine.calls)
        assertTrue(f.engine.origins.all { it == ChangeOrigin.SYNC })
        f.engine.calls.clear()
        f.sync.applyAppState(appState(position = 5_500))
        assertEquals(listOf("volume:50", "repeat:ALL", "play"), f.engine.calls)
        f.engine.calls.clear()
        f.sync.applyAppState(appState(position = 5_501))
        assertTrue("seek:5501" in f.engine.calls)
        assertFalse(f.engine.calls.any { it.startsWith("queue:") || it.startsWith("shuffle:") })
    }

    @Test fun `wire queue uses insertion order and unknown playback states stop`() = runTest {
        val f = Fixture(this)
        f.engine.setQueue(listOf(song, secondSong))
        f.engine.queue.value = f.engine.queue.value.copy(order = listOf(1, 0), cursor = 0, shuffle = true)
        val data = AppStateSyncPayload.capture(f.engine).toJson()
        assertEquals("stopped", data.string("state"))
        assertEquals(listOf("one", "two"), data["queue"]!!.jsonArray.map { it.jsonObject.string("id") })
        assertEquals("two", data["current_track"]!!.jsonObject.string("id"))
    }

    @Test fun `conflict detection respects each selected domain`() {
        val empty = SyncPreferences(syncAppState = false, syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false)
        val samples = listOf(
            "app_state" to (appState() to empty.copy(syncAppState = true)),
            "playlists" to (JsonArray(listOf(JsonObject(emptyMap()))) to empty.copy(syncPlaylists = true)),
            "settings" to (json("{\"x\":true}") to empty.copy(syncSettings = true)),
            "provider_configuration" to (json("{\"plex\":{}}") to empty.copy(syncProviderConfiguration = true)),
        )
        for ((domain, pair) in samples) {
            val snapshot = buildJsonObject { put(domain, pair.first) }
            assertTrue(domain, DefaultSyncEngine.hasServerData(snapshot, pair.second))
            assertFalse(domain, DefaultSyncEngine.hasServerData(snapshot, empty))
        }
        assertFalse(DefaultSyncEngine.hasServerData(json("{\"app_state\":{\"current_track\":{},\"queue\":[]}}"), empty.copy(syncAppState = true)))
    }

    @Test fun `connect detects conflict and dismiss preserves local state`() = runTest {
        val f = Fixture(this)
        server.enqueue(MockResponse().setBody(buildJsonObject { put("version", 2); put("app_state", appState()) }.toString()))
        f.sync.connect()
        f.sync.state.first { it.conflictPending }
        assertEquals("Server already has synced data - choose which side to keep.", f.sync.state.value.status)
        f.sync.dismissConflict()
        assertEquals("Sync connect cancelled.", f.sync.state.value.status)
        assertTrue(f.engine.queue.value.entries.isEmpty())
    }

    @Test fun `playlist overwrite requires confirmation and skips provider import too`() = runTest {
        val prefs = preferences().copy(syncAppState = false, syncPlaylists = true, syncProviderConfiguration = true)
        val f = Fixture(this, prefs)
        f.playlists.customPlaylists.value = listOf(localPlaylist)
        server.enqueue(MockResponse().setBody("{\"version\":7,\"playlists\":[],\"provider_configuration\":{}}"))
        f.sync.pull(false)
        f.sync.state.first { it.status == "Sync cancelled: playlist overwrite requires confirmation." }
        assertTrue(f.config.imports.isEmpty())
        assertEquals(7L, f.sync.lastSyncVersion)
        server.enqueue(MockResponse().setBody("{\"version\":8,\"playlists\":[],\"provider_configuration\":{}}"))
        f.sync.pull(true)
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(MergePolicy.REPLACE_ALL, f.config.imports.single().second)
        assertEquals(8L, f.sync.lastSyncVersion)
    }

    @Test fun `provider only import merges without deleting playlists and supports camelCase settings`() = runTest {
        val f = Fixture(this, preferences().copy(syncAppState = false, syncProviderConfiguration = true, syncSettings = true))
        server.enqueue(MockResponse().setBody("{\"version\":3,\"settings\":{\"audioNormalizationEnabled\":true,\"audioNormalizationStrictMode\":true},\"provider_configuration\":{}}"))
        f.sync.pull(false)
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(MergePolicy.MERGE_KEEP_LOCAL, f.config.imports.single().second)
        assertTrue("normalization:true:true" in f.engine.calls)
        assertEquals(3L, f.sync.lastSyncVersion)
    }

    @Test fun `since_version 304 is ignored and old own unknown events make no request`() = runTest {
        val f = Fixture(this, preferences().copy(syncSettings = true))
        f.start(); request()
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("app_state", 2, "this-client"))
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("app_state", 1, "other"))
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("unknown", 2, "other"))
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(304))
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("settings", 2, "other"))
        assertEquals("/v1/snapshot?since_version=1", request().path)
        assertEquals(1L, f.sync.lastSyncVersion)
    }

    @Test fun `app state endpoint falls back to snapshot since version`() = runTest {
        val f = Fixture(this); f.start(); request()
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(buildJsonObject { put("version", 4); put("app_state", appState()) }.toString()))
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("app_state", 4, "other"))
        assertEquals("/v1/state/app-state", request().path)
        assertEquals("/v1/snapshot?since_version=1", request().path)
        assertEquals(song, f.engine.queue.value.current?.track)
        assertEquals(4L, f.sync.lastSyncVersion)
    }

    @Test fun `startup pull does not rewind local changes during fetch`() = runTest {
        val f = Fixture(this)
        val fetchStarted = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                fetchStarted.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return MockResponse().setBody(buildJsonObject { put("version", 9); put("app_state", appState()) }.toString())
            }
        }
        f.sync.start(); fetchStarted.await()
        f.engine.setQueue(listOf(secondSong)); runCurrent()
        release.countDown()
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(secondSong, f.engine.queue.value.current?.track)
        assertEquals(9L, f.sync.lastSyncVersion)
    }

    @Test fun `websocket filters malformed frames and fetches app state only for remote event`() = runTest {
        val f = Fixture(this, online = true)
        val socketReady = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().setBody("{\"version\":1,\"app_state\":{}}"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socketReady.complete(webSocket) }
        }))
        f.sync.start()
        val socket = socketReady.await()
        server.enqueue(MockResponse().setBody(buildJsonObject { put("version", 2); put("data", appState()) }.toString()))
        socket.send("not json")
        socket.send("{\"event_type\":\"hello\",\"namespace\":\"app_state\",\"version\":2}")
        socket.send("{\"event_type\":\"state_updated\",\"namespace\":\"app_state\",\"version\":2,\"source_client_id\":\"this-client\"}")
        socket.send("{\"event_type\":\"state_updated\",\"namespace\":\"app_state\",\"version\":1}")
        socket.send("{\"event_type\":\"state_updated\",\"namespace\":\"app_state\",\"version\":2,\"source_client_id\":\"other\"}")
        f.engine.queue.first { it.current?.track == song }
        assertEquals("/v1/snapshot", request().path)
        assertEquals("/v1/ws", request().path)
        assertEquals("/v1/state/app-state", request().path)
        assertEquals(3, server.requestCount)
    }

    @Test fun `remote conflict resolution confirms playlist overwrite`() = runTest {
        val f = Fixture(this, preferences().copy(syncPlaylists = true))
        f.playlists.customPlaylists.value = listOf(localPlaylist)
        val remote = buildJsonObject { put("version", 4); put("app_state", appState()); put("playlists", JsonArray(emptyList())) }.toString()
        server.enqueue(MockResponse().setBody(remote))
        f.sync.connect()
        f.sync.state.first { it.conflictPending }
        server.enqueue(MockResponse().setBody(remote))
        f.sync.resolveConflict(false)
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(song, f.engine.queue.value.current?.track)
        assertEquals(MergePolicy.REPLACE_ALL, f.config.imports.single().second)
        assertFalse(f.sync.state.value.conflictPending)
    }

    @Test fun `local conflict resolution exports each selected domain`() = runTest {
        val f = Fixture(this, preferences().copy(syncSettings = true, syncPlaylists = true, syncProviderConfiguration = true))
        f.engine.setQueue(listOf(song), origin = ChangeOrigin.RESTORE)
        server.enqueue(MockResponse().setBody("{\"version\":3,\"settings\":{\"existing\":true}}"))
        f.sync.connect()
        f.sync.state.first { it.conflictPending }
        request()
        repeat(4) { server.enqueue(MockResponse().setBody("{}")) }
        f.sync.resolveConflict(true)
        f.sync.state.first { it.status == "Pushed local data to server." }
        val requests = List(4) { request() }
        assertEquals(setOf("/v1/state/app-state", "/v1/state/settings", "/v1/state/playlists", "/v1/state/provider-configuration"), requests.map { it.path }.toSet())
        requests.forEach { assertEquals("this-client", json(it.body.readUtf8()).string("client_id")) }
    }

    @Test fun `offline realtime waits for connectivity and persisted credentials restart socket`() = runTest {
        val f = Fixture(this)
        f.start(); request(); runCurrent()
        assertEquals(1, server.requestCount)
        val connected = kotlinx.coroutines.channels.Channel<WebSocket>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        fun socketResponse() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { connected.trySend(webSocket) }
        })
        server.enqueue(socketResponse())
        f.network.value = true
        connected.receive()
        assertEquals("/v1/ws", request().path)
        server.enqueue(socketResponse())
        f.settings.update { it.copy(authToken = "replacement") }
        connected.receive()
        assertEquals("Bearer replacement", request().getHeader("Authorization"))
        f.network.value = false; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(3, server.requestCount)
    }

    @Test fun `discrete changes coalesce at least two seconds apart without periodic pushes`() = runTest {
        val f = Fixture(this)
        f.start(); request(); runCurrent()
        val pushTimes = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                pushTimes.trySend(testScheduler.currentTime)
                return MockResponse().setBody("{}")
            }
        }
        f.engine.setQueue(listOf(song)); runCurrent()
        val firstTime = pushTimes.receive()
        f.engine.setVolume(25); f.engine.seekTo(12_345); f.engine.pause(); runCurrent()
        advanceTimeBy(1_999); runCurrent()
        assertEquals(2, server.requestCount)
        advanceTimeBy(2_001); runCurrent()
        val secondTime = pushTimes.receive()
        assertTrue(secondTime - firstTime >= 2_000)
        request()
        val payload = json(request().body.readUtf8())["data"]!!.jsonObject
        assertEquals(12_345L, payload.number("position"))
        assertEquals(25L, payload.number("volume"))
        assertEquals("paused", payload.string("state"))
        advanceTimeBy(120_000); runCurrent()
        assertEquals(3, server.requestCount)
    }

    @Test fun `base and bearer normalization match legacy`() {
        assertEquals("", normalizeSyncBaseUrl(" / "))
        assertEquals("https://example.test/base", normalizeSyncBaseUrl(" example.test/base/// "))
        assertEquals("http://example.test", normalizeSyncBaseUrl("http://example.test/"))
        assertEquals("token", normalizeSyncToken(" bEaReR  token "))
        assertEquals("", normalizeSyncToken(" "))
    }
}
