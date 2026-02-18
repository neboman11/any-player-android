package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    @SerialName("spotify")
    SPOTIFY,

    @SerialName("jellyfin")
    JELLYFIN,

    @SerialName("plex")
    PLEX,

    @SerialName("custom")
    CUSTOM,

    @SerialName("all")
    ALL
}
