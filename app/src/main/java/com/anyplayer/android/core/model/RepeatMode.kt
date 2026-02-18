package com.anyplayer.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RepeatMode {
    @SerialName("off")
    OFF,

    @SerialName("one")
    ONE,

    @SerialName("all")
    ALL
}
