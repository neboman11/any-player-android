package com.anyplayer.android.dj

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.data.settings.SyncPreferences
import com.anyplayer.android.data.settings.SyncSettingsStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class FakeSync(target: String) : SyncSettingsStore {
    override val preferences = MutableStateFlow(SyncPreferences(serverTarget = target, authToken = "token"))
    override suspend fun awaitLoaded() = preferences.value
    override suspend fun update(transform: (SyncPreferences) -> SyncPreferences) { preferences.value = transform(preferences.value) }
    override suspend fun clientId() = "test"
}
internal class MemoryPassageStore : PassageStateStore {
    val values = ConcurrentHashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
}
class PassageRepositoryTest {
    private val server = MockWebServer()
    private val receipts = CopyOnWriteArrayList<JsonObject>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var receiptCode = 204
    private var lookupFailures = 0
    private var lookupCode = 200
    private var offered = listOf(Passage(1, "First story", "source"), Passage(2, "Second story", "source"))
    private val store = MemoryPassageStore()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
    private var now = Instant.parse("2026-10-01T00:00:00Z")
    private lateinit var sync: FakeSync
    private lateinit var repo: PassageRepository
    private val track = Track("id", "Song <&>", "Artist", album = "Album", source = SourceType.CUSTOM)
    @Before fun setup() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.path!!.startsWith("/v1/dj-passages/played")) {
                    receipts += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    return MockResponse().setResponseCode(receiptCode)
                }
                if (lookupFailures-- > 0) return MockResponse().setResponseCode(503)
                return MockResponse().setResponseCode(lookupCode).setBody(buildJsonObject {
                    put("status", "ok"); put("passages", Json.encodeToJsonElement(offered))
                }.toString())
            }
        }
        server.start(); sync = FakeSync(server.url("/").toString())
        repo = repository()
    }
    private fun repository() = PassageRepository(OkHttpClient(), sync, store, scope, DjDiagnostics()) { now }
    @After fun close() { scope.cancel(); server.shutdown() }
    @Test fun `lookup escapes metadata and authenticates`() = runBlocking {
        assertEquals(listOf(1L, 2L), repo.findPassages(track)!!.chunkIds)
        val request = requests.first()
        assertEquals("Song <&>", request.requestUrl!!.queryParameter("song"))
        assertEquals("Album", request.requestUrl!!.queryParameter("album"))
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }
    @Test fun `locally played passages excluded for thirty days across restart`() = runBlocking {
        repo.recordPlayed(Passages(listOf(offered[0])))
        repo = repository()
        assertEquals(listOf(2L), repo.findPassages(track)!!.chunkIds)
        now = now.plusSeconds(PassageRepository.EXCLUSION_SECONDS + 1)
        assertEquals(listOf(1L, 2L), repo.findPassages(track)!!.chunkIds)
    }
    @Test fun `all excluded returns no break`() = runBlocking {
        repo.recordPlayed(Passages(offered)); assertNull(repo.findPassages(track))
    }
    @Test fun `exclusion happens before rank ordered token budget`() = runBlocking {
        offered = listOf(Passage(1, "x".repeat(12_000), "x"), Passage(2, "y".repeat(6_000), "x"),
            Passage(3, "z".repeat(6_000), "x"), Passage(4, "short", "x"))
        repo.recordPlayed(Passages(listOf(offered[0])))
        assertEquals(listOf(2L), repo.findPassages(track)!!.chunkIds)
    }
    @Test fun `first over budget passage is retained`() = runBlocking {
        offered = listOf(Passage(1, "漢".repeat(3_000), "x"), Passage(2, "short", "x"))
        assertEquals(listOf(1L), repo.findPassages(track)!!.chunkIds)
        assertEquals(4, estimateTokens("abc 漢字字"))
    }
    @Test fun `503 retries twice then succeeds`() = runBlocking {
        lookupFailures = 2
        assertNotNull(repo.findPassages(track)); assertEquals(3, requests.size)
    }
    @Test fun `other HTTP errors are not retried`() = runBlocking {
        lookupCode = 401
        assertNull(repo.findPassages(track)); assertEquals(1, requests.size)
    }
    @Test fun `503 exhausts three attempts`() = runBlocking {
        lookupFailures = 8
        assertNull(repo.findPassages(track)); assertEquals(3, requests.size)
    }
    @Test fun `receipts split sixteen per event and reuse event ids until accepted`() = runBlocking {
        val all = (1L..35L).map { Passage(it, "text", "x") }
        repo.recordPlayed(Passages(all)); receiptCode = 503; repo.flushPending()
        val firstId = receipts.first()["event_id"]!!.jsonPrimitive.content
        receiptCode = 204; repo.flushPending(); repo.flushPending()
        assertEquals(4, receipts.size)
        assertEquals(firstId, receipts[1]["event_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(16, 16, 3), receipts.drop(1).map { it["chunk_ids"]!!.jsonArray.size })
    }
    @Test fun `client errors drop but transient errors retain receipts`() = runBlocking {
        for (code in listOf(400, 401, 403, 404, 408, 429, 500)) {
            receipts.clear(); store.values.clear()
            repo.recordPlayed(Passages(listOf(offered[0]))); receiptCode = code; repo.flushPending()
            receiptCode = 204; repo.flushPending()
            assertEquals("HTTP $code", if (code in listOf(408, 429, 500)) 2 else 1, receipts.size)
        }
    }
    @Test fun `accounts preserve separate queues and exclusions`() = runBlocking {
        repo.recordPlayed(Passages(offered))
        sync.update { it.copy(authToken = "other") }
        assertEquals(listOf(1L, 2L), repo.findPassages(track)!!.chunkIds)
        repo.flushPending(); assertTrue(receipts.isEmpty())
        sync.update { it.copy(authToken = "token") }; repo.flushPending()
        assertEquals(1, receipts.size)
        assertTrue(store.values.keys.all { it.matches(Regex("state_[0-9a-f]{64}")) })
    }
    @Test fun `pending receipts bounded and legacy JSON field names preserved`() = runBlocking {
        val chunks = (1L..(PassageRepository.MAX_PENDING + 2L) * 16).map { Passage(it, "text", "x") }
        repo.recordPlayed(Passages(chunks))
        val json = Json.parseToJsonElement(store.values.values.single()).jsonObject
        assertEquals(PassageRepository.MAX_PENDING, json.getValue("played").jsonArray.size)
        assertEquals(setOf("chunkIds", "eventId", "playedAt"), json.getValue("played").jsonArray.first().jsonObject.keys)
        assertEquals(setOf("chunkId", "playedAt"), json.getValue("usage").jsonArray.first().jsonObject.keys)
    }
    @Test fun `lookup never waits for background receipt flush`() = runBlocking {
        repo.recordPlayed(Passages(listOf(offered[0])))
        assertEquals(listOf(2L), repo.findPassages(track)!!.chunkIds)
        assertTrue(receipts.isEmpty())
    }
}
