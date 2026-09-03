package com.anyplayer.android.feature.playback.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.anyplayer.android.AnyPlayerApplication
import com.anyplayer.android.MainActivity
import com.anyplayer.android.R
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus

/** Builds the foreground-service media-style playback notification and its transport actions. */
internal class PlaybackNotificationBuilder(private val context: Context) {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.anyplayer.android.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.anyplayer.android.action.NEXT"
        const val ACTION_PREVIOUS = "com.anyplayer.android.action.PREVIOUS"
    }

    fun build(status: PlaybackStatus, session: MediaLibrarySession?): Notification {
        val currentTrack = status.currentTrack
        val hasTrack = currentTrack != null
        val isPlaying = status.state == PlaybackStateType.PLAYING
        val mediaStyle = MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
        @Suppress("DEPRECATION")
        session?.sessionCompatToken?.let { mediaStyle.setMediaSession(it) }
        return NotificationCompat.Builder(context, AnyPlayerApplication.PLAYBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.title ?: context.getString(R.string.app_name))
            .setContentText(currentTrack?.artist ?: "Ready")
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(hasTrack && status.state != PlaybackStateType.IDLE && status.state != PlaybackStateType.ERROR)
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
        val intent = Intent(context, AnyPlayerMediaLibraryService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
