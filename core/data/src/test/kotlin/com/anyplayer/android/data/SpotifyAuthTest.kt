package com.anyplayer.android.data

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.providers.SpotifyClient
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class SpotifyAuthTest {
    private val http = OkHttpClient()
    private val now = 1_000_000L
    private fun conn(expiry: Long? = now + 100_000, refresh: String? = "refresh") = StoredConnection(SourceType.SPOTIFY,
        token = "old", refreshToken = refresh, tokenExpiresAt = expiry, spotifyPremium = true, playbackReady = true)

    @Test fun concurrentCallersShareRefreshAndPersistRotation() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"rotated","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("""{"id":"user","product":"premium"}"""))
            val store = FakeConnections(conn())
            val tokens = DefaultSpotifyTokenProvider(store, SpotifyAccounts(http, server.url("/token").toString(), server.url("/me").toString()), "client") { now }
            assertEquals(List(20) { "new" }, (1..20).map { async { tokens.accessToken() } }.awaitAll())
            assertEquals(2, server.requestCount)
            val saved = store.connections.value.getValue(SourceType.SPOTIFY)
            assertEquals("rotated", saved.refreshToken)
            assertEquals(now + 3_600_000, saved.tokenExpiresAt)
            assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
        }
    }
    @Test fun unknownExpiryAndUnexpiredTokenDoNotRefreshBut401ForcesIt() = runTest {
        MockWebServer().use { server ->
            val store = FakeConnections(conn(expiry = null))
            val tokens = DefaultSpotifyTokenProvider(store, SpotifyAccounts(http, server.url("/token").toString(), server.url("/me").toString()), "client") { now }
            assertEquals("old", tokens.accessToken())
            assertEquals(0, server.requestCount)
            store.update(SourceType.SPOTIFY) { conn(now + 3_600_000) }
            assertEquals("old", tokens.accessToken())
            server.enqueue(MockResponse().setBody("""{"access_token":"forced","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("""{"id":"user","product":"premium"}"""))
            assertEquals("forced", tokens.refreshAfterUnauthorized())
            assertEquals("refresh", store.connections.value.getValue(SourceType.SPOTIFY).refreshToken)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun missingRefreshTokenReturnsOnlyStillValidToken() = runTest {
        val store = FakeConnections(conn(refresh = null))
        val tokens = DefaultSpotifyTokenProvider(store, SpotifyAccounts(http, "http://localhost/token"), "client") { now }
        assertEquals("old", tokens.accessToken())
        store.update(SourceType.SPOTIFY) { conn(now, null) }
        assertNull(tokens.accessToken())
    }
    @Test fun transientRefreshFailureKeepsConnectionAndValidToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val store = FakeConnections(conn())
            val tokens = DefaultSpotifyTokenProvider(store, SpotifyAccounts(http, server.url("/token").toString()), "client") { now }
            assertEquals("old", tokens.accessToken())
            assertEquals(true, store.connections.value.getValue(SourceType.SPOTIFY).playbackReady)
        }
    }
    @Test fun concurrentUnauthorizedRefreshFailureNeverReturnsRejectedToken() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val store = FakeConnections(conn(now + 3_600_000))
            val tokens = DefaultSpotifyTokenProvider(store, SpotifyAccounts(http, server.url("/token").toString()), "client") { now }
            assertEquals(List<String?>(20) { null }, (1..20).map { async { tokens.refreshAfterUnauthorized() } }.awaitAll())
            assertEquals(1, server.requestCount)
            assertEquals(true, store.connections.value.getValue(SourceType.SPOTIFY).playbackReady)
        }
    }

    @Test fun refreshedTokenValidation401DisconnectsButServerFailureDoesNot() = runTest {
        MockWebServer().use { server ->
            val store = FakeConnections(conn())
            val accounts = SpotifyAccounts(http, server.url("/token").toString(), server.url("/me").toString())
            val tokens = DefaultSpotifyTokenProvider(store, accounts, "client") { now }
            server.enqueue(MockResponse().setBody("""{"access_token":"new","expires_in":3600}"""))
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals("new", tokens.accessToken())
            assertEquals(false, store.connections.value.getValue(SourceType.SPOTIFY).playbackReady)
            store.update(SourceType.SPOTIFY) { conn() }
            server.enqueue(MockResponse().setBody("""{"access_token":"new2","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("invalid-json"))
            assertEquals("new2", tokens.accessToken())
            assertEquals(true, store.connections.value.getValue(SourceType.SPOTIFY).playbackReady)
        }
    }
    private fun flow(sessions: FakeSessions, server: MockWebServer, store: FakeConnections = FakeConnections()) = SpotifyAuthFlow(
        sessions, SpotifyAccounts(http, server.url("/token").toString()),
        SpotifyClient(http, FakeTokens(), server.url("/v1/").toString()), store, "client")
    @Test fun completeAuthPreservesExactErrorOrderingAndSessionClearing() = runTest {
        MockWebServer().use { server ->
            val sessions = FakeSessions()
            val flow = flow(sessions, server)
            suspend fun failure(callback: String, expected: String, clears: Int) {
                try { flow.completeAuth(callback); fail("Expected failure") }
                catch (e: IllegalStateException) { assertEquals(expected, e.message) }
                assertEquals(clears, sessions.clears)
            }
            failure("bad uri", "No pending Spotify auth session. Start linking again.", 0)
            val pending = PendingSpotifyAuthSession("expected", "verifier", "client", SpotifyAuthFlow.REDIRECT_URI)
            sessions.pending = pending
            failure("bad uri", "Invalid Spotify callback URI", 0)
            failure("anyplayer://spotify-callback?error=denied&state=wrong", "Spotify authorization failed: denied", 1)
            sessions.pending = pending
            failure("anyplayer://spotify-callback?code=code", "Spotify auth state mismatch. Start linking again.", 2)
            sessions.pending = pending
            failure("anyplayer://spotify-callback?state=expected", "Spotify callback did not include an authorization code", 3)
            sessions.pending = pending
            server.enqueue(MockResponse().setResponseCode(400))
            failure("anyplayer://spotify-callback?state=expected&code=code", "Unable to exchange Spotify authorization code", 4)
        }
    }
    @Test fun beginCreatesValidPkceWithoutStreamingAndNonPremiumCompletes() = runTest {
        MockWebServer().use { server ->
            val sessions = FakeSessions()
            val store = FakeConnections()
            val flow = flow(sessions, server, store)
            val url = flow.beginAuth().toHttpUrl()
            assertEquals(64, sessions.pending!!.codeVerifier.length)
            assertEquals(24, sessions.pending!!.state.length)
            assertFalse(url.queryParameter("scope")!!.split(' ').contains("streaming"))
            assertEquals("S256", url.queryParameter("code_challenge_method"))
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("""{"id":"user","product":"free"}"""))
            val connection = flow.completeAuth("anyplayer://spotify-callback?state=${sessions.pending!!.state}&code=code")
            assertEquals(false, connection.spotifyPremium)
            assertEquals("access", store.connections.value.getValue(SourceType.SPOTIFY).token)
            assertNull(sessions.pending)
        }
    }
}
