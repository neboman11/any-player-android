package com.anyplayer.android.feature.playback.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.PlaybackStatus
import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.feature.playback.PlaybackQueueManager

/**
 * Manual Android audio-focus handling for the Spotify Connect playback path only - it's
 * driven remotely over HTTP and never touches ExoPlayer, so unlike local playback (which
 * gets this for free via Media3's setAudioAttributes(..., handleAudioFocus = true)) it
 * needs its own focus request/abandon/pause-resume handling.
 */
internal class SpotifyAudioFocusGuard(
    context: Context,
    private val playbackQueueManager: PlaybackQueueManager
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var wasPlayingBeforeFocusLoss = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val activeSource = playbackQueueManager.status.value.currentTrack?.source
        if (activeSource != SourceType.SPOTIFY) {
            wasPlayingBeforeFocusLoss = false
            return@OnAudioFocusChangeListener
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                val shouldResume = wasPlayingBeforeFocusLoss
                wasPlayingBeforeFocusLoss = false
                if (shouldResume &&
                    playbackQueueManager.status.value.state == PlaybackStateType.PAUSED
                ) {
                    playbackQueueManager.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                playbackQueueManager.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val isPlaying = playbackQueueManager.status.value.state == PlaybackStateType.PLAYING
                wasPlayingBeforeFocusLoss = isPlaying
                if (isPlaying) {
                    playbackQueueManager.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Let the system handle ducking via AudioAttributes; no manual volume change needed.
                // Media3/ExoPlayer handles ducking automatically when AudioAttributes are set.
            }
        }
    }

    /** Call on every [PlaybackStatus] update; requests/abandons focus to match. */
    fun update(status: PlaybackStatus) {
        val currentTrack = status.currentTrack

        // Only manage audio focus here for sources that do NOT rely on ExoPlayer's
        // built-in audio focus handling (e.g., the Spotify Connect path). Media3/ExoPlayer
        // playback already handles audio focus via setAudioAttributes(..., handleAudioFocus = true),
        // so we avoid double pause/resume handling by skipping those sources here.
        if (currentTrack != null && currentTrack.source != SourceType.SPOTIFY) {
            // We may still be holding audio focus from a previous Spotify track.
            // Explicitly abandon it before deferring to ExoPlayer's own focus handling.
            abandon()
            wasPlayingBeforeFocusLoss = false
            return
        }

        if (status.state == PlaybackStateType.PLAYING && currentTrack != null) {
            request()
        } else {
            abandon()
        }
    }

    /** Call from the hosting Service's onDestroy. */
    fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun request() {
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
}
