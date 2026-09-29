package com.anyplayer.android.feature.playback.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.feature.playback.PlaybackQueueManager

internal class AudioBecomingNoisyReceiver(
    private val playbackQueueManager: PlaybackQueueManager
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY &&
            playbackQueueManager.status.value.state in setOf(PlaybackStateType.PLAYING, PlaybackStateType.BUFFERING)
        ) {
            playbackQueueManager.pause()
        }
    }
}
