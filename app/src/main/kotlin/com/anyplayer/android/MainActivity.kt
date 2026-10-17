package com.anyplayer.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.anyplayer.android.playback.PlaybackEngine
import com.anyplayer.android.feature.playback.service.AnyPlayerMediaLibraryService
import com.anyplayer.android.ui.shell.AnyPlayerShell
import com.anyplayer.android.ui.shell.SpotifyRedirectHolder
import com.anyplayer.android.ui.theme.AnyPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var engine: PlaybackEngine
    @Inject lateinit var spotifyRedirects: SpotifyRedirectHolder

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        startService(Intent(this, AnyPlayerMediaLibraryService::class.java))
        handleIncomingIntent(intent)
        requestNotificationsOnFirstPlayback()
        setContent {
            AnyPlayerTheme { AnyPlayerShell() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "anyplayer" && uri.host == "spotify-callback") spotifyRedirects.publish(uri.toString())
    }

    private fun requestNotificationsOnFirstPlayback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.player.first { it.playRequested }
                val shouldRequest = withContext(Dispatchers.IO) {
                    val preferences = getSharedPreferences("ui_permissions", MODE_PRIVATE)
                    if (preferences.getBoolean("notifications_requested", false)) false
                    else {
                        preferences.edit().putBoolean("notifications_requested", true).apply()
                        true
                    }
                }
                if (shouldRequest && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
