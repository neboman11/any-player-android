package com.anyplayer.android.feature.auth.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyClientPlaybackTest {
    @Test
    fun playbackUrisKeepFullQueueAsCanonicalUris() {
        assertEquals(
            listOf(
                "spotify:track:1111111111111111111111",
                "spotify:track:2222222222222222222222",
                "spotify:track:3333333333333333333333"
            ),
            spotifyPlaybackUris(
                trackIds = listOf(
                    "1111111111111111111111",
                    "spotify:track:2222222222222222222222",
                    "3333333333333333333333"
                )
            )
        )
    }

    @Test
    fun playbackOffsetPointsAtRequestedQueueIndex() {
        val trackIds = listOf(
            "1111111111111111111111",
            "spotify:track:2222222222222222222222",
            "3333333333333333333333"
        )

        assertEquals(1, spotifyPlaybackOffset(trackIds, startIndex = 1))
    }

    @Test
    fun playbackOffsetSkipsInvalidIdsAheadOfRequestedIndex() {
        val trackIds = listOf(
            "not-a-valid-id",
            "spotify:track:2222222222222222222222",
            "3333333333333333333333"
        )

        assertEquals(0, spotifyPlaybackOffset(trackIds, startIndex = 1))
    }
}
