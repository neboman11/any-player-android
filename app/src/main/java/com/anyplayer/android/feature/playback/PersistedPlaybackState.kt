package com.anyplayer.android.feature.playback

import com.anyplayer.android.core.model.PlaybackStateType
import com.anyplayer.android.core.model.RepeatMode
import com.anyplayer.android.core.model.Track
import kotlinx.serialization.Serializable

@Serializable
internal data class PersistedPlaybackState(
    val queue: List<Track>,
    val orderedQueue: List<Track>? = null,
    val currentQueueIndex: Int?,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: RepeatMode,
    val volume: Int,
    val audioNormalizationEnabled: Boolean = false,
    val audioNormalizationStrictMode: Boolean = false,
    val aiDjEnabled: Boolean = false,
    val showDjEntriesInQueue: Boolean = false,
    val state: PlaybackStateType
)
