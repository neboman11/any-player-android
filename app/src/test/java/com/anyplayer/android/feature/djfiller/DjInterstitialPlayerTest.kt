package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import com.anyplayer.android.feature.playback.Media3PlaybackController
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DjInterstitialPlayerTest {
    @Test
    fun `standalone handoff failure deletes generated audio`() {
        val audioFile = Files.createTempFile("dj-filler", ".wav").toFile()
        val controller = mock<Media3PlaybackController>()
        whenever(controller.playInterstitialStandalone(any(), any(), any()))
            .thenThrow(IllegalStateException("player unavailable"))
        val player = DjInterstitialPlayer(controller)
        try {
            assertThrows(IllegalStateException::class.java) {
                player.playStandalone(
                    PreparedFiller(
                        track = Track(id = "next", title = "Next", artist = "Artist", source = SourceType.JELLYFIN),
                        audioFile = audioFile
                    )
                ) {}
            }
            assertFalse(audioFile.exists())
        } finally {
            audioFile.delete()
        }
    }
    @Test
    fun `local insertion deletes audio when no timeline accepts it`() {
        val audioFile = Files.createTempFile("dj-filler", ".wav").toFile()
        val controller = mock<Media3PlaybackController>()
        whenever(controller.insertInterstitial(any(), any())).thenReturn(false)

        try {
            DjInterstitialPlayer(controller).insertLocal(
                PreparedFiller(
                    track = Track(id = "next", title = "Next", artist = "Artist", source = SourceType.JELLYFIN),
                    audioFile = audioFile
                )
            )

            assertFalse(audioFile.exists())
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun `played interstitial deletes generated audio`() {
        val audioFile = Files.createTempFile("dj-filler", ".wav").toFile()
        val controller = mock<Media3PlaybackController>()
        val mediaId = argumentCaptor<String>()
        whenever(controller.insertInterstitial(any(), any())).thenReturn(true)

        try {
            val player = DjInterstitialPlayer(controller)
            player.insertLocal(
                PreparedFiller(
                    track = Track(id = "next", title = "Next", artist = "Artist", source = SourceType.JELLYFIN),
                    audioFile = audioFile
                )
            )
            verify(controller).insertInterstitial(any(), mediaId.capture())

            player.onInterstitialEnded(mediaId.firstValue)

            assertFalse(audioFile.exists())
        } finally {
            audioFile.delete()
        }
    }
}
