package com.anyplayer.android.feature.djfiller.model

import com.anyplayer.android.core.model.SourceType
import com.anyplayer.android.core.model.Track
import java.io.File

/** Synthetic display track shown wherever the UI needs to represent the AI DJ - as the
 *  "now playing" override while a break is actually playing, and as the upcoming-queue
 *  placeholder while a break is ready but not yet reached. */
val AI_DJ_PRESENTATION_TRACK = Track(
    id = "dj-filler-presentation",
    title = "AnyPlayer DJ",
    artist = "",
    source = SourceType.CUSTOM,
    isDjFiller = true
)

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
