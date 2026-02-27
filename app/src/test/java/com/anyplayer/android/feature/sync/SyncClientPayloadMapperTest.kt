package com.anyplayer.android.feature.sync

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncClientPayloadMapperTest {
    @Test
    fun mapsPlayingStateAndRepeatOne() {
        val track = Track(
            id = "track-1",
            title = "Song",
            artist = "Artist",
            source = SourceType.SPOTIFY
        )

        val status = PlaybackStatus(
            state = PlaybackStateType.PLAYING,
            shuffle = true,
            repeatMode = RepeatMode.ONE,
            volume = 67,
            currentTrack = track,
            position = 12345,
            duration = 250000,
            queue = listOf(track)
        )

        val payload = playbackStatusToAppStatePayload(status)

        assertEquals("playing", payload.state)
        assertEquals(true, payload.shuffle)
        assertEquals("one", payload.repeat_mode)
        assertEquals(67, payload.volume)
        assertEquals(12345, payload.position)
        assertEquals(250000, payload.duration)
        assertEquals("track-1", payload.current_track?.id)
        assertEquals(1, payload.queue.size)
    }

    @Test
    fun mapsIdleToStoppedAndRepeatOff() {
        val status = PlaybackStatus(
            state = PlaybackStateType.IDLE,
            shuffle = false,
            repeatMode = RepeatMode.OFF,
            volume = 40,
            currentTrack = null,
            position = 0,
            duration = 0,
            queue = emptyList()
        )

        val payload = playbackStatusToAppStatePayload(status)

        assertEquals("stopped", payload.state)
        assertEquals("off", payload.repeat_mode)
        assertEquals(0, payload.queue.size)
    }
}
