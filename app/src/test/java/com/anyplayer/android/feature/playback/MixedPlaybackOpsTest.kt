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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

/**
 * Regression coverage for [MixedPlaybackOps.sync]'s two stall-recovery paths - the
 * Spotify-track dwell check mirrored from [SpotifyPlaybackOpsTest] (finding #5, PR 33
 * review) and the pre-existing Media3 end-of-track stall detector, which had no test
 * coverage at all despite being flagged (finding #10) as the highest-regression-risk
 * code in the decomposition - plus the normalized-id lookup [MixedPlaybackOps] uses
 * for next()/previous() (finding #12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MixedPlaybackOpsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val media3: Media3PlaybackController = mock()
    private val spotify: SpotifyPlaybackController = mock()
    private val audioCache: AudioCacheManager = mock()
    private val spotifyOps: SpotifyPlaybackOps = mock()
    private lateinit var context: PlaybackEngineContext
    private lateinit var ops: MixedPlaybackOps

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = PlaybackEngineContext(spotify)
        context.mixedMode = true
        ops = MixedPlaybackOps(
            media3PlaybackController = media3,
            spotifyPlaybackController = spotify,
            audioCacheManager = audioCache,
            spotifyOps = spotifyOps,
            context = context,
            isNearTrackEnd = { _, _, _ -> false },
            applyNormalizedMedia3Volume = { _, _ -> },
            persistStateAsync = {},
            djFillerScheduler = mock(),
            djInterstitialPlayer = mock()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun track(id: String, source: SourceType) = Track(
        id = id,
        title = id,
        artist = "Artist",
        source = source,
        url = if (source == SourceType.SPOTIFY) null else "https://example.com/$id",
        durationMs = 200_000L
    )

    private fun seedQueue(tracks: List<Track>, currentIndex: Int, state: PlaybackStateType) {
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = tracks[currentIndex],
            state = state,
            position = 5_000L,
            duration = 200_000L
        )
    }

    private fun spotifySnapshot(trackId: String, playing: Boolean, positionMs: Long = 5_000L) = SpotifyPlaybackState(
        isPlaying = playing,
        progressMs = positionMs,
        durationMs = 200_000L,
        endOfTrackCount = 0,
        volumePercent = 100,
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF,
        currentTrackId = trackId
    )

    private fun media3Snapshot(positionMs: Long, state: PlaybackStateType = PlaybackStateType.PLAYING) = PlaybackSnapshot(
        state = state,
        positionMs = positionMs,
        durationMs = 200_000L,
        currentMediaIndex = 0,
        volume = 100,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        shuffledMediaIndices = emptyList()
    )

    // ---- Spotify-track dwell check ----

    @Test
    fun sync_spotifyTrackSinglePollPause_doesNotForceRestart() = runTest {
        val tracks = listOf(track("s1", SourceType.SPOTIFY), track("local1", SourceType.JELLYFIN))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { spotify.snapshot() } doReturn spotifySnapshot("s1", playing = false)

        ops.sync()

        verifyBlocking(spotifyOps, never()) { maybeRecoverSpotifyTrack(any(), any(), any()) }
        assertEquals("s1", context.recovery.spotifyMidTrackStallTrackId)
    }

    @Test
    fun sync_spotifyTrackStallPersistsPastThreshold_recoversViaMaybeRecover() = runTest {
        val tracks = listOf(track("s1", SourceType.SPOTIFY), track("local1", SourceType.JELLYFIN))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { spotify.snapshot() } doReturn spotifySnapshot("s1", playing = false)

        ops.sync()
        context.recovery.spotifyMidTrackStallSinceMs -= (SpotifyConnectBridge.POLL_INTERVAL_MS * 3 + 1)
        ops.sync()

        verifyBlocking(spotifyOps) { maybeRecoverSpotifyTrack(eq(listOf("s1")), eq(0), any()) }
        assertNull(context.recovery.spotifyMidTrackStallTrackId)
    }

    @Test
    fun sync_spotifySnapshotUnavailableWhilePlaying_attemptsRecovery() = runTest {
        val tracks = listOf(track("s1", SourceType.SPOTIFY))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { spotify.snapshot() } doReturn null

        ops.sync()

        verifyBlocking(spotifyOps) { maybeRecoverSpotifyTrack(eq(listOf("s1")), eq(0), any()) }
    }

    // ---- Media3 (local-track) end-of-track stall check ----

    @Test
    fun sync_media3NearEndStalledPosition_singlePollDoesNotAdvance() = runTest {
        val opsWithNearEnd = MixedPlaybackOps(
            media3PlaybackController = media3,
            spotifyPlaybackController = spotify,
            audioCacheManager = audioCache,
            spotifyOps = spotifyOps,
            context = context,
            isNearTrackEnd = { _, _, _ -> true },
            applyNormalizedMedia3Volume = { _, _ -> },
            persistStateAsync = {},
            djFillerScheduler = mock(),
            djInterstitialPlayer = mock()
        )
        val tracks = listOf(track("local1", SourceType.JELLYFIN), track("local2", SourceType.JELLYFIN))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { media3.snapshot() } doReturn media3Snapshot(positionMs = 198_500L)

        opsWithNearEnd.sync()

        assertEquals("local1", context.mutableStatus.value.currentTrack?.id)
        assertEquals("local1", context.recovery.mixedMediaEndStallTrackId)
    }

    @Test
    fun sync_media3NearEndStalledPositionPersists_advancesToNextTrack() = runTest {
        val opsWithNearEnd = MixedPlaybackOps(
            media3PlaybackController = media3,
            spotifyPlaybackController = spotify,
            audioCacheManager = audioCache,
            spotifyOps = spotifyOps,
            context = context,
            isNearTrackEnd = { _, _, _ -> true },
            applyNormalizedMedia3Volume = { _, _ -> },
            persistStateAsync = {},
            djFillerScheduler = mock(),
            djInterstitialPlayer = mock()
        )
        val tracks = listOf(track("local1", SourceType.JELLYFIN), track("local2", SourceType.JELLYFIN))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { media3.snapshot() } doReturn media3Snapshot(positionMs = 198_500L)
        wheneverBlocking { media3.setQueue(any(), any(), any()) } doReturn 0

        opsWithNearEnd.sync()
        context.recovery.mixedMediaEndStallSinceMs -= 1_801L
        opsWithNearEnd.sync()

        assertEquals("local2", context.mutableStatus.value.currentTrack?.id)
    }

    @Test
    fun sync_media3ReachedEndAndTransitionedOutOfPlaying_advancesImmediately() = runTest {
        val tracks = listOf(track("local1", SourceType.JELLYFIN), track("local2", SourceType.JELLYFIN))
        seedQueue(tracks, currentIndex = 0, state = PlaybackStateType.PLAYING)
        wheneverBlocking { media3.snapshot() } doReturn media3Snapshot(
            positionMs = 199_500L,
            state = PlaybackStateType.PAUSED
        )
        wheneverBlocking { media3.setQueue(any(), any(), any()) } doReturn 0

        ops.sync()

        assertEquals("local2", context.mutableStatus.value.currentTrack?.id)
    }

    // ---- normalized-id lookup ----

    @Test
    fun next_currentTrackIdInAlternateSpotifyUriFormat_stillAdvancesToCorrectTrack() {
        // Queue stores the bare id; currentTrack carries the "spotify:track:" URI
        // form - trackIdsMatch()/sequenceIndexOf() must normalize both sides.
        val tracks = listOf(
            track("abc123", SourceType.SPOTIFY),
            track("local1", SourceType.JELLYFIN),
            track("def456", SourceType.SPOTIFY)
        )
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = track("spotify:track:abc123", SourceType.SPOTIFY),
            state = PlaybackStateType.PLAYING
        )
        wheneverBlocking { spotify.startQueue(any(), any()) } doReturn true

        ops.next(context.mutableStatus.value)

        assertEquals("local1", context.mutableStatus.value.currentTrack?.id)
    }

    // ---- setQueue dispatch ----

    @Test
    fun setQueue_autoPlayFirstTrackLocal_startsMedia3AndClearsSpotifyRecoveryState() {
        context.recovery.spotifyRecoveryAttempts = 2
        val tracks = listOf(track("local1", SourceType.JELLYFIN), track("s1", SourceType.SPOTIFY))
        wheneverBlocking { media3.setQueue(any(), any(), any()) } doReturn 0

        ops.setQueue(tracks, startIndex = 0, autoPlay = true)

        assertEquals("local1", context.mutableStatus.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, context.mutableStatus.value.state)
        assertEquals(0, context.recovery.spotifyRecoveryAttempts)
    }
}
