package com.anyplayer.android.feature.playback.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.media3.session.MediaSession
import com.anyplayer.android.core.log.CompatLog
import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.feature.playback.PlaybackQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Validates controller trust and tracks Android Auto / projection controller connect state,
 * pausing playback shortly after the last such controller disconnects.
 */
internal class ProjectionControllerGuard(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val playbackQueueManager: PlaybackQueueManager
) {
    companion object {
        private const val TAG = "ProjectionControllerGuard"
        private const val PROJECTION_DISCONNECT_GRACE_MS = 1500L
        private val TRUSTED_CONTROLLER_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.android.car.media",
            "com.google.android.apps.automotive.media"
        )
    }

    private var activeProjectionControllers = 0
    private var projectionDisconnectPauseJob: Job? = null

    fun isTrustedController(controllerInfo: MediaSession.ControllerInfo): Boolean {
        val controllerPackage = controllerInfo.packageName
        if (controllerPackage == context.packageName) return true
        if (controllerInfo.uid == Process.SYSTEM_UID) return true
        if (controllerPackage in TRUSTED_CONTROLLER_PACKAGES) return true
        return isSystemApp(controllerPackage)
    }

    private fun isSystemApp(controllerPackage: String): Boolean {
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(controllerPackage, 0)
        }.getOrNull() ?: return false
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return (appInfo.flags and systemFlags) != 0
    }

    fun isProjectionController(controllerPackage: String): Boolean {
        if (controllerPackage == "com.google.android.projection.gearhead") return true
        if (controllerPackage.startsWith("com.google.android.apps.auto")) return true
        if (controllerPackage.startsWith("com.android.car")) return true
        return false
    }

    fun onProjectionControllerConnected(controller: MediaSession.ControllerInfo) {
        val wasInactive = activeProjectionControllers == 0
        activeProjectionControllers += 1
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = null
        if (wasInactive) {
            playbackQueueManager.resetSpotifyConnectionState()
        }
        CompatLog.i(
            TAG,
            "Projection controller connected package=${controller.packageName} active=$activeProjectionControllers"
        )
    }

    fun onProjectionControllerDisconnected(controller: MediaSession.ControllerInfo) {
        activeProjectionControllers = (activeProjectionControllers - 1).coerceAtLeast(0)
        CompatLog.i(
            TAG,
            "Projection controller disconnected package=${controller.packageName} active=$activeProjectionControllers"
        )
        if (activeProjectionControllers > 0) return
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = serviceScope.launch {
            delay(PROJECTION_DISCONNECT_GRACE_MS)
            val current = playbackQueueManager.status.value
            if (activeProjectionControllers == 0 && current.state == PlaybackStateType.PLAYING) {
                CompatLog.i(TAG, "Projection controllers inactive after grace period; pausing playback")
                playbackQueueManager.pause()
            }
        }
    }

    fun release() {
        projectionDisconnectPauseJob?.cancel()
        projectionDisconnectPauseJob = null
        activeProjectionControllers = 0
    }
}
