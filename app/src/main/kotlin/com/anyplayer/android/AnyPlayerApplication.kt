package com.anyplayer.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.anyplayer.android.playback.PlaybackNotifications
import dagger.hilt.android.HiltAndroidApp
import com.anyplayer.android.data.sync.AppStartup
import javax.inject.Inject

@HiltAndroidApp
class AnyPlayerApplication : Application() {
    @Inject lateinit var startup: AppStartup
    override fun onCreate() {
        super.onCreate()
        createPlaybackNotificationChannel()
        startup.start()
    }

    private fun createPlaybackNotificationChannel() {
        val channel = NotificationChannel(
            PlaybackNotifications.CHANNEL_ID,
            "Any Player Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
