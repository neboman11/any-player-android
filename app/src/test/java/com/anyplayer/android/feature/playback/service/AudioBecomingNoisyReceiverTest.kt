package com.anyplayer.android.feature.playback.service

import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioBecomingNoisyReceiverTest {
    private fun queueManager(state: PlaybackStateType): PlaybackQueueManager = mock<PlaybackQueueManager>().also {
        whenever(it.status).thenReturn(
            MutableStateFlow(PlaybackStatus(state, false, RepeatMode.OFF, 100, position = 0, duration = 0, queue = emptyList()))
        )
    }

    @Test
    fun pausesPlaybackWhenAudioRouteDisconnects() {
        val queueManager = queueManager(PlaybackStateType.PLAYING)
        val receiver = AudioBecomingNoisyReceiver(queueManager)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        verify(queueManager).pause()
    }

    @Test
    fun pausesBufferingPlaybackWhenAudioRouteDisconnects() {
        val queueManager = queueManager(PlaybackStateType.BUFFERING)
        val receiver = AudioBecomingNoisyReceiver(queueManager)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        verify(queueManager).pause()
    }

    @Test
    fun ignoresOtherBroadcasts() {
        val queueManager = queueManager(PlaybackStateType.PLAYING)
        val receiver = AudioBecomingNoisyReceiver(queueManager)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent("unrelated"))

        verify(queueManager, never()).pause()
    }

    @Test
    fun doesNotSendAnotherPauseWhenAlreadyPaused() {
        val queueManager = queueManager(PlaybackStateType.PAUSED)
        val receiver = AudioBecomingNoisyReceiver(queueManager)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        verify(queueManager, never()).pause()
    }
}
