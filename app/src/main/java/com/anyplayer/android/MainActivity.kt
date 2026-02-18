package com.anyplayer.android

import android.os.Bundle
import com.anyplayer.android.app.SpotifyAuthRedirectBus
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.anyplayer.android.app.AnyPlayerApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        setContent {
            MaterialTheme {
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
