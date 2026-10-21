package com.anyplayer.android.playback.spotify

import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.ConnectionStore
import com.anyplayer.android.data.auth.SpotifyTokenProvider
import com.anyplayer.android.data.auth.StoredConnection
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlayerApiTest {
    private class Tokens(var token: String? = "old", var refreshed: String? = "new") : SpotifyTokenProvider {
        var refreshes = 0
        override suspend fun accessToken() = token
        override suspend fun refreshAfterUnauthorized(): String? { refreshes++; return refreshed }
    }

    private class Connections(linked: Boolean = true) : ConnectionStore {
        override val connections = MutableStateFlow(
            if (linked) mapOf(SourceType.SPOTIFY to StoredConnection(SourceType.SPOTIFY)) else emptyMap(),
        )
        override suspend fun awaitLoaded() = connections.value
        override suspend fun update(source: SourceType, transform: (StoredConnection?) -> StoredConnection?) {
            val result = transform(connections.value[source])
            connections.value = connections.value.toMutableMap().apply {
                if (result == null) remove(source) else put(source, result)
            }
        }
    }

    @Test fun state_parsesActualAndLinkedTrackIds() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"is_playing":true,"progress_ms":1234,"item":{"id":"actual","linked_from":{"id":"original"},"duration_ms":9000},"device":{"volume_percent":42},"shuffle_state":true,"repeat_state":"track"}"""))
            val api = OkHttpSpotifyPlayerApi(OkHttpClient(), Tokens(), Connections(), server.url("/v1/"))
            val state = requireNotNull(api.playbackState())
            assertEquals("actual", state.trackId)
            assertEquals("original", state.linkedFromId)
            assertEquals(1234L, state.progressMs)
            assertEquals(9000L, state.durationMs)
            assertEquals(42, state.volumePercent)
            assertTrue(state.isPlaying && state.shuffle)
            assertEquals(RepeatMode.ONE, state.repeat)
            assertEquals("/v1/me/player", server.takeRequest().path)
        }
    }

    @Test fun unauthorized_refreshesAndRetriesExactlyOnce() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(204))
            val tokens = Tokens()
            val api = OkHttpSpotifyPlayerApi(OkHttpClient(), tokens, Connections(), server.url("/v1/"))
            api.pause()
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, tokens.refreshes)
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            val failure = try { api.resume(); null } catch (e: SpotifyCommandException) { e }
            assertEquals("Spotify command failed: play. HTTP 401", failure?.message)
            assertEquals(4, server.requestCount)
            assertEquals(2, tokens.refreshes)
        }
    }

    @Test fun endpoints_preserveUriOffsetPositionAndQueryParameters() = runTest {
        MockWebServer().use { server ->
            val api = OkHttpSpotifyPlayerApi(OkHttpClient(), Tokens(), Connections(), server.url("/v1/"))
            repeat(8) { server.enqueue(MockResponse().setResponseCode(204)) }
            api.startPlayback("phone & speaker", listOf("one", "two"), 1, 42000)
            val start = server.takeRequest()
            assertEquals("PUT", start.method)
            assertEquals("phone & speaker", start.requestUrl?.queryParameter("device_id"))
            val body = Json.parseToJsonElement(start.body.readUtf8()).jsonObject
            assertEquals(listOf("spotify:track:one", "spotify:track:two"), body.getValue("uris").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("1", body.getValue("offset").jsonObject.getValue("position").jsonPrimitive.content)
            assertEquals("42000", body.getValue("position_ms").jsonPrimitive.content)
            api.resume()
            assertEquals("{}", server.takeRequest().body.readUtf8())
            api.pause()
            assertEquals("/v1/me/player/pause", server.takeRequest().path)
            api.seek(123)
            assertEquals("/v1/me/player/seek?position_ms=123", server.takeRequest().path)
            api.setVolume(101)
            assertEquals("/v1/me/player/volume?volume_percent=100", server.takeRequest().path)
            api.setShuffle(false)
            assertEquals("/v1/me/player/shuffle?state=false", server.takeRequest().path)
            api.setRepeat(RepeatMode.ONE)
            assertEquals("/v1/me/player/repeat?state=track", server.takeRequest().path)
            api.setRepeat(RepeatMode.OFF)
            assertEquals("/v1/me/player/repeat?state=off", server.takeRequest().path)
            // Targeted at the just-started device: untargeted calls 404 until Spotify marks it active.
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(204))
            api.setRepeat(RepeatMode.OFF, "dev1")
            assertEquals("/v1/me/player/repeat?state=off&device_id=dev1", server.takeRequest().path)
            api.setVolume(40, "dev1")
            assertEquals("/v1/me/player/volume?volume_percent=40&device_id=dev1", server.takeRequest().path)
        }
    }

    @Test fun devicesAndEmptyState_areParsed() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"devices":[{"id":"phone","name":"Phone","type":"Smartphone","is_active":true,"is_restricted":false}]}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val api = OkHttpSpotifyPlayerApi(OkHttpClient(), Tokens(), Connections(), server.url("/v1/"))
            assertEquals(listOf(SpotifyDevice("phone", "Phone", "Smartphone", true, false)), api.devices())
            assertEquals("/v1/me/player/devices", server.takeRequest().path)
            assertNull(api.playbackState())
        }
    }

    @Test fun tokenFailures_useLegacyMessages() = runTest {
        MockWebServer().use { server ->
            val tokens = Tokens(token = null)
            suspend fun message(linked: Boolean): String? = try {
                OkHttpSpotifyPlayerApi(OkHttpClient(), tokens, Connections(linked), server.url("/v1/")).pause()
                null
            } catch (e: SpotifyCommandException) { e.message }
            assertEquals(SpotifyMessages.NO_ACCOUNT, message(false))
            assertEquals(SpotifyMessages.TOKEN_REFRESH_FAILED, message(true))
            tokens.token = "old"
            tokens.refreshed = null
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(SpotifyMessages.TOKEN_REFRESH_FAILED, message(true))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun responseBodies_areReadOffTheCallersThread() = runTest {
        MockWebServer().use { server ->
            val readers = CopyOnWriteArrayList<Thread>()
            // Records the thread that consumes each body; on Android a Main-thread read throws NetworkOnMainThreadException.
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = requireNotNull(response.body)
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource { readers += Thread.currentThread(); return body.source() }
                }).build()
            }.build()
            server.enqueue(MockResponse().setBody("""{"devices":[]}"""))
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"status":403,"message":"Restricted device"}}"""))
            val api = OkHttpSpotifyPlayerApi(client, Tokens(), Connections(), server.url("/v1/"))
            val caller = Thread.currentThread()
            assertEquals(emptyList<SpotifyDevice>(), api.devices())
            val jsonReads = readers.size
            val failure = try { api.pause(); null } catch (e: SpotifyCommandException) { e }
            assertEquals("Spotify command failed: pause. Restricted device HTTP 403", failure?.message)
            assertTrue("JSON and error bodies were both read", jsonReads > 0 && readers.size > jsonReads)
            assertTrue("Body read on the caller's thread", readers.none { it == caller })
        }
    }
}
