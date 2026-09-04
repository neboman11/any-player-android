package com.anyplayer.android.feature.djfiller.model

import com.anyplayer.android.core.model.Track
import java.io.File

/** A fully-generated, ready-to-play AI DJ voice-over: the [track] it introduces, the
 *  script text that was synthesized (kept for debugging/logging), and the rendered
 *  audio file on disk. */
data class PreparedFiller(
    val track: Track,
    val scriptText: String,
    val audioFile: File
)

sealed class DjModelDownloadState {
    data object NotDownloaded : DjModelDownloadState()
    data class Downloading(val progress: Float) : DjModelDownloadState()
    data class Ready(val file: File) : DjModelDownloadState()
    data class Failed(val reason: String) : DjModelDownloadState()
}
