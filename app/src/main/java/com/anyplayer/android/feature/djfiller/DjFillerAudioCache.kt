package com.anyplayer.android.feature.djfiller

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the one generated AI DJ voice-over waiting to play across a restart. */
@Singleton
class DjFillerAudioCache @Inject constructor(
    @ApplicationContext context: Context
) {
    private val directory = File(context.filesDir, "dj_filler").apply { mkdirs() }

    private val readyFile = File(directory, "ready.wav")
    private val readyTrackIdFile = File(directory, "ready-track-id")

    fun newOutputFile(): File = File(directory, "${UUID.randomUUID()}.wav")

    fun save(trackId: String, audioFile: File): File {
        readyFile.delete()
        if (!audioFile.renameTo(readyFile)) {
            audioFile.copyTo(readyFile, overwrite = true)
            audioFile.delete()
        }
        readyTrackIdFile.writeText(trackId)
        return readyFile
    }

    fun load(trackId: String): File? {
        if (!readyFile.isFile) {
            readyTrackIdFile.delete()
            return null
        }
        return readyFile.takeIf { readyTrackIdFile.takeIf(File::isFile)?.readText() == trackId }
    }

    fun delete(audioFile: File) {
        audioFile.delete()
        if (audioFile == readyFile) readyTrackIdFile.delete()
    }

    fun clear() {
        readyFile.delete()
        readyTrackIdFile.delete()
        directory.listFiles()?.forEach { file ->
            if (file != readyFile && file != readyTrackIdFile) file.delete()
        }
    }
}
