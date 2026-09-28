package com.anyplayer.android.feature.djfiller

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.anyplayer.android.feature.djfiller.metadata.DjPassages
import kotlinx.serialization.json.Json
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
    private val readyPassagesFile = File(directory, "ready-passages.json")

    fun newOutputFile(): File = File(directory, "${UUID.randomUUID()}.wav")

    fun save(trackId: String, audioFile: File, passages: DjPassages? = null): File {
        readyPassagesFile.delete()
        if (audioFile != readyFile) {
            readyFile.delete()
            if (!audioFile.renameTo(readyFile)) {
                audioFile.copyTo(readyFile, overwrite = true)
                audioFile.delete()
            }
        }
        readyTrackIdFile.writeText(trackId)
        passages?.let { readyPassagesFile.writeText(Json.encodeToString(DjPassages.serializer(), it)) }
        return readyFile
    }

    fun loadPassages(trackId: String): DjPassages? = if (load(trackId) != null) {
        runCatching { Json.decodeFromString(DjPassages.serializer(), readyPassagesFile.readText()) }.getOrNull()
    } else null

    fun load(trackId: String): File? {
        if (!readyFile.isFile) {
            readyTrackIdFile.delete()
            return null
        }
        return readyFile.takeIf { readyTrackIdFile.takeIf(File::isFile)?.readText() == trackId }
    }

    fun delete(audioFile: File) {
        audioFile.delete()
        if (audioFile == readyFile) {
            readyTrackIdFile.delete()
            readyPassagesFile.delete()
        }
    }

    fun clear() {
        readyFile.delete()
        readyTrackIdFile.delete()
        readyPassagesFile.delete()
        File(directory, "ready-fact.json").delete() // upgrade cleanup: leftover from the previous version
        directory.listFiles()?.forEach { file ->
            if (file != readyFile && file != readyTrackIdFile && file != readyPassagesFile) file.delete()
        }
    }
}
