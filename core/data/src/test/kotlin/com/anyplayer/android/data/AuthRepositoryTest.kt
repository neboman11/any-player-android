package com.anyplayer.android.data

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.data.auth.*
import com.anyplayer.android.data.providers.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AuthRepositoryTest {
    private fun repository(store: FakeConnections, server: MockWebServer): DefaultAuthRepository {
        val http = OkHttpClient()
        val tokens = FakeTokens()
        val spotify = SpotifyClient(http, tokens, server.url("/v1/").toString())
        val flow = SpotifyAuthFlow(FakeSessions(), SpotifyAccounts(http, server.url("/token").toString()), spotify, store, "client")
        return DefaultAuthRepository(store, ProviderRegistry(JellyfinClient(http, store), PlexClient(http), spotify), flow, tokens)
    }
    @Test fun restoreMakesNoNetworkCallsAndRetainsLegacyProviderProfiles() = runTest {
        MockWebServer().use { server ->
            val store = FakeConnections(StoredConnection(SourceType.JELLYFIN, token = "key", playbackReady = false),
                StoredConnection(SourceType.PLEX, token = "key"),
                StoredConnection(SourceType.SPOTIFY, token = "spotify", spotifyPremium = true, playbackReady = true))
            val auth = repository(store, server)
            val restored = auth.restore().associateBy { it.source }
            assertTrue(restored.getValue(SourceType.JELLYFIN).connected)
            assertTrue(restored.getValue(SourceType.PLEX).connected)
            assertTrue(restored.getValue(SourceType.SPOTIFY).connected)
            assertEquals(0, server.requestCount)
            assertTrue(auth.isSourceConnected(null))
            assertTrue(auth.isSourceConnected(SourceType.CUSTOM))
            assertTrue(auth.isSourceConnected(SourceType.ALL))
            assertTrue(auth.updatePageSize(SourceType.JELLYFIN, 2000))
            assertEquals(1000, store.connections.value.getValue(SourceType.JELLYFIN).playlistPageSize)
            assertFalse(auth.updatePageSize(SourceType.SPOTIFY, 10))
            auth.disconnect(SourceType.PLEX)
            assertFalse(store.connections.value.containsKey(SourceType.PLEX))
        }
    }
    @Test fun premiumAndFallbackStatusStringsRemainExact() = runTest {
        MockWebServer().use { server ->
            val store = FakeConnections(StoredConnection(SourceType.SPOTIFY, token = "token", spotifyPremium = false, playbackReady = true))
            val auth = repository(store, server)
            val free = auth.restore().single()
            assertFalse(free.connected)
            assertEquals("Spotify account is not Premium. Provider is ignored.", free.lastError)
            store.update(SourceType.SPOTIFY) { it!!.copy(spotifyPremium = true, playbackReady = false) }
            val fallback = auth.restore().single()
            assertFalse(fallback.connected)
            assertEquals("Spotify direct playback may be unavailable; app-control fallback is active.", fallback.lastError)
        }
    }
    @Test fun connectValidationAndPageSizeClampingPersistSelectedJellyfinUser() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            server.enqueue(MockResponse().setBody("""[{"Id":"uid","Name":"Listener"}]"""))
            val store = FakeConnections()
            val auth = repository(store, server)
            val profile = auth.connectJellyfin(" ${server.url("/")} ", " secret ", 0)
            assertEquals("Listener", profile.username)
            assertTrue(profile.connected)
            val saved = store.connections.value.getValue(SourceType.JELLYFIN)
            assertEquals("uid", saved.jellyfinUserId)
            assertEquals(1, saved.playlistPageSize)
            assertEquals("secret", saved.token)
            assertFalse(saved.serverUrl!!.endsWith('/'))
        }
    }
}
