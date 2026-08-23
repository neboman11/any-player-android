package com.anyplayer.android.feature.auth.spotify

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyClientGetPlaybackStateTest {
    @Test
    fun getPlaybackState_parsesFullResponse() {
        val client = clientReturning(
            code = 200,
            body = """
                {
                  "is_playing": true,
                  "progress_ms": 45000,
                  "shuffle_state": true,
                  "repeat_state": "track",
                  "device": {"volume_percent": 60},
                  "item": {"id": "abc123", "duration_ms": 180000}
                }
            """.trimIndent()
        )

        val state = client.getPlaybackState("token")

        assertEquals(true, state?.isPlaying)
        assertEquals(45000L, state?.progressMs)
        assertEquals(180000L, state?.durationMs)
        assertEquals(60, state?.volumePercent)
        assertTrue(state?.shuffleEnabled == true)
        assertEquals("abc123", state?.currentTrackId)
    }

    @Test
    fun getPlaybackState_noActiveDevice_returns204AndNull() {
        val client = clientReturning(code = 204, body = "")

        assertNull(client.getPlaybackState("token"))
    }

    @Test
    fun getPlaybackState_unsuccessfulResponse_returnsNull() {
        val client = clientReturning(code = 401, body = "")

        assertNull(client.getPlaybackState("token"))
    }

    @Test
    fun getPlaybackState_missingFields_fallsBackToDefaults() {
        val client = clientReturning(
            code = 200,
            body = """{"is_playing": false, "item": {}}"""
        )

        val state = client.getPlaybackState("token")

        assertEquals(false, state?.isPlaying)
        assertEquals(0L, state?.progressMs)
        assertEquals(0L, state?.durationMs)
        assertEquals(100, state?.volumePercent)
        assertNull(state?.currentTrackId)
    }

    @Test
    fun getAvailableDeviceId_skipsRestrictedDevices() {
        val client = clientReturning(
            code = 200,
            body = """
                {
                  "devices": [
                    {"id": "restricted-active", "is_active": true, "is_restricted": true},
                    {"id": "available", "is_active": false, "is_restricted": false}
                  ]
                }
            """.trimIndent()
        )

        assertEquals("available", client.getAvailableDeviceId("token"))
    }

    private fun clientReturning(code: Int, body: String): SpotifyPlayerClient {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody(null))
                        .build()
                }
            )
            .build()
        return SpotifyPlayerClient(spotifyApiExecutor = SpotifyApiExecutor(okHttpClient = okHttpClient, json = Json))
    }
}
