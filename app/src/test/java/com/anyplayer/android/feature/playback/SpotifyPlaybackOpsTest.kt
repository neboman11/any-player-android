package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.auth.spotify.SpotifyPlaybackState
import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

/**
 * Regression coverage for [SpotifyPlaybackOps.sync]'s mid-track-stall recovery
 * (finding #4 of the PR #33 review): a single poll reporting the track as paused
 * mid-playback must not force a restart (that reintroduces the spurious-restart
 * bug fixed 3 times before), but a stall that persists past
 * [SpotifyPlaybackOps.Companion] MID_TRACK_STALL_THRESHOLD_MS must recover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPlaybackOpsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val spotify: SpotifyPlaybackController = mock()
    private val media3: Media3PlaybackController = mock()
    private val audioCache: AudioCacheManager = mock()
    private lateinit var context: PlaybackEngineContext
    private lateinit var ops: SpotifyPlaybackOps

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = PlaybackEngineContext(spotify)
        context.spotifyMode = true
        wheneverBlocking { spotify.startQueue(any(), any()) } doReturn true
        wheneverBlocking { spotify.setVolume(any()) } doReturn true
        ops = SpotifyPlaybackOps(
            media3PlaybackController = media3,
            spotifyPlaybackController = spotify,
            audioCacheManager = audioCache,
            context = context,
            isNearTrackEnd = { _, _, _ -> false },
            persistStateAsync = {},
            djFillerScheduler = mock(),
            djInterstitialPlayer = mock()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = SourceType.SPOTIFY,
        durationMs = 200_000L
    )

    private fun seedPlayingState(tracks: List<Track>) {
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = tracks.first(),
            state = PlaybackStateType.PLAYING,
            position = 5_000L,
            duration = 200_000L
        )
    }

    private fun stalledSnapshot(trackId: String, playing: Boolean = false) = SpotifyPlaybackState(
        isPlaying = playing,
        progressMs = 5_000L,
        durationMs = 200_000L,
        endOfTrackCount = 0,
        volumePercent = 100,
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF,
        currentTrackId = trackId
    )

    @Test
    fun sync_singlePollMidTrackPause_doesNotForceRestart() = runTest {
        // Covers the scenario the removed force-restart used to misfire on: a
        // single stalled poll alone (e.g. right after a track starts, before the
        // bridge's next poll catches up) must never be enough to recover.
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        wheneverBlocking { spotify.snapshot() } doReturn stalledSnapshot("a")

        ops.sync()

        verifyBlocking(spotify, never()) { startQueue(any(), any()) }
        assertEquals("a", context.recovery.spotifyMidTrackStallTrackId)
        assertEquals(5_000L, context.recovery.spotifyMidTrackStallPositionMs)
    }

    @Test
    fun sync_stallPersistsPastThreshold_recoversViaStartQueue() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        wheneverBlocking { spotify.snapshot() } doReturn stalledSnapshot("a")

        ops.sync() // seeds the dwell timer at "now"
        // Simulate the dwell threshold having elapsed without a real delay.
        context.recovery.spotifyMidTrackStallSinceMs -= (SpotifyConnectBridge.POLL_INTERVAL_MS * 3 + 1)

        ops.sync()

        verifyBlocking(spotify) { startQueue(tracks.map { it.id }, 0) }
        assertNull(context.recovery.spotifyMidTrackStallTrackId)
    }

    @Test
    fun sync_playbackResumes_resetsDwellTimer() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        wheneverBlocking { spotify.snapshot() } doReturn stalledSnapshot("a")
        ops.sync()
        assertEquals("a", context.recovery.spotifyMidTrackStallTrackId)

        wheneverBlocking { spotify.snapshot() } doReturn stalledSnapshot("a", playing = true)
        ops.sync()

        assertNull(context.recovery.spotifyMidTrackStallTrackId)

        // Even after the threshold elapses, no recovery should fire - the dwell
        // window was reset by the resumed playback, not just carried forward.
        context.recovery.spotifyMidTrackStallSinceMs -= (SpotifyConnectBridge.POLL_INTERVAL_MS * 3 + 1)
        ops.sync()

        verifyBlocking(spotify, never()) { startQueue(any(), any()) }
    }

    @Test
    fun sync_endOfTrackDetected_advancesQueueCursorToNextTrack() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        // Both the outer endOfTrack check and awaitSpotifyAdvance's poll read the same
        // snapshot - reporting "b" as already current lets the advance resolve on its
        // very first check, with no real delay() needed to keep this deterministic.
        wheneverBlocking { spotify.snapshot() } doReturn SpotifyPlaybackState(
            isPlaying = true,
            progressMs = 0L,
            durationMs = 200_000L,
            endOfTrackCount = 1,
            volumePercent = 100,
            shuffleEnabled = false,
            repeatMode = RepeatMode.OFF,
            currentTrackId = "b"
        )
        wheneverBlocking { spotify.next() } doReturn true

        ops.sync()

        assertEquals(1, context.queueIndexCache.spotifyCurrentQueueIndex)
        assertEquals(1L, context.recovery.lastAcknowledgedEndOfTrackCount)
    }

    @Test
    fun sync_errorState_attemptsRecoveryAtCurrentQueueIndex() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.ERROR)
        wheneverBlocking { spotify.snapshot() } doReturn stalledSnapshot("a", playing = false)

        ops.sync()

        verifyBlocking(spotify) { startQueue(tracks.map { it.id }, 0) }
    }

    @Test
    fun maybeRecoverSpotifyTrack_exhaustsAfterThreeFailedAttempts_thenStopsRetrying() = runTest {
        wheneverBlocking { spotify.startQueue(any(), any()) } doReturn false

        repeat(3) {
            val triggered = ops.maybeRecoverSpotifyTrack(listOf("a"), 0, "failed")
            assertEquals(true, triggered)
            // Bypass the real cooldown window so each successive call in this loop
            // isn't itself suppressed by the just-triggered attempt's cooldown.
            context.recovery.spotifyRecoveryLastAttemptMs = 0L
        }

        val fourthAttempt = ops.maybeRecoverSpotifyTrack(listOf("a"), 0, "failed")

        assertEquals(false, fourthAttempt)
        verifyBlocking(spotify, org.mockito.kotlin.times(3)) { startQueue(any(), any()) }
    }

    @Test
    fun next_alreadyAtLastTrack_noOpsWithoutCallingStartQueue() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        context.queueIndexCache.spotifyCurrentQueueIndex = 1
        context.mutableStatus.value = context.mutableStatus.value.copy(currentTrack = tracks[1])

        ops.next(context.mutableStatus.value)

        verifyBlocking(spotify, never()) { startQueue(any(), any()) }
    }

    @Test
    fun previous_alreadyAtFirstTrack_noOpsWithoutCallingStartQueue() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedPlayingState(tracks)
        context.queueIndexCache.spotifyCurrentQueueIndex = 0

        ops.previous(context.mutableStatus.value)

        verifyBlocking(spotify, never()) { startQueue(any(), any()) }
    }
}
