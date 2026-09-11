package com.anyplayer.android.feature.djfiller

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the small directory of generated AI DJ voice-over `.wav` files. These are
 *  never persisted as part of playback state (the scheduler's in-memory bookkeeping
 *  resets on app restart), so any files left over from a killed/crashed session are
 *  just stale and safe to delete on the next cold start. */
@Singleton
class DjFillerAudioCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val directory = File(context.cacheDir, "dj_filler").apply { mkdirs() }

    fun newOutputFile(): File = File(directory, "${UUID.randomUUID()}.wav")

    fun clearStale() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
