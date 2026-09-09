package com.anyplayer.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.anyplayer.android.feature.djfiller.DjFillerAudioCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AnyPlayerApplication : Application() {
    @Inject
    lateinit var djFillerAudioCache: DjFillerAudioCache

    override fun onCreate() {
        super.onCreate()
        createPlaybackNotificationChannel()
        // Directory-listing-and-delete I/O; runs on cold start even for users who never
        // touch the AI DJ feature, so it must not block the main thread before first frame.
        Thread { djFillerAudioCache.clearStale() }.start()
    }

    private fun createPlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "Any Player Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "any_player_playback"
    }
}
