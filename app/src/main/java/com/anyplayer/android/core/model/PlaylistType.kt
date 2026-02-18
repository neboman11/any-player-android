package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PlaylistType {
    @SerialName("standard")
    STANDARD,

    @SerialName("union")
    UNION
}
