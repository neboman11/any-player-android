package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    @SerialName("spotify") SPOTIFY,
    @SerialName("jellyfin") JELLYFIN,
    @SerialName("plex") PLEX,
    @SerialName("custom") CUSTOM,
    @SerialName("all") ALL,
}

@Serializable
enum class PlaylistType {
    @SerialName("standard") STANDARD,
    @SerialName("union") UNION,
}

@Serializable
enum class RepeatMode {
    @SerialName("off") OFF,
    @SerialName("one") ONE,
    @SerialName("all") ALL,
}

@Serializable
enum class PlaybackStateType {
    @SerialName("idle") IDLE,
    @SerialName("playing") PLAYING,
    @SerialName("paused") PAUSED,
    @SerialName("buffering") BUFFERING,
    @SerialName("error") ERROR,
}
