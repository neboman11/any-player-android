package com.anyplayer.android.feature.playback.service

import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionControllerGuardTest {
    @Test
    fun pausesBufferingPlaybackAfterLastProjectionControllerDisconnects() = runTest {
        val queueManager: PlaybackQueueManager = mock()
        whenever(queueManager.status).thenReturn(
            MutableStateFlow(PlaybackStatus(PlaybackStateType.BUFFERING, false, RepeatMode.OFF, 100, position = 0, duration = 0, queue = emptyList()))
        )
        val controller: MediaSession.ControllerInfo = mock()
        whenever(controller.packageName).thenReturn("com.google.android.projection.gearhead")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val guard = ProjectionControllerGuard(context, this, queueManager)

        guard.onProjectionControllerConnected(controller)
        guard.onProjectionControllerDisconnected(controller)
        advanceTimeBy(1_500)
        runCurrent()

        verify(queueManager).pause()
    }
}
