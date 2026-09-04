package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.AudioNormalizationSettings
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking
import org.mockito.kotlin.whenever

/**
 * Characterization tests for [PlaybackQueueManager]'s mode-dispatch behavior (local/Media3,
 * Spotify, and mixed) ahead of the decomposition described in
 * docs/playback-queue-manager-decomposition-plan.md — this is the Stage 0 regression net, not
 * exhaustive coverage of every branch (recovery/auto-advance paths driven by
 * `syncFromPlaybackEngine` are out of reach here since that method is private and driven by
 * the internal polling loop rather than a public entry point).
 *
 * NOTE: reading PlaybackQueueManager.kt with the `Read` tool mangles its content (drops tokens
 * silently); use `sed`/`cat -n` on disk instead, per the decomposition plan's Stage 0 warning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackQueueManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val media3: Media3PlaybackController = mock()
    private val spotify: SpotifyPlaybackController = mock()
    private val stateStore: PlaybackStateStore = mock()
    private val audioCache: AudioCacheManager = mock()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var manager: PlaybackQueueManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        whenever(spotify.getAudioNormalizationSettings()).thenReturn(AudioNormalizationSettings())
        whenever(spotify.normalizeVolumeForSource(any(), any())).thenAnswer { it.arguments[0] }
        wheneverBlocking { spotify.startQueue(any(), any()) } doReturn true
        wheneverBlocking { spotify.play() } doReturn true
        wheneverBlocking { spotify.pause() } doReturn true
        wheneverBlocking { spotify.seekTo(any()) } doReturn true
        wheneverBlocking { spotify.setVolume(any()) } doReturn true
        wheneverBlocking { spotify.setShuffle(any()) } doReturn true
        wheneverBlocking { spotify.setRepeatMode(any()) } doReturn true
        whenever(media3.setQueue(any(), any(), any())).thenReturn(0)
        whenever(media3.snapshot()).thenReturn(
            PlaybackSnapshot(
                state = PlaybackStateType.IDLE,
                positionMs = 0L,
                durationMs = 0L,
                currentMediaIndex = 0,
                volume = 100,
                shuffle = false,
                repeatMode = RepeatMode.OFF,
                shuffledMediaIndices = emptyList()
            )
        )

        manager = PlaybackQueueManager(media3, spotify, stateStore, audioCache, json, mock(), mock())
        // Leave providerRestoreGate uncompleted: init's restore/poll loop parks on
        // providerRestoreGate.await() and never runs syncFromPlaybackEngine() during these
        // tests, so it can't race with the assertions below.
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun localTrack(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = SourceType.JELLYFIN,
        url = "https://example.com/$id",
        durationMs = 200_000L
    )

    private fun spotifyTrack(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = SourceType.SPOTIFY,
        durationMs = 200_000L
    )

    // ---- local (Media3-only) mode ----

    @Test
    fun localMode_setQueue_startsPlayingThroughMedia3() {
        val tracks = listOf(localTrack("a"), localTrack("b"))

        manager.setQueue(tracks)

        verify(media3).setQueue(eq(tracks), eq(0), eq(true))
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
        assertEquals("a", manager.status.value.currentTrack?.id)
    }

    @Test
    fun localMode_pause_callsMedia3AndUpdatesState() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.pause()

        verify(media3).pause()
        assertEquals(PlaybackStateType.PAUSED, manager.status.value.state)
    }

    @Test
    fun localMode_play_callsMedia3AndUpdatesState() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))
        manager.pause()

        manager.play()

        verify(media3).play()
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    @Test
    fun localMode_togglePlayPause_fromPlaying_pauses() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.togglePlayPause()

        verify(media3).togglePlayPause()
        assertEquals(PlaybackStateType.PAUSED, manager.status.value.state)
    }

    @Test
    fun localMode_next_callsMedia3Next() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.next()

        verify(media3).next()
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    @Test
    fun localMode_previous_callsMedia3PreviousOnlyWhenMoved() {
        whenever(media3.previous()).thenReturn(true)
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.previous()

        verify(media3).previous()
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    @Test
    fun localMode_seekTo_callsMedia3AndClampsPosition() {
        manager.setQueue(listOf(localTrack("a")))

        manager.seekTo(50_000L)

        verify(media3).seekTo(50_000L)
        assertEquals(50_000L, manager.status.value.position)
    }

    @Test
    fun localMode_setVolume_appliesNormalizedVolumeThroughMedia3() {
        // applyNormalizedMedia3Volume() hops through the real Dispatchers.IO pool (not the
        // test's Main dispatcher), so the media3 call and the resulting state update race
        // against this test thread — verify with a timeout instead of asserting immediately.
        manager.setQueue(listOf(localTrack("a")))

        manager.setVolume(42)

        verify(media3, timeout(2000)).setVolume(42)
        val deadline = System.currentTimeMillis() + 2000
        while (manager.status.value.volume != 42 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(42, manager.status.value.volume)
    }

    @Test
    fun localMode_setShuffle_callsMedia3() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.setShuffle(true)

        verify(media3).setShuffle(true)
        assertTrue(manager.status.value.shuffle)
    }

    @Test
    fun localMode_setRepeatMode_callsMedia3() {
        manager.setQueue(listOf(localTrack("a")))

        manager.setRepeatMode(RepeatMode.ALL)

        verify(media3).setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, manager.status.value.repeatMode)
    }

    @Test
    fun localMode_playFromIndex_callsMedia3PlayFromIndex() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")), autoPlay = false)

        manager.playFromIndex(1)

        verify(media3).playFromIndex(1)
        assertEquals("b", manager.status.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    // ---- Spotify-only mode ----

    @Test
    fun spotifyMode_setQueue_startsQueueThroughSpotify() {
        val tracks = listOf(spotifyTrack("s1"), spotifyTrack("s2"))

        manager.setQueue(tracks)

        verifyBlocking(spotify) { startQueue(listOf("s1", "s2"), 0) }
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    @Test
    fun spotifyMode_pause_callsSpotifyPause() {
        manager.setQueue(listOf(spotifyTrack("s1"), spotifyTrack("s2")))

        manager.pause()

        verifyBlocking(spotify) { pause() }
        assertEquals(PlaybackStateType.PAUSED, manager.status.value.state)
    }

    @Test
    fun spotifyMode_togglePlayPause_fromPlaying_pauses() {
        manager.setQueue(listOf(spotifyTrack("s1")))

        manager.togglePlayPause()

        verifyBlocking(spotify) { pause() }
        assertEquals(PlaybackStateType.PAUSED, manager.status.value.state)
    }

    @Test
    fun spotifyMode_next_advancesQueueIndex() {
        manager.setQueue(listOf(spotifyTrack("s1"), spotifyTrack("s2")))

        manager.next()

        verifyBlocking(spotify) { startQueue(listOf("s1", "s2"), 1) }
        assertEquals("s2", manager.status.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    @Test
    fun spotifyMode_previous_movesQueueIndexBack() {
        manager.setQueue(listOf(spotifyTrack("s1"), spotifyTrack("s2")), startIndex = 1)

        manager.previous()

        verifyBlocking(spotify) { startQueue(listOf("s1", "s2"), 0) }
        assertEquals("s1", manager.status.value.currentTrack?.id)
    }

    @Test
    fun spotifyMode_seekTo_callsSpotifySeek() {
        manager.setQueue(listOf(spotifyTrack("s1")))

        manager.seekTo(30_000L)

        verifyBlocking(spotify) { seekTo(30_000L) }
        assertEquals(30_000L, manager.status.value.position)
    }

    @Test
    fun spotifyMode_setVolume_callsSpotifySetVolume() {
        manager.setQueue(listOf(spotifyTrack("s1")))

        manager.setVolume(77)

        verifyBlocking(spotify) { setVolume(77) }
        assertEquals(77, manager.status.value.volume)
    }

    @Test
    fun spotifyMode_setShuffle_callsSpotifySetShuffle() {
        manager.setQueue(listOf(spotifyTrack("s1"), spotifyTrack("s2")))

        manager.setShuffle(true)

        verifyBlocking(spotify) { setShuffle(true) }
        assertTrue(manager.status.value.shuffle)
    }

    @Test
    fun spotifyMode_setRepeatMode_callsSpotifySetRepeatMode() {
        manager.setQueue(listOf(spotifyTrack("s1")))

        manager.setRepeatMode(RepeatMode.ONE)

        verifyBlocking(spotify) { setRepeatMode(RepeatMode.ONE) }
        assertEquals(RepeatMode.ONE, manager.status.value.repeatMode)
    }

    @Test
    fun spotifyMode_playFromIndex_startsQueueAtTarget() {
        manager.setQueue(listOf(spotifyTrack("s1"), spotifyTrack("s2")), autoPlay = false)

        manager.playFromIndex(1)

        verifyBlocking(spotify) { startQueue(listOf("s1", "s2"), 1) }
        assertEquals("s2", manager.status.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
    }

    // ---- mixed mode ----

    @Test
    fun mixedMode_setQueue_startsFirstTrackThroughMedia3WhenLocal() {
        val tracks = listOf(localTrack("a"), spotifyTrack("s1"))

        manager.setQueue(tracks)

        verify(media3).setQueue(listOf(tracks[0]), 0, true)
        assertEquals(PlaybackStateType.PLAYING, manager.status.value.state)
        assertEquals("a", manager.status.value.currentTrack?.id)
    }

    @Test
    fun mixedMode_next_movesToNextTrackInOrderedQueueRegardlessOfSource() {
        manager.setQueue(listOf(localTrack("a"), spotifyTrack("s1")))

        manager.next()

        assertEquals("s1", manager.status.value.currentTrack?.id)
        verifyBlocking(spotify) { startQueue(listOf("s1"), 0) }
    }

    @Test
    fun mixedMode_pause_dispatchesByCurrentTrackSource() {
        manager.setQueue(listOf(localTrack("a"), localTrack("b")))

        manager.pause()

        verify(media3).pause()
        assertEquals(PlaybackStateType.PAUSED, manager.status.value.state)
    }

    @Test
    fun mixedMode_seekTo_dispatchesByCurrentTrackSource() {
        manager.setQueue(listOf(localTrack("a"), spotifyTrack("s1")))

        manager.seekTo(10_000L)

        verify(media3).seekTo(10_000L)
        assertEquals(10_000L, manager.status.value.position)
    }

    @Test
    fun mixedMode_setShuffle_updatesOrderedQueueWithoutCallingControllers() {
        manager.setQueue(listOf(localTrack("a"), spotifyTrack("s1")))

        manager.setShuffle(true)

        assertTrue(manager.status.value.shuffle)
        verify(media3, never()).setShuffle(any())
        verifyBlocking(spotify, never()) { setShuffle(any()) }
    }

    @Test
    fun mixedMode_setRepeatMode_fallsThroughToMedia3() {
        manager.setQueue(listOf(localTrack("a"), spotifyTrack("s1")))

        manager.setRepeatMode(RepeatMode.ALL)

        verify(media3).setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, manager.status.value.repeatMode)
    }
}
