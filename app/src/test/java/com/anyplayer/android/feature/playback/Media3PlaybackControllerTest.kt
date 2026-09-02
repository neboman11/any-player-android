package com.anyplayer.android.feature.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.StoredConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * [Media3PlaybackControllerTest] previously covered only its two pure helper
 * functions (buildJellyfinRequestHeaders/isMedia3PlayableTrack) - the controller
 * itself wraps a real ExoPlayer built inline and had no test coverage at all. These
 * tests construct a real (Robolectric-shadowed) ExoPlayer via the controller's normal
 * constructor - see media3-test-utils-robolectric in app/build.gradle.kts - and assert
 * on synchronous, decode-independent state (queue mapping, mediaItemCount,
 * playWhenReady, volume/shuffle/repeatMode property writes, empty-queue guards).
 * Deliberately out of scope: driving the player to READY/PLAYING, which needs real
 * decodable audio fixtures this repo doesn't have - see setQueue tests' comments.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class Media3PlaybackControllerTest {

    private fun newController(): Media3PlaybackController {
        val audioCacheManager: AudioCacheManager = mock()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        whenever(audioCacheManager.buildPlayerDataSourceFactory())
            .thenReturn(DefaultDataSource.Factory(context))
        return Media3PlaybackController(context, audioCacheManager)
    }

    private fun playableTrack(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = SourceType.JELLYFIN,
        url = "https://example.com/$id.mp3",
        durationMs = 200_000L
    )

    private fun spotifyTrack(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = SourceType.SPOTIFY
    )

    // ---- setQueue: filtering + index mapping (all synchronous, no decode needed) ----

    @Test
    fun setQueue_allTracksPlayable_populatesPlayerAndMapsStartIndexDirectly() {
        val controller = newController()
        val tracks = listOf(playableTrack("a"), playableTrack("b"), playableTrack("c"))

        val mappedIndex = controller.setQueue(tracks, startIndex = 1, autoPlay = false)

        assertEquals(1, mappedIndex)
        assertEquals(3, controller.player.mediaItemCount)
        assertEquals(1, controller.player.currentMediaItemIndex)
    }

    @Test
    fun setQueue_spotifyTracksFilteredOut_mapsStartIndexIntoFilteredList() {
        val controller = newController()
        // startIndex=2 points at "b" in the original list; after Spotify tracks are
        // filtered out, "b" is index 1 in the list ExoPlayer actually receives.
        val tracks = listOf(spotifyTrack("s1"), playableTrack("a"), playableTrack("b"))

        val mappedIndex = controller.setQueue(tracks, startIndex = 2, autoPlay = false)

        assertEquals(1, mappedIndex)
        assertEquals(2, controller.player.mediaItemCount)
    }

    @Test
    fun setQueue_noPlayableTracks_returnsNegativeOneAndClearsPlayer() {
        val controller = newController()
        val tracks = listOf(spotifyTrack("s1"), spotifyTrack("s2"))

        val mappedIndex = controller.setQueue(tracks, startIndex = 0, autoPlay = true)

        assertEquals(-1, mappedIndex)
        assertEquals(0, controller.player.mediaItemCount)
    }

    @Test
    fun setQueue_autoPlayFlag_propagatesToPlayWhenReady() {
        val controller = newController()
        val tracks = listOf(playableTrack("a"))

        controller.setQueue(tracks, startIndex = 0, autoPlay = true)
        assertTrue(controller.player.playWhenReady)

        controller.setQueue(tracks, startIndex = 0, autoPlay = false)
        assertFalse(controller.player.playWhenReady)
    }

    // ---- simple property proxies ----

    @Test
    fun setVolume_clampsToValidRangeAndConvertsToPlayerScale() {
        val controller = newController()

        controller.setVolume(50)
        assertEquals(0.5f, controller.player.volume, 0.001f)

        controller.setVolume(150)
        assertEquals(1.0f, controller.player.volume, 0.001f)

        controller.setVolume(-10)
        assertEquals(0.0f, controller.player.volume, 0.001f)
    }

    @Test
    fun setShuffleAndSetRepeatMode_updateRealPlayerState() {
        val controller = newController()

        controller.setShuffle(true)
        controller.setRepeatMode(RepeatMode.ALL)

        assertTrue(controller.player.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, controller.player.repeatMode)
    }

    @Test
    fun snapshot_freshController_reportsIdleWithNoTracks() {
        val controller = newController()

        val snapshot = controller.snapshot()

        assertEquals(PlaybackStateType.IDLE, snapshot.state)
        assertEquals(0, snapshot.durationMs)
    }

    // ---- empty-queue transport guards ----

    @Test
    fun playFromIndexNextAndPrevious_emptyPlayer_noOpSafely() {
        val controller = newController()

        controller.playFromIndex(0)
        controller.next()
        val moved = controller.previous()

        assertFalse(moved)
        assertEquals(0, controller.player.mediaItemCount)
    }

    @Test
    fun previous_singleTrackQueue_returnsFalseWithoutMoving() {
        val controller = newController()
        controller.setQueue(listOf(playableTrack("a")), startIndex = 0, autoPlay = false)

        val moved = controller.previous()

        assertFalse(moved)
        assertEquals(0, controller.player.currentMediaItemIndex)
    }

    private fun track(source: SourceType, url: String?) = Track(
        id = "t1",
        title = "Title",
        artist = "Artist",
        source = source,
        url = url
    )

    @Test
    fun isMedia3PlayableTrack_spotifySource_alwaysFalseRegardlessOfUrl() {
        assertFalse(isMedia3PlayableTrack(track(SourceType.SPOTIFY, "https://example.com/a.mp3")))
    }

    @Test
    fun isMedia3PlayableTrack_blankUrl_false() {
        assertFalse(isMedia3PlayableTrack(track(SourceType.JELLYFIN, "   ")))
        assertFalse(isMedia3PlayableTrack(track(SourceType.JELLYFIN, null)))
    }

    @Test
    fun isMedia3PlayableTrack_unsupportedScheme_false() {
        assertFalse(isMedia3PlayableTrack(track(SourceType.PLEX, "ftp://example.com/a.mp3")))
        assertFalse(isMedia3PlayableTrack(track(SourceType.PLEX, "not a uri at all")))
    }

    @Test
    fun isMedia3PlayableTrack_supportedSchemes_true() {
        listOf("http", "https", "file", "content", "android.resource").forEach { scheme ->
            assertTrue(
                "expected $scheme:// to be playable",
                isMedia3PlayableTrack(track(SourceType.JELLYFIN, "$scheme://example.com/a.mp3"))
            )
        }
    }

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
