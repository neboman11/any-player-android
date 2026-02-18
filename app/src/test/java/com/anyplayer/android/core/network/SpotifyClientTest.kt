package com.anyplayer.android.core.network

import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyClientTest {
    @Test
    fun authUrl_containsExpectedParameters() {
        val client = SpotifyClient()
        val url = client.authUrl(
            clientId = "client-123",
            redirectUri = "anyplayer://spotify-callback"
        )

        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("redirect_uri=anyplayer://spotify-callback"))
    }
}
