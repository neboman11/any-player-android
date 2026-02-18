package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PlaybackStateType {
    @SerialName("idle")
    IDLE,

    @SerialName("playing")
    PLAYING,

    @SerialName("paused")
    PAUSED,

    @SerialName("buffering")
    BUFFERING,

    @SerialName("error")
    ERROR
}
