package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val owner: String,
    val trackCount: Int,
    val source: SourceType,
    val imageUrl: String? = null,
    val tracks: List<Track>? = null,
    val description: String? = null
)
