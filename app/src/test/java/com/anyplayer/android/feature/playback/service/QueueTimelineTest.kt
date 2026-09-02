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
    fun getIndexOfPeriod_resolvesViaUidRoundTrip() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("c")))

        val uid = timeline.getUidOfPeriod(1)

        assertEquals(1, timeline.getIndexOfPeriod(uid))
    }

    @Test
    fun getWindow_duplicateTrackIds_assignsDistinctUidsPerWindow() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("a")))
        val window = Timeline.Window()

        val uidFirst = timeline.getWindow(0, window).uid
        val uidSecond = timeline.getWindow(2, window).uid

        assertNotEquals(uidFirst, uidSecond)
    }

    @Test
    fun getIndexOfPeriod_duplicateTrackIds_resolveToDistinctIndices() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("a")))

        val uidFirst = timeline.getUidOfPeriod(0)
        val uidSecond = timeline.getUidOfPeriod(2)

        assertNotEquals(uidFirst, uidSecond)
        assertEquals(0, timeline.getIndexOfPeriod(uidFirst))
        assertEquals(2, timeline.getIndexOfPeriod(uidSecond))
    }

    @Test
    fun getWindow_multiTrackQueue_ownsItsOwnPeriodNotAlwaysPeriodZero() {
        val timeline = QueueTimeline(listOf(track("a"), track("b"), track("c")))
        val window = Timeline.Window()

        for (index in 0..2) {
            val result = timeline.getWindow(index, window)
            assertEquals("firstPeriodIndex for window $index", index, result.firstPeriodIndex)
            assertEquals("lastPeriodIndex for window $index", index, result.lastPeriodIndex)
        }
    }
}
