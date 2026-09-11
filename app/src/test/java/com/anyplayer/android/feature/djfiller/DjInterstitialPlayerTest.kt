package com.anyplayer.android.feature.djfiller

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import com.anyplayer.android.feature.djfiller.model.PreparedFiller
import com.anyplayer.android.feature.playback.Media3PlaybackController
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DjInterstitialPlayerTest {
    @Test
    fun `local insertion deletes audio when no timeline accepts it`() {
        val audioFile = Files.createTempFile("dj-filler", ".wav").toFile()
        val controller = mock<Media3PlaybackController>()
        whenever(controller.insertInterstitial(any(), any())).thenReturn(false)

        try {
            DjInterstitialPlayer(controller).insertLocal(
                PreparedFiller(
                    track = Track(id = "next", title = "Next", artist = "Artist", source = SourceType.JELLYFIN),
                    scriptText = "intro",
                    audioFile = audioFile
                )
            )

            assertFalse(audioFile.exists())
        } finally {
            audioFile.delete()
        }
    }
}
