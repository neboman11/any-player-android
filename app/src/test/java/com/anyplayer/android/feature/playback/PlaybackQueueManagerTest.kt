package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueManagerTest {
    @Test
    fun playFromIndex_jumpsToSelectedRow() {
        val manager = PlaybackQueueManager()
        val queue = sampleQueue()

        manager.setQueue(queue, startIndex = 0, autoPlay = false)
        manager.playFromIndex(2)

        assertEquals("3", manager.status.value.currentTrack?.id)
        assertTrue(manager.status.value.queue.map { it.id } == listOf("1", "2", "3"))
    }

    @Test
    fun repeatAll_wrapsAtQueueEnd() {
        val manager = PlaybackQueueManager()
        val queue = sampleQueue()

        manager.setQueue(queue, startIndex = 2, autoPlay = true)
        manager.setRepeatMode(RepeatMode.ALL)
        manager.next()

        assertEquals("1", manager.status.value.currentTrack?.id)
    }

    private fun sampleQueue() = listOf(
        Track(id = "1", title = "One", artist = "A", source = SourceType.CUSTOM),
        Track(id = "2", title = "Two", artist = "B", source = SourceType.CUSTOM),
        Track(id = "3", title = "Three", artist = "C", source = SourceType.CUSTOM)
    )
}
