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
    @After fun after() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        assertTrue(http.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        server.shutdown()
    }

    private fun preferences() = SyncPreferences(serverTarget = server.url("/").toString(), authToken = " Bearer secret ", syncPlaylists = false, syncProviderConfiguration = false, syncSettings = false)
    private inner class Fixture(
        scope: TestScope, preferences: SyncPreferences = preferences(), online: Boolean = false,
        playlistSource: com.anyplayer.android.data.playlists.PlaylistRepository? = null,
    ) {
        val engine = FakeEngine()
        val settings = FakeSyncSettings(preferences)
        val playerSettings = FakePlayerSettings()
        val playlists = FakePlaylists()
        val config = FakeConfig()
        val network = MutableStateFlow(online)
        val backoffs = mutableListOf<Long>()
        val base = FakeAppStateBase()
        val importStatus = SyncImportStatus()
        val imports = SyncImports(config, com.anyplayer.android.ui.settings.FakeConnections(),
            com.anyplayer.android.ui.settings.FakeAuth(), com.anyplayer.android.ui.settings.FakeCatalog(), importStatus)
        val sync = DefaultSyncEngine(engine, settings, playerSettings, playlistSource ?: playlists, config, SyncClient(http),
            object : SyncNetwork { override val available = network }, scope.backgroundScope, StandardTestDispatcher(scope.testScheduler),
            { scope.testScheduler.currentTime }, { backoffs += it; it }, imports, base)
        val target = server.url("/").toString().trimEnd('/')
        suspend fun start() {
            server.enqueue(MockResponse().setBody("{\"version\":1,\"app_state\":{}}"))
            sync.start()
            sync.state.first { it.status == "Sync pull complete." }
        }
    }
    @Test fun `slow Room emission conservatively skips playlists without disabling realtime`() = runTest {
        val slow = object : com.anyplayer.android.data.playlists.PlaylistRepository by unused() {
            override val customPlaylists = flow<List<CustomPlaylist>> { awaitCancellation() }
        }
        val f = Fixture(this, preferences().copy(syncPlaylists = true), online = true, playlistSource = slow)
        val opened = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody("{\"version\":1,\"playlists\":[]}"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
        }))
        f.sync.start()
        opened.await()
        assertEquals("Sync cancelled: playlist overwrite requires confirmation.", f.sync.state.value.status)
        assertTrue(f.config.imports.isEmpty())
        assertEquals("/v1/snapshot", request().path)
        assertEquals("/v1/ws", request().path)
        f.network.value = false
        runCurrent()
    }

    @Test fun `editing persisted target cannot redirect automatic pushes without Connect`() = runTest {
        val f = Fixture(this)
        f.start(); request(); runCurrent()
        f.settings.update { it.copy(serverTarget = server.url("/unconfirmed").toString(), authToken = "unconfirmed") }
        server.enqueue(MockResponse().setBody("{}"))
        f.engine.setQueue(listOf(song)); runCurrent()
        val push = request()
        assertEquals("/v1/state/app-state", push.path)
        assertEquals("Bearer secret", push.getHeader("Authorization"))
    }

    @Test fun `healthy socket resets reconnect delay after each later drop`() = runTest {
        val f = Fixture(this, online = true)
        val connected = kotlinx.coroutines.channels.Channel<WebSocket>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        fun response() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { connected.trySend(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        server.enqueue(MockResponse().setBody("{\"version\":1,\"app_state\":{}}"))
        server.enqueue(response())
        f.sync.start()
        repeat(2) {
            val socket = connected.receive()
            f.sync.state.first { it.realtimeConnected }
            server.enqueue(response())
            socket.close(1000, "drop")
            f.sync.state.first { !it.realtimeConnected }
            runCurrent()
        }
        connected.receive()
        assertEquals(listOf(1_000L, 1_000L), f.backoffs)
        f.network.value = false
        runCurrent()
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

    @Test fun `dismissing the connect conflict preserves local state and stops realtime and automatic pushes`() = runTest {
        val f = Fixture(this, online = true)
        val opened = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setBody(buildJsonObject { put("version", 2); put("app_state", appState()) }.toString()))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
        }))
        f.sync.connect()
        f.sync.state.first { it.conflictPending }
        assertEquals("Server already has synced data - choose which side to keep.", f.sync.state.value.status)
        opened.await()
        f.sync.state.first { it.realtimeConnected }
        f.sync.dismissConflict()
        assertEquals("Sync connect cancelled.", f.sync.state.value.status)
        assertTrue(f.engine.queue.value.entries.isEmpty())
        f.sync.state.first { !it.realtimeConnected }
        f.engine.setQueue(listOf(song)); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals("/v1/snapshot", request().path)
        assertEquals("/v1/ws", request().path)
        assertEquals("No reconnect or push after Cancel", 2, server.requestCount)
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
        assertTrue(f.playerSettings.settings.value.normalizationEnabled)
        assertTrue(f.playerSettings.settings.value.normalizationStrict)
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
    @Test fun `realtime domain updates never replay app state or unrelated domains`() = runTest {
        for (namespace in listOf("playlists", "settings", "provider_configuration")) {
            val prefs = preferences().copy(syncPlaylists = true, syncProviderConfiguration = true, syncSettings = true)
            val f = Fixture(this, prefs)
            f.playlists.customPlaylists.value = listOf(localPlaylist)
            server.enqueue(MockResponse().setBody(buildJsonObject {
                put("version", 2); put("app_state", appState())
                put("settings", json("{\"audio_normalization_enabled\":true}"))
                put("playlists", JsonArray(emptyList()))
                put("provider_configuration", JsonObject(emptyMap()))
            }.toString()))
            f.sync.receiveUpdate(prefs, SyncUpdate(namespace, 2, "other"))
            assertTrue(f.engine.queue.value.entries.isEmpty())
            assertFalse(f.engine.calls.any { it.startsWith("seek:") || it == "play" })
            assertFalse(f.sync.state.value.status.contains("confirmation"))
            assertEquals(namespace == "settings", f.playerSettings.settings.value.normalizationEnabled)
            assertEquals(if (namespace == "provider_configuration") 1 else 0, f.config.imports.size)
            request()
        }
    }

    @Test fun `startup duration and anchor updates do not veto remote state`() = runTest {
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
        f.engine.player.value = f.engine.player.value.copy(durationMs = 100_000, anchorElapsedRealtimeMs = 300)
        release.countDown()
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(song, f.engine.queue.value.current?.track)
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

    private fun remote(state: JsonObject, version: Long = 9) = MockResponse().setBody(buildJsonObject { put("version", version); put("app_state", state) }.toString())
    private fun pushedCurrentId(push: okhttp3.mockwebserver.RecordedRequest) =
        json(push.body.readUtf8())["data"]!!.jsonObject["current_track"]!!.jsonObject.string("id")

    @Test fun `startup pushes playback changed since the last sync instead of rewinding to the unchanged server copy`() = runTest {
        val f = Fixture(this)
        // The server still holds what this device last pushed; playback moved on while it was unreachable.
        f.base.values.value = mapOf(f.target to appStateSignature(song, listOf(song), true, "all"))
        f.engine.setQueue(listOf(song, secondSong), startIndex = 1, origin = ChangeOrigin.RESTORE)
        server.enqueue(remote(appState()))
        server.enqueue(MockResponse().setBody("{}"))
        f.sync.start()
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(secondSong, f.engine.queue.value.current?.track)
        assertEquals("/v1/snapshot", request().path)
        val push = request()
        assertEquals("/v1/state/app-state", push.path)
        assertEquals("two", pushedCurrentId(push))
    }

    @Test fun `startup applies server state another client changed even when local playback changed too`() = runTest {
        val f = Fixture(this)
        f.base.values.value = mapOf(f.target to appStateSignature(secondSong, listOf(secondSong), false, "off"))
        f.engine.setQueue(listOf(song, secondSong), startIndex = 1, origin = ChangeOrigin.RESTORE)
        server.enqueue(remote(appState()))
        f.sync.start()
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(song, f.engine.queue.value.current?.track)
        assertEquals(appStateSignature(song, listOf(song), true, "all"), f.base.values.value[f.target])
    }

    @Test fun `startup never replaces playback already running locally`() = runTest {
        val f = Fixture(this)
        f.engine.setQueue(listOf(secondSong), origin = ChangeOrigin.RESTORE)
        f.engine.player.value = f.engine.player.value.copy(status = PlaybackStateType.PLAYING, playRequested = true)
        server.enqueue(remote(appState()))
        server.enqueue(MockResponse().setBody("{}"))
        f.sync.start()
        f.sync.state.first { it.status == "Sync pull complete." }
        assertEquals(secondSong, f.engine.queue.value.current?.track)
        assertFalse("pause" in f.engine.calls)
        request()
        assertEquals("two", pushedCurrentId(request()))
    }

    @Test fun `realtime republish of the last synced state answers with unpushed local playback`() = runTest {
        val f = Fixture(this); f.start(); request(); runCurrent()
        server.enqueue(MockResponse().setBody("{}"))
        f.engine.setQueue(listOf(song)); runCurrent()
        request()
        f.base.values.first { it[f.target] != null }
        advanceTimeBy(2_001); runCurrent()
        // Server unreachable: the next change is not stored remotely.
        server.enqueue(MockResponse().setResponseCode(503))
        f.engine.setQueue(listOf(song, secondSong), startIndex = 1); runCurrent()
        request()
        val stale = buildJsonObject {
            put("state", "paused"); put("shuffle", false); put("repeat_mode", "off")
            put("current_track", AppJson.encodeToJsonElement(Track.serializer(), song))
            put("queue", JsonArray(listOf(AppJson.encodeToJsonElement(Track.serializer(), song))))
        }
        server.enqueue(MockResponse().setBody(buildJsonObject { put("version", 5); put("data", stale) }.toString()))
        server.enqueue(MockResponse().setBody("{}"))
        f.sync.receiveUpdate(f.settings.preferences.value, SyncUpdate("app_state", 5, "other"))
        assertEquals(secondSong, f.engine.queue.value.current?.track)
        assertFalse("pause" in f.engine.calls)
        assertEquals("/v1/state/app-state", request().path)
        val push = request()
        assertEquals("PUT", push.method)
        assertEquals("two", pushedCurrentId(push))
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

    @Test fun `offline realtime waits for connectivity and credentials restart only on connect`() = runTest {
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
        f.settings.update { it.copy(authToken = "replacement") }
        runCurrent()
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody("{\"version\":2,\"app_state\":{}}"))
        server.enqueue(socketResponse())
        f.sync.connect()
        connected.receive()
        assertEquals("/v1/snapshot", request().path)
        assertEquals("Bearer replacement", request().getHeader("Authorization"))
        f.network.value = false; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(4, server.requestCount)
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
}
