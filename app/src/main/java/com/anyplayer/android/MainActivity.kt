package com.anyplayer.android

import android.content.Intent
import android.os.Bundle
import com.anyplayer.android.app.SpotifyAuthRedirectBus
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.anyplayer.android.ui.AnyPlayerTheme
import androidx.media3.common.util.UnstableApi
import com.anyplayer.android.app.AnyPlayerApp
import com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the media library service so the MediaLibrarySession is live before
        // Android Auto tries to connect. Without this, Auto cannot find the session
        // and will not display Any Player as the active media source.
        startService(Intent(this, AnyPlayerMediaLibraryService::class.java))
        handleIncomingIntent(intent)
        setContent {
            AnyPlayerTheme {
                AnyPlayerApp()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "anyplayer" && uri.host == "spotify-callback") {
            SpotifyAuthRedirectBus.publish(uri.toString())
        }
    }
}
