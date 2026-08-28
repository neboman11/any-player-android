package com.anyplayer.android.feature.playback.service

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class QueueTimelineTest {
    private fun track(id: String) = Track(
        id = id,
        title = "Title $id",
        artist = "Artist",
        source = SourceType.JELLYFIN
    )

    @Test
    fun getWindow_multiTrackQueue_assignsDistinctUidsPerWindow() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("c")))
        val window = Timeline.Window()

        val uidA = timeline.getWindow(0, window).uid
        val uidB = timeline.getWindow(1, window).uid
        val uidC = timeline.getWindow(2, window).uid

        assertNotEquals(uidA, uidB)
        assertNotEquals(uidB, uidC)
        assertNotEquals(uidA, uidC)
    }

    @Test
    fun getIndexOfPeriod_resolvesToMatchingTrack() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("c")))

        assertEquals(1, timeline.getIndexOfPeriod("b"))
    }
}
