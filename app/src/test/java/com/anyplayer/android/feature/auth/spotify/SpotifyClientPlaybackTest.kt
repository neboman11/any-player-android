package com.anyplayer.android.feature.auth.spotify

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyClientPlaybackTest {
    @Test
    fun playbackUrisStartAtTheRequestedQueueIndexAndKeepCanonicalUris() {
        assertEquals(
            listOf(
                "spotify:track:2222222222222222222222",
                "spotify:track:3333333333333333333333"
            ),
            spotifyPlaybackUris(
                trackIds = listOf(
                    "1111111111111111111111",
                    "spotify:track:2222222222222222222222",
                    "3333333333333333333333"
                ),
                startIndex = 1
            )
        )
    }
}
