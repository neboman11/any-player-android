package com.anyplayer.android.feature.playback

import android.net.Uri
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.auth.StoredConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Media3PlaybackControllerTest {

    @Test
    fun buildJellyfinRequestHeaders_returnsAuthorizationForMatchingHost() {
        val headers = buildJellyfinRequestHeaders(
            requestUri = Uri.parse("https://jellyfin.example.com/Audio/track-1/universal"),
            connection = StoredConnection(
                source = SourceType.JELLYFIN,
                serverUrl = "https://jellyfin.example.com",
                token = "token-123"
            ),
            clientName = "Any Player",
            deviceName = "Pixel",
            deviceId = "device-1",
            version = "1.0.0"
        )

        assertEquals("token-123", headers["X-Emby-Token"])
        assertEquals(
            "MediaBrowser Token=\"token-123\", Client=\"Any Player\", Device=\"Pixel\", DeviceId=\"device-1\", Version=\"1.0.0\"",
            headers["Authorization"]
        )
    }

    @Test
    fun buildJellyfinRequestHeaders_returnsEmptyForDifferentHost() {
        val headers = buildJellyfinRequestHeaders(
            requestUri = Uri.parse("https://cdn.example.com/Audio/track-1/universal"),
            connection = StoredConnection(
                source = SourceType.JELLYFIN,
                serverUrl = "https://jellyfin.example.com",
                token = "token-123"
            ),
            clientName = "Any Player",
            deviceName = "Pixel",
            deviceId = "device-1",
            version = "1.0.0"
        )

        assertTrue(headers.isEmpty())
    }

    @Test
    fun buildJellyfinRequestHeaders_returnsEmptyForDifferentScheme() {
        val headers = buildJellyfinRequestHeaders(
            requestUri = Uri.parse("http://jellyfin.example.com/Audio/track-1/universal"),
            connection = StoredConnection(
                source = SourceType.JELLYFIN,
                serverUrl = "https://jellyfin.example.com",
                token = "token-123"
            ),
            clientName = "Any Player",
            deviceName = "Pixel",
            deviceId = "device-1",
            version = "1.0.0"
        )

        assertTrue(headers.isEmpty())
    }
}
