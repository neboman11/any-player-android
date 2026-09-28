package com.anyplayer.android.feature.playback

import android.content.Context
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.feature.auth.ProviderAuthRepository
import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState
import com.anyplayer.android.feature.auth.spotify.SpotifyPlayerClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpotifyConnectBridgeLifecycleTest {
    /** The playback controller that reads [SpotifyConnectBridge.snapshot] is a process-wide
     *  singleton, so the poll loop must not depend on the media service being alive: Android
     *  stops an idle background service, and a dead loop made every snapshot null, which the
     *  mixed-mode sync treated as a disconnect and "recovered" by restarting the song. */
    @Test
    fun `polls Spotify without any service attaching it`() = runBlocking {
        val playing = SpotifyPlaybackState(
            isPlaying = true,
            progressMs = 30_000,
            durationMs = 180_000,
            volumePercent = 100,
            shuffleEnabled = false,
            repeatMode = RepeatMode.OFF,
            currentTrackId = "track"
        )
        val client = mock<SpotifyPlayerClient>().also { whenever(it.getPlaybackState(any())).thenReturn(playing) }
        val auth = mock<ProviderAuthRepository>().also { whenever(it.refreshSpotifyTokenIfNeeded()).thenReturn("token") }
        val bridge = SpotifyConnectBridge(client, auth, mock<Context>())

        val snapshot = withTimeoutOrNull(5_000) {
            var current = bridge.snapshot()
            while (current == null) {
                delay(50)
                current = bridge.snapshot()
            }
            current
        }

        assertEquals("track", snapshot?.currentTrackId)
    }
}
