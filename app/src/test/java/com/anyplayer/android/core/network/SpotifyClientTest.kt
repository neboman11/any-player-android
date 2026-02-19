package com.anyplayer.android.core.network

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class SpotifyClientTest {
    @Test
    fun createPkceSession_containsExpectedParameters() {
        val client = SpotifyClient(
            okHttpClient = OkHttpClient(),
            json = Json
        )
        val session = client.createPkceSession(
            clientId = "client-123",
            redirectUri = "anyplayer://spotify-callback"
        )
        val url = session.authorizationUrl

        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("redirect_uri=anyplayer%3A%2F%2Fspotify-callback"))
    }
}
