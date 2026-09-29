package com.anyplayer.android.feature.playback.service

import android.content.Context
import android.content.Intent
import com.anyplayer.android.core.log.CompatLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** (Re)starts [AnyPlayerMediaLibraryService] when playback begins. Android stops the service
 *  once it has sat paused out of foreground for a while, and only a cold launch of
 *  MainActivity used to start it again - so resuming from an already-open app played audio
 *  with no media session, notification or lock-screen controls. Starting an already-running
 *  service is a harmless no-op onStartCommand. */
@Singleton
class PlaybackServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun ensureRunning() {
        runCatching { context.startService(Intent(context, AnyPlayerMediaLibraryService::class.java)) }
            // Background-start restrictions can refuse this when playback was resumed with no
            // visible UI; the session then comes back the next time the app is opened.
            .onFailure { CompatLog.w("PlaybackServiceLauncher", "could not start media service", it) }
    }
}
