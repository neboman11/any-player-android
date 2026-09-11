package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.DjFillerScheduler
import com.anyplayer.android.feature.djfiller.DjInterstitialPlayer
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class DjFillerAdvanceHookTest {
    @Test
    fun `pause failure skips standalone filler and advances normally`() = runTest {
        val scheduler: DjFillerScheduler = mock()
        val interstitial: DjInterstitialPlayer = mock()
        val filler = PreparedFiller(
            track = Track(
                id = "next",
                title = "Next",
                artist = "Artist",
                source = SourceType.SPOTIFY
            ),
            scriptText = "intro",
            audioFile = File("intro.wav")
        )
        var advanced = false

        whenever(scheduler.consumeReadyFillerIfDue("next")).thenReturn(filler)

        scheduler.playFillerThenAdvance(
            djInterstitialPlayer = interstitial,
            upcomingTrackId = "next",
            pauseActiveSpotify = { false }
        ) {
            advanced = true
        }

        assertTrue(advanced)
        verify(interstitial, never()).playStandalone(any(), any())
    }
}
