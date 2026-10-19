package com.anyplayer.android.playback

import com.anyplayer.android.core.model.*
import com.anyplayer.android.playback.persistence.LegacyPlaybackState
import org.junit.Assert.*
import org.junit.Test

class LegacyPlaybackStateTest {
    private fun track(id: String) = Track(id = id, title = id, artist = "Artist", source = SourceType.CUSTOM, url = "file:///$id.wav")
    @Test fun duplicatesMapByOccurrenceAndCursorUsesInsertionSpace() {
        val a = track("a"); val b = track("b")
        val legacy = LegacyPlaybackState(queue = listOf(a, b, a), orderedQueue = listOf(a, a, b), currentQueueIndex = 2, shuffle = true, positionMs = 4321)
        val queue = legacy.toQueue()
        assertEquals(listOf(0, 2, 1), queue.order)
        assertEquals(1, queue.cursor)
        assertEquals(3L, queue.current?.uid)
        assertEquals(4321L, legacy.toSettings().positionMs)
    }
    @Test fun incompleteOrderedQueueNeverLosesEntries() {
        val legacy = LegacyPlaybackState(queue = listOf(track("a"), track("b"), track("a")), orderedQueue = listOf(track("a")), currentQueueIndex = 1, shuffle = true)
        assertEquals(listOf(0, 1, 2), legacy.toQueue().order)
        assertEquals(2L, legacy.toQueue().current?.uid)
    }
    @Test fun legacyNullableOrderAndMissingFlagsDecode() {
        val encoded = """{"queue":[{"id":"a","title":"A","artist":"Artist","source":"custom","url":"file:///a.wav"}],"orderedQueue":null,"currentQueueIndex":0,"positionMs":1200,"shuffle":false,"repeatMode":"one","volume":75,"state":"playing"}"""
        val legacy = AppJson.decodeFromString<LegacyPlaybackState>(encoded)
        assertEquals(RepeatMode.ONE, legacy.toQueue().repeat)
        assertEquals(1200L, legacy.toSettings().positionMs)
        assertFalse(legacy.toSettings().aiDjEnabled)
        assertEquals(75, legacy.toSettings().volume)
    }
}
