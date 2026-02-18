package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class UnionPlaylistSource(
    val id: String,
    val unionPlaylistId: String,
    val sourceType: SourceType,
    val sourcePlaylistId: String,
    val position: Int,
    val addedAt: String
)
