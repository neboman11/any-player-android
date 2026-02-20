package com.anyplayer.android.playback

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.anyplayer.android.AnyPlayerApplication
import com.anyplayer.android.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AnyPlayerMediaLibraryService : MediaLibraryService() {
    @Inject
    lateinit var playerBridge: MediaSessionPlayerBridge

    private var mediaLibrarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()

        // Use the app-defined notification channel so the foreground notification
        // appears in the correct channel on Android O+.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(AnyPlayerApplication.PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            playerBridge,
            object : MediaLibrarySession.Callback {
                // Must return a valid root so MediaBrowserCompat clients (Android Auto,
                // DHU, Google Home) don't get onConnectionFailed. The default
                // implementation returns RESULT_ERROR_NOT_SUPPORTED which causes all
                // legacy-API clients to disconnect immediately.
                override fun onGetLibraryRoot(
                    session: MediaLibrarySession,
                    browser: MediaSession.ControllerInfo,
                    params: LibraryParams?
                ): ListenableFuture<LibraryResult<MediaItem>> {
                    val root = MediaItem.Builder()
                        .setMediaId("root")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setIsBrowsable(true)
                                .setIsPlayable(false)
                                .setTitle("Any Player")
                                .build()
                        )
                        .build()
                    return Futures.immediateFuture(LibraryResult.ofItem(root, params))
                }
            }
        ).build()

        // Put the service into the foreground immediately (required within 5 s of
        // startForegroundService on API 26+). Media3's DefaultMediaNotificationProvider
        // will replace this placeholder once the player has metadata to show.
        val placeholder: Notification = NotificationCompat.Builder(
            this,
            AnyPlayerApplication.PLAYBACK_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Any Player")
            .setContentText("Ready")
            .setSilent(true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, placeholder)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
