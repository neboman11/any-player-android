package com.anyplayer.android.core.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val trackCount: Int,
    val playlistType: PlaylistType
)
