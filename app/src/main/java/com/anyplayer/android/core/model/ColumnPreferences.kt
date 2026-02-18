package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ColumnPreferences(
    val key: String,
    val visible: Boolean,
    val position: Int
)
