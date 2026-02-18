package com.anyplayer.android.playback

import androidx.media3.common.Player
import com.anyplayer.android.feature.playback.Media3PlaybackController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AnyPlayerMediaLibraryService : MediaLibraryService() {
    @Inject
    lateinit var playbackController: Media3PlaybackController

    private var player: Player? = null
    private var mediaLibrarySession: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val localPlayer = playbackController.player
        player = localPlayer

        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            localPlayer,
            object : MediaLibrarySession.Callback {}
        ).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        mediaLibrarySession?.run {
            release()
        }
        mediaLibrarySession = null
        player = null
        super.onDestroy()
    }
}
