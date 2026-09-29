package com.anyplayer.android.feature.djfiller.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.sync.SyncPreferences
import com.anyplayer.android.feature.sync.SyncPreferencesStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DjPassageRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val track = Track("1", "Song One - 2011 Remaster", "Singer Two", album = "Album Three", source = SourceType.JELLYFIN)

    @Before fun clear() {
        context.getSharedPreferences("dj_passage_repository", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun preferences(token: String = "secret"): SyncPreferencesStore = mock<SyncPreferencesStore>().also {
        whenever(it.read()).thenReturn(SyncPreferences(serverTarget = "https://sync.example", authToken = token))
    }

    private fun response(request: Request, code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .body(body.toResponseBody()).build()

    private fun repository(token: String = "secret", answer: (Request) -> Pair<Int, String>): DjPassageRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = answer(chain.request())
            response(chain.request(), code, body)
        }.build()
        return DjPassageRepository(context, http, json, preferences(token))
    }

    private val okBody = """{"status":"ok","passages":[
        {"chunk_id":11,"text":"Song One was recorded in one night.","source":"genius","source_url":"https://genius.com/x","title":"Song One","subject":"song","lang":"en"},
        {"chunk_id":12,"text":"Singer Two formed the band in 1990.","source":"wikipedia-en","source_url":"https://en.wikipedia.org/wiki/Singer_Two","title":"Singer Two","subject":"artist","lang":"en"}]}"""

    @Test fun `ok response returns passages and sends song artist album with auth`() = runTest {
        var seen: Request? = null
        val passages = repository { request -> seen = request; 200 to okBody }.findPassages(track)
        assertEquals(listOf(11L, 12L), passages?.chunkIds)
        val url = seen!!.url
        assertEquals("/v1/dj-passages", url.encodedPath)
        assertEquals("Song One - 2011 Remaster", url.queryParameter("song"))
        assertEquals("Singer Two", url.queryParameter("artist"))
        assertEquals("Album Three", url.queryParameter("album"))
        assertEquals("Bearer secret", seen!!.header("Authorization"))
    }

    @Test fun `non ok statuses and failures return null`() = runTest {
        for (status in listOf("queued", "exhausted", "none")) {
            assertNull(repository { 200 to """{"status":"$status","passages":[]}""" }.findPassages(track))
        }
        assertNull(repository { 500 to "" }.findPassages(track))
        assertNull(repository { throw java.io.IOException("offline") }.findPassages(track))
    }

    @Test fun `played chunks are excluded locally and the event is sent once after restart`() = runTest {
        repository { 503 to "" }.recordPlayed(DjPassages(listOf(DjPassage(11, "t", "genius", "u"))))
        val posted = mutableListOf<String>()
        val online = repository { request ->
            if (request.url.encodedPath == "/v1/dj-passages/played") {
                posted += okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                204 to ""
            } else 200 to okBody
        }
        assertEquals(listOf(12L), online.findPassages(track)?.chunkIds) // chunk 11 excluded for 30 days
        online.flushPending()
        assertEquals(1, posted.size)
        assertTrue(posted.single().contains("\"chunk_ids\":[11]"))
    }

    @Test fun `rejected played event is dropped instead of retried forever`() = runTest {
        repository { 400 to """{"error":"bad"}""" }.also {
            it.recordPlayed(DjPassages(listOf(DjPassage(11, "t", "genius", "u"))))
            it.flushPending()
        }
        var calls = 0
        repository { request -> if (request.url.encodedPath.endsWith("/played")) calls++; 204 to "" }.flushPending()
        assertEquals(0, calls)
    }

    @Test fun `401 is dropped like 400`() = runTest {
        repository { 401 to """{"error":"unauthorized"}""" }.also {
            it.recordPlayed(DjPassages(listOf(DjPassage(11, "t", "genius", "u"))))
            it.flushPending()
        }
        var calls = 0
        repository { request -> if (request.url.encodedPath.endsWith("/played")) calls++; 204 to "" }.flushPending()
        assertEquals(0, calls)
    }

    @Test fun `429 is retried rather than dropped`() = runTest {
        repository { 429 to "" }.also {
            it.recordPlayed(DjPassages(listOf(DjPassage(11, "t", "genius", "u"))))
            it.flushPending()
        }
        var calls = 0
        repository { request -> if (request.url.encodedPath.endsWith("/played")) calls++; 204 to "" }.flushPending()
        assertEquals(1, calls)
    }

    @Test fun `played events are split into chunks of at most 16 ids each and every id is sent`() = runTest {
        val ids = (1L..20L).toList()
        val posted = mutableListOf<String>()
        val repo = repository { request ->
            if (request.url.encodedPath == "/v1/dj-passages/played") {
                posted += okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                204 to ""
            } else 200 to okBody
        }
        repo.recordPlayed(DjPassages(ids.map { DjPassage(it, "t", "genius", "u") }))
        repo.flushPending()

        assertEquals(2, posted.size)
        val events = posted.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("each event has its own event_id", 2, events.map { it["event_id"]!!.jsonPrimitive.content }.toSet().size)
        events.forEach { assertTrue(it["chunk_ids"]!!.jsonArray.size <= 16) }
        assertEquals(ids, events.flatMap { it["chunk_ids"]!!.jsonArray.map { id -> id.jsonPrimitive.long } })
    }

    @Test fun `a malformed response logs the exception class, never the passage text`() = runTest {
        CompatLog.clearAiDjLogs()
        val passageText = "PRIVATE PASSAGE CONTENT"
        repository { 200 to """{"status":"ok","passages":[{"chunk_id":"not-a-number","text":"$passageText"}]}""" }.findPassages(track)
        val logged = CompatLog.aiDjLogs.value.filter { it.message.contains("passage lookup failed") }
        assertEquals(1, logged.size)
        assertFalse(logged.single().message.contains(passageText))
    }

    @Test fun `excluded chunks are removed before fitting the budget, and over-budget passages are trimmed`() = runTest {
        val big = "word ".repeat(1500) // ~2000 estimated tokens
        val small = "word ".repeat(100) // ~134 estimated tokens
        val body = """{"status":"ok","passages":[
            {"chunk_id":1,"text":"$big","source":"genius","source_url":"u"},
            {"chunk_id":2,"text":"$big","source":"genius","source_url":"u"},
            {"chunk_id":3,"text":"$small","source":"genius","source_url":"u"},
            {"chunk_id":4,"text":"$big","source":"genius","source_url":"u"}]}"""
        val repo = repository { 200 to body }
        repo.recordPlayed(DjPassages(listOf(DjPassage(1, "x", "genius", "u")))) // excludes chunk 1
        // Without removing chunk 1 first, fitToBudget would keep [1] (always kept first) then
        // drop 2 and 4; chunk 2 only fits once the excluded chunk's budget is freed up front.
        // Chunk 4 is still over budget once 2 and 3 are kept, so it must be trimmed.
        assertEquals(listOf(2L, 3L), repo.findPassages(track)?.chunkIds)
    }

    @Test fun `different tokens keep separate played queues and exclusions`() = runTest {
        val repoA = repository(token = "token-a") { 200 to okBody }
        repoA.recordPlayed(DjPassages(listOf(DjPassage(11, "t", "genius", "u"))))
        val repoB = repository(token = "token-b") { 200 to okBody }

        // Token B has no exclusions of its own, so it still sees chunk 11.
        assertEquals(listOf(11L, 12L), repoB.findPassages(track)?.chunkIds)
        // Token A's own play is still excluded for itself.
        assertEquals(listOf(12L), repoA.findPassages(track)?.chunkIds)
    }
}
