package com.anyplayer.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTrackIdsTest {
    @Test
    fun normalize_stripsUriPrefixAndTrims() {
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", normalizeSpotifyTrackId("spotify:track:4uLU6hMCjMI75M1A2tKUQC "))
        assertEquals("abc", normalizeSpotifyTrackId(" abc "))
    }

    @Test
    fun trackIdsMatch_acrossFormats() {
        assertTrue(trackIdsMatch("spotify:track:4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(trackIdsMatch("x", "x"))
        assertFalse(trackIdsMatch("a", "b"))
    }
}
