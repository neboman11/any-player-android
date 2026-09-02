package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Regression coverage for [LocalPlaybackOps], previously exercised only indirectly
 * through [PlaybackQueueManagerTest]. Focuses on [LocalPlaybackOps.sync]'s Media3
 * fatal-error retry state machine (untested despite gating every transport control
 * once ExoPlayer hits a fatal player error) and the [LocalPlaybackOps.setQueue]
 * branch extracted from [PlaybackQueueManager] this session (finding #9).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalPlaybackOpsTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val media3: Media3PlaybackController = mock()
    private val spotify: SpotifyPlaybackController = mock()
    private lateinit var context: PlaybackEngineContext
    private lateinit var ops: LocalPlaybackOps

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = PlaybackEngineContext(spotify)
        ops = LocalPlaybackOps(
            media3PlaybackController = media3,
            context = context,
            applyNormalizedMedia3Volume = { _, _ -> },
            triggerPrefetch = {},
            persistStateAsync = {}
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
        source = SourceType.JELLYFIN,
        url = "https://example.com/$id",
        durationMs = 200_000L
    )

    private fun seedQueue(tracks: List<Track>) {
        context.playableQueueIndices = tracks.indices.toList()
        context.queueIndexCache.rebuildQueueCaches(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(
            queue = tracks,
            orderedQueue = tracks,
            currentTrack = tracks.first(),
            state = PlaybackStateType.PLAYING
        )
    }

    private fun errorSnapshot() = PlaybackSnapshot(
        state = PlaybackStateType.ERROR,
        positionMs = 1_000L,
        durationMs = 200_000L,
        currentMediaIndex = 0,
        volume = 100,
        shuffle = false,
        repeatMode = RepeatMode.OFF,
        shuffledMediaIndices = emptyList()
    )

    // ---- setQueue ----

    @Test
    fun setQueue_media3MappedIndexNegative_setsErrorStateAndClearsCurrentTrack() {
        val tracks = listOf(track("a"), track("b"))
        whenever(media3.setQueue(any(), any(), any())).thenReturn(-1)

        ops.setQueue(tracks, startIndex = 0, autoPlay = true)

        assertEquals(PlaybackStateType.ERROR, context.mutableStatus.value.state)
        assertNull(context.mutableStatus.value.currentTrack)
    }

    @Test
    fun setQueue_autoPlaySuccess_selectsMappedTrackAndPlays() {
        val tracks = listOf(track("a"), track("b"))
        whenever(media3.setQueue(any(), any(), any())).thenReturn(1)

        ops.setQueue(tracks, startIndex = 1, autoPlay = true)

        assertEquals("b", context.mutableStatus.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, context.mutableStatus.value.state)
    }

    // ---- Media3 fatal-error retry ----

    @Test
    fun sync_media3ErrorPersists_retriesThreeTimesThenSkipsTrack() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedQueue(tracks)
        whenever(media3.snapshot()).thenReturn(errorSnapshot())

        repeat(4) {
            // Bypass the real 1500ms cooldown between retry attempts.
            context.recovery.media3ErrorRecoveryLastAttemptMs = 0L
            ops.sync()
        }

        verify(media3, times(4)).retryAfterError()
        verify(media3, times(1)).next()
        assertNull(context.recovery.media3ErrorRecoveryTrackId)
        assertEquals(0, context.recovery.media3ErrorRecoveryAttempts)
    }

    @Test
    fun sync_media3ErrorWithinCooldown_doesNotRetryAgain() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedQueue(tracks)
        whenever(media3.snapshot()).thenReturn(errorSnapshot())

        ops.sync() // first attempt, sets lastAttemptMs = now
        ops.sync() // still within the 1500ms cooldown

        verify(media3, times(1)).retryAfterError()
        assertEquals(1, context.recovery.media3ErrorRecoveryAttempts)
    }

    @Test
    fun sync_media3RecoversToPlaying_resetsErrorRecoveryState() = runTest {
        val tracks = listOf(track("a"), track("b"))
        seedQueue(tracks)
        whenever(media3.snapshot()).thenReturn(errorSnapshot())
        ops.sync()
        assertEquals("a", context.recovery.media3ErrorRecoveryTrackId)

        whenever(media3.snapshot()).thenReturn(
            errorSnapshot().copy(state = PlaybackStateType.PLAYING)
        )
        ops.sync()

        assertNull(context.recovery.media3ErrorRecoveryTrackId)
        assertEquals(0, context.recovery.media3ErrorRecoveryAttempts)
    }

    // ---- transport guards ----

    @Test
    fun playFromIndex_updatesCurrentTrackAndSeeksMedia3() {
        val tracks = listOf(track("a"), track("b"))
        seedQueue(tracks)

        ops.playFromIndex(context.mutableStatus.value, target = 1)

        verify(media3).playFromIndex(1)
        assertEquals("b", context.mutableStatus.value.currentTrack?.id)
        assertEquals(PlaybackStateType.PLAYING, context.mutableStatus.value.state)
    }

    @Test
    fun previous_media3ReportsNoMove_doesNotChangeState() {
        val tracks = listOf(track("a"), track("b"))
        seedQueue(tracks)
        context.mutableStatus.value = context.mutableStatus.value.copy(state = PlaybackStateType.PAUSED)
        whenever(media3.previous()).thenReturn(false)

        ops.previous(context.mutableStatus.value)

        assertEquals(PlaybackStateType.PAUSED, context.mutableStatus.value.state)
    }

    @Test
    fun previous_emptyQueue_doesNotCallMedia3() {
        ops.previous(context.mutableStatus.value)

        verify(media3, never()).previous()
    }
}
