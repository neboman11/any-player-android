package com.anyplayer.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeNormalizationTest {
    @Test
    fun disabled_returnsClampedVolume() {
        assertEquals(77, VolumeNormalization.effective(77, SourceType.SPOTIFY, enabled = false))
        assertEquals(100, VolumeNormalization.effective(140, SourceType.JELLYFIN, enabled = false))
        assertEquals(0, VolumeNormalization.effective(-5, SourceType.PLEX, enabled = false))
    }

    @Test
    fun spotify_usesIntegerTargetScaling() {
        // ((v * 85 + 50) / 100) with integer division, as in the Rust core.
        assertEquals(85, VolumeNormalization.effective(100, SourceType.SPOTIFY, true))
        assertEquals(43, VolumeNormalization.effective(50, SourceType.SPOTIFY, true))
        assertEquals(1, VolumeNormalization.effective(1, SourceType.SPOTIFY, true))
        assertEquals(0, VolumeNormalization.effective(0, SourceType.SPOTIFY, true))
    }

    @Test
    fun otherSources_useRuntimeFactor() {
        // factor = (0.04 + 0.85*0.18) / 0.22 = 0.8772...
        assertEquals(0.8772727f, VolumeNormalization.OTHER_SOURCE_FACTOR, 1e-6f)
        assertEquals(88, VolumeNormalization.effective(100, SourceType.JELLYFIN, true))
        assertEquals(44, VolumeNormalization.effective(50, SourceType.PLEX, true))
        assertEquals(9, VolumeNormalization.effective(10, SourceType.ALL, true))
        assertEquals(0, VolumeNormalization.effective(0, SourceType.CUSTOM, true))
    }

    @Test
    fun matchesRustFormulaForEveryVolume() {
        for (v in 0..100) {
            val spotify = ((v * 85 + 50) / 100).coerceAtMost(100)
            assertEquals(spotify, VolumeNormalization.effective(v, SourceType.SPOTIFY, true))
            val factor = ((0.04f + 0.85f * 0.18f) / (0.04f + 0.18f)).coerceIn(0.1f, 1.0f)
            assertEquals(Math.round(v * factor), VolumeNormalization.effective(v, SourceType.JELLYFIN, true))
        }
    }
}
