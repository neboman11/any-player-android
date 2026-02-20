package com.anyplayer.android.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.AnyPlayerApplication
import com.anyplayer.android.MainActivity
import com.anyplayer.android.R
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
@UnstableApi
class AnyPlayerMediaLibraryService : MediaLibraryService() {
    @Inject
    lateinit var playerBridge: MediaSessionPlayerBridge
    @Inject
    lateinit var playbackQueueManager: PlaybackQueueManager

    private var mediaLibrarySession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()

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

        serviceScope.launch {
            playbackQueueManager.status.collect { status ->
                updateAudioFocus(status)
                startForegroundCompat(buildPlaybackNotification(status))
            }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playbackQueueManager.togglePlayPause()
            ACTION_NEXT -> playbackQueueManager.next()
            ACTION_PREVIOUS -> playbackQueueManager.previous()
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        serviceScope.cancel()
        abandonAudioFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaLibrarySession?.run {
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PLAY_PAUSE = "com.anyplayer.android.action.PLAY_PAUSE"
        private const val ACTION_NEXT = "com.anyplayer.android.action.NEXT"
        private const val ACTION_PREVIOUS = "com.anyplayer.android.action.PREVIOUS"
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildPlaybackNotification(status: PlaybackStatus): Notification {
        val currentTrack = status.currentTrack
        val hasTrack = currentTrack != null
        val isPlaying = status.state == PlaybackStateType.PLAYING
        return NotificationCompat.Builder(this, AnyPlayerApplication.PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.title ?: getString(R.string.app_name))
            .setContentText(currentTrack?.artist ?: "Ready")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(hasTrack)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                actionPendingIntent(ACTION_PREVIOUS)
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                actionPendingIntent(ACTION_PLAY_PAUSE)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                actionPendingIntent(ACTION_NEXT)
            )
            .build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AnyPlayerMediaLibraryService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateAudioFocus(status: PlaybackStatus) {
        val isSpotifySource = status.currentTrack?.source == SourceType.SPOTIFY
        if (isSpotifySource && status.state == PlaybackStateType.PLAYING) {
            requestAudioFocus()
        } else {
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusListener)
        }
    }
}
