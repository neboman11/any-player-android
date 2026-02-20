package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlaybackStatus(
    val state: PlaybackStateType,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val volume: Int,
    val currentTrack: Track? = null,
    val position: Long,
    val duration: Long,
    val queue: List<Track>,
    /** Tracks in actual playback order — equals [queue] when shuffle is off, reordered when on. */
    val orderedQueue: List<Track> = emptyList(),
    val errorMessage: String? = null
)
